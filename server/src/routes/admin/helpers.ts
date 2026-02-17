/**
 * Shared helpers and clients for the admin sub-routers.
 *
 * - s3Client:        Pre-configured R2 (S3-compatible) client, null when R2 is not configured.
 * - deleteR2Objects: Batch-deletes R2 objects with a concurrency limit of 20.
 * - deleteUserData:  Comprehensive user deletion — collects R2 keys, deletes R2 objects and
 *                    all DB rows across every user-scoped table in a single transaction,
 *                    then removes the user record itself.
 */

import { S3Client, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { config } from '../../config';
import { query, transaction } from '../../services/database';

// ── R2 client (only if configured) ──────────────────────────────────────────

export const s3Client = config.r2.endpoint ? new S3Client({
  region: 'auto',
  endpoint: config.r2.endpoint,
  credentials: {
    accessKeyId: config.r2.accessKeyId,
    secretAccessKey: config.r2.secretAccessKey,
  },
}) : null;

// ── deleteR2Objects ─────────────────────────────────────────────────────────
/**
 * Delete R2 objects by key in parallel batches of 20.
 * Returns the count of successfully deleted objects.
 */
export async function deleteR2Objects(r2Keys: string[]): Promise<number> {
  if (!s3Client || r2Keys.length === 0) return 0;
  let deleted = 0;
  const BATCH_SIZE = 20;
  for (let i = 0; i < r2Keys.length; i += BATCH_SIZE) {
    const batch = r2Keys.slice(i, i + BATCH_SIZE).filter(Boolean);
    const results = await Promise.allSettled(
      batch.map(key =>
        s3Client!.send(new DeleteObjectCommand({ Bucket: config.r2.bucketName, Key: key }))
      )
    );
    deleted += results.filter(r => r.status === 'fulfilled').length;
  }
  return deleted;
}

// ── deleteUserData ──────────────────────────────────────────────────────────
/**
 * Delete ALL data for a user: R2 objects (photos, file transfers, MMS attachments)
 * and every user-scoped DB table row, finishing with the users row itself.
 *
 * Steps:
 *   1. Collect R2 keys from photos, file transfers, and MMS parts (parallel).
 *   2. Start R2 deletion in background (non-blocking).
 *   3. Delete all DB rows in a single transaction.
 *   4. Await R2 cleanup completion.
 */
export async function deleteUserData(userId: string): Promise<void> {
  // Step 1: Collect R2 keys BEFORE deleting DB rows (parallel queries)
  const r2Keys: string[] = [];
  const [photoResult, transferResult, mmsResult] = await Promise.allSettled([
    query<{ r2_key: string }>('SELECT r2_key FROM user_photos WHERE user_id = $1 AND r2_key IS NOT NULL', [userId]),
    query<{ r2_key: string }>('SELECT r2_key FROM user_file_transfers WHERE user_id = $1 AND r2_key IS NOT NULL', [userId]),
    query<{ mms_parts: any }>('SELECT mms_parts FROM user_messages WHERE user_id = $1 AND mms_parts IS NOT NULL', [userId]),
  ]);

  if (photoResult.status === 'fulfilled') {
    r2Keys.push(...photoResult.value.map(p => p.r2_key));
  }
  if (transferResult.status === 'fulfilled') {
    r2Keys.push(...transferResult.value.map(t => t.r2_key));
  }
  if (mmsResult.status === 'fulfilled') {
    for (const msg of mmsResult.value) {
      let parts = msg.mms_parts;
      if (typeof parts === 'string') { try { parts = JSON.parse(parts); } catch { continue; } }
      if (Array.isArray(parts)) {
        for (const part of parts) {
          if (part?.r2Key) r2Keys.push(part.r2Key);
        }
      }
    }
  }

  // Step 2: Start R2 deletion in background
  const r2Promise = deleteR2Objects(r2Keys).then(count => {
    if (r2Keys.length > 0) {
      console.log(`[Admin] Deleted ${count}/${r2Keys.length} R2 objects for user: ${userId}`);
    }
  });

  // Step 3: Delete all DB tables in a single transaction
  const userIdTables = [
    'user_messages', 'user_contacts', 'user_call_history', 'user_photos',
    'user_file_transfers', 'user_outgoing_messages', 'user_spam_messages',
    'user_scheduled_messages', 'user_read_receipts', 'user_notifications',
    'user_typing_indicators', 'user_clipboard', 'user_voicemails',
    'user_shared_links', 'user_e2ee_keys', 'user_subscriptions', 'user_usage',
    'user_dnd_status', 'user_dnd_commands', 'user_hotspot_status', 'user_hotspot_commands',
    'user_media_status', 'user_media_commands', 'user_phone_status',
    'user_continuity_state', 'user_active_calls',
    'user_call_requests', 'user_call_commands', 'user_sims',
    'user_message_reactions', 'user_groups', 'user_spam_lists',
    'user_webrtc_signaling', 'user_syncflow_calls',
    'user_find_phone_requests', 'user_phone_registry',
    'e2ee_key_requests', 'e2ee_key_responses',
    'pairing_requests', 'user_profiles', 'recovery_codes',
    'user_devices',
  ];
  const uidTables = ['e2ee_public_keys', 'crash_reports', 'fcm_tokens'];

  await transaction(async (client) => {
    for (const table of userIdTables) {
      try {
        await client.query(`DELETE FROM ${table} WHERE user_id = $1`, [userId]);
      } catch (e) {
        // Table may not exist, ignore
      }
    }
    for (const table of uidTables) {
      try {
        await client.query(`DELETE FROM ${table} WHERE uid = $1`, [userId]);
      } catch (e) {}
    }
    // Finally delete the user record itself
    await client.query('DELETE FROM users WHERE uid = $1', [userId]);
  });

  // Step 4: Wait for R2 cleanup to finish
  await r2Promise;
  console.log(`[Admin] Deleted all data for user: ${userId}`);
}
