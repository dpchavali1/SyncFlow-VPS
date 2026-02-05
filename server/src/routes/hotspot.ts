import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const hotspotStatusSchema = z.object({
  enabled: z.boolean(),
  ssid: z.string().max(255).optional(),
  connectedDevices: z.number().optional(),
});

const hotspotCommandSchema = z.object({
  action: z.enum(['enable', 'disable', 'toggle']),
});

// GET /hotspot/status - Get hotspot status
router.get('/status', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const status = await queryOne(
      `SELECT enabled, ssid, connected_devices, updated_at
       FROM user_hotspot_status
       WHERE user_id = $1`,
      [userId]
    );

    res.json({
      enabled: status?.enabled || false,
      ssid: status?.ssid || null,
      connectedDevices: status?.connected_devices || 0,
      updatedAt: status?.updated_at ? new Date(status.updated_at).getTime() : null,
    });
  } catch (error) {
    console.error('Get hotspot status error:', error);
    res.status(500).json({ error: 'Failed to get hotspot status' });
  }
});

// POST /hotspot/status - Update hotspot status
router.post('/status', async (req: Request, res: Response) => {
  try {
    const body = hotspotStatusSchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO user_hotspot_status (user_id, enabled, ssid, connected_devices, updated_at)
       VALUES ($1, $2, $3, $4, NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         enabled = EXCLUDED.enabled,
         ssid = EXCLUDED.ssid,
         connected_devices = EXCLUDED.connected_devices,
         updated_at = NOW()`,
      [userId, body.enabled, body.ssid, body.connectedDevices || 0]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update hotspot status error:', error);
    res.status(500).json({ error: 'Failed to update hotspot status' });
  }
});

// GET /hotspot/commands - Get pending hotspot commands
router.get('/commands', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const commands = await query(
      `SELECT id, action, timestamp
       FROM user_hotspot_commands
       WHERE user_id = $1 AND processed = FALSE
       ORDER BY timestamp DESC
       LIMIT 10`,
      [userId]
    );

    res.json({
      commands: commands.map(c => ({
        id: c.id,
        action: c.action,
        timestamp: parseInt(c.timestamp),
      })),
    });
  } catch (error) {
    console.error('Get hotspot commands error:', error);
    res.status(500).json({ error: 'Failed to get hotspot commands' });
  }
});

// POST /hotspot/commands - Create hotspot command
router.post('/commands', async (req: Request, res: Response) => {
  try {
    const body = hotspotCommandSchema.parse(req.body);
    const userId = req.userId!;
    const commandId = `hotspot_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_hotspot_commands (id, user_id, action, timestamp)
       VALUES ($1, $2, $3, $4)`,
      [commandId, userId, body.action, Date.now()]
    );

    res.json({ id: commandId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create hotspot command error:', error);
    res.status(500).json({ error: 'Failed to create hotspot command' });
  }
});

// PUT /hotspot/commands/:id/processed - Mark command as processed
router.put('/commands/:id/processed', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `UPDATE user_hotspot_commands SET processed = TRUE
       WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Mark hotspot command processed error:', error);
    res.status(500).json({ error: 'Failed to mark command processed' });
  }
});

export default router;
