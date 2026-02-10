import { query, queryOne } from './database';
import { sendCleanupReportEmail } from './email';

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

async function runAutoCleanup(): Promise<Record<string, number>> {
  const results: Record<string, number> = {};

  const cleanups: Array<{ name: string; sql: string }> = [
    { name: 'expiredPairings', sql: `DELETE FROM pairing_requests WHERE expires_at < NOW() OR (status != 'pending' AND created_at < NOW() - INTERVAL '1 day') RETURNING *` },
    { name: 'oldOutgoingMessages', sql: `DELETE FROM user_outgoing_messages WHERE status != 'pending' AND created_at < NOW() - INTERVAL '7 days' RETURNING *` },
    { name: 'oldSpamMessages', sql: `DELETE FROM user_spam_messages WHERE created_at < NOW() - INTERVAL '30 days' RETURNING *` },
    { name: 'oldReadReceipts', sql: `DELETE FROM user_read_receipts WHERE read_at < NOW() - INTERVAL '30 days' RETURNING *` },
    { name: 'oldTypingIndicators', sql: `DELETE FROM user_typing_indicators WHERE created_at < NOW() - INTERVAL '1 hour' RETURNING *` },
    { name: 'oldNotifications', sql: `DELETE FROM user_notifications WHERE created_at < NOW() - INTERVAL '30 days' RETURNING *` },
    { name: 'oldClipboard', sql: `DELETE FROM user_clipboard WHERE created_at < NOW() - INTERVAL '7 days' RETURNING *` },
    { name: 'oldSharedLinks', sql: `DELETE FROM user_shared_links WHERE created_at < NOW() - INTERVAL '30 days' RETURNING *` },
    { name: 'inactiveDevices', sql: `DELETE FROM user_devices WHERE last_seen < NOW() - INTERVAL '90 days' RETURNING *` },
    { name: 'oldCallRequests', sql: `DELETE FROM user_call_requests WHERE status != 'pending' AND created_at < NOW() - INTERVAL '7 days' RETURNING *` },
    { name: 'oldCallCommands', sql: `DELETE FROM user_call_commands WHERE created_at < NOW() - INTERVAL '7 days' RETURNING *` },
    { name: 'orphanedPairingUsers', sql: `DELETE FROM users WHERE uid NOT IN (SELECT DISTINCT user_id FROM user_devices) AND uid NOT IN (SELECT DISTINCT user_id FROM user_messages) AND created_at < NOW() - INTERVAL '1 hour' RETURNING *` },
  ];

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
        // Record deletion then remove user data
        await query(
          `INSERT INTO deleted_accounts (user_id, deletion_reason, deleted_by)
           SELECT uid, deletion_reason, 'auto-cleanup' FROM users WHERE uid = $1`,
          [user.uid]
        );
        // Delete from all user tables
        const userTables = [
          'user_messages', 'user_contacts', 'user_call_history', 'user_photos',
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
          'e2ee_key_requests', 'e2ee_key_responses', 'pairing_requests',
          'user_profiles', 'recovery_codes', 'user_devices',
        ];
        for (const table of userTables) {
          try { await query(`DELETE FROM ${table} WHERE user_id = $1`, [user.uid]); } catch {}
        }
        for (const table of ['e2ee_public_keys', 'crash_reports', 'fcm_tokens']) {
          try { await query(`DELETE FROM ${table} WHERE uid = $1`, [user.uid]); } catch {}
        }
        await query('DELETE FROM users WHERE uid = $1', [user.uid]);
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

async function runE2eeCleanup(): Promise<Record<string, number>> {
  const results: Record<string, number> = {};

  const e2eeCleanups: Array<{ name: string; sql: string }> = [
    { name: 'staleDeviceKeys', sql: `DELETE FROM user_e2ee_keys WHERE updated_at < NOW() - INTERVAL '90 days' RETURNING *` },
    { name: 'orphanedDeviceKeys', sql: `DELETE FROM user_e2ee_keys WHERE user_id NOT IN (SELECT uid FROM users) RETURNING *` },
    { name: 'orphanedPublicKeys', sql: `DELETE FROM e2ee_public_keys WHERE uid NOT IN (SELECT uid FROM users) RETURNING *` },
    { name: 'keysForMissingDevices', sql: `DELETE FROM user_e2ee_keys k WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = k.user_id AND d.device_id = k.device_id) RETURNING *` },
    { name: 'publicKeysForMissingDevices', sql: `DELETE FROM e2ee_public_keys pk WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = pk.uid AND d.device_id = pk.device_id) RETURNING *` },
    { name: 'expiredKeyRequests', sql: `DELETE FROM e2ee_key_requests WHERE created_at < NOW() - INTERVAL '7 days' RETURNING *` },
    { name: 'expiredKeyResponses', sql: `DELETE FROM e2ee_key_responses WHERE created_at < NOW() - INTERVAL '7 days' RETURNING *` },
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

async function runDailyCleanup() {
  const startTime = Date.now();
  console.log(`[DailyCleanup] Starting at ${new Date().toISOString()}`);

  try {
    const autoResults = await runAutoCleanup();
    const e2eeResults = await runE2eeCleanup();

    const duration = Date.now() - startTime;
    const totalAuto = Object.values(autoResults).reduce((a, b) => a + b, 0);
    const totalE2ee = Object.values(e2eeResults).reduce((a, b) => a + b, 0);

    console.log(`[DailyCleanup] Complete in ${(duration / 1000).toFixed(1)}s — ${totalAuto} general + ${totalE2ee} E2EE items removed`);

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
