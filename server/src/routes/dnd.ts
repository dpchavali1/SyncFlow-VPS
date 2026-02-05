import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const dndStatusSchema = z.object({
  enabled: z.boolean(),
  untilTime: z.number().optional(),
});

const dndCommandSchema = z.object({
  action: z.enum(['enable', 'disable', 'toggle']),
  durationMinutes: z.number().optional(),
});

// GET /dnd/status - Get DND status
router.get('/status', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const status = await queryOne(
      `SELECT enabled, until_time, updated_at
       FROM user_dnd_status
       WHERE user_id = $1`,
      [userId]
    );

    res.json({
      enabled: status?.enabled || false,
      untilTime: status?.until_time ? parseInt(status.until_time) : null,
      updatedAt: status?.updated_at ? new Date(status.updated_at).getTime() : null,
    });
  } catch (error) {
    console.error('Get DND status error:', error);
    res.status(500).json({ error: 'Failed to get DND status' });
  }
});

// POST /dnd/status - Update DND status
router.post('/status', async (req: Request, res: Response) => {
  try {
    const body = dndStatusSchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO user_dnd_status (user_id, enabled, until_time, updated_at)
       VALUES ($1, $2, $3, NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         enabled = EXCLUDED.enabled,
         until_time = EXCLUDED.until_time,
         updated_at = NOW()`,
      [userId, body.enabled, body.untilTime]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update DND status error:', error);
    res.status(500).json({ error: 'Failed to update DND status' });
  }
});

// GET /dnd/commands - Get pending DND commands
router.get('/commands', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const commands = await query(
      `SELECT id, action, duration_minutes, timestamp
       FROM user_dnd_commands
       WHERE user_id = $1 AND processed = FALSE
       ORDER BY timestamp DESC
       LIMIT 10`,
      [userId]
    );

    res.json({
      commands: commands.map(c => ({
        id: c.id,
        action: c.action,
        durationMinutes: c.duration_minutes,
        timestamp: parseInt(c.timestamp),
      })),
    });
  } catch (error) {
    console.error('Get DND commands error:', error);
    res.status(500).json({ error: 'Failed to get DND commands' });
  }
});

// POST /dnd/commands - Create DND command
router.post('/commands', async (req: Request, res: Response) => {
  try {
    const body = dndCommandSchema.parse(req.body);
    const userId = req.userId!;
    const commandId = `dnd_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_dnd_commands (id, user_id, action, duration_minutes, timestamp)
       VALUES ($1, $2, $3, $4, $5)`,
      [commandId, userId, body.action, body.durationMinutes, Date.now()]
    );

    res.json({ id: commandId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create DND command error:', error);
    res.status(500).json({ error: 'Failed to create DND command' });
  }
});

// PUT /dnd/commands/:id/processed - Mark command as processed
router.put('/commands/:id/processed', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `UPDATE user_dnd_commands SET processed = TRUE
       WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Mark DND command processed error:', error);
    res.status(500).json({ error: 'Failed to mark command processed' });
  }
});

export default router;
