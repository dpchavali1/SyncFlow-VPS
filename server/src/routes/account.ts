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

// GET /api/account/export-data
// GDPR / Apple / Google privacy compliance: allow users to export all their data
router.get('/export-data', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    // Fetch user profile
    const user = await queryOne<Record<string, any>>(
      `SELECT uid, phone_number, email, display_name, created_at, last_active_at,
              plan, plan_expires_at, trial_started_at, admin
       FROM users WHERE uid = $1`,
      [userId]
    );

    if (!user) {
      res.status(404).json({ error: 'User not found' });
      return;
    }

    // Fetch all user data in parallel
    const [
      devices,
      messages,
      contacts,
      callHistory,
      scheduledMessages,
      spamLists,
      subscriptions,
      fileTransfers,
      notifications,
    ] = await Promise.all([
      query<Record<string, any>>(
        `SELECT device_id, device_name, device_type, platform, last_seen_at, created_at
         FROM user_devices WHERE user_id = $1 ORDER BY created_at DESC`,
        [userId]
      ),
      query<Record<string, any>>(
        `SELECT id, thread_id, sender, body, timestamp, read, message_type, status
         FROM user_messages WHERE user_id = $1 ORDER BY timestamp DESC LIMIT 10000`,
        [userId]
      ),
      query<Record<string, any>>(
        `SELECT id, display_name, phone_number, email, photo_uri, starred, created_at
         FROM user_contacts WHERE user_id = $1 ORDER BY display_name ASC`,
        [userId]
      ),
      query<Record<string, any>>(
        `SELECT id, phone_number, contact_name, call_type, duration, timestamp
         FROM user_call_history WHERE user_id = $1 ORDER BY timestamp DESC LIMIT 5000`,
        [userId]
      ),
      query<Record<string, any>>(
        `SELECT id, recipient, body, scheduled_at, status, created_at
         FROM user_scheduled_messages WHERE user_id = $1 ORDER BY created_at DESC`,
        [userId]
      ),
      query<Record<string, any>>(
        `SELECT id, phone_number, list_type, reason, created_at
         FROM user_spam_lists WHERE user_id = $1 ORDER BY created_at DESC`,
        [userId]
      ),
      query<Record<string, any>>(
        `SELECT id, plan, status, started_at, expires_at, source
         FROM user_subscriptions WHERE user_id = $1 ORDER BY started_at DESC`,
        [userId]
      ),
      query<Record<string, any>>(
        `SELECT id, file_name, file_size, direction, status, created_at
         FROM user_file_transfers WHERE user_id = $1 ORDER BY created_at DESC LIMIT 1000`,
        [userId]
      ),
      query<Record<string, any>>(
        `SELECT id, app_name, title, body, timestamp
         FROM user_notifications WHERE user_id = $1 ORDER BY timestamp DESC LIMIT 5000`,
        [userId]
      ),
    ]);

    const exportData = {
      exportedAt: new Date().toISOString(),
      user: {
        ...user,
        // Redact sensitive internal fields
        admin: undefined,
      },
      devices: devices || [],
      messages: messages || [],
      contacts: contacts || [],
      callHistory: callHistory || [],
      scheduledMessages: scheduledMessages || [],
      spamLists: spamLists || [],
      subscriptions: subscriptions || [],
      fileTransfers: fileTransfers || [],
      notifications: notifications || [],
    };

    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Content-Disposition', `attachment; filename="syncflow-data-export-${Date.now()}.json"`);
    res.json(exportData);
  } catch (error) {
    console.error('Data export error:', error);
    res.status(500).json({ error: 'Failed to export data' });
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
