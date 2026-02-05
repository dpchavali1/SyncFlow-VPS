import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const sharedLinkSchema = z.object({
  url: z.string().url().max(2000),
  title: z.string().max(500).optional(),
});

// GET /links/shared - Get pending shared links
router.get('/shared', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const links = await query(
      `SELECT id, url, title, source_device, status, timestamp
       FROM user_shared_links
       WHERE user_id = $1 AND status = 'pending'
       ORDER BY timestamp DESC
       LIMIT 50`,
      [userId]
    );

    res.json({
      links: links.map(l => ({
        id: l.id,
        url: l.url,
        title: l.title,
        sourceDevice: l.source_device,
        status: l.status,
        timestamp: parseInt(l.timestamp),
      })),
    });
  } catch (error) {
    console.error('Get shared links error:', error);
    res.status(500).json({ error: 'Failed to get shared links' });
  }
});

// POST /links/shared - Create shared link
router.post('/shared', async (req: Request, res: Response) => {
  try {
    const body = sharedLinkSchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;
    const linkId = `link_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_shared_links (id, user_id, url, title, source_device, status, timestamp)
       VALUES ($1, $2, $3, $4, $5, 'pending', $6)`,
      [linkId, userId, body.url, body.title, deviceId, Date.now()]
    );

    res.json({ id: linkId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create shared link error:', error);
    res.status(500).json({ error: 'Failed to create shared link' });
  }
});

// PUT /links/shared/:id/status - Update link status
router.put('/shared/:id/status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { status } = req.body;
    const userId = req.userId!;

    if (!['pending', 'opened', 'dismissed'].includes(status)) {
      res.status(400).json({ error: 'Invalid status' });
      return;
    }

    await query(
      `UPDATE user_shared_links SET status = $1
       WHERE id = $2 AND user_id = $3`,
      [status, id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Update shared link status error:', error);
    res.status(500).json({ error: 'Failed to update link status' });
  }
});

// DELETE /links/shared/:id - Delete shared link
router.delete('/shared/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_shared_links WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Delete shared link error:', error);
    res.status(500).json({ error: 'Failed to delete link' });
  }
});

export default router;
