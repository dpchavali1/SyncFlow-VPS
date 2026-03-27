import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { rateLimit } from '../middleware/rateLimit';
import { broadcastToDevice, broadcastToUser } from '../services/websocket';

const router = Router();

// E2EE routes get a higher rate limit since key exchange requires polling
const e2eeRateLimit = rateLimit({
  windowMs: 60000,
  maxRequests: 200,
  keyPrefix: 'rl:e2ee',
  keyGenerator: (req) => req.userId || req.ip || 'unknown',
});

// Per-device key request rate limit: 5 requests per 5 minutes per (userId+targetDevice)
const keyRequestRateLimit = rateLimit({
  windowMs: 5 * 60 * 1000,
  maxRequests: 5,
  keyPrefix: 'rl:e2ee:keyreq',
  keyGenerator: (req) => `${req.userId}:${req.body?.targetDevice || 'unknown'}`,
});

router.use(authenticate);
router.use(e2eeRateLimit);

// X9.63 format validation: base64 → 65 bytes starting with 0x04
const isValidX963PublicKey = (base64: string): boolean => {
  try {
    const bytes = Buffer.from(base64, 'base64');
    return bytes.length === 65 && bytes[0] === 0x04;
  } catch {
    return false;
  }
};

// Validation schemas
const publicKeySchema = z.object({
  publicKey: z.string().max(4096),
  keyType: z.string().max(64).default('signal'),
  version: z.number().default(1),
});

const deviceKeySchema = z.object({
  encryptedKey: z.string().max(4096).optional(),
  publicKeyX963: z.string().max(4096).optional(),
  keyType: z.string().max(64).default('signal'),
  format: z.string().max(64).optional(),
  keyVersion: z.number().optional(),
  platform: z.string().max(32).optional(),
  fromDevice: z.string().max(128).optional(),
  timestamp: z.number().optional(),
  signature: z.string().max(4096).optional(),
  signingDeviceId: z.string().max(128).optional(),
}).refine(data => data.encryptedKey || data.publicKeyX963, {
  message: 'Either encryptedKey or publicKeyX963 must be provided',
});

// GET /e2ee/public-key/:userId - Get user's public E2EE key
// Only allows fetching keys for the authenticated user's own account
router.get('/public-key/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const authenticatedUserId = req.userId!;

    // Security: only allow fetching your own public key
    if (userId !== authenticatedUserId) {
      res.status(403).json({ error: 'Cannot access another user\'s keys' });
      return;
    }

    const key = await queryOne(
      `SELECT public_key, device_id, created_at
       FROM e2ee_public_keys
       WHERE uid = $1
       ORDER BY created_at DESC
       LIMIT 1`,
      [authenticatedUserId]
    );

    if (!key) {
      res.json({ publicKey: null });
      return;
    }

    res.json({
      publicKey: key.public_key,
      deviceId: key.device_id,
      createdAt: new Date(key.created_at).getTime(),
    });
  } catch (error) {
    console.error('Get E2EE public key error:', error);
    res.status(500).json({ error: 'Failed to get public key' });
  }
});

// POST /e2ee/public-key - Publish user's public E2EE key
router.post('/public-key', async (req: Request, res: Response) => {
  try {
    const body = publicKeySchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;

    await query(
      `INSERT INTO e2ee_public_keys (uid, device_id, public_key, created_at)
       VALUES ($1, $2, $3, NOW())
       ON CONFLICT (uid, device_id) DO UPDATE SET
         public_key = EXCLUDED.public_key,
         created_at = NOW()`,
      [userId, deviceId, body.publicKey]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Publish E2EE public key error:', error);
    res.status(500).json({ error: 'Failed to publish public key' });
  }
});

// GET /e2ee/device-keys/:userId - Get all device E2EE keys for user
// Only allows fetching keys for the authenticated user's own account
router.get('/device-keys/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const authenticatedUserId = req.userId!;

    // Security: only allow fetching your own device keys
    if (userId !== authenticatedUserId) {
      res.status(403).json({ error: 'Cannot access another user\'s keys' });
      return;
    }

    const keys = await query(
      `SELECT device_id, encrypted_key, key_version, signature, signing_device_id, created_at, updated_at
       FROM user_e2ee_keys
       WHERE user_id = $1`,
      [authenticatedUserId]
    );

    const keyMap: Record<string, any> = {};
    for (const key of keys) {
      keyMap[key.device_id] = {
        encryptedKey: key.encrypted_key,
        publicKeyX963: key.encrypted_key, // Android reads this field name
        keyVersion: key.key_version || 1,
        signature: key.signature || null,
        signingDeviceId: key.signing_device_id || null,
        createdAt: new Date(key.created_at).getTime(),
        updatedAt: key.updated_at ? new Date(key.updated_at).getTime() : null,
      };
    }

    // Log on first poll to help debug E2EE key exchange issues
    if (keys.length === 0) {
      console.log(`[E2EE] GET device-keys for userId=${authenticatedUserId}: no keys found (caller deviceId=${req.deviceId})`);
    }

    res.json(keyMap);
  } catch (error) {
    console.error('Get E2EE device keys error:', error);
    res.status(500).json({ error: 'Failed to get device keys' });
  }
});

// POST /e2ee/device-key/:deviceId - Publish device-specific E2EE key
router.post('/device-key/:deviceId', async (req: Request, res: Response) => {
  try {
    const { deviceId } = req.params;
    const body = deviceKeySchema.parse(req.body);
    const userId = req.userId!;

    // Verify the target deviceId belongs to the authenticated user or is self
    if (deviceId !== req.deviceId) {
      const deviceOwned = await queryOne(
        `SELECT id FROM user_devices WHERE id = $1 AND user_id = $2`,
        [deviceId, userId]
      );
      if (!deviceOwned) {
        res.status(403).json({ error: 'Cannot publish keys for a device you do not own' });
        return;
      }
    }

    // Validate X9.63 public key format if provided directly (not encrypted payload)
    if (body.publicKeyX963 && !body.encryptedKey && body.format === 'x963') {
      if (!isValidX963PublicKey(body.publicKeyX963)) {
        res.status(400).json({ error: 'Invalid X9.63 public key format (must be 65 bytes starting with 0x04)' });
        return;
      }
    }

    // Accept either encryptedKey (from DesktopSyncService) or publicKeyX963 (from SignalProtocolManager)
    const keyValue = body.encryptedKey || body.publicKeyX963!;
    const keyVersion = body.keyVersion ?? 1;

    console.log(`[E2EE] Storing key for userId=${userId}, deviceId=${deviceId}, keyLength=${keyValue.length}, format=${body.format || 'default'}, keyVersion=${keyVersion}`);

    await query(
      `INSERT INTO user_e2ee_keys (user_id, device_id, encrypted_key, key_version, signature, signing_device_id, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, NOW(), NOW())
       ON CONFLICT (user_id, device_id) DO UPDATE SET
         encrypted_key = EXCLUDED.encrypted_key,
         key_version = EXCLUDED.key_version,
         signature = EXCLUDED.signature,
         signing_device_id = EXCLUDED.signing_device_id,
         updated_at = NOW()`,
      [userId, deviceId, keyValue, keyVersion, body.signature || null, body.signingDeviceId || null]
    );

    console.log(`[E2EE] Key stored successfully for userId=${userId}, deviceId=${deviceId}`);

    // Notify the target device that its E2EE key is available
    broadcastToDevice(userId, deviceId, {
      type: 'e2ee_key_available',
      data: { deviceId },
    });

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      console.error(`[E2EE] Validation error for device-key:`, error.errors);
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Publish E2EE device key error:', error);
    res.status(500).json({ error: 'Failed to publish device key' });
  }
});

// DELETE /e2ee/keys - Clear all E2EE keys for user
router.delete('/keys', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    await query(`DELETE FROM user_e2ee_keys WHERE user_id = $1`, [userId]);
    await query(`DELETE FROM e2ee_public_keys WHERE uid = $1`, [userId]);

    res.json({ success: true });
  } catch (error) {
    console.error('Clear E2EE keys error:', error);
    res.status(500).json({ error: 'Failed to clear keys' });
  }
});

// POST /e2ee/repair - Clear stale E2EE keys and trigger full message re-sync
router.post('/repair', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const deviceId = req.deviceId;

    console.log(`[E2EE] Repair requested by userId=${userId}`);

    // Circuit breaker: check if repair was done recently (< 1 hour)
    const lastRepair = await queryOne<{ created_at: string }>(
      `SELECT created_at FROM e2ee_repair_log
       WHERE user_id = $1 AND created_at > NOW() - INTERVAL '1 hour'
       ORDER BY created_at DESC LIMIT 1`,
      [userId]
    );

    if (lastRepair) {
      res.status(429).json({ error: 'Repair was performed recently. Please wait at least 1 hour between repairs.' });
      return;
    }

    // Log this repair
    await query(
      `INSERT INTO e2ee_repair_log (user_id, device_id, created_at) VALUES ($1, $2, NOW())`,
      [userId, deviceId]
    );

    // Clear all E2EE keys for this user
    await query(`DELETE FROM user_e2ee_keys WHERE user_id = $1`, [userId]);
    await query(`DELETE FROM e2ee_public_keys WHERE uid = $1`, [userId]);

    // Broadcast request_sync to all user's devices so Android re-syncs messages
    broadcastToUser(userId, 'devices', {
      type: 'request_sync',
      data: { categories: ['messages'] },
    });

    console.log(`[E2EE] Repair: cleared keys and broadcast request_sync(messages) for userId=${userId}`);
    res.json({ success: true });
  } catch (error) {
    console.error('E2EE repair error:', error);
    res.status(500).json({ error: 'Failed to repair encryption' });
  }
});

// POST /e2ee/key-request - Create key exchange request
router.post('/key-request', keyRequestRateLimit, async (req: Request, res: Response) => {
  try {
    const { targetDevice } = req.body;
    const userId = req.userId!;
    const deviceId = req.deviceId;

    if (!targetDevice || typeof targetDevice !== 'string' || targetDevice.length > 128) {
      res.status(400).json({ error: 'targetDevice is required (max 128 chars)' });
      return;
    }

    // Get the requesting device's public key to include in the notification
    const pubKeyResult = await query(
      `SELECT public_key FROM e2ee_public_keys WHERE uid = $1 AND device_id = $2`,
      [userId, deviceId]
    );
    const requestingPublicKey = pubKeyResult.length > 0 ? pubKeyResult[0].public_key : null;

    const result = await query(
      `INSERT INTO e2ee_key_requests (user_id, requesting_device, target_device, status, created_at, expires_at)
       VALUES ($1, $2, $3, 'pending', NOW(), NOW() + INTERVAL '24 hours')
       RETURNING id`,
      [userId, deviceId, targetDevice]
    );

    // Notify target device via WebSocket so it can auto-push keys
    broadcastToDevice(userId, targetDevice, {
      type: 'e2ee_key_request',
      data: {
        requestId: result[0].id,
        requestingDevice: deviceId,
        requestingPublicKey,
      },
    });

    console.log(`[E2EE] Key request created: ${deviceId} -> ${targetDevice} (requestId=${result[0].id})`);
    res.json({ id: result[0].id, success: true });
  } catch (error) {
    console.error('Create key request error:', error);
    res.status(500).json({ error: 'Failed to create key request' });
  }
});

// GET /e2ee/key-requests - Get pending key requests for this device
router.get('/key-requests', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const deviceId = req.deviceId;

    const requests = await query(
      `SELECT id, requesting_device, status, created_at
       FROM e2ee_key_requests
       WHERE user_id = $1 AND target_device = $2 AND status = 'pending'
         AND (expires_at IS NULL OR expires_at > NOW())
       ORDER BY created_at DESC`,
      [userId, deviceId]
    );

    res.json({
      requests: requests.map(r => ({
        id: r.id,
        requestingDevice: r.requesting_device,
        status: r.status,
        createdAt: new Date(r.created_at).getTime(),
      })),
    });
  } catch (error) {
    console.error('Get key requests error:', error);
    res.status(500).json({ error: 'Failed to get key requests' });
  }
});

// POST /e2ee/key-response - Respond to key exchange request
router.post('/key-response', async (req: Request, res: Response) => {
  try {
    const { requestId, encryptedKey } = req.body;
    const userId = req.userId!;

    if (!requestId || !encryptedKey) {
      res.status(400).json({ error: 'requestId and encryptedKey are required' });
      return;
    }

    if (typeof encryptedKey !== 'string' || encryptedKey.length > 4096) {
      res.status(400).json({ error: 'encryptedKey exceeds maximum length' });
      return;
    }

    // Check for duplicate response (prevent replay)
    const existingResponse = await queryOne(
      `SELECT id FROM e2ee_key_responses WHERE request_id = $1 AND user_id = $2`,
      [requestId, userId]
    );

    if (existingResponse) {
      res.status(409).json({ error: 'Response already submitted for this request' });
      return;
    }

    // Create response
    await query(
      `INSERT INTO e2ee_key_responses (user_id, request_id, encrypted_key, created_at)
       VALUES ($1, $2, $3, NOW())`,
      [userId, requestId, encryptedKey]
    );

    // Update request status
    await query(
      `UPDATE e2ee_key_requests SET status = 'completed'
       WHERE id = $1 AND user_id = $2`,
      [requestId, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Create key response error:', error);
    res.status(500).json({ error: 'Failed to create key response' });
  }
});

// GET /e2ee/key-responses/:requestId - Get key response
router.get('/key-responses/:requestId', async (req: Request, res: Response) => {
  try {
    const { requestId } = req.params;
    const userId = req.userId!;

    const response = await queryOne(
      `SELECT encrypted_key, created_at
       FROM e2ee_key_responses
       WHERE request_id = $1 AND user_id = $2`,
      [requestId, userId]
    );

    if (!response) {
      res.json({ encryptedKey: null });
      return;
    }

    res.json({
      encryptedKey: response.encrypted_key,
      createdAt: new Date(response.created_at).getTime(),
    });
  } catch (error) {
    console.error('Get key response error:', error);
    res.status(500).json({ error: 'Failed to get key response' });
  }
});

// Validation schema for key backup
const keyBackupSchema = z.object({
  encryptedBackup: z.string().max(16384),
  salt: z.string().max(64),
  iterations: z.number().min(400000),
  keyVersion: z.number().min(1),
});

// POST /e2ee/key-backup - Store encrypted key backup
router.post('/key-backup', async (req: Request, res: Response) => {
  try {
    const body = keyBackupSchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO e2ee_key_backups (user_id, encrypted_backup, salt, iterations, key_version, created_at)
       VALUES ($1, $2, $3, $4, $5, NOW())
       ON CONFLICT (user_id, key_version) DO UPDATE SET
         encrypted_backup = EXCLUDED.encrypted_backup,
         salt = EXCLUDED.salt,
         iterations = EXCLUDED.iterations,
         created_at = NOW()`,
      [userId, body.encryptedBackup, body.salt, body.iterations, body.keyVersion]
    );

    console.log(`[E2EE] Key backup stored for userId=${userId}, keyVersion=${body.keyVersion}`);
    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Store key backup error:', error);
    res.status(500).json({ error: 'Failed to store key backup' });
  }
});

// GET /e2ee/key-backup - Get all key backups for user
router.get('/key-backup', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const backups = await query(
      `SELECT encrypted_backup, salt, iterations, key_version, created_at
       FROM e2ee_key_backups
       WHERE user_id = $1
       ORDER BY key_version ASC`,
      [userId]
    );

    res.json({
      backups: backups.map(b => ({
        encryptedBackup: b.encrypted_backup,
        salt: b.salt,
        iterations: b.iterations,
        keyVersion: b.key_version,
        createdAt: new Date(b.created_at).getTime(),
      })),
    });
  } catch (error) {
    console.error('Get key backups error:', error);
    res.status(500).json({ error: 'Failed to get key backups' });
  }
});

export default router;
