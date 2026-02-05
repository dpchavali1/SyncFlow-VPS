import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const notificationSchema = z.object({
  id: z.string().optional(),
  appPackage: z.string().max(255),
  appName: z.string().max(255).optional(),
  title: z.string().max(1000).optional(),
  body: z.string().max(5000).optional(),
  timestamp: z.number(),
});

// GET /notifications/mirror - Get mirrored notifications
router.get('/mirror', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);
    const since = req.query.since ? parseInt(req.query.since as string) : null;

    let queryText = `
      SELECT id, app_package, app_name, title, body, timestamp, is_read, created_at
      FROM user_notifications
      WHERE user_id = $1
    `;
    const params: any[] = [userId];

    if (since) {
      queryText += ` AND timestamp > $2`;
      params.push(since);
    }

    queryText += ` ORDER BY timestamp DESC LIMIT $${params.length + 1}`;
    params.push(limit);

    const notifications = await query(queryText, params);

    res.json({
      notifications: notifications.map(n => ({
        id: n.id,
        appPackage: n.app_package,
        appName: n.app_name,
        title: n.title,
        body: n.body,
        timestamp: parseInt(n.timestamp),
        isRead: n.is_read,
      })),
    });
  } catch (error) {
    console.error('Get mirrored notifications error:', error);
    res.status(500).json({ error: 'Failed to get notifications' });
  }
});

// POST /notifications/mirror - Sync mirrored notification
router.post('/mirror', async (req: Request, res: Response) => {
  try {
    const body = notificationSchema.parse(req.body);
    const userId = req.userId!;
    const notificationId = body.id || `notif_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_notifications
       (id, user_id, app_package, app_name, title, body, timestamp, is_read)
       VALUES ($1, $2, $3, $4, $5, $6, $7, FALSE)
       ON CONFLICT (id) DO UPDATE SET
         title = EXCLUDED.title,
         body = EXCLUDED.body,
         timestamp = EXCLUDED.timestamp`,
      [notificationId, userId, body.appPackage, body.appName, body.title, body.body, body.timestamp]
    );

    res.json({ id: notificationId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Sync notification error:', error);
    res.status(500).json({ error: 'Failed to sync notification' });
  }
});

// PUT /notifications/mirror/:id/read - Mark notification as read
router.put('/mirror/:id/read', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `UPDATE user_notifications SET is_read = TRUE
       WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Mark notification read error:', error);
    res.status(500).json({ error: 'Failed to mark notification read' });
  }
});

// DELETE /notifications/mirror/:id - Remove mirrored notification
router.delete('/mirror/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_notifications WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Delete notification error:', error);
    res.status(500).json({ error: 'Failed to delete notification' });
  }
});

// DELETE /notifications/mirror - Clear all notifications (or older than 7 days)
router.delete('/mirror', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const clearAll = req.query.all === 'true';

    let result;
    if (clearAll) {
      result = await query(
        `DELETE FROM user_notifications WHERE user_id = $1 RETURNING id`,
        [userId]
      );
    } else {
      // Delete notifications older than 7 days
      const sevenDaysAgo = Date.now() - (7 * 24 * 60 * 60 * 1000);
      result = await query(
        `DELETE FROM user_notifications WHERE user_id = $1 AND timestamp < $2 RETURNING id`,
        [userId, sevenDaysAgo]
      );
    }

    res.json({ success: true, deleted: result.length });
  } catch (error) {
    console.error('Clear notifications error:', error);
    res.status(500).json({ error: 'Failed to clear notifications' });
  }
});

export default router;
