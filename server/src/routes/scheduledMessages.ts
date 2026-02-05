import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const scheduledMessageSchema = z.object({
  recipientNumber: z.string().max(500),
  recipientName: z.string().max(255).optional(),
  message: z.string().max(1600),
  scheduledTime: z.number(),
  simSlot: z.number().optional(),
});

const updateStatusSchema = z.object({
  status: z.enum(['pending', 'sent', 'failed', 'cancelled']),
  errorMessage: z.string().optional(),
  sentAt: z.number().optional(),
});

// GET /scheduled-messages - Get scheduled messages
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const status = req.query.status as string;

    let queryText = `
      SELECT id, address, recipient_name, body, scheduled_time, status,
             sim_subscription_id, error_message, created_at
      FROM user_scheduled_messages
      WHERE user_id = $1
    `;
    const params: any[] = [userId];

    if (status) {
      queryText += ` AND status = $2`;
      params.push(status);
    }

    queryText += ` ORDER BY scheduled_time ASC LIMIT 100`;

    const messages = await query(queryText, params);

    res.json({
      messages: messages.map(m => ({
        id: m.id,
        recipientNumber: m.address,
        recipientName: m.recipient_name,
        message: m.body,
        scheduledTime: parseInt(m.scheduled_time),
        status: m.status,
        simSlot: m.sim_subscription_id,
        errorMessage: m.error_message,
        createdAt: new Date(m.created_at).getTime(),
      })),
    });
  } catch (error) {
    console.error('Get scheduled messages error:', error);
    res.status(500).json({ error: 'Failed to get scheduled messages' });
  }
});

// POST /scheduled-messages - Create scheduled message
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = scheduledMessageSchema.parse(req.body);
    const userId = req.userId!;
    const messageId = `sched_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_scheduled_messages
       (id, user_id, address, recipient_name, body, scheduled_time, status, sim_subscription_id)
       VALUES ($1, $2, $3, $4, $5, $6, 'pending', $7)`,
      [messageId, userId, body.recipientNumber, body.recipientName,
       body.message, body.scheduledTime, body.simSlot]
    );

    res.json({ id: messageId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create scheduled message error:', error);
    res.status(500).json({ error: 'Failed to create scheduled message' });
  }
});

// PUT /scheduled-messages/:id/status - Update message status
router.put('/:id/status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const body = updateStatusSchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `UPDATE user_scheduled_messages
       SET status = $1, error_message = $2
       WHERE id = $3 AND user_id = $4`,
      [body.status, body.errorMessage, id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update scheduled message status error:', error);
    res.status(500).json({ error: 'Failed to update message status' });
  }
});

// DELETE /scheduled-messages/:id - Delete scheduled message
router.delete('/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_scheduled_messages WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Delete scheduled message error:', error);
    res.status(500).json({ error: 'Failed to delete scheduled message' });
  }
});

// GET /scheduled-messages/due - Get messages due to be sent (for worker)
router.get('/due', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const now = Date.now();

    const messages = await query(
      `SELECT id, address, recipient_name, body, scheduled_time, sim_subscription_id
       FROM user_scheduled_messages
       WHERE user_id = $1 AND status = 'pending' AND scheduled_time <= $2
       ORDER BY scheduled_time ASC
       LIMIT 50`,
      [userId, now]
    );

    res.json({
      messages: messages.map(m => ({
        id: m.id,
        recipientNumber: m.address,
        recipientName: m.recipient_name,
        message: m.body,
        scheduledTime: parseInt(m.scheduled_time),
        simSlot: m.sim_subscription_id,
      })),
    });
  } catch (error) {
    console.error('Get due messages error:', error);
    res.status(500).json({ error: 'Failed to get due messages' });
  }
});

export default router;
