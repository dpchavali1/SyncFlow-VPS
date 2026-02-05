import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const publicKeySchema = z.object({
  publicKey: z.string(),
  keyType: z.string().default('signal'),
  version: z.number().default(1),
});

const deviceKeySchema = z.object({
  encryptedKey: z.string(),
  keyType: z.string().default('signal'),
});

// GET /e2ee/public-key/:userId - Get user's public E2EE key
router.get('/public-key/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;

    const key = await queryOne(
      `SELECT public_key, device_id, created_at
       FROM e2ee_public_keys
       WHERE uid = $1
       ORDER BY created_at DESC
       LIMIT 1`,
      [userId]
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
router.get('/device-keys/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;

    const keys = await query(
      `SELECT device_id, encrypted_key, created_at, updated_at
       FROM user_e2ee_keys
       WHERE user_id = $1`,
      [userId]
    );

    const keyMap: Record<string, any> = {};
    for (const key of keys) {
      keyMap[key.device_id] = {
        encryptedKey: key.encrypted_key,
        createdAt: new Date(key.created_at).getTime(),
        updatedAt: key.updated_at ? new Date(key.updated_at).getTime() : null,
      };
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

    await query(
      `INSERT INTO user_e2ee_keys (user_id, device_id, encrypted_key, created_at, updated_at)
       VALUES ($1, $2, $3, NOW(), NOW())
       ON CONFLICT (user_id, device_id) DO UPDATE SET
         encrypted_key = EXCLUDED.encrypted_key,
         updated_at = NOW()`,
      [userId, deviceId, body.encryptedKey]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
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

// POST /e2ee/key-request - Create key exchange request
router.post('/key-request', async (req: Request, res: Response) => {
  try {
    const { targetDevice } = req.body;
    const userId = req.userId!;
    const deviceId = req.deviceId;

    if (!targetDevice) {
      res.status(400).json({ error: 'targetDevice is required' });
      return;
    }

    const result = await query(
      `INSERT INTO e2ee_key_requests (user_id, requesting_device, target_device, status, created_at)
       VALUES ($1, $2, $3, 'pending', NOW())
       RETURNING id`,
      [userId, deviceId, targetDevice]
    );

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

export default router;
