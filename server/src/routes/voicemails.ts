import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const voicemailSchema = z.object({
  id: z.string().optional(),
  phoneNumber: z.string().max(50),
  duration: z.number(),
  date: z.number(),
  storageUrl: z.string().optional(),
  transcription: z.string().max(10000).optional(),
  isRead: z.boolean().default(false),
});

// GET /voicemails - Get voicemails
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);

    const voicemails = await query(
      `SELECT id, phone_number, duration, storage_url, transcription, date, is_read, created_at
       FROM user_voicemails
       WHERE user_id = $1
       ORDER BY date DESC
       LIMIT $2`,
      [userId, limit]
    );

    res.json({
      voicemails: voicemails.map(v => ({
        id: v.id,
        phoneNumber: v.phone_number,
        duration: v.duration,
        storageUrl: v.storage_url,
        transcription: v.transcription,
        date: parseInt(v.date),
        isRead: v.is_read,
      })),
    });
  } catch (error) {
    console.error('Get voicemails error:', error);
    res.status(500).json({ error: 'Failed to get voicemails' });
  }
});

// POST /voicemails - Sync voicemail
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = voicemailSchema.parse(req.body);
    const userId = req.userId!;
    const voicemailId = body.id || `vm_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_voicemails
       (id, user_id, phone_number, duration, storage_url, transcription, date, is_read)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
       ON CONFLICT (id) DO UPDATE SET
         transcription = COALESCE(EXCLUDED.transcription, user_voicemails.transcription),
         is_read = EXCLUDED.is_read`,
      [voicemailId, userId, body.phoneNumber, body.duration,
       body.storageUrl, body.transcription, body.date, body.isRead]
    );

    res.json({ id: voicemailId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Sync voicemail error:', error);
    res.status(500).json({ error: 'Failed to sync voicemail' });
  }
});

// PUT /voicemails/:id/read - Mark voicemail as read
router.put('/:id/read', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { isRead } = req.body;
    const userId = req.userId!;

    await query(
      `UPDATE user_voicemails SET is_read = $1
       WHERE id = $2 AND user_id = $3`,
      [isRead !== false, id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Mark voicemail read error:', error);
    res.status(500).json({ error: 'Failed to mark voicemail read' });
  }
});

// DELETE /voicemails/:id - Delete voicemail
router.delete('/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_voicemails WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Delete voicemail error:', error);
    res.status(500).json({ error: 'Failed to delete voicemail' });
  }
});

export default router;
