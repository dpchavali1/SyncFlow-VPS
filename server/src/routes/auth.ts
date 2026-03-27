/**
 * Authentication and device pairing routes.
 *
 * Auth flows:
 *   1. Firebase (Android) - POST /auth/firebase
 *      Android authenticates with its Firebase UID. The server creates or looks up
 *      a user record, reuses existing devices of the same type to prevent pile-up
 *      on reinstall, and returns a JWT access + refresh token pair.
 *
 *   2. Pairing (Mac/Web) - Three-step QR-code flow:
 *      a) POST /auth/pair/initiate  - Mac/Web generates a pairing token (no DB user created)
 *      b) POST /auth/pair/complete  - Android scans QR and approves, linking its userId
 *      c) POST /auth/pair/redeem    - Mac/Web exchanges approved token for real JWT tokens
 *
 *   3. Anonymous - POST /auth/anonymous (creates a throwaway user for testing)
 *
 *   4. Admin login - POST /auth/admin/login (username/password from config)
 *
 * JWT structure:
 *   - Access token (short-lived): { sub, deviceId, admin?, pairedUid? }
 *   - Refresh token (long-lived): same claims, type='refresh'
 *   - pairedUid is set when a Mac/Web device is paired to an Android user
 *
 * Anti-abuse:
 *   On Firebase auth, the server checks deleted_accounts for recently deleted
 *   accounts on the same device and carries forward their bandwidth usage to
 *   prevent delete-and-recreate limit evasion.
 */

import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import bcrypt from 'bcrypt';
import {
  createAnonymousUser,
  generateTokenPair,
  createPairingRequest,
  getPairingRequest,
  completePairing,
  redeemPairingToken,
  registerDevice,
  verifyToken,
  generateToken,
  getOrCreateUserByFirebaseUid,
  migrateUserData,
} from '../services/auth';
import { authenticate } from '../middleware/auth';
import { authRateLimit, pollingRateLimit } from '../middleware/rateLimit';
import { config } from '../config';
import { query, queryOne } from '../services/database';

const router = Router();

// Validation schemas
const initiatePairingSchema = z.object({
  deviceName: z.string().min(1).max(255),
  deviceType: z.enum(['android', 'macos', 'web']),
});

const completePairingSchema = z.object({
  token: z.string().min(1),
});

const redeemPairingSchema = z.object({
  token: z.string().min(1),
  deviceName: z.string().min(1).max(255).optional(),
  deviceType: z.enum(['android', 'macos', 'web']).optional(),
  tempUserId: z.string().optional(),
  signingKey: z.string().max(4096).optional(),
});

const refreshTokenSchema = z.object({
  refreshToken: z.string().min(1),
});

const firebaseAuthSchema = z.object({
  firebaseUid: z.string().min(1).max(128),
  deviceName: z.string().min(1).max(255),
  deviceType: z.enum(['android', 'macos', 'web']),
  signingKey: z.string().max(4096).optional(),
});

// POST /auth/firebase - Authenticate with Firebase UID (for Android app)
router.post('/firebase', authRateLimit, async (req: Request, res: Response) => {
  try {
    const body = firebaseAuthSchema.parse(req.body);

    // Get or create user with Firebase UID
    const { userId, migrationPerformed, existingUser } = await getOrCreateUserByFirebaseUid(body.firebaseUid);

    // Reuse existing device of the same type if one exists (prevents device pile-up on reinstall)
    const existingDevice = await query<{ id: string }>(
      `SELECT id FROM user_devices WHERE user_id = $1 AND device_type = $2 ORDER BY last_seen DESC LIMIT 1`,
      [userId, body.deviceType]
    );

    const deviceId = existingDevice.length > 0
      ? existingDevice[0].id
      : randomUUID();

    if (existingDevice.length > 0) {
      console.log(`[Auth] Reusing existing ${body.deviceType} device: ${deviceId}`);
    }

    // Register the device (or update last_seen for existing)
    await registerDevice(userId, deviceId, {
      name: body.deviceName,
      type: body.deviceType,
      signingKey: body.signingKey,
    });

    // Anti-abuse: carry forward bandwidth from recently deleted account on same device
    try {
      const priorDeletion = await queryOne(
        `SELECT bandwidth_bytes_at_deletion FROM deleted_accounts
         WHERE $1 = ANY(device_ids) AND deleted_at > NOW() - INTERVAL '30 days'
         ORDER BY deleted_at DESC LIMIT 1`,
        [deviceId]
      );
      if (priorDeletion && parseInt(priorDeletion.bandwidth_bytes_at_deletion || '0') > 0) {
        const priorBandwidth = parseInt(priorDeletion.bandwidth_bytes_at_deletion);
        await query(
          `INSERT INTO user_usage (user_id, storage_bytes, bandwidth_bytes_month, updated_at)
           VALUES ($1, 0, $2, NOW())
           ON CONFLICT (user_id) DO UPDATE SET
             bandwidth_bytes_month = GREATEST(user_usage.bandwidth_bytes_month, $2),
             updated_at = NOW()`,
          [userId, priorBandwidth]
        );
        console.log(`[Auth] Anti-abuse: carried forward ${priorBandwidth} bytes bandwidth for user ${userId} from deleted account`);
      }
    } catch (e) {
      console.error('[Auth] Anti-abuse check failed (non-fatal):', e);
    }

    // Generate tokens
    const tokens = generateTokenPair(userId, deviceId);

    console.log(`[Auth] Firebase user authenticated: ${userId} (device: ${body.deviceName})${migrationPerformed ? ' [MIGRATION]' : ''}${existingUser ? ' [EXISTING]' : ' [NEW]'}`);

    res.json({
      userId,
      deviceId,
      ...tokens,
      migrationPerformed,
      existingUser,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Firebase auth error:', error);
    res.status(500).json({ error: 'Failed to authenticate with Firebase UID' });
  }
});

// POST /auth/anonymous - Create anonymous user and get tokens
router.post('/anonymous', authRateLimit, async (req: Request, res: Response) => {
  try {
    const { userId, deviceId } = await createAnonymousUser();
    const tokens = generateTokenPair(userId, deviceId);

    res.json({
      userId,
      deviceId,
      ...tokens,
    });
  } catch (error) {
    console.error('Anonymous auth error:', error);
    res.status(500).json({ error: 'Failed to create anonymous user' });
  }
});

// POST /auth/pair/initiate - Start pairing process (macOS/Web)
router.post('/pair/initiate', authRateLimit, async (req: Request, res: Response) => {
  try {
    const body = initiatePairingSchema.parse(req.body);

    // Generate temp IDs WITHOUT creating a database user row.
    // Previously this called createAnonymousUser() which inserted into the users table,
    // creating orphan rows every time a QR code was displayed but never scanned.
    // The temp IDs are only used for JWT generation and Redis caching.
    const tempUserId = randomUUID();
    const deviceId = randomUUID();

    // Create pairing request (stored in Redis + pairing_requests table)
    const token = await createPairingRequest(
      deviceId,
      body.deviceName,
      body.deviceType,
      tempUserId
    );

    // Generate temporary tokens (will be replaced after pairing)
    const tokens = generateTokenPair(tempUserId, deviceId);

    res.json({
      pairingToken: token,
      deviceId,
      tempUserId,
      ...tokens,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Initiate pairing error:', error);
    res.status(500).json({ error: 'Failed to initiate pairing' });
  }
});

// GET /auth/pair/status/:token - Check pairing status (polled every 2s by web/Mac)
router.get('/pair/status/:token', pollingRateLimit, async (req: Request, res: Response) => {
  try {
    const request = await getPairingRequest(req.params.token);

    if (!request) {
      res.status(404).json({ error: 'Pairing request not found or expired' });
      return;
    }

    res.json({
      status: request.status,
      deviceName: request.deviceName,
      approved: request.status === 'approved',
    });
  } catch (error) {
    console.error('Pairing status error:', error);
    res.status(500).json({ error: 'Failed to check pairing status' });
  }
});

// POST /auth/pair/complete - Android approves pairing
router.post(
  '/pair/complete',
  authenticate,
  authRateLimit,
  async (req: Request, res: Response) => {
    try {
      const body = completePairingSchema.parse(req.body);

      const success = await completePairing(body.token, req.userId!);

      if (!success) {
        res.status(400).json({ error: 'Invalid or expired pairing token' });
        return;
      }

      res.json({ success: true, message: 'Pairing approved' });
    } catch (error) {
      if (error instanceof z.ZodError) {
        res.status(400).json({ error: 'Invalid request', details: error.errors });
        return;
      }
      console.error('Complete pairing error:', error);
      res.status(500).json({ error: 'Failed to complete pairing' });
    }
  }
);

// POST /auth/pair/redeem - macOS/Web redeems approved pairing
router.post('/pair/redeem', authRateLimit, async (req: Request, res: Response) => {
  try {
    const body = redeemPairingSchema.parse(req.body);

    const result = await redeemPairingToken(body.token, body.tempUserId);

    if (!result) {
      res.status(400).json({ error: 'Pairing not approved or already redeemed' });
      return;
    }

    // Register the device under the paired user (Android's user)
    await registerDevice(result.userId, result.deviceId, {
      name: body.deviceName,
      type: body.deviceType || 'web',
      signingKey: body.signingKey,
    });

    // Generate tokens with pairedUid
    const tokens = generateTokenPair(result.userId, result.deviceId);

    res.json({
      userId: result.userId,
      deviceId: result.deviceId,
      ...tokens,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Redeem pairing error:', error);
    res.status(500).json({ error: 'Failed to redeem pairing' });
  }
});

// POST /auth/refresh - Refresh access token
router.post('/refresh', async (req: Request, res: Response) => {
  try {
    const body = refreshTokenSchema.parse(req.body);

    const payload = verifyToken(body.refreshToken);

    if (!payload || payload.type !== 'refresh') {
      res.status(401).json({ error: 'Invalid refresh token' });
      return;
    }

    // Skip device check for admin tokens (they use synthetic device IDs)
    if (!payload.admin && payload.deviceId) {
      const device = await queryOne(
        'SELECT id FROM user_devices WHERE id = $1 AND user_id = $2',
        [payload.deviceId, payload.pairedUid || payload.sub]
      );
      if (!device) {
        res.status(401).json({ error: 'Device no longer registered' });
        return;
      }
    }

    // Generate new access token
    const accessToken = generateToken({
      sub: payload.sub,
      deviceId: payload.deviceId,
      admin: payload.admin,
      pairedUid: payload.pairedUid,
    }, 'access');

    res.json({ accessToken });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Refresh token error:', error);
    res.status(500).json({ error: 'Failed to refresh token' });
  }
});

// GET /auth/me - Get current user info
router.get('/me', authenticate, async (req: Request, res: Response) => {
  res.json({
    userId: req.userId,
    deviceId: req.deviceId,
    admin: req.user?.admin || false,
  });
});

// Admin login schema
const adminLoginSchema = z.object({
  username: z.string().min(1),
  password: z.string().min(1),
});

// POST /auth/admin/login - Admin login to get admin tokens
router.post('/admin/login', authRateLimit, async (req: Request, res: Response) => {
  try {
    const body = adminLoginSchema.parse(req.body);

    // Validate credentials - supports both bcrypt-hashed and plaintext ADMIN_PASSWORD
    // Use timing-safe comparison for plaintext to prevent timing attacks
    const expectedUsername = config.admin.username;
    const usernameMatch = body.username.length === expectedUsername.length &&
      timingSafeEqual(Buffer.from(body.username), Buffer.from(expectedUsername));
    const storedPassword = config.admin.password;
    const isBcryptHash = storedPassword.startsWith('$2');
    let passwordMatch: boolean;
    if (isBcryptHash) {
      passwordMatch = await bcrypt.compare(body.password, storedPassword);
    } else {
      // Pad both to same length for timingSafeEqual (requires equal-length buffers)
      const maxLen = Math.max(body.password.length, storedPassword.length);
      const inputBuf = Buffer.alloc(maxLen, 0);
      const storedBuf = Buffer.alloc(maxLen, 0);
      Buffer.from(body.password).copy(inputBuf);
      Buffer.from(storedPassword).copy(storedBuf);
      passwordMatch = body.password.length === storedPassword.length &&
        timingSafeEqual(inputBuf, storedBuf);
    }
    if (!usernameMatch || !passwordMatch) {
      res.status(401).json({ error: 'Invalid credentials' });
      return;
    }

    // Generate admin tokens
    const tokens = generateTokenPair('admin', 'admin-console', { admin: true });

    res.json({
      userId: 'admin',
      deviceId: 'admin-console',
      admin: true,
      ...tokens,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Admin login error:', error);
    res.status(500).json({ error: 'Failed to authenticate' });
  }
});

// GET /auth/admin/token with API key (header-based auth for scripts)
router.get('/admin/token', authRateLimit, async (req: Request, res: Response) => {
  try {
    const apiKey = req.headers['x-api-key'] as string;

    // Check API key if configured — use timing-safe comparison to prevent timing attacks
    if (config.admin.apiKey && apiKey) {
      const keyA = Buffer.from(config.admin.apiKey);
      const keyB = Buffer.from(apiKey);
      if (keyA.length === keyB.length && timingSafeEqual(keyA, keyB)) {
        const tokens = generateTokenPair('admin', 'admin-api', { admin: true });
        res.json({
          userId: 'admin',
          admin: true,
          ...tokens,
        });
        return;
      }
    }

    res.status(401).json({ error: 'Invalid API key' });
  } catch (error) {
    console.error('Admin token error:', error);
    res.status(500).json({ error: 'Failed to generate token' });
  }
});

// POST /auth/admin/migrate - Migrate data from one user to another (admin only)
const migrateSchema = z.object({
  fromUserId: z.string().uuid(),
  toUserId: z.string().uuid(),
});

router.post('/admin/migrate', authenticate, async (req: Request, res: Response) => {
  try {
    // Check admin permission
    if (!req.user?.admin) {
      res.status(403).json({ error: 'Admin access required' });
      return;
    }

    const body = migrateSchema.parse(req.body);

    console.log(`[Admin] Migrating data from ${body.fromUserId} to ${body.toUserId}`);
    const result = await migrateUserData(body.fromUserId, body.toUserId);

    if (result.migrated) {
      res.json({
        success: true,
        message: `Migrated data from ${body.fromUserId} to ${body.toUserId}`,
        counts: result.counts,
      });
    } else {
      res.status(500).json({ error: 'Migration failed' });
    }
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Migration error:', error);
    res.status(500).json({ error: 'Failed to migrate data' });
  }
});

export default router;
