import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { randomUUID } from 'crypto';
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
import { authRateLimit } from '../middleware/rateLimit';
import { config } from '../config';
import { query } from '../services/database';

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
});

const refreshTokenSchema = z.object({
  refreshToken: z.string().min(1),
});

const firebaseAuthSchema = z.object({
  firebaseUid: z.string().min(1).max(128),
  deviceName: z.string().min(1).max(255),
  deviceType: z.enum(['android', 'macos', 'web']),
});

// POST /auth/firebase - Authenticate with Firebase UID (for Android app)
router.post('/firebase', authRateLimit, async (req: Request, res: Response) => {
  try {
    const body = firebaseAuthSchema.parse(req.body);

    // Get or create user with Firebase UID
    const userId = await getOrCreateUserByFirebaseUid(body.firebaseUid);

    // Reuse existing device of the same type if one exists (prevents device pile-up on reinstall)
    const existingDevice = await query<{ id: string }>(
      `SELECT id FROM user_devices WHERE user_id = $1 AND device_type = $2 ORDER BY last_seen DESC LIMIT 1`,
      [userId, body.deviceType]
    );

    const deviceId = existingDevice.length > 0
      ? existingDevice[0].id
      : `${body.deviceType}_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    if (existingDevice.length > 0) {
      console.log(`[Auth] Reusing existing ${body.deviceType} device: ${deviceId}`);
    }

    // Register the device (or update last_seen for existing)
    await registerDevice(userId, deviceId, {
      name: body.deviceName,
      type: body.deviceType,
    });

    // Generate tokens
    const tokens = generateTokenPair(userId, deviceId);

    console.log(`[Auth] Firebase user authenticated: ${userId} (device: ${body.deviceName})`);

    res.json({
      userId,
      deviceId,
      ...tokens,
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

// GET /auth/pair/status/:token - Check pairing status
router.get('/pair/status/:token', async (req: Request, res: Response) => {
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

    // Validate credentials
    if (body.username !== config.admin.username || body.password !== config.admin.password) {
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

// GET /auth/admin/login with API key (header-based auth for scripts)
router.get('/admin/token', async (req: Request, res: Response) => {
  try {
    const apiKey = req.headers['x-api-key'] as string;

    // Check API key if configured
    if (config.admin.apiKey && apiKey === config.admin.apiKey) {
      const tokens = generateTokenPair('admin', 'admin-api', { admin: true });
      res.json({
        userId: 'admin',
        admin: true,
        ...tokens,
      });
      return;
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
