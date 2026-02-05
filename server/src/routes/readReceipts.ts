import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const readReceiptSchema = z.object({
  messageKey: z.string(),
  readAt: z.number(),
  readBy: z.string().optional(),
  conversationAddress: z.string().max(500),
  readDeviceName: z.string().max(255).optional(),
  sourceId: z.number().optional(),
  sourceType: z.string().optional(),
});

const batchReadReceiptsSchema = z.object({
  receipts: z.array(readReceiptSchema).max(100),
});

// GET /read-receipts - Get read receipts
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const limit = Math.min(parseInt(req.query.limit as string) || 100, 500);
    const since = req.query.since ? parseInt(req.query.since as string) : null;

    let queryText = `
      SELECT id, message_id, message_key, device_id, conversation_address,
             read_device_name, read_at
      FROM user_read_receipts
      WHERE user_id = $1
    `;
    const params: any[] = [userId];

    if (since) {
      queryText += ` AND read_at > to_timestamp($2 / 1000.0)`;
      params.push(since);
    }

    queryText += ` ORDER BY read_at DESC LIMIT $${params.length + 1}`;
    params.push(limit);

    const receipts = await query(queryText, params);

    res.json({
      receipts: receipts.map(r => ({
        id: r.id,
        messageId: r.message_id,
        messageKey: r.message_key,
        deviceId: r.device_id,
        conversationAddress: r.conversation_address,
        readDeviceName: r.read_device_name,
        readAt: r.read_at ? new Date(r.read_at).getTime() : null,
      })),
    });
  } catch (error) {
    console.error('Get read receipts error:', error);
    res.status(500).json({ error: 'Failed to get read receipts' });
  }
});

// POST /read-receipts - Create single read receipt
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = readReceiptSchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;

    await query(
      `INSERT INTO user_read_receipts
       (user_id, message_key, device_id, conversation_address, read_device_name, read_at)
       VALUES ($1, $2, $3, $4, $5, to_timestamp($6 / 1000.0))
       ON CONFLICT (user_id, message_id) DO UPDATE SET
         read_at = EXCLUDED.read_at`,
      [userId, body.messageKey, deviceId, body.conversationAddress,
       body.readDeviceName, body.readAt]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create read receipt error:', error);
    res.status(500).json({ error: 'Failed to create read receipt' });
  }
});

// POST /read-receipts/batch - Create batch of read receipts
router.post('/batch', async (req: Request, res: Response) => {
  try {
    const body = batchReadReceiptsSchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;

    let created = 0;
    for (const receipt of body.receipts) {
      try {
        await query(
          `INSERT INTO user_read_receipts
           (user_id, message_key, device_id, conversation_address, read_device_name, read_at)
           VALUES ($1, $2, $3, $4, $5, to_timestamp($6 / 1000.0))
           ON CONFLICT DO NOTHING`,
          [userId, receipt.messageKey, deviceId, receipt.conversationAddress,
           receipt.readDeviceName, receipt.readAt]
        );
        created++;
      } catch (e) {
        // Continue on individual failures
      }
    }

    res.json({ success: true, created, total: body.receipts.length });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Batch read receipts error:', error);
    res.status(500).json({ error: 'Failed to create read receipts' });
  }
});

// DELETE /read-receipts/cleanup - Cleanup old read receipts (>30 days)
router.delete('/cleanup', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const thirtyDaysAgo = Date.now() - (30 * 24 * 60 * 60 * 1000);

    const result = await query(
      `DELETE FROM user_read_receipts
       WHERE user_id = $1 AND read_at < to_timestamp($2 / 1000.0)
       RETURNING id`,
      [userId, thirtyDaysAgo]
    );

    res.json({ success: true, deleted: result.length });
  } catch (error) {
    console.error('Cleanup read receipts error:', error);
    res.status(500).json({ error: 'Failed to cleanup read receipts' });
  }
});

export default router;
