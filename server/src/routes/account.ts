import { Router, Request, Response } from 'express';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { sendDeletionRequestEmail, sendDeletionCancelledEmail } from '../services/email';

const router = Router();

// All routes require authentication
router.use(authenticate);

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;

// GET /api/account/deletion-status
router.get('/deletion-status', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const user = await queryOne<{
      deletion_requested_at: string | null;
      deletion_reason: string | null;
      deletion_scheduled_for: string | null;
    }>(
      'SELECT deletion_requested_at, deletion_reason, deletion_scheduled_for FROM users WHERE uid = $1',
      [userId]
    );

    if (!user) {
      res.status(404).json({ error: 'User not found' });
      return;
    }

    const scheduled = !!user.deletion_requested_at;
    const scheduledFor = user.deletion_scheduled_for ? parseInt(user.deletion_scheduled_for) : null;
    const requestedAt = user.deletion_requested_at ? parseInt(user.deletion_requested_at) : null;
    const daysRemaining = scheduledFor ? Math.max(0, Math.ceil((scheduledFor - Date.now()) / (24 * 60 * 60 * 1000))) : 0;

    res.json({
      scheduled,
      scheduledDate: scheduledFor,
      reason: user.deletion_reason,
      requestedAt,
      daysRemaining,
      // Android-compatible fields
      isScheduledForDeletion: scheduled,
      scheduledDeletionAt: scheduledFor,
    });
  } catch (error) {
    console.error('Deletion status error:', error);
    res.status(500).json({ error: 'Failed to get deletion status' });
  }
});

// POST /api/account/delete
router.post('/delete', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const { reason } = req.body;

    const now = Date.now();
    const scheduledFor = now + THIRTY_DAYS_MS;

    await query(
      `UPDATE users SET deletion_requested_at = $1, deletion_reason = $2, deletion_scheduled_for = $3 WHERE uid = $4`,
      [now, reason || 'No reason provided', scheduledFor, userId]
    );

    // Get user email for notification
    const user = await queryOne<{ email: string | null }>('SELECT email FROM users WHERE uid = $1', [userId]);

    // Send admin email (non-blocking)
    sendDeletionRequestEmail(userId, user?.email || null, reason || 'No reason provided', new Date(scheduledFor));

    res.json({
      success: true,
      scheduledDeletionAt: scheduledFor,
      scheduledDate: scheduledFor,
      daysRemaining: 30,
    });
  } catch (error) {
    console.error('Request deletion error:', error);
    res.status(500).json({ error: 'Failed to request deletion' });
  }
});

// POST /api/account/cancel-deletion
router.post('/cancel-deletion', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    await query(
      `UPDATE users SET deletion_requested_at = NULL, deletion_reason = NULL, deletion_scheduled_for = NULL WHERE uid = $1`,
      [userId]
    );

    // Get user email for notification
    const user = await queryOne<{ email: string | null }>('SELECT email FROM users WHERE uid = $1', [userId]);

    // Send admin email (non-blocking)
    sendDeletionCancelledEmail(userId, user?.email || null);

    res.json({ success: true });
  } catch (error) {
    console.error('Cancel deletion error:', error);
    res.status(500).json({ error: 'Failed to cancel deletion' });
  }
});

export default router;
