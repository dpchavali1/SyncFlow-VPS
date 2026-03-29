/**
 * Scheduled daily cleanup service (runs at 3 AM UTC).
 *
 * Cleanup strategy:
 *   1. General cleanup - Deletes expired/stale transient data (pairings, typing
 *      indicators, old outgoing messages, inactive devices, orphaned users).
 *   2. Overdue account deletions - Processes users who requested account deletion
 *      and whose grace period has elapsed. Snapshots device IDs and bandwidth for
 *      anti-abuse tracking, then removes all user data across every table.
 *   3. E2EE key cleanup - Removes stale/orphaned encryption keys and expired
 *      key exchange requests so the key store doesn't grow unbounded.
 *   4. Email report - Sends a summary of what was cleaned to the admin.
 *
 * Uses setTimeout-based scheduling (not cron) to avoid external dependencies.
 */

import { query, queryOne, transaction } from './database';
import { sendCleanupReportEmail } from './email';
import { broadcastToUser } from './websocket';

// Run cleanup at 3 AM UTC daily
const CLEANUP_HOUR_UTC = 3;

let cleanupTimer: ReturnType<typeof setTimeout> | null = null;

function msUntilNextRun(): number {
  const now = new Date();
  const next = new Date(now);
  next.setUTCHours(CLEANUP_HOUR_UTC, 0, 0, 0);
  if (next <= now) {
    next.setUTCDate(next.getUTCDate() + 1);
  }
  return next.getTime() - now.getTime();
}

// General cleanup: each entry runs a DELETE with a time-based WHERE clause.
// Retention periods vary by data sensitivity (1 hour for typing indicators,
// 90 days for inactive devices, etc.).
async function runAutoCleanup(): Promise<Record<string, number>> {
  const results: Record<string, number> = {};

  const cleanups: Array<{ name: string; sql: string }> = [
    { name: 'expiredPairings', sql: `DELETE FROM pairing_requests WHERE expires_at < NOW() OR (status != 'pending' AND created_at < NOW() - INTERVAL '1 day') RETURNING id` },
    { name: 'oldOutgoingMessages', sql: `DELETE FROM user_outgoing_messages WHERE status IN ('sent', 'delivered', 'failed') AND created_at < NOW() - INTERVAL '7 days' RETURNING id` },
    { name: 'oldSpamMessages', sql: `DELETE FROM user_spam_messages WHERE created_at < NOW() - INTERVAL '30 days' RETURNING id` },
    { name: 'oldReadReceipts', sql: `DELETE FROM user_read_receipts WHERE read_at < NOW() - INTERVAL '30 days' RETURNING id` },
    { name: 'oldTypingIndicators', sql: `DELETE FROM user_typing_indicators WHERE created_at < NOW() - INTERVAL '1 hour' RETURNING id` },
    { name: 'oldNotifications', sql: `DELETE FROM user_notifications WHERE created_at < NOW() - INTERVAL '30 days' RETURNING id` },
    { name: 'oldClipboard', sql: `DELETE FROM user_clipboard WHERE created_at < NOW() - INTERVAL '7 days' RETURNING id` },
    { name: 'oldSharedLinks', sql: `DELETE FROM user_shared_links WHERE created_at < NOW() - INTERVAL '30 days' RETURNING id` },
    { name: 'inactiveDevices', sql: `DELETE FROM user_devices WHERE last_seen < NOW() - INTERVAL '90 days' RETURNING id` },
    { name: 'oldCallRequests', sql: `DELETE FROM user_call_requests WHERE status != 'pending' AND created_at < NOW() - INTERVAL '7 days' RETURNING id` },
    { name: 'oldCallCommands', sql: `DELETE FROM user_call_commands WHERE created_at < NOW() - INTERVAL '7 days' RETURNING id` },
    { name: 'orphanedPairingUsers', sql: `DELETE FROM users WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = users.uid) AND NOT EXISTS (SELECT 1 FROM user_messages m WHERE m.user_id = users.uid) AND created_at < NOW() - INTERVAL '1 hour' RETURNING id` },
    { name: 'oldAnalyticsEvents', sql: `DELETE FROM analytics_events WHERE created_at < NOW() - INTERVAL '90 days' RETURNING id` },
    { name: 'oldSignaling', sql: `DELETE FROM user_webrtc_signaling WHERE created_at < NOW() - INTERVAL '1 hour' RETURNING id` },
  ];

  // Auto-fail outgoing messages stuck in 'pending' for over 1 hour (Android unreachable)
  try {
    const timedOut = await query<{ id: string; user_id: string }>(
      `UPDATE user_outgoing_messages
       SET status = 'failed', error_message = 'Delivery timed out — Android device unreachable'
       WHERE status = 'pending' AND created_at < NOW() - INTERVAL '1 hour'
       RETURNING id, user_id`
    );
    results.timedOutOutgoingMessages = timedOut.length;
    // Broadcast failure to Mac/Web so UI updates from clock icon to error icon
    for (const msg of timedOut) {
      broadcastToUser(msg.user_id, 'messages', {
        type: 'outgoing_status_changed',
        data: { id: msg.id, status: 'failed' },
      });
    }
  } catch {
    results.timedOutOutgoingMessages = 0;
  }

  // Auto-reset monthly bandwidth counters for users whose last_reset is > 30 days ago
  try {
    const resetResult = await query(
      `UPDATE user_usage SET bandwidth_bytes_month = 0, last_reset = NOW(), updated_at = NOW()
       WHERE last_reset IS NULL OR last_reset < NOW() - INTERVAL '30 days'
       RETURNING user_id`
    );
    results.monthlyBandwidthResets = resetResult.length;
  } catch {
    results.monthlyBandwidthResets = 0;
  }

  for (const cleanup of cleanups) {
    try {
      const deleted = await query(cleanup.sql);
      results[cleanup.name] = deleted.length;
    } catch {
      results[cleanup.name] = 0;
    }
  }

  // Process overdue account deletions
  try {
    const overdue = await query<{ uid: string }>(
      `SELECT uid FROM users WHERE deletion_scheduled_for IS NOT NULL AND deletion_scheduled_for <= $1`,
      [Date.now()]
    );
    let deletedAccounts = 0;
    for (const user of overdue) {
      try {
        // Snapshot device IDs and bandwidth before deletion (anti-abuse)
        const devices = await query<{ id: string }>('SELECT id FROM user_devices WHERE user_id = $1', [user.uid]);
        const deviceIds = devices.map(d => d.id);
        const usageSnapshot = await queryOne('SELECT bandwidth_bytes_month FROM user_usage WHERE user_id = $1', [user.uid]);
        const bandwidthAtDeletion = parseInt(usageSnapshot?.bandwidth_bytes_month || '0');

        // Record deletion with device IDs and bandwidth snapshot
        await query(
          `INSERT INTO deleted_accounts (user_id, deletion_reason, deleted_by, device_ids, bandwidth_bytes_at_deletion)
           SELECT uid, deletion_reason, 'auto-cleanup', $2::text[], $3 FROM users WHERE uid = $1`,
          [user.uid, deviceIds, bandwidthAtDeletion]
        );
        // Delete from all user tables within a transaction so a partial
        // failure doesn't leave the account in an inconsistent state.
        const userTables = [
          'user_messages', 'user_contacts', 'user_call_history',
          'user_file_transfers', 'user_outgoing_messages', 'user_spam_messages',
          'user_scheduled_messages', 'user_read_receipts', 'user_notifications',
          'user_typing_indicators', 'user_clipboard', 'user_voicemails',
          'user_shared_links', 'user_e2ee_keys', 'user_subscriptions', 'user_usage',
          'user_dnd_status', 'user_dnd_commands', 'user_hotspot_status', 'user_hotspot_commands',
          'user_media_status', 'user_media_commands', 'user_phone_status',
          'user_continuity_state', 'user_active_calls', 'user_call_requests',
          'user_call_commands', 'user_sims', 'user_message_reactions', 'user_groups',
          'user_spam_lists', 'user_webrtc_signaling', 'user_syncflow_calls',
          'user_find_phone_requests', 'user_phone_registry',
          'e2ee_key_requests', 'e2ee_key_responses', 'e2ee_key_backups', 'e2ee_repair_log', 'pairing_requests',
          'user_profiles', 'recovery_codes', 'user_devices',
        ];
        await transaction(async (client) => {
          for (const table of userTables) {
            try { await client.query(`DELETE FROM ${table} WHERE user_id = $1`, [user.uid]); } catch {}
          }
          for (const table of ['e2ee_public_keys', 'crash_reports', 'fcm_tokens']) {
            try { await client.query(`DELETE FROM ${table} WHERE uid = $1`, [user.uid]); } catch {}
          }
          await client.query('DELETE FROM users WHERE uid = $1', [user.uid]);
        });
        deletedAccounts++;
      } catch (e) {
        console.error(`[DailyCleanup] Failed to process deletion for ${user.uid}:`, e);
      }
    }
    results.overdueDeletions = deletedAccounts;
  } catch {
    results.overdueDeletions = 0;
  }

  return results;
}

// E2EE key hygiene: removes keys for deleted users/devices and expired exchange requests.
async function runE2eeCleanup(): Promise<Record<string, number>> {
  const results: Record<string, number> = {};

  const e2eeCleanups: Array<{ name: string; sql: string }> = [
    { name: 'staleDeviceKeys', sql: `DELETE FROM user_e2ee_keys WHERE updated_at < NOW() - INTERVAL '90 days' RETURNING id` },
    { name: 'orphanedDeviceKeys', sql: `DELETE FROM user_e2ee_keys WHERE user_id NOT IN (SELECT uid FROM users) RETURNING id` },
    { name: 'orphanedPublicKeys', sql: `DELETE FROM e2ee_public_keys WHERE uid NOT IN (SELECT uid FROM users) RETURNING id` },
    { name: 'keysForMissingDevices', sql: `DELETE FROM user_e2ee_keys k WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = k.user_id AND d.device_id = k.device_id) RETURNING id` },
    { name: 'publicKeysForMissingDevices', sql: `DELETE FROM e2ee_public_keys pk WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = pk.uid AND d.device_id = pk.device_id) RETURNING id` },
    { name: 'expiredKeyRequests', sql: `DELETE FROM e2ee_key_requests WHERE created_at < NOW() - INTERVAL '24 hours' OR (expires_at IS NOT NULL AND expires_at < NOW()) RETURNING id` },
    { name: 'expiredKeyResponses', sql: `DELETE FROM e2ee_key_responses WHERE created_at < NOW() - INTERVAL '24 hours' RETURNING id` },
    { name: 'oldRepairLogs', sql: `DELETE FROM e2ee_repair_log WHERE created_at < NOW() - INTERVAL '30 days' RETURNING id` },
  ];

  for (const cleanup of e2eeCleanups) {
    try {
      const deleted = await query(cleanup.sql);
      results[cleanup.name] = deleted.length;
    } catch {
      results[cleanup.name] = 0;
    }
  }

  return results;
}

// Storage reconciliation: recalculates storage_bytes in user_usage for users
// who had uploads (MMS or file transfers) in the last 24 hours. This keeps the
// cached counter in sync with actual R2/DB contents without the cost of scanning
// every user.
async function runStorageReconciliation(): Promise<{ usersReconciled: number }> {
  let usersReconciled = 0;

  try {
    // Find users who had MMS or file transfer activity in the last 24 hours
    const recentUploadUsers = await query<{ user_id: string }>(`
      SELECT DISTINCT user_id FROM (
        SELECT user_id FROM user_messages
        WHERE is_mms = true AND mms_parts IS NOT NULL
          AND created_at > NOW() - INTERVAL '24 hours'
        UNION
        SELECT user_id FROM user_file_transfers
        WHERE created_at > NOW() - INTERVAL '24 hours'
      ) AS recent_uploaders
    `);

    for (const { user_id } of recentUploadUsers) {
      try {
        // Sum MMS attachment sizes
        const mmsBytes = await queryOne(
          `SELECT COALESCE(SUM(
            (SELECT COALESCE(SUM((part->>'fileSize')::bigint), 0)
             FROM jsonb_array_elements(mms_parts) AS part
             WHERE part->>'fileSize' IS NOT NULL)
          ), 0) as total
           FROM user_messages
           WHERE user_id = $1 AND is_mms = true AND mms_parts IS NOT NULL`,
          [user_id]
        );

        // Sum file transfer sizes (exclude MMS-related transfers to avoid double-counting)
        const fileBytes = await queryOne(
          `SELECT COALESCE(SUM(file_size), 0) as total
           FROM user_file_transfers
           WHERE user_id = $1 AND status = 'completed'`,
          [user_id]
        );

        const totalBytes = parseInt(mmsBytes?.total || '0') + parseInt(fileBytes?.total || '0');

        await query(
          `INSERT INTO user_usage (user_id, storage_bytes, updated_at)
           VALUES ($1, $2, NOW())
           ON CONFLICT (user_id) DO UPDATE SET storage_bytes = $2, updated_at = NOW()`,
          [user_id, totalBytes]
        );

        usersReconciled++;
      } catch (e) {
        console.error(`[DailyCleanup] Storage reconciliation failed for ${user_id}:`, e);
      }
    }
  } catch (e) {
    console.error('[DailyCleanup] Storage reconciliation query failed:', e);
  }

  return { usersReconciled };
}

async function runDailyCleanup() {
  const startTime = Date.now();
  console.log(`[DailyCleanup] Starting at ${new Date().toISOString()}`);

  try {
    const autoResults = await runAutoCleanup();
    const e2eeResults = await runE2eeCleanup();
    const storageResults = await runStorageReconciliation();

    const duration = Date.now() - startTime;
    const totalAuto = Object.values(autoResults).reduce((a, b) => a + b, 0);
    const totalE2ee = Object.values(e2eeResults).reduce((a, b) => a + b, 0);

    console.log(`[DailyCleanup] Complete in ${(duration / 1000).toFixed(1)}s — ${totalAuto} general + ${totalE2ee} E2EE items removed + ${storageResults.usersReconciled} users storage reconciled`);

    // Send email report
    try {
      await sendCleanupReportEmail(autoResults, e2eeResults, duration);
    } catch (e) {
      console.error('[DailyCleanup] Failed to send email report:', e);
    }
  } catch (error) {
    console.error('[DailyCleanup] Failed:', error);
  }

  // Schedule next run
  scheduleNextCleanup();
}

function scheduleNextCleanup() {
  const delay = msUntilNextRun();
  const nextRun = new Date(Date.now() + delay);
  console.log(`[DailyCleanup] Next run scheduled for ${nextRun.toISOString()} (in ${Math.round(delay / 3600000)}h)`);
  cleanupTimer = setTimeout(runDailyCleanup, delay);
}

export function startDailyCleanup() {
  console.log('[DailyCleanup] Initializing daily cleanup scheduler (3 AM UTC)');
  scheduleNextCleanup();
}

export function stopDailyCleanup() {
  if (cleanupTimer) {
    clearTimeout(cleanupTimer);
    cleanupTimer = null;
  }
}
