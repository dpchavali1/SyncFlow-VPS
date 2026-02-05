import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const clipboardSchema = z.object({
  text: z.string().max(100000), // 100KB max
  type: z.string().default('text'),
  source: z.string().optional(),
});

// GET /clipboard - Get latest clipboard content
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const clipboard = await queryOne(
      `SELECT content, content_type, source_device, created_at
       FROM user_clipboard
       WHERE user_id = $1`,
      [userId]
    );

    if (!clipboard) {
      res.json({ text: null, type: null, timestamp: null, source: null });
      return;
    }

    res.json({
      text: clipboard.content,
      type: clipboard.content_type,
      timestamp: new Date(clipboard.created_at).getTime(),
      source: clipboard.source_device,
    });
  } catch (error) {
    console.error('Get clipboard error:', error);
    res.status(500).json({ error: 'Failed to get clipboard' });
  }
});

// POST /clipboard - Update clipboard content
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = clipboardSchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;

    await query(
      `INSERT INTO user_clipboard (user_id, content, content_type, source_device, created_at)
       VALUES ($1, $2, $3, $4, NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         content = EXCLUDED.content,
         content_type = EXCLUDED.content_type,
         source_device = EXCLUDED.source_device,
         created_at = NOW()`,
      [userId, body.text, body.type, body.source || deviceId]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update clipboard error:', error);
    res.status(500).json({ error: 'Failed to update clipboard' });
  }
});

// DELETE /clipboard - Clear clipboard
router.delete('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    await query(`DELETE FROM user_clipboard WHERE user_id = $1`, [userId]);

    res.json({ success: true });
  } catch (error) {
    console.error('Clear clipboard error:', error);
    res.status(500).json({ error: 'Failed to clear clipboard' });
  }
});

export default router;
