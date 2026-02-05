import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const spamMessageSchema = z.object({
  id: z.string().optional(),
  address: z.string().max(500),
  body: z.string().max(5000).optional(),
  date: z.number(),
  spamScore: z.number().optional(),
  spamReason: z.string().max(255).optional(),
});

const listEntrySchema = z.object({
  phoneNumber: z.string().max(50),
});

// ==================== Spam Messages ====================

// GET /spam/messages - Get spam messages
router.get('/messages', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);

    const messages = await query(
      `SELECT id, address, body, date, spam_score, spam_reason, created_at
       FROM user_spam_messages
       WHERE user_id = $1
       ORDER BY date DESC
       LIMIT $2`,
      [userId, limit]
    );

    res.json({
      messages: messages.map(m => ({
        id: m.id,
        address: m.address,
        body: m.body,
        date: parseInt(m.date),
        spamScore: m.spam_score,
        spamReason: m.spam_reason,
      })),
    });
  } catch (error) {
    console.error('Get spam messages error:', error);
    res.status(500).json({ error: 'Failed to get spam messages' });
  }
});

// POST /spam/messages - Sync spam message
router.post('/messages', async (req: Request, res: Response) => {
  try {
    const body = spamMessageSchema.parse(req.body);
    const userId = req.userId!;
    const messageId = body.id || `spam_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_spam_messages
       (id, user_id, address, body, date, spam_score, spam_reason)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (id) DO UPDATE SET
         spam_score = EXCLUDED.spam_score,
         spam_reason = EXCLUDED.spam_reason`,
      [messageId, userId, body.address, body.body, body.date,
       body.spamScore, body.spamReason]
    );

    res.json({ id: messageId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Sync spam message error:', error);
    res.status(500).json({ error: 'Failed to sync spam message' });
  }
});

// DELETE /spam/messages/:id - Delete spam message
router.delete('/messages/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_spam_messages WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Delete spam message error:', error);
    res.status(500).json({ error: 'Failed to delete spam message' });
  }
});

// DELETE /spam/messages - Clear all spam messages
router.delete('/messages', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const result = await query(
      `DELETE FROM user_spam_messages WHERE user_id = $1 RETURNING id`,
      [userId]
    );

    res.json({ success: true, deleted: result.length });
  } catch (error) {
    console.error('Clear spam messages error:', error);
    res.status(500).json({ error: 'Failed to clear spam messages' });
  }
});

// ==================== Whitelist ====================

// GET /spam/whitelist - Get whitelist
router.get('/whitelist', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const entries = await query(
      `SELECT phone_number, created_at
       FROM user_spam_lists
       WHERE user_id = $1 AND list_type = 'whitelist'
       ORDER BY created_at DESC`,
      [userId]
    );

    res.json({
      whitelist: entries.map(e => ({
        phoneNumber: e.phone_number,
        addedAt: new Date(e.created_at).getTime(),
      })),
    });
  } catch (error) {
    console.error('Get whitelist error:', error);
    res.status(500).json({ error: 'Failed to get whitelist' });
  }
});

// POST /spam/whitelist - Add to whitelist
router.post('/whitelist', async (req: Request, res: Response) => {
  try {
    const body = listEntrySchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO user_spam_lists (user_id, phone_number, list_type, created_at)
       VALUES ($1, $2, 'whitelist', NOW())
       ON CONFLICT (user_id, phone_number, list_type) DO NOTHING`,
      [userId, body.phoneNumber]
    );

    // Remove from blocklist if present
    await query(
      `DELETE FROM user_spam_lists
       WHERE user_id = $1 AND phone_number = $2 AND list_type = 'blocklist'`,
      [userId, body.phoneNumber]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Add to whitelist error:', error);
    res.status(500).json({ error: 'Failed to add to whitelist' });
  }
});

// DELETE /spam/whitelist/:phoneNumber - Remove from whitelist
router.delete('/whitelist/:phoneNumber', async (req: Request, res: Response) => {
  try {
    const { phoneNumber } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_spam_lists
       WHERE user_id = $1 AND phone_number = $2 AND list_type = 'whitelist'`,
      [userId, phoneNumber]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Remove from whitelist error:', error);
    res.status(500).json({ error: 'Failed to remove from whitelist' });
  }
});

// ==================== Blocklist ====================

// GET /spam/blocklist - Get blocklist
router.get('/blocklist', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const entries = await query(
      `SELECT phone_number, created_at
       FROM user_spam_lists
       WHERE user_id = $1 AND list_type = 'blocklist'
       ORDER BY created_at DESC`,
      [userId]
    );

    res.json({
      blocklist: entries.map(e => ({
        phoneNumber: e.phone_number,
        addedAt: new Date(e.created_at).getTime(),
      })),
    });
  } catch (error) {
    console.error('Get blocklist error:', error);
    res.status(500).json({ error: 'Failed to get blocklist' });
  }
});

// POST /spam/blocklist - Add to blocklist
router.post('/blocklist', async (req: Request, res: Response) => {
  try {
    const body = listEntrySchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO user_spam_lists (user_id, phone_number, list_type, created_at)
       VALUES ($1, $2, 'blocklist', NOW())
       ON CONFLICT (user_id, phone_number, list_type) DO NOTHING`,
      [userId, body.phoneNumber]
    );

    // Remove from whitelist if present
    await query(
      `DELETE FROM user_spam_lists
       WHERE user_id = $1 AND phone_number = $2 AND list_type = 'whitelist'`,
      [userId, body.phoneNumber]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Add to blocklist error:', error);
    res.status(500).json({ error: 'Failed to add to blocklist' });
  }
});

// DELETE /spam/blocklist/:phoneNumber - Remove from blocklist
router.delete('/blocklist/:phoneNumber', async (req: Request, res: Response) => {
  try {
    const { phoneNumber } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_spam_lists
       WHERE user_id = $1 AND phone_number = $2 AND list_type = 'blocklist'`,
      [userId, phoneNumber]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Remove from blocklist error:', error);
    res.status(500).json({ error: 'Failed to remove from blocklist' });
  }
});

// GET /spam/check/:phoneNumber - Check if number is spam/whitelist/blocklist
router.get('/check/:phoneNumber', async (req: Request, res: Response) => {
  try {
    const { phoneNumber } = req.params;
    const userId = req.userId!;

    const entry = await query(
      `SELECT list_type FROM user_spam_lists
       WHERE user_id = $1 AND phone_number = $2`,
      [userId, phoneNumber]
    );

    res.json({
      isWhitelisted: entry.some(e => e.list_type === 'whitelist'),
      isBlocklisted: entry.some(e => e.list_type === 'blocklist'),
    });
  } catch (error) {
    console.error('Check spam status error:', error);
    res.status(500).json({ error: 'Failed to check spam status' });
  }
});

export default router;
