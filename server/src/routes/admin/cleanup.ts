/**
 * Admin sub-router: Cleanup Operations, Orphans, Duplicates & Pending Deletions.
 *
 * Cleanup endpoints run batched DELETEs (LIMIT 10000 via ctid subquery) to avoid
 * OOM on large tables. Orphan detection finds rows whose user_id no longer exists
 * in the users table. Duplicate detection finds users sharing firebase_uid or phone.
 *
 * Endpoints:
 *   POST   /cleanup/auto                    — Auto-cleanup all expired/stale data categories
 *   POST   /cleanup/messages                — Delete old messages (+ R2 MMS attachments)
 *   POST   /cleanup/devices                 — Delete devices not seen in 90+ days
 *   POST   /cleanup/devices/deduplicate     — Keep only newest device per type per user
 *   DELETE /users/:userId/devices/stale     — Remove stale devices for a specific user
 *   GET    /orphans                         — Detect orphaned data counts
 *   POST   /cleanup/orphans                 — Delete orphaned data rows
 *   GET    /duplicates                      — Detect duplicate users (by firebase_uid or phone)
 *   POST   /cleanup/duplicates              — Merge data into keepUserId, delete duplicates
 *   GET    /pending-deletions               — List users with scheduled account deletions
 *   POST   /process-deletion/:userId        — Immediately process a pending deletion
 *   POST   /cancel-deletion/:userId         — Cancel a pending deletion
 */

import { Router, Request, Response } from 'express';
import { query, queryOne } from '../../services/database';
import { deleteUserData, deleteR2Objects } from './helpers';

const router = Router();

// ── POST /cleanup/auto — Auto-cleanup all stale/expired data ────────────────

router.post('/cleanup/auto', async (req: Request, res: Response) => {
  try {
    const results: Record<string, number> = {};

    // Each DELETE uses a ctid subquery with LIMIT 10000 to prevent OOM on large tables
    const cleanups: Array<{ name: string; sql: string }> = [
      { name: 'expiredPairings', sql: `DELETE FROM pairing_requests WHERE ctid IN (SELECT ctid FROM pairing_requests WHERE expires_at < NOW() OR (status != 'pending' AND created_at < NOW() - INTERVAL '1 day') LIMIT 10000) RETURNING *` },
      { name: 'oldOutgoingMessages', sql: `DELETE FROM user_outgoing_messages WHERE ctid IN (SELECT ctid FROM user_outgoing_messages WHERE status != 'pending' AND created_at < NOW() - INTERVAL '7 days' LIMIT 10000) RETURNING *` },
      { name: 'oldSpamMessages', sql: `DELETE FROM user_spam_messages WHERE ctid IN (SELECT ctid FROM user_spam_messages WHERE created_at < NOW() - INTERVAL '30 days' LIMIT 10000) RETURNING *` },
      { name: 'oldReadReceipts', sql: `DELETE FROM user_read_receipts WHERE ctid IN (SELECT ctid FROM user_read_receipts WHERE read_at < NOW() - INTERVAL '30 days' LIMIT 10000) RETURNING *` },
      { name: 'oldTypingIndicators', sql: `DELETE FROM user_typing_indicators WHERE ctid IN (SELECT ctid FROM user_typing_indicators WHERE created_at < NOW() - INTERVAL '1 hour' LIMIT 10000) RETURNING *` },
      { name: 'oldNotifications', sql: `DELETE FROM user_notifications WHERE ctid IN (SELECT ctid FROM user_notifications WHERE created_at < NOW() - INTERVAL '30 days' LIMIT 10000) RETURNING *` },
      { name: 'oldClipboard', sql: `DELETE FROM user_clipboard WHERE ctid IN (SELECT ctid FROM user_clipboard WHERE created_at < NOW() - INTERVAL '7 days' LIMIT 10000) RETURNING *` },
      { name: 'oldSharedLinks', sql: `DELETE FROM user_shared_links WHERE ctid IN (SELECT ctid FROM user_shared_links WHERE created_at < NOW() - INTERVAL '30 days' LIMIT 10000) RETURNING *` },
      { name: 'inactiveDevices', sql: `DELETE FROM user_devices WHERE ctid IN (SELECT ctid FROM user_devices WHERE last_seen < NOW() - INTERVAL '90 days' LIMIT 10000) RETURNING *` },
      { name: 'oldCallRequests', sql: `DELETE FROM user_call_requests WHERE ctid IN (SELECT ctid FROM user_call_requests WHERE status != 'pending' AND created_at < NOW() - INTERVAL '7 days' LIMIT 10000) RETURNING *` },
      { name: 'expiredE2eeRequests', sql: `DELETE FROM e2ee_key_requests WHERE ctid IN (SELECT ctid FROM e2ee_key_requests WHERE created_at < NOW() - INTERVAL '7 days' LIMIT 10000) RETURNING *` },
      { name: 'oldCallCommands', sql: `DELETE FROM user_call_commands WHERE ctid IN (SELECT ctid FROM user_call_commands WHERE created_at < NOW() - INTERVAL '7 days' LIMIT 10000) RETURNING *` },
      // Clean up orphaned users from abandoned pairing initiations (no devices AND no messages AND older than 1 hour)
      { name: 'orphanedPairingUsers', sql: `DELETE FROM users WHERE ctid IN (SELECT ctid FROM users WHERE uid NOT IN (SELECT DISTINCT user_id FROM user_devices) AND uid NOT IN (SELECT DISTINCT user_id FROM user_messages) AND created_at < NOW() - INTERVAL '1 hour' LIMIT 10000) RETURNING *` },
    ];

    for (const cleanup of cleanups) {
      try {
        const deleted = await query(cleanup.sql);
        results[cleanup.name] = deleted.length;
      } catch (e) {
        results[cleanup.name] = 0;
      }
    }

    // Process overdue account deletions
    try {
      const overdue = await query<{ uid: string; email: string | null; phone: string | null; deletion_reason: string | null }>(
        `SELECT uid, email, phone, deletion_reason FROM users WHERE deletion_scheduled_for IS NOT NULL AND deletion_scheduled_for <= $1`,
        [Date.now()]
      );
      let deletedAccounts = 0;
      for (const user of overdue) {
        try {
          await query(
            `INSERT INTO deleted_accounts (user_id, email, phone, deletion_reason, deleted_by) VALUES ($1, $2, $3, $4, 'auto-cleanup')`,
            [user.uid, user.email, user.phone, user.deletion_reason]
          );
          await deleteUserData(user.uid);
          deletedAccounts++;
        } catch (e) {
          console.error(`[Admin] Failed to process overdue deletion for ${user.uid}:`, e);
        }
      }
      results['overdueDeletions'] = deletedAccounts;
    } catch (e) {
      results['overdueDeletions'] = 0;
    }

    const totalDeleted = Object.values(results).reduce((sum, c) => sum + c, 0);
    res.json({ success: true, totalDeleted, breakdown: results });
  } catch (error) {
    console.error('Admin auto cleanup error:', error);
    res.status(500).json({ error: 'Failed to run auto cleanup' });
  }
});

// ── POST /cleanup/messages — Delete old messages (with R2 MMS cleanup) ──────

router.post('/cleanup/messages', async (req: Request, res: Response) => {
  try {
    const { olderThanDays, mmsOnly } = req.body;
    if (!olderThanDays || olderThanDays < 1) { res.status(400).json({ error: 'olderThanDays required' }); return; }

    // Collect R2 keys from MMS parts before deleting
    let mmsR2Keys: string[] = [];
    try {
      let mmsSql = `SELECT mms_parts FROM user_messages WHERE date < (NOW() - INTERVAL '1 day' * $1) AND mms_parts IS NOT NULL`;
      if (mmsOnly) mmsSql += ' AND is_mms = true';
      const mmsRows = await query<{ mms_parts: any }>(mmsSql, [olderThanDays]);
      for (const row of mmsRows) {
        let parts = row.mms_parts;
        if (typeof parts === 'string') { try { parts = JSON.parse(parts); } catch { continue; } }
        if (Array.isArray(parts)) {
          for (const part of parts) { if (part?.r2Key) mmsR2Keys.push(part.r2Key); }
        }
      }
    } catch (e) {}

    // Delete R2 objects
    const r2Deleted = await deleteR2Objects(mmsR2Keys);

    // Delete DB rows
    let sql = `DELETE FROM user_messages WHERE date < (NOW() - INTERVAL '1 day' * $1)`;
    if (mmsOnly) sql += ' AND is_mms = true';
    sql += ' RETURNING id';

    const deleted = await query(sql, [olderThanDays]);
    res.json({ success: true, deletedCount: deleted.length, r2Deleted, olderThanDays, mmsOnly: !!mmsOnly });
  } catch (error) {
    console.error('Admin cleanup messages error:', error);
    res.status(500).json({ error: 'Failed to cleanup messages' });
  }
});

// ── POST /cleanup/devices — Delete devices not seen in 90+ days ─────────────

router.post('/cleanup/devices', async (req: Request, res: Response) => {
  try {
    const deleted = await query(`DELETE FROM user_devices WHERE last_seen < NOW() - INTERVAL '90 days' RETURNING *`);
    res.json({ success: true, deletedCount: deleted.length });
  } catch (error) {
    console.error('Admin cleanup devices error:', error);
    res.status(500).json({ error: 'Failed to cleanup devices' });
  }
});

// ── POST /cleanup/devices/deduplicate — Keep newest device per type per user ─

router.post('/cleanup/devices/deduplicate', async (req: Request, res: Response) => {
  try {
    const deleted = await query(
      `DELETE FROM user_devices
       WHERE id NOT IN (
         SELECT DISTINCT ON (user_id, device_type) id
         FROM user_devices
         ORDER BY user_id, device_type, last_seen DESC
       )
       RETURNING id, user_id, device_type, last_seen`
    );
    console.log(`[Admin] Deduplicated devices: removed ${deleted.length} duplicates`);
    res.json({ success: true, deletedCount: deleted.length, deleted: deleted.map(d => ({ id: d.id, userId: d.user_id, type: d.device_type })) });
  } catch (error) {
    console.error('Admin deduplicate devices error:', error);
    res.status(500).json({ error: 'Failed to deduplicate devices' });
  }
});

// ── DELETE /users/:userId/devices/stale — Remove stale devices for a user ───

router.delete('/users/:userId/devices/stale', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    // Keep only the most recently seen device of each type
    const deleted = await query(
      `DELETE FROM user_devices
       WHERE user_id = $1
       AND id NOT IN (
         SELECT DISTINCT ON (device_type) id
         FROM user_devices
         WHERE user_id = $1
         ORDER BY device_type, last_seen DESC
       )
       RETURNING id, device_type, last_seen`,
      [userId]
    );
    console.log(`[Admin] Cleaned stale devices for user ${userId}: removed ${deleted.length}`);
    res.json({ success: true, deletedCount: deleted.length, remaining: await query('SELECT id, device_type, last_seen FROM user_devices WHERE user_id = $1', [userId]) });
  } catch (error) {
    console.error('Admin clean user devices error:', error);
    res.status(500).json({ error: 'Failed to clean devices' });
  }
});

// ── GET /orphans — Detect orphaned data counts ──────────────────────────────

router.get('/orphans', async (req: Request, res: Response) => {
  try {
    const counts = await queryOne<{
      orphaned_messages: string; orphaned_contacts: string; orphaned_devices: string;
      orphaned_calls: string; users_no_devices: string; users_no_messages: string;
    }>(`
      SELECT
        (SELECT COUNT(*) FROM user_messages m WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = m.user_id)) as orphaned_messages,
        (SELECT COUNT(*) FROM user_contacts c WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = c.user_id)) as orphaned_contacts,
        (SELECT COUNT(*) FROM user_devices d WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = d.user_id)) as orphaned_devices,
        (SELECT COUNT(*) FROM user_call_history ch WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = ch.user_id)) as orphaned_calls,
        (SELECT COUNT(*) FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = u.uid)) as users_no_devices,
        (SELECT COUNT(*) FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_messages m WHERE m.user_id = u.uid)) as users_no_messages
    `);

    const p = (v: string | undefined) => parseInt(v || '0');
    res.json({
      orphanedMessages: p(counts?.orphaned_messages), orphanedContacts: p(counts?.orphaned_contacts),
      orphanedDevices: p(counts?.orphaned_devices), orphanedCalls: p(counts?.orphaned_calls),
      usersWithoutDevices: p(counts?.users_no_devices), usersWithoutMessages: p(counts?.users_no_messages),
    });
  } catch (error) {
    console.error('Admin orphans error:', error);
    res.status(500).json({ error: 'Failed to detect orphaned data' });
  }
});

// ── POST /cleanup/orphans — Delete orphaned rows ────────────────────────────
// Table names are from the hardcoded ORPHAN_CLEANUP_ALLOWED_TABLES array (not user input).
// Uses ctid subquery to LIMIT deletes and prevent OOM on large tables.

const ORPHAN_CLEANUP_ALLOWED_TABLES = [
  'user_messages', 'user_contacts', 'user_call_history',
  'user_devices', 'user_photos', 'user_file_transfers',
];

router.post('/cleanup/orphans', async (req: Request, res: Response) => {
  try {
    const { deleteUsersWithoutDevices } = req.body || {};
    const results: Record<string, number> = {};

    // Hardcoded SQL per table to avoid interpolation of table names
    for (const table of ORPHAN_CLEANUP_ALLOWED_TABLES) {
      try {
        const sql = ({
          user_messages: `DELETE FROM user_messages WHERE ctid IN (SELECT ctid FROM user_messages WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = user_messages.user_id) LIMIT 10000) RETURNING *`,
          user_contacts: `DELETE FROM user_contacts WHERE ctid IN (SELECT ctid FROM user_contacts WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = user_contacts.user_id) LIMIT 10000) RETURNING *`,
          user_call_history: `DELETE FROM user_call_history WHERE ctid IN (SELECT ctid FROM user_call_history WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = user_call_history.user_id) LIMIT 10000) RETURNING *`,
          user_devices: `DELETE FROM user_devices WHERE ctid IN (SELECT ctid FROM user_devices WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = user_devices.user_id) LIMIT 10000) RETURNING *`,
          user_photos: `DELETE FROM user_photos WHERE ctid IN (SELECT ctid FROM user_photos WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = user_photos.user_id) LIMIT 10000) RETURNING *`,
          user_file_transfers: `DELETE FROM user_file_transfers WHERE ctid IN (SELECT ctid FROM user_file_transfers WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = user_file_transfers.user_id) LIMIT 10000) RETURNING *`,
        } as Record<string, string>)[table];
        if (!sql) continue;
        const deleted = await query(sql);
        results[table] = deleted.length;
      } catch (e) { results[table] = 0; }
    }

    if (deleteUsersWithoutDevices) {
      const usersNoDevices = await query<{ uid: string }>(`SELECT u.uid FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = u.uid)`);
      let count = 0;
      for (const user of usersNoDevices) {
        try { await deleteUserData(user.uid); count++; } catch (e) {}
      }
      results.usersWithoutDevices = count;
    }

    const totalDeleted = Object.values(results).reduce((sum, c) => sum + c, 0);
    res.json({ success: true, totalDeleted, breakdown: results });
  } catch (error) {
    console.error('Admin cleanup orphans error:', error);
    res.status(500).json({ error: 'Failed to cleanup orphans' });
  }
});

// ── GET /duplicates — Detect duplicate users ────────────────────────────────

router.get('/duplicates', async (req: Request, res: Response) => {
  try {
    // Find users sharing the same firebase_uid (excluding NULL)
    const byFirebaseUid = await query<{ firebase_uid: string; user_ids: string[]; count: string }>(`
      SELECT firebase_uid, array_agg(uid) as user_ids, COUNT(*) as count FROM users
      WHERE firebase_uid IS NOT NULL AND firebase_uid != '' GROUP BY firebase_uid HAVING COUNT(*) > 1
    `);

    // Find users sharing the same phone number (excluding NULL)
    const byPhoneNumber = await query<{ phone: string; user_ids: string[]; count: string }>(`
      SELECT phone, array_agg(uid) as user_ids, COUNT(*) as count FROM users
      WHERE phone IS NOT NULL AND phone != '' GROUP BY phone HAVING COUNT(*) > 1
    `);

    res.json({
      byFirebaseUid: byFirebaseUid.map(d => ({ firebaseUid: d.firebase_uid, userIds: d.user_ids, count: parseInt(d.count) })),
      byPhoneNumber: byPhoneNumber.map(d => ({ phoneNumber: d.phone, userIds: d.user_ids, count: parseInt(d.count) })),
    });
  } catch (error) {
    console.error('Admin duplicates error:', error);
    res.status(500).json({ error: 'Failed to detect duplicates' });
  }
});

// ── POST /cleanup/duplicates — Merge + delete duplicate users ───────────────
// Migrates data from deleteUserIds into keepUserId, then deletes the duplicates.

router.post('/cleanup/duplicates', async (req: Request, res: Response) => {
  try {
    const { keepUserId, deleteUserIds } = req.body;
    if (!keepUserId || !Array.isArray(deleteUserIds)) { res.status(400).json({ error: 'keepUserId and deleteUserIds[] required' }); return; }

    const migrationCounts: Record<string, number> = {};
    // Hardcoded SQL per table to avoid interpolation of table names
    const MIGRATE_QUERIES: Record<string, string> = {
      user_messages: `UPDATE user_messages SET user_id = $1 WHERE user_id = $2 RETURNING *`,
      user_contacts: `UPDATE user_contacts SET user_id = $1 WHERE user_id = $2 RETURNING *`,
      user_call_history: `UPDATE user_call_history SET user_id = $1 WHERE user_id = $2 RETURNING *`,
      user_photos: `UPDATE user_photos SET user_id = $1 WHERE user_id = $2 RETURNING *`,
      user_file_transfers: `UPDATE user_file_transfers SET user_id = $1 WHERE user_id = $2 RETURNING *`,
      user_devices: `UPDATE user_devices SET user_id = $1 WHERE user_id = $2 RETURNING *`,
    };

    for (const deleteUserId of deleteUserIds) {
      for (const [table, sql] of Object.entries(MIGRATE_QUERIES)) {
        try {
          const updated = await query(sql, [keepUserId, deleteUserId]);
          migrationCounts[table] = (migrationCounts[table] || 0) + updated.length;
        } catch (e) {}
      }
      await deleteUserData(deleteUserId);
    }
    res.json({ success: true, keepUserId, deletedUserIds: deleteUserIds, migrationCounts });
  } catch (error) {
    console.error('Admin cleanup duplicates error:', error);
    res.status(500).json({ error: 'Failed to cleanup duplicates' });
  }
});

// ── GET /pending-deletions — Users with scheduled account deletions ─────────

router.get('/pending-deletions', async (req: Request, res: Response) => {
  try {
    const users = await query<{
      uid: string;
      email: string | null;
      phone: string | null;
      deletion_requested_at: string;
      deletion_reason: string | null;
      deletion_scheduled_for: string;
    }>(
      `SELECT uid, email, phone, deletion_requested_at, deletion_reason, deletion_scheduled_for
       FROM users
       WHERE deletion_requested_at IS NOT NULL
       ORDER BY deletion_scheduled_for ASC`
    );

    res.json({
      pendingDeletions: users.map(u => ({
        userId: u.uid,
        email: u.email,
        phone: u.phone,
        requestedAt: parseInt(u.deletion_requested_at),
        reason: u.deletion_reason,
        scheduledFor: parseInt(u.deletion_scheduled_for),
        daysRemaining: Math.max(0, Math.ceil((parseInt(u.deletion_scheduled_for) - Date.now()) / (24 * 60 * 60 * 1000))),
      })),
    });
  } catch (error) {
    console.error('Admin pending deletions error:', error);
    res.status(500).json({ error: 'Failed to fetch pending deletions' });
  }
});

// ── POST /process-deletion/:userId — Immediately process a pending deletion ─

router.post('/process-deletion/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;

    // Get user info for audit trail
    const user = await queryOne<{ uid: string; email: string | null; phone: string | null; deletion_reason: string | null }>(
      'SELECT uid, email, phone, deletion_reason FROM users WHERE uid = $1', [userId]
    );
    if (!user) {
      res.status(404).json({ error: 'User not found' });
      return;
    }

    // Insert audit record before deleting
    await query(
      `INSERT INTO deleted_accounts (user_id, email, phone, deletion_reason, deleted_by) VALUES ($1, $2, $3, $4, $5)`,
      [userId, user.email, user.phone, user.deletion_reason, req.userId]
    );

    await deleteUserData(userId);

    console.log(`[Admin] Processed deletion for user: ${userId}`);
    res.json({ success: true, message: 'User data deleted' });
  } catch (error) {
    console.error('Admin process deletion error:', error);
    res.status(500).json({ error: 'Failed to process deletion' });
  }
});

// ── POST /cancel-deletion/:userId — Cancel a pending deletion ───────────────

router.post('/cancel-deletion/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;

    const result = await query(
      `UPDATE users SET deletion_requested_at = NULL, deletion_reason = NULL, deletion_scheduled_for = NULL WHERE uid = $1 RETURNING uid`,
      [userId]
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'User not found' });
      return;
    }

    console.log(`[Admin] Cancelled deletion for user: ${userId}`);
    res.json({ success: true });
  } catch (error) {
    console.error('Admin cancel deletion error:', error);
    res.status(500).json({ error: 'Failed to cancel deletion' });
  }
});

export default router;
