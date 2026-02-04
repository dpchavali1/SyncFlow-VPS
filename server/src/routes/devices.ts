import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const updateDeviceSchema = z.object({
  name: z.string().max(255).optional(),
  fcmToken: z.string().optional(),
});

const registerSimSchema = z.object({
  id: z.string().min(1),
  subscriptionId: z.number(),
  displayName: z.string().max(255).optional(),
  carrierName: z.string().max(255).optional(),
  phoneNumber: z.string().max(20).optional(),
  isDefault: z.boolean().default(false),
});

// GET /devices - Get all devices for user
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const devices = await query(
      `SELECT id, name, device_type, paired_at, last_seen
       FROM user_devices
       WHERE user_id = $1
       ORDER BY last_seen DESC`,
      [userId]
    );

    res.json({
      devices: devices.map((d) => ({
        id: d.id,
        name: d.name,
        deviceType: d.device_type,
        pairedAt: d.paired_at,
        lastSeen: d.last_seen,
        isCurrent: d.id === req.deviceId,
      })),
    });
  } catch (error) {
    console.error('Get devices error:', error);
    res.status(500).json({ error: 'Failed to get devices' });
  }
});

// PUT /devices/:id - Update device info
router.put('/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const body = updateDeviceSchema.parse(req.body);
    const userId = req.userId!;

    // Verify ownership
    const existing = await query(
      `SELECT id FROM user_devices WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    if (existing.length === 0) {
      res.status(404).json({ error: 'Device not found' });
      return;
    }

    const updates: string[] = [];
    const params: any[] = [];
    let paramIndex = 1;

    if (body.name !== undefined) {
      updates.push(`name = $${paramIndex++}`);
      params.push(body.name);
    }

    if (body.fcmToken !== undefined) {
      updates.push(`fcm_token = $${paramIndex++}`);
      params.push(body.fcmToken);
    }

    if (updates.length > 0) {
      params.push(id, userId);
      await query(
        `UPDATE user_devices SET ${updates.join(', ')}, last_seen = NOW()
         WHERE id = $${paramIndex++} AND user_id = $${paramIndex}`,
        params
      );
    }

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update device error:', error);
    res.status(500).json({ error: 'Failed to update device' });
  }
});

// DELETE /devices/:id - Remove device
router.delete('/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    // Don't allow deleting current device
    if (id === req.deviceId) {
      res.status(400).json({ error: 'Cannot remove current device' });
      return;
    }

    const result = await query(
      `DELETE FROM user_devices WHERE id = $1 AND user_id = $2 RETURNING id`,
      [id, userId]
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'Device not found' });
      return;
    }

    res.json({ success: true });
  } catch (error) {
    console.error('Delete device error:', error);
    res.status(500).json({ error: 'Failed to delete device' });
  }
});

// GET /devices/sims - Get SIM cards
router.get('/sims', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const sims = await query(
      `SELECT id, subscription_id, display_name, carrier_name, phone_number, is_default
       FROM user_sims
       WHERE user_id = $1
       ORDER BY is_default DESC, display_name ASC`,
      [userId]
    );

    res.json({
      sims: sims.map((s) => ({
        id: s.id,
        subscriptionId: s.subscription_id,
        displayName: s.display_name,
        carrierName: s.carrier_name,
        phoneNumber: s.phone_number,
        isDefault: s.is_default,
      })),
    });
  } catch (error) {
    console.error('Get sims error:', error);
    res.status(500).json({ error: 'Failed to get SIM cards' });
  }
});

// POST /devices/sims - Register/update SIM card
router.post('/sims', async (req: Request, res: Response) => {
  try {
    const body = registerSimSchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO user_sims
       (id, user_id, subscription_id, display_name, carrier_name, phone_number, is_default)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (id) DO UPDATE SET
         display_name = EXCLUDED.display_name,
         carrier_name = EXCLUDED.carrier_name,
         phone_number = EXCLUDED.phone_number,
         is_default = EXCLUDED.is_default`,
      [
        body.id,
        userId,
        body.subscriptionId,
        body.displayName,
        body.carrierName,
        body.phoneNumber,
        body.isDefault,
      ]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Register sim error:', error);
    res.status(500).json({ error: 'Failed to register SIM' });
  }
});

export default router;
