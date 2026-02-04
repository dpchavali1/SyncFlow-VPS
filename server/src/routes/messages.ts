import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

// All routes require authentication
router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const getMessagesSchema = z.object({
  limit: z.coerce.number().min(1).max(500).default(100),
  before: z.coerce.number().optional(), // timestamp for pagination
  after: z.coerce.number().optional(), // timestamp for incremental sync
  threadId: z.coerce.number().optional(),
});

const createMessageSchema = z.object({
  id: z.string().min(1),
  threadId: z.number().optional(),
  address: z.string().min(1).max(50),
  contactName: z.string().max(255).optional(),
  body: z.string().max(10000).optional(),
  date: z.number(),
  type: z.number(), // 1=received, 2=sent
  read: z.boolean().default(false),
  isMms: z.boolean().default(false),
  mmsParts: z.any().optional(),
  encrypted: z.boolean().default(false),
});

const syncMessagesSchema = z.object({
  messages: z.array(createMessageSchema).max(500),
});

const sendMessageSchema = z.object({
  address: z.string().min(1).max(50),
  body: z.string().min(1).max(1600),
  simSubscriptionId: z.number().optional(),
});

// GET /messages - Get messages with pagination
router.get('/', async (req: Request, res: Response) => {
  try {
    const params = getMessagesSchema.parse(req.query);
    const userId = req.userId!;

    let queryText = `
      SELECT id, thread_id, address, contact_name, body, date, type, read,
             is_mms, mms_parts, encrypted, created_at
      FROM user_messages
      WHERE user_id = $1
    `;
    const queryParams: any[] = [userId];
    let paramIndex = 2;

    if (params.threadId) {
      queryText += ` AND thread_id = $${paramIndex++}`;
      queryParams.push(params.threadId);
    }

    if (params.after) {
      queryText += ` AND date > $${paramIndex++}`;
      queryParams.push(params.after);
    }

    if (params.before) {
      queryText += ` AND date < $${paramIndex++}`;
      queryParams.push(params.before);
    }

    queryText += ` ORDER BY date DESC LIMIT $${paramIndex}`;
    queryParams.push(params.limit);

    const messages = await query(queryText, queryParams);

    res.json({
      messages: messages.map((m) => ({
        id: m.id,
        threadId: m.thread_id,
        address: m.address,
        contactName: m.contact_name,
        body: m.body,
        date: parseInt(m.date),
        type: m.type,
        read: m.read,
        isMms: m.is_mms,
        mmsParts: m.mms_parts,
        encrypted: m.encrypted,
      })),
      hasMore: messages.length === params.limit,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Get messages error:', error);
    res.status(500).json({ error: 'Failed to get messages' });
  }
});

// POST /messages/sync - Sync messages from device (batch)
router.post('/sync', async (req: Request, res: Response) => {
  try {
    const body = syncMessagesSchema.parse(req.body);
    const userId = req.userId!;

    let synced = 0;
    let skipped = 0;

    for (const msg of body.messages) {
      try {
        await query(
          `INSERT INTO user_messages
           (id, user_id, thread_id, address, contact_name, body, date, type, read, is_mms, mms_parts, encrypted)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
           ON CONFLICT (id) DO UPDATE SET
             read = EXCLUDED.read,
             updated_at = NOW()`,
          [
            msg.id,
            userId,
            msg.threadId,
            msg.address,
            msg.contactName,
            msg.body,
            msg.date,
            msg.type,
            msg.read,
            msg.isMms,
            msg.mmsParts ? JSON.stringify(msg.mmsParts) : null,
            msg.encrypted,
          ]
        );
        synced++;
      } catch (e) {
        skipped++;
      }
    }

    res.json({ synced, skipped, total: body.messages.length });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Sync messages error:', error);
    res.status(500).json({ error: 'Failed to sync messages' });
  }
});

// POST /messages/send - Queue message to send
router.post('/send', async (req: Request, res: Response) => {
  try {
    const body = sendMessageSchema.parse(req.body);
    const userId = req.userId!;
    const messageId = `out_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_outgoing_messages
       (id, user_id, address, body, timestamp, status, sim_subscription_id)
       VALUES ($1, $2, $3, $4, $5, 'pending', $6)`,
      [messageId, userId, body.address, body.body, Date.now(), body.simSubscriptionId]
    );

    res.json({
      id: messageId,
      status: 'pending',
      message: 'Message queued for sending',
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Send message error:', error);
    res.status(500).json({ error: 'Failed to queue message' });
  }
});

// GET /messages/outgoing - Get pending outgoing messages (for Android to pick up)
router.get('/outgoing', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const messages = await query(
      `SELECT id, address, body, timestamp, status, sim_subscription_id
       FROM user_outgoing_messages
       WHERE user_id = $1 AND status = 'pending'
       ORDER BY timestamp ASC
       LIMIT 50`,
      [userId]
    );

    res.json({
      messages: messages.map((m) => ({
        id: m.id,
        address: m.address,
        body: m.body,
        timestamp: parseInt(m.timestamp),
        simSubscriptionId: m.sim_subscription_id,
      })),
    });
  } catch (error) {
    console.error('Get outgoing messages error:', error);
    res.status(500).json({ error: 'Failed to get outgoing messages' });
  }
});

// PUT /messages/outgoing/:id/status - Update outgoing message status
router.put('/outgoing/:id/status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { status, error: errorMsg } = req.body;
    const userId = req.userId!;

    if (!['sent', 'failed'].includes(status)) {
      res.status(400).json({ error: 'Invalid status' });
      return;
    }

    await query(
      `UPDATE user_outgoing_messages
       SET status = $1, error_message = $2
       WHERE id = $3 AND user_id = $4`,
      [status, errorMsg, id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Update outgoing status error:', error);
    res.status(500).json({ error: 'Failed to update status' });
  }
});

// PUT /messages/:id/read - Mark message as read
router.put('/:id/read', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `UPDATE user_messages SET read = true, updated_at = NOW()
       WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Mark read error:', error);
    res.status(500).json({ error: 'Failed to mark as read' });
  }
});

// GET /messages/count - Get message count (for sync status)
router.get('/count', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const result = await queryOne<{ count: string }>(
      `SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1`,
      [userId]
    );

    res.json({ count: parseInt(result?.count || '0') });
  } catch (error) {
    console.error('Get count error:', error);
    res.status(500).json({ error: 'Failed to get count' });
  }
});

export default router;
