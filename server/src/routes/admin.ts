import { Router, Request, Response } from 'express';
import { query, queryOne, transaction } from '../services/database';
import { authenticate, requireAdmin } from '../middleware/auth';
import { S3Client, DeleteObjectCommand, ListObjectsV2Command } from '@aws-sdk/client-s3';
import { config } from '../config';
import { getOnlineUsers } from '../services/websocket';
import { getCache, setCache, deleteCache, checkRedisHealth } from '../services/redis';
import { getLogs, clearLogs } from '../services/logger';

const router = Router();

// Apply authentication and admin check to all routes
router.use(authenticate);
router.use(requireAdmin);

// R2 client (only if configured)
const s3Client = config.r2.endpoint ? new S3Client({
  region: 'auto',
  endpoint: config.r2.endpoint,
  credentials: {
    accessKeyId: config.r2.accessKeyId,
    secretAccessKey: config.r2.secretAccessKey,
  },
}) : null;

// Helper: delete R2 objects by key
async function deleteR2Objects(r2Keys: string[]) {
  if (!s3Client || r2Keys.length === 0) return 0;
  let deleted = 0;
  for (const key of r2Keys) {
    if (!key) continue;
    try {
      await s3Client.send(new DeleteObjectCommand({ Bucket: config.r2.bucketName, Key: key }));
      deleted++;
    } catch (e) {}
  }
  return deleted;
}

// Helper: delete all data for a user (comprehensive - covers ALL tables + R2)
async function deleteUserData(userId: string) {
  // Step 1: Collect R2 keys BEFORE deleting DB rows
  const r2Keys: string[] = [];
  try {
    const photos = await query<{ r2_key: string }>('SELECT r2_key FROM user_photos WHERE user_id = $1 AND r2_key IS NOT NULL', [userId]);
    r2Keys.push(...photos.map(p => p.r2_key));
  } catch (e) {}
  try {
    const transfers = await query<{ r2_key: string }>('SELECT r2_key FROM user_file_transfers WHERE user_id = $1 AND r2_key IS NOT NULL', [userId]);
    r2Keys.push(...transfers.map(t => t.r2_key));
  } catch (e) {}
  try {
    // Extract R2 keys from MMS parts (JSONB array with r2Key in each element)
    const mmsMessages = await query<{ mms_parts: any }>('SELECT mms_parts FROM user_messages WHERE user_id = $1 AND mms_parts IS NOT NULL', [userId]);
    for (const msg of mmsMessages) {
      let parts = msg.mms_parts;
      if (typeof parts === 'string') { try { parts = JSON.parse(parts); } catch { continue; } }
      if (Array.isArray(parts)) {
        for (const part of parts) {
          if (part?.r2Key) r2Keys.push(part.r2Key);
        }
      }
    }
  } catch (e) {}

  // Step 2: Delete R2 objects
  const r2Deleted = await deleteR2Objects(r2Keys);
  if (r2Keys.length > 0) {
    console.log(`[Admin] Deleted ${r2Deleted}/${r2Keys.length} R2 objects for user: ${userId}`);
  }

  // Step 3: Delete all DB tables
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
  for (const table of userIdTables) {
    try {
      await query(`DELETE FROM ${table} WHERE user_id = $1`, [userId]);
    } catch (e) {
      // Table may not exist, ignore
    }
  }
  // Tables with uid column instead of user_id
  const uidTables = ['e2ee_public_keys', 'crash_reports', 'fcm_tokens'];
  for (const table of uidTables) {
    try {
      await query(`DELETE FROM ${table} WHERE uid = $1`, [userId]);
    } catch (e) {}
  }
  // Finally delete the user record itself
  await query('DELETE FROM users WHERE uid = $1', [userId]);
  console.log(`[Admin] Deleted all data for user: ${userId}`);
}

// ============================================
// OVERVIEW & STATS
// ============================================

// GET /admin/stats
router.get('/stats', async (req: Request, res: Response) => {
  try {
    const stats = await queryOne<{
      total_users: string;
      total_devices: string;
      total_messages: string;
      total_contacts: string;
      total_calls: string;
    }>(`
      SELECT
        (SELECT COUNT(*) FROM users) as total_users,
        (SELECT COUNT(*) FROM user_devices) as total_devices,
        (SELECT COUNT(*) FROM user_messages) as total_messages,
        (SELECT COUNT(*) FROM user_contacts) as total_contacts,
        (SELECT COUNT(*) FROM user_call_history) as total_calls
    `);

    res.json({
      totalUsers: parseInt(stats?.total_users || '0'),
      totalDevices: parseInt(stats?.total_devices || '0'),
      totalMessages: parseInt(stats?.total_messages || '0'),
      totalContacts: parseInt(stats?.total_contacts || '0'),
      totalCalls: parseInt(stats?.total_calls || '0'),
    });
  } catch (error) {
    console.error('Admin stats error:', error);
    res.status(500).json({ error: 'Failed to fetch stats' });
  }
});

// GET /admin/overview - Comprehensive system overview
router.get('/overview', async (req: Request, res: Response) => {
  try {
    // Helper to safely count from a table
    const safeCount = async (sql: string): Promise<number> => {
      try {
        const r = await queryOne<{ count: string }>(sql);
        return parseInt(r?.count || '0');
      } catch { return 0; }
    };
    const safeSum = async (sql: string): Promise<number> => {
      try {
        const r = await queryOne<{ total: string }>(sql);
        return parseInt(r?.total || '0');
      } catch { return 0; }
    };

    const [
      totalUsers, totalDevices, totalMessages, totalContacts, totalCalls,
      totalSms, totalMms, totalEncrypted, totalPhotos, totalPhotoSize,
      totalTransfers, totalTransferSize, activeUsers7d, inactiveUsers30d,
      usersNoDevices, totalCrashReports, orphanedMessages, orphanedContacts, orphanedDevices
    ] = await Promise.all([
      safeCount('SELECT COUNT(*) as count FROM users'),
      safeCount('SELECT COUNT(*) as count FROM user_devices'),
      safeCount('SELECT COUNT(*) as count FROM user_messages'),
      safeCount('SELECT COUNT(*) as count FROM user_contacts'),
      safeCount('SELECT COUNT(*) as count FROM user_call_history'),
      safeCount(`SELECT COUNT(*) as count FROM user_messages WHERE is_mms = false OR is_mms IS NULL`),
      safeCount(`SELECT COUNT(*) as count FROM user_messages WHERE is_mms = true`),
      safeCount(`SELECT COUNT(*) as count FROM user_messages WHERE encrypted = true`),
      safeCount('SELECT COUNT(*) as count FROM user_photos'),
      safeSum('SELECT COALESCE(SUM(file_size), 0) as total FROM user_photos'),
      safeCount('SELECT COUNT(*) as count FROM user_file_transfers'),
      safeSum('SELECT COALESCE(SUM(file_size), 0) as total FROM user_file_transfers'),
      safeCount(`SELECT COUNT(DISTINCT u.uid) as count FROM users u JOIN user_devices d ON d.user_id = u.uid WHERE d.last_seen > NOW() - INTERVAL '7 days'`),
      safeCount(`SELECT COUNT(*) as count FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = u.uid AND d.last_seen > NOW() - INTERVAL '30 days')`),
      safeCount(`SELECT COUNT(*) as count FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = u.uid)`),
      safeCount('SELECT COUNT(*) as count FROM crash_reports'),
      safeCount(`SELECT COUNT(*) as count FROM user_messages m WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = m.user_id)`),
      safeCount(`SELECT COUNT(*) as count FROM user_contacts c WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = c.user_id)`),
      safeCount(`SELECT COUNT(*) as count FROM user_devices d WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = d.user_id)`),
    ]);

    let databaseSize = 'unknown';
    try {
      const dbSize = await queryOne<{ size: string }>(`SELECT pg_size_pretty(pg_database_size(current_database())) as size`);
      databaseSize = dbSize?.size || 'unknown';
    } catch {}

    res.json({
      users: { total: totalUsers, activeLastWeek: activeUsers7d, inactive30d: inactiveUsers30d, withoutDevices: usersNoDevices },
      devices: { total: totalDevices },
      messages: { total: totalMessages, sms: totalSms, mms: totalMms, encrypted: totalEncrypted },
      contacts: { total: totalContacts },
      calls: { total: totalCalls },
      storage: {
        photos: { count: totalPhotos, sizeBytes: totalPhotoSize },
        fileTransfers: { count: totalTransfers, sizeBytes: totalTransferSize },
        totalSizeBytes: totalPhotoSize + totalTransferSize,
      },
      orphanedData: {
        messages: orphanedMessages,
        contacts: orphanedContacts,
        devices: orphanedDevices,
      },
      crashes: { total: totalCrashReports },
      databaseSize,
    });
  } catch (error) {
    console.error('Admin overview error:', error);
    res.status(500).json({ error: 'Failed to fetch overview' });
  }
});

// ============================================
// USER MANAGEMENT
// ============================================

// GET /admin/users
router.get('/users', async (req: Request, res: Response) => {
  try {
    const limit = parseInt(req.query.limit as string) || 100;
    const offset = parseInt(req.query.offset as string) || 0;
    const search = req.query.search as string | undefined;

    let whereClause = '';
    const params: any[] = [];

    if (search) {
      params.push(`%${search}%`);
      whereClause = `WHERE u.uid ILIKE $${params.length} OR u.phone ILIKE $${params.length} OR u.email ILIKE $${params.length}`;
    }

    params.push(limit, offset);

    // Check which optional tables exist
    const tableCheck = await query<{ tablename: string }>(`
      SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('user_subscriptions', 'user_photos', 'user_file_transfers')
    `);
    const existingTables = new Set(tableCheck.map(t => t.tablename));
    const hasSubs = existingTables.has('user_subscriptions');
    const hasPhotos = existingTables.has('user_photos');
    const hasTransfers = existingTables.has('user_file_transfers');

    const users = await query<{
      uid: string;
      phone: string | null;
      email: string | null;
      created_at: Date;
      updated_at: Date;
      device_count: string;
      message_count: string;
      last_seen: Date | null;
      storage_bytes: string;
      plan: string | null;
      plan_expires_at: Date | null;
      plan_assigned_by: string | null;
    }>(`
      SELECT
        u.uid, u.phone, u.email, u.created_at, u.updated_at,
        (SELECT COUNT(*) FROM user_devices d WHERE d.user_id = u.uid) as device_count,
        (SELECT COUNT(*) FROM user_messages m WHERE m.user_id = u.uid) as message_count,
        (SELECT MAX(d.last_seen) FROM user_devices d WHERE d.user_id = u.uid) as last_seen,
        ${hasPhotos ? `COALESCE((SELECT COUNT(*) * 500000 FROM user_photos p WHERE p.user_id = u.uid), 0)` : '0'} +
        ${hasTransfers ? `COALESCE((SELECT SUM(file_size) FROM user_file_transfers ft WHERE ft.user_id = u.uid), 0)` : '0'} as storage_bytes,
        ${hasSubs ? `(SELECT s.plan FROM user_subscriptions s WHERE s.user_id = u.uid ORDER BY s.started_at DESC LIMIT 1)` : 'NULL'} as plan,
        ${hasSubs ? `(SELECT s.expires_at FROM user_subscriptions s WHERE s.user_id = u.uid ORDER BY s.started_at DESC LIMIT 1)` : 'NULL'} as plan_expires_at,
        NULL as plan_assigned_by
      FROM users u
      ${whereClause}
      ORDER BY u.created_at DESC
      LIMIT $${params.length - 1} OFFSET $${params.length}
    `, params);

    const totalResult = await queryOne<{ count: string }>(
      search ? `SELECT COUNT(*) FROM users u ${whereClause}` : 'SELECT COUNT(*) FROM users',
      search ? [`%${search}%`] : []
    );

    const now = Date.now();
    const thirtyDaysAgo = now - 30 * 24 * 60 * 60 * 1000;

    res.json({
      users: users.map(u => {
        const lastSeenMs = u.last_seen ? new Date(u.last_seen).getTime() : 0;
        return {
          userId: u.uid,
          phone: u.phone,
          email: u.email,
          createdAt: u.created_at,
          updatedAt: u.updated_at,
          devicesCount: parseInt(u.device_count),
          messagesCount: parseInt(u.message_count),
          lastActivity: u.last_seen,
          isActive: lastSeenMs > thirtyDaysAgo,
          storageUsedMB: (parseInt(u.storage_bytes || '0') / (1024 * 1024)).toFixed(2),
          plan: u.plan || 'free',
          planExpiresAt: u.plan_expires_at,
          planAssignedBy: u.plan_assigned_by,
          wasPremium: u.plan && u.plan !== 'free',
        };
      }),
      total: parseInt(totalResult?.count || '0'),
      limit,
      offset,
    });
  } catch (error) {
    console.error('Admin users error:', error);
    res.status(500).json({ error: 'Failed to fetch users' });
  }
});

// GET /admin/users/:userId
router.get('/users/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const user = await queryOne<{ uid: string; phone: string; email: string; created_at: Date; updated_at: Date }>(
      'SELECT uid, phone, email, created_at, updated_at FROM users WHERE uid = $1', [userId]
    );
    if (!user) { res.status(404).json({ error: 'User not found' }); return; }

    const devices = await query<{ id: string; device_type: string; name: string; paired_at: Date; last_seen: Date }>(
      'SELECT id, device_type, name, paired_at, last_seen FROM user_devices WHERE user_id = $1 ORDER BY paired_at DESC', [userId]
    );
    const msgCount = await queryOne<{ count: string }>('SELECT COUNT(*) FROM user_messages WHERE user_id = $1', [userId]);
    const contactCount = await queryOne<{ count: string }>('SELECT COUNT(*) FROM user_contacts WHERE user_id = $1', [userId]);
    const sub = await queryOne<{ plan: string; expires_at: Date | null }>('SELECT plan, expires_at FROM user_subscriptions WHERE user_id = $1', [userId]);

    res.json({
      id: user.uid, phone: user.phone, email: user.email,
      createdAt: user.created_at, updatedAt: user.updated_at,
      devices: devices.map(d => ({ id: d.id, type: d.device_type, name: d.name, createdAt: d.paired_at, lastSeen: d.last_seen })),
      messageCount: parseInt(msgCount?.count || '0'),
      contactCount: parseInt(contactCount?.count || '0'),
      subscription: sub ? { plan: sub.plan, expiresAt: sub.expires_at } : { plan: 'free', expiresAt: null },
    });
  } catch (error) {
    console.error('Admin user details error:', error);
    res.status(500).json({ error: 'Failed to fetch user details' });
  }
});

// GET /admin/users/:userId/messages
router.get('/users/:userId/messages', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const limit = parseInt(req.query.limit as string) || 50;
    const offset = parseInt(req.query.offset as string) || 0;

    const messages = await query<{ id: string; address: string; body: string; date: string; type: number; read: boolean; is_mms: boolean }>(`
      SELECT id, address, body, date, type, read, is_mms FROM user_messages WHERE user_id = $1 ORDER BY date DESC LIMIT $2 OFFSET $3
    `, [userId, limit, offset]);

    const total = await queryOne<{ count: string }>('SELECT COUNT(*) FROM user_messages WHERE user_id = $1', [userId]);

    res.json({
      messages: messages.map(m => ({ id: m.id, address: m.address, body: m.body, date: m.date, type: m.type, read: m.read, isMms: m.is_mms })),
      total: parseInt(total?.count || '0'), limit, offset,
    });
  } catch (error) {
    console.error('Admin user messages error:', error);
    res.status(500).json({ error: 'Failed to fetch messages' });
  }
});

// DELETE /admin/users/:userId
router.delete('/users/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    await deleteUserData(userId);
    res.json({ success: true, message: 'User and all data deleted' });
  } catch (error) {
    console.error('Admin delete user error:', error);
    res.status(500).json({ error: 'Failed to delete user' });
  }
});

// POST /admin/users/bulk-delete - Bulk delete inactive users
router.post('/users/bulk-delete', async (req: Request, res: Response) => {
  try {
    const inactiveDays = req.body.inactiveDays || 90;
    const inactiveUsers = await query<{ uid: string }>(`
      SELECT u.uid FROM users u
      WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = u.uid AND d.last_seen > NOW() - INTERVAL '1 day' * $1)
    `, [inactiveDays]);

    let deletedCount = 0;
    for (const user of inactiveUsers) {
      try { await deleteUserData(user.uid); deletedCount++; } catch (e) { console.error(`Failed to delete ${user.uid}:`, e); }
    }
    res.json({ success: true, deletedCount, inactiveDays });
  } catch (error) {
    console.error('Admin bulk delete error:', error);
    res.status(500).json({ error: 'Failed to bulk delete users' });
  }
});

// POST /admin/users/:userId/plan - Set user plan
router.post('/users/:userId/plan', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const { plan, expiresAt } = req.body;
    const validPlans = ['free', 'monthly', 'yearly', 'lifetime'];
    if (!validPlans.includes(plan)) { res.status(400).json({ error: `plan must be one of: ${validPlans.join(', ')}` }); return; }

    const user = await queryOne<{ uid: string }>('SELECT uid FROM users WHERE uid = $1', [userId]);
    if (!user) { res.status(404).json({ error: 'User not found' }); return; }

    await query(`
      INSERT INTO user_subscriptions (user_id, plan, status, expires_at, created_at, updated_at)
      VALUES ($1, $2, 'active', $3, NOW(), NOW())
      ON CONFLICT (user_id) DO UPDATE SET plan = $2, expires_at = $3, updated_at = NOW()
    `, [userId, plan, expiresAt || null]);

    res.json({ success: true, userId, plan, expiresAt: expiresAt || null });
  } catch (error) {
    console.error('Admin set plan error:', error);
    res.status(500).json({ error: 'Failed to set user plan' });
  }
});

// POST /admin/users/:userId/recalculate-storage
router.post('/users/:userId/recalculate-storage', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const photoStorage = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM(file_size), 0) as total_size FROM user_photos WHERE user_id = $1`, [userId]);
    const transferStorage = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM(file_size), 0) as total_size FROM user_file_transfers WHERE user_id = $1`, [userId]);

    const totalBytes = parseInt(photoStorage?.total_size || '0') + parseInt(transferStorage?.total_size || '0');
    await query(`INSERT INTO user_usage (user_id, storage_bytes, updated_at) VALUES ($1, $2, NOW()) ON CONFLICT (user_id) DO UPDATE SET storage_bytes = $2, updated_at = NOW()`, [userId, totalBytes]);

    res.json({ success: true, userId, storage: { photos: { count: parseInt(photoStorage?.count || '0'), sizeBytes: parseInt(photoStorage?.total_size || '0') }, fileTransfers: { count: parseInt(transferStorage?.count || '0'), sizeBytes: parseInt(transferStorage?.total_size || '0') }, totalStorageBytes: totalBytes } });
  } catch (error) {
    console.error('Admin recalculate storage error:', error);
    res.status(500).json({ error: 'Failed to recalculate storage' });
  }
});

// ============================================
// DEVICES & PAIRING
// ============================================

// GET /admin/devices
router.get('/devices', async (req: Request, res: Response) => {
  try {
    const limit = parseInt(req.query.limit as string) || 100;
    const offset = parseInt(req.query.offset as string) || 0;
    const devices = await query<{ id: string; user_id: string; device_type: string; name: string; paired_at: Date; last_seen: Date }>(`
      SELECT id, user_id, device_type, name, paired_at, last_seen FROM user_devices ORDER BY last_seen DESC LIMIT $1 OFFSET $2
    `, [limit, offset]);
    const total = await queryOne<{ count: string }>('SELECT COUNT(*) FROM user_devices');
    res.json({ devices: devices.map(d => ({ id: d.id, userId: d.user_id, type: d.device_type, name: d.name, createdAt: d.paired_at, lastSeen: d.last_seen })), total: parseInt(total?.count || '0'), limit, offset });
  } catch (error) {
    console.error('Admin devices error:', error);
    res.status(500).json({ error: 'Failed to fetch devices' });
  }
});

// GET /admin/pairing-requests
router.get('/pairing-requests', async (req: Request, res: Response) => {
  try {
    const requests = await query<{ token: string; device_id: string; device_name: string; device_type: string; status: string; created_at: Date; expires_at: Date }>(`
      SELECT token, device_id, device_name, device_type, status, created_at, expires_at FROM pairing_requests WHERE status = 'pending' AND expires_at > NOW() ORDER BY created_at DESC
    `);
    res.json({ requests: requests.map(r => ({ token: r.token.substring(0, 8) + '...', deviceId: r.device_id, deviceName: r.device_name, deviceType: r.device_type, status: r.status, createdAt: r.created_at, expiresAt: r.expires_at })) });
  } catch (error) {
    console.error('Admin pairing requests error:', error);
    res.status(500).json({ error: 'Failed to fetch pairing requests' });
  }
});

// ============================================
// CLEANUP OPERATIONS
// ============================================

// POST /admin/cleanup/auto - Auto cleanup all categories
router.post('/cleanup/auto', async (req: Request, res: Response) => {
  try {
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
      { name: 'expiredE2eeRequests', sql: `DELETE FROM e2ee_key_requests WHERE created_at < NOW() - INTERVAL '7 days' RETURNING *` },
      { name: 'oldCallCommands', sql: `DELETE FROM user_call_commands WHERE created_at < NOW() - INTERVAL '7 days' RETURNING *` },
      // Clean up orphaned users from abandoned pairing initiations (no devices AND no messages AND older than 1 hour)
      { name: 'orphanedPairingUsers', sql: `DELETE FROM users WHERE uid NOT IN (SELECT DISTINCT user_id FROM user_devices) AND uid NOT IN (SELECT DISTINCT user_id FROM user_messages) AND created_at < NOW() - INTERVAL '1 hour' RETURNING *` },
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

// POST /admin/cleanup/messages - Delete old messages (with R2 MMS cleanup)
router.post('/cleanup/messages', async (req: Request, res: Response) => {
  try {
    const { olderThanDays, mmsOnly } = req.body;
    if (!olderThanDays || olderThanDays < 1) { res.status(400).json({ error: 'olderThanDays required' }); return; }

    // First collect R2 keys from MMS parts before deleting
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

// POST /admin/cleanup/devices - Clean old devices
router.post('/cleanup/devices', async (req: Request, res: Response) => {
  try {
    const deleted = await query(`DELETE FROM user_devices WHERE last_seen < NOW() - INTERVAL '90 days' RETURNING *`);
    res.json({ success: true, deletedCount: deleted.length });
  } catch (error) {
    console.error('Admin cleanup devices error:', error);
    res.status(500).json({ error: 'Failed to cleanup devices' });
  }
});

// POST /admin/cleanup/devices/deduplicate - Keep only the most recent device per type per user
router.post('/cleanup/devices/deduplicate', async (req: Request, res: Response) => {
  try {
    // Delete all but the most recently seen device of each type per user
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

// DELETE /admin/users/:userId/devices/stale - Remove stale devices for a specific user
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

// ============================================
// ORPHANS & DUPLICATES
// ============================================

// GET /admin/orphans - Detect orphaned data
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

// POST /admin/cleanup/orphans
router.post('/cleanup/orphans', async (req: Request, res: Response) => {
  try {
    const { deleteUsersWithoutDevices } = req.body || {};
    const results: Record<string, number> = {};

    const orphanTables = ['user_messages', 'user_contacts', 'user_call_history', 'user_devices', 'user_photos', 'user_file_transfers'];
    for (const table of orphanTables) {
      try {
        const deleted = await query(`DELETE FROM ${table} WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.uid = ${table}.user_id) RETURNING *`);
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

// GET /admin/duplicates
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

// POST /admin/cleanup/duplicates
router.post('/cleanup/duplicates', async (req: Request, res: Response) => {
  try {
    const { keepUserId, deleteUserIds } = req.body;
    if (!keepUserId || !Array.isArray(deleteUserIds)) { res.status(400).json({ error: 'keepUserId and deleteUserIds[] required' }); return; }

    const migrationCounts: Record<string, number> = {};
    const tables = ['user_messages', 'user_contacts', 'user_call_history', 'user_photos', 'user_file_transfers', 'user_devices'];

    for (const deleteUserId of deleteUserIds) {
      for (const table of tables) {
        try {
          const updated = await query(`UPDATE ${table} SET user_id = $1 WHERE user_id = $2 RETURNING *`, [keepUserId, deleteUserId]);
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

// ============================================
// R2 STORAGE MANAGEMENT
// ============================================

// GET /admin/r2/analytics
router.get('/r2/analytics', async (req: Request, res: Response) => {
  try {
    const photoStats = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM(file_size), 0) as total_size FROM user_photos`);
    const transferStats = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM(file_size), 0) as total_size FROM user_file_transfers`);

    // MMS attachments stored in R2
    let mmsCount = 0;
    try {
      const mmsStats = await queryOne<{ count: string }>(`SELECT COUNT(*) as count FROM user_messages, jsonb_array_elements(mms_parts) AS part WHERE mms_parts IS NOT NULL AND part->>'r2Key' IS NOT NULL`);
      mmsCount = parseInt(mmsStats?.count || '0');
    } catch { /* mms_parts column may not exist or be empty */ }

    // Top users by storage
    const topUsers = await query<{ user_id: string; total_size: string; last_updated: Date }>(`
      SELECT combined.user_id, SUM(combined.total_size) as total_size, MAX(combined.last_updated) as last_updated FROM (
        SELECT user_id, COALESCE(SUM(file_size), 0) as total_size, MAX(synced_at) as last_updated FROM user_photos GROUP BY user_id
        UNION ALL
        SELECT user_id, COALESCE(SUM(file_size), 0) as total_size, MAX(created_at) as last_updated FROM user_file_transfers GROUP BY user_id
      ) combined GROUP BY combined.user_id ORDER BY total_size DESC LIMIT 20
    `);

    // Largest files across photos and transfers
    const largestPhotos = await query<{ r2_key: string; file_size: string; synced_at: Date }>(`SELECT r2_key, file_size, synced_at FROM user_photos WHERE r2_key IS NOT NULL ORDER BY file_size DESC LIMIT 10`);
    const largestTransfers = await query<{ r2_key: string; file_size: string; created_at: Date }>(`SELECT r2_key, file_size, created_at FROM user_file_transfers WHERE r2_key IS NOT NULL ORDER BY file_size DESC LIMIT 10`);

    // Oldest files
    const oldestPhotos = await query<{ r2_key: string; file_size: string; synced_at: Date }>(`SELECT r2_key, file_size, synced_at FROM user_photos WHERE r2_key IS NOT NULL ORDER BY synced_at ASC LIMIT 10`);
    const oldestTransfers = await query<{ r2_key: string; file_size: string; created_at: Date }>(`SELECT r2_key, file_size, created_at FROM user_file_transfers WHERE r2_key IS NOT NULL ORDER BY created_at ASC LIMIT 10`);

    const p = (v: string | undefined) => parseInt(v || '0');
    const photoCount = p(photoStats?.count);
    const photoSize = p(photoStats?.total_size);
    const transferCount = p(transferStats?.count);
    const transferSize = p(transferStats?.total_size);
    const totalSize = photoSize + transferSize;
    // R2 pricing: $0.015/GB/month
    const estimatedCost = (totalSize / (1024 * 1024 * 1024)) * 0.015;

    const allLargest = [
      ...largestPhotos.map(f => ({ key: f.r2_key, size: p(f.file_size), uploadedAt: f.synced_at })),
      ...largestTransfers.map(f => ({ key: f.r2_key, size: p(f.file_size), uploadedAt: f.created_at })),
    ].sort((a, b) => b.size - a.size).slice(0, 10);

    const allOldest = [
      ...oldestPhotos.map(f => ({ key: f.r2_key, size: p(f.file_size), uploadedAt: f.synced_at })),
      ...oldestTransfers.map(f => ({ key: f.r2_key, size: p(f.file_size), uploadedAt: f.created_at })),
    ].sort((a, b) => new Date(a.uploadedAt).getTime() - new Date(b.uploadedAt).getTime()).slice(0, 10);

    res.json({
      totalFiles: photoCount + transferCount + mmsCount,
      totalSize,
      estimatedCost,
      fileCounts: { files: transferCount, mms: mmsCount, photos: photoCount },
      sizeCounts: { files: transferSize, mms: 0, photos: photoSize },
      largestFiles: allLargest,
      oldestFiles: allOldest,
      userStorage: topUsers.map(u => ({ userId: u.user_id, storageBytes: p(u.total_size), lastUpdatedAt: u.last_updated })),
      totalUsersWithStorage: topUsers.length,
      r2Available: !!s3Client,
    });
  } catch (error) {
    console.error('Admin R2 analytics error:', error);
    res.status(500).json({ error: 'Failed to fetch R2 analytics' });
  }
});

// GET /admin/r2/files
router.get('/r2/files', async (req: Request, res: Response) => {
  try {
    const type = req.query.type as string | undefined;
    const limit = parseInt(req.query.limit as string) || 100;
    const offset = parseInt(req.query.offset as string) || 0;
    const files: any[] = [];

    if (!type || type === 'photos') {
      const photos = await query<{ user_id: string; file_name: string; file_size: string; r2_key: string; created_at: Date }>(`
        SELECT user_id, file_name, file_size, r2_key, synced_at as created_at FROM user_photos ORDER BY synced_at DESC LIMIT $1 OFFSET $2
      `, [limit, offset]);
      files.push(...photos.map(p => ({ source: 'photos', userId: p.user_id, fileName: p.file_name, fileSize: parseInt(p.file_size || '0'), r2Key: p.r2_key, createdAt: p.created_at })));
    }
    if (!type || type === 'transfers') {
      const transfers = await query<{ user_id: string; file_name: string; file_size: string; r2_key: string; created_at: Date }>(`
        SELECT user_id, file_name, file_size, r2_key, created_at FROM user_file_transfers ORDER BY created_at DESC LIMIT $1 OFFSET $2
      `, [limit, offset]);
      files.push(...transfers.map(t => ({ source: 'transfers', userId: t.user_id, fileName: t.file_name, fileSize: parseInt(t.file_size || '0'), r2Key: t.r2_key, createdAt: t.created_at })));
    }

    res.json({ files, limit, offset });
  } catch (error) {
    console.error('Admin R2 files error:', error);
    res.status(500).json({ error: 'Failed to fetch R2 files' });
  }
});

// POST /admin/r2/cleanup - Cleanup old R2 files
router.post('/r2/cleanup', async (req: Request, res: Response) => {
  try {
    const { olderThanDays, type } = req.body;
    if (!olderThanDays || olderThanDays < 1) { res.status(400).json({ error: 'olderThanDays required' }); return; }

    const results: Record<string, number> = {};

    if (!type || type === 'photos') {
      const old = await query<{ r2_key: string }>(`DELETE FROM user_photos WHERE synced_at < NOW() - INTERVAL '1 day' * $1 RETURNING r2_key`, [olderThanDays]);
      let r2Count = 0;
      if (s3Client) { for (const f of old) { if (f.r2_key) { try { await s3Client.send(new DeleteObjectCommand({ Bucket: config.r2.bucketName, Key: f.r2_key })); r2Count++; } catch (e) {} } } }
      results.photosDb = old.length; results.photosR2 = r2Count;
    }
    if (!type || type === 'transfers') {
      const old = await query<{ r2_key: string }>(`DELETE FROM user_file_transfers WHERE created_at < NOW() - INTERVAL '1 day' * $1 RETURNING r2_key`, [olderThanDays]);
      let r2Count = 0;
      if (s3Client) { for (const f of old) { if (f.r2_key) { try { await s3Client.send(new DeleteObjectCommand({ Bucket: config.r2.bucketName, Key: f.r2_key })); r2Count++; } catch (e) {} } } }
      results.transfersDb = old.length; results.transfersR2 = r2Count;
    }

    res.json({ success: true, olderThanDays, type: type || 'all', results, r2Available: !!s3Client });
  } catch (error) {
    console.error('Admin R2 cleanup error:', error);
    res.status(500).json({ error: 'Failed to cleanup R2 files' });
  }
});

// DELETE /admin/r2/files/:key - Delete specific R2 file
router.delete('/r2/files/*', async (req: Request, res: Response) => {
  try {
    const r2Key = decodeURIComponent(req.params[0]);
    const photoDeleted = await query('DELETE FROM user_photos WHERE r2_key = $1 RETURNING *', [r2Key]);
    const transferDeleted = await query('DELETE FROM user_file_transfers WHERE r2_key = $1 RETURNING *', [r2Key]);
    let r2Deleted = false;
    if (s3Client) { try { await s3Client.send(new DeleteObjectCommand({ Bucket: config.r2.bucketName, Key: r2Key })); r2Deleted = true; } catch (e) {} }

    res.json({ success: true, r2Key, dbRecordsDeleted: photoDeleted.length + transferDeleted.length, r2Deleted });
  } catch (error) {
    console.error('Admin delete R2 file error:', error);
    res.status(500).json({ error: 'Failed to delete R2 file' });
  }
});

// ============================================
// CRASH REPORTS
// ============================================

// GET /admin/crashes
router.get('/crashes', async (req: Request, res: Response) => {
  try {
    const limit = parseInt(req.query.limit as string) || 50;
    const offset = parseInt(req.query.offset as string) || 0;
    const resolved = req.query.resolved as string | undefined;

    let sql = 'SELECT * FROM crash_reports';
    const conditions: string[] = [];
    const params: any[] = [];

    if (resolved === 'true') {
      conditions.push('resolved = true');
    } else if (resolved === 'false') {
      conditions.push('(resolved = false OR resolved IS NULL)');
    }

    if (conditions.length > 0) {
      sql += ' WHERE ' + conditions.join(' AND ');
    }

    sql += ' ORDER BY created_at DESC';
    sql += ` LIMIT $${params.length + 1} OFFSET $${params.length + 2}`;
    params.push(limit, offset);

    const crashes = await query(sql, params);
    const totalCount = await queryOne<{ count: string }>('SELECT COUNT(*) as count FROM crash_reports');
    const unresolvedCount = await queryOne<{ count: string }>(
      'SELECT COUNT(*) as count FROM crash_reports WHERE resolved = false OR resolved IS NULL'
    );
    const byVersion = await query<{ app_version: string; count: string }>(`SELECT app_version, COUNT(*) as count FROM crash_reports WHERE app_version IS NOT NULL GROUP BY app_version ORDER BY count DESC`);

    res.json({
      crashes,
      stats: {
        total: parseInt(totalCount?.count || '0'),
        unresolved: parseInt(unresolvedCount?.count || '0'),
        byVersion: byVersion.map(v => ({ appVersion: v.app_version, count: parseInt(v.count) })),
      },
      limit, offset,
    });
  } catch (error) {
    console.error('Admin crashes error:', error);
    res.status(500).json({ error: 'Failed to fetch crash reports' });
  }
});

// POST /admin/crashes/:id/resolve - Mark crash resolved
router.post('/crashes/:id/resolve', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;

    // Attempt to set resolved = true; if column doesn't exist, add it first
    try {
      await query('UPDATE crash_reports SET resolved = true WHERE id = $1', [id]);
    } catch (columnError: any) {
      // If the resolved column doesn't exist (error code 42703), try adding it
      if (columnError.code === '42703') {
        await query('ALTER TABLE crash_reports ADD COLUMN IF NOT EXISTS resolved BOOLEAN DEFAULT false');
        await query('UPDATE crash_reports SET resolved = true WHERE id = $1', [id]);
      } else {
        throw columnError;
      }
    }

    res.json({ success: true, id, resolved: true });
  } catch (error) {
    console.error('Admin resolve crash error:', error);
    res.status(500).json({ error: 'Failed to resolve crash report' });
  }
});

// DELETE /admin/crashes/:id
router.delete('/crashes/:id', async (req: Request, res: Response) => {
  try {
    const deleted = await query('DELETE FROM crash_reports WHERE id = $1 RETURNING *', [req.params.id]);
    if (deleted.length === 0) { res.status(404).json({ error: 'Crash report not found' }); return; }
    res.json({ success: true, id: req.params.id });
  } catch (error) {
    console.error('Admin delete crash error:', error);
    res.status(500).json({ error: 'Failed to delete crash report' });
  }
});

// ============================================
// ANALYTICS
// ============================================

// GET /admin/analytics/dashboard - Daily active users, new users, message volume over time
router.get('/analytics/dashboard', async (req: Request, res: Response) => {
  try {
    // Daily active users for last 30 days (from user_devices last_seen)
    const dau = await query<{ date: string; count: string }>(`
      SELECT d.date, COUNT(DISTINCT d.user_id) as count
      FROM (
        SELECT user_id, DATE(last_seen) as date
        FROM user_devices
        WHERE last_seen > NOW() - INTERVAL '30 days'
      ) d
      GROUP BY d.date
      ORDER BY d.date ASC
    `);

    // New user signups per day (from users created_at)
    const newUsers = await query<{ date: string; count: string }>(`
      SELECT DATE(created_at) as date, COUNT(*) as count
      FROM users
      WHERE created_at > NOW() - INTERVAL '30 days'
      GROUP BY DATE(created_at)
      ORDER BY date ASC
    `);

    // Message volume per day (from user_messages date)
    const messageVolume = await query<{ date: string; count: string }>(`
      SELECT DATE(date) as date, COUNT(*) as count
      FROM user_messages
      WHERE date > NOW() - INTERVAL '30 days'
      GROUP BY DATE(date)
      ORDER BY date ASC
    `);

    res.json({
      dau: dau.map(d => ({ date: d.date, count: parseInt(d.count) })),
      newUsers: newUsers.map(d => ({ date: d.date, count: parseInt(d.count) })),
      messageVolume: messageVolume.map(d => ({ date: d.date, count: parseInt(d.count) })),
    });
  } catch (error) {
    console.error('Admin analytics dashboard error:', error);
    res.status(500).json({ error: 'Failed to fetch dashboard analytics' });
  }
});

// GET /admin/analytics/bandwidth - Bandwidth usage analytics
router.get('/analytics/bandwidth', async (req: Request, res: Response) => {
  try {
    // Total bandwidth this month across all users
    let totalBytesThisMonth = 0;
    try {
      const total = await queryOne<{ total: string }>(`
        SELECT COALESCE(SUM(bandwidth_bytes), 0) as total
        FROM user_usage
        WHERE updated_at >= DATE_TRUNC('month', NOW())
      `);
      totalBytesThisMonth = parseInt(total?.total || '0');
    } catch { totalBytesThisMonth = 0; }

    // Top bandwidth consumers
    let topUsers: Array<{ userId: string; bytes: number }> = [];
    try {
      const top = await query<{ user_id: string; bytes: string }>(`
        SELECT user_id, COALESCE(bandwidth_bytes, 0) as bytes
        FROM user_usage
        WHERE bandwidth_bytes > 0
        ORDER BY bandwidth_bytes DESC
        LIMIT 20
      `);
      topUsers = top.map(u => ({ userId: u.user_id, bytes: parseInt(u.bytes) }));
    } catch {}

    // Daily bandwidth trend
    let dailyTrend: Array<{ date: string; bytes: number }> = [];
    try {
      const trend = await query<{ date: string; bytes: string }>(`
        SELECT DATE(updated_at) as date, SUM(bandwidth_bytes) as bytes
        FROM user_usage
        WHERE updated_at > NOW() - INTERVAL '30 days'
        GROUP BY DATE(updated_at)
        ORDER BY date ASC
      `);
      dailyTrend = trend.map(d => ({ date: d.date, bytes: parseInt(d.bytes) }));
    } catch {}

    res.json({ totalBytesThisMonth, topUsers, dailyTrend });
  } catch (error) {
    console.error('Admin analytics bandwidth error:', error);
    res.status(500).json({ error: 'Failed to fetch bandwidth analytics' });
  }
});

// GET /admin/analytics/retention - User retention analytics
router.get('/analytics/retention', async (req: Request, res: Response) => {
  try {
    const safeCount = async (sql: string): Promise<number> => {
      try {
        const r = await queryOne<{ count: string }>(sql);
        return parseInt(r?.count || '0');
      } catch { return 0; }
    };

    const [active1d, active7d, active30d, active90d, totalUsers] = await Promise.all([
      safeCount(`SELECT COUNT(DISTINCT user_id) as count FROM user_devices WHERE last_seen > NOW() - INTERVAL '1 day'`),
      safeCount(`SELECT COUNT(DISTINCT user_id) as count FROM user_devices WHERE last_seen > NOW() - INTERVAL '7 days'`),
      safeCount(`SELECT COUNT(DISTINCT user_id) as count FROM user_devices WHERE last_seen > NOW() - INTERVAL '30 days'`),
      safeCount(`SELECT COUNT(DISTINCT user_id) as count FROM user_devices WHERE last_seen > NOW() - INTERVAL '90 days'`),
      safeCount('SELECT COUNT(*) as count FROM users'),
    ]);

    // Churn rate: users not seen in 30 days / total users
    const churnRate = totalUsers > 0 ? parseFloat(((totalUsers - active30d) / totalUsers).toFixed(4)) : 0;

    res.json({ active1d, active7d, active30d, active90d, churnRate, totalUsers });
  } catch (error) {
    console.error('Admin analytics retention error:', error);
    res.status(500).json({ error: 'Failed to fetch retention analytics' });
  }
});

// GET /admin/analytics/features - Feature usage analytics
router.get('/analytics/features', async (req: Request, res: Response) => {
  try {
    const featureTables = [
      { name: 'Clipboard', table: 'user_clipboard' },
      { name: 'Photos', table: 'user_photos' },
      { name: 'Notifications', table: 'user_notifications' },
      { name: 'Voicemails', table: 'user_voicemails' },
      { name: 'Scheduled Messages', table: 'user_scheduled_messages' },
      { name: 'E2EE Keys', table: 'user_e2ee_keys' },
      { name: 'File Transfers', table: 'user_file_transfers' },
      { name: 'Call History', table: 'user_call_history' },
      { name: 'Contacts', table: 'user_contacts' },
      { name: 'Messages', table: 'user_messages' },
      { name: 'Spam Filter', table: 'user_spam_messages' },
      { name: 'Shared Links', table: 'user_shared_links' },
    ];

    const features: Array<{ name: string; userCount: number; totalRecords: number }> = [];

    for (const feature of featureTables) {
      try {
        const result = await queryOne<{ user_count: string; total_records: string }>(`
          SELECT COUNT(DISTINCT user_id) as user_count, COUNT(*) as total_records FROM ${feature.table}
        `);
        features.push({
          name: feature.name,
          userCount: parseInt(result?.user_count || '0'),
          totalRecords: parseInt(result?.total_records || '0'),
        });
      } catch {
        // Table may not exist, skip
        features.push({ name: feature.name, userCount: 0, totalRecords: 0 });
      }
    }

    res.json({ features });
  } catch (error) {
    console.error('Admin analytics features error:', error);
    res.status(500).json({ error: 'Failed to fetch feature analytics' });
  }
});

// GET /admin/analytics/costs - Cost estimation
router.get('/analytics/costs', async (req: Request, res: Response) => {
  try {
    // Storage: sum of photos + file transfers
    let storageTotalBytes = 0;
    try {
      const photoSize = await queryOne<{ total: string }>('SELECT COALESCE(SUM(file_size), 0) as total FROM user_photos');
      const transferSize = await queryOne<{ total: string }>('SELECT COALESCE(SUM(file_size), 0) as total FROM user_file_transfers');
      storageTotalBytes = parseInt(photoSize?.total || '0') + parseInt(transferSize?.total || '0');
    } catch {}

    // R2 pricing: $0.015/GB/month
    const storageGB = storageTotalBytes / (1024 * 1024 * 1024);
    const storageCost = parseFloat((storageGB * 0.015).toFixed(4));

    // Bandwidth estimate from user_usage
    let bandwidthTotalBytes = 0;
    try {
      const bw = await queryOne<{ total: string }>(`
        SELECT COALESCE(SUM(bandwidth_bytes), 0) as total
        FROM user_usage
        WHERE updated_at >= DATE_TRUNC('month', NOW())
      `);
      bandwidthTotalBytes = parseInt(bw?.total || '0');
    } catch {}

    // R2 egress: first 10GB free, then $0.045/GB (Class B operations pricing varies)
    const bandwidthGB = bandwidthTotalBytes / (1024 * 1024 * 1024);
    const bandwidthCost = parseFloat((Math.max(0, bandwidthGB - 10) * 0.045).toFixed(4));

    // Database size
    let databaseSize = 'unknown';
    try {
      const dbSize = await queryOne<{ size: string }>('SELECT pg_size_pretty(pg_database_size(current_database())) as size');
      databaseSize = dbSize?.size || 'unknown';
    } catch {}

    res.json({
      storage: {
        bytes: storageTotalBytes,
        estimatedCost: storageCost,
      },
      bandwidth: {
        bytes: bandwidthTotalBytes,
        estimatedCost: bandwidthCost,
      },
      database: {
        size: databaseSize,
      },
    });
  } catch (error) {
    console.error('Admin analytics costs error:', error);
    res.status(500).json({ error: 'Failed to fetch cost analytics' });
  }
});

// GET /admin/account-deletions - List deleted accounts
router.get('/account-deletions', async (req: Request, res: Response) => {
  try {
    // Check if the deleted_accounts table exists
    const tableExists = await queryOne<{ exists: boolean }>(`
      SELECT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'deleted_accounts'
      ) as exists
    `);

    if (!tableExists?.exists) {
      res.json({ deletions: [], message: 'deleted_accounts table does not exist' });
      return;
    }

    const deletions = await query<{ user_id: string; deleted_at: Date; reason: string | null }>(`
      SELECT user_id, deleted_at, reason
      FROM deleted_accounts
      ORDER BY deleted_at DESC
    `);

    res.json({
      deletions: deletions.map(d => ({
        userId: d.user_id,
        deletedAt: d.deleted_at,
        reason: d.reason,
      })),
    });
  } catch (error) {
    console.error('Admin account deletions error:', error);
    res.status(500).json({ error: 'Failed to fetch account deletions' });
  }
});

// ============================================
// DATABASE BROWSER
// ============================================

// GET /admin/tables - List all tables with row counts and sizes
router.get('/tables', async (req: Request, res: Response) => {
  try {
    const tables = await query<{
      table_name: string;
      estimated_rows: string;
      total_size: string;
      total_size_bytes: string;
    }>(`
      SELECT
        s.relname AS table_name,
        s.n_live_tup AS estimated_rows,
        pg_size_pretty(pg_total_relation_size(quote_ident(s.relname))) AS total_size,
        pg_total_relation_size(quote_ident(s.relname)) AS total_size_bytes
      FROM pg_stat_user_tables s
      ORDER BY pg_total_relation_size(quote_ident(s.relname)) DESC
    `);

    const dbSize = await queryOne<{ size: string; size_bytes: string }>(`
      SELECT
        pg_size_pretty(pg_database_size(current_database())) AS size,
        pg_database_size(current_database()) AS size_bytes
    `);

    res.json({
      tables: tables.map(t => ({
        name: t.table_name,
        estimatedRows: parseInt(t.estimated_rows),
        totalSize: t.total_size,
        totalSizeBytes: parseInt(t.total_size_bytes),
      })),
      databaseSize: dbSize?.size || 'unknown',
      databaseSizeBytes: parseInt(dbSize?.size_bytes || '0'),
    });
  } catch (error) {
    console.error('Admin tables error:', error);
    res.status(500).json({ error: 'Failed to fetch tables' });
  }
});

// GET /admin/tables/:tableName/schema - Column definitions for a table
router.get('/tables/:tableName/schema', async (req: Request, res: Response) => {
  try {
    const { tableName } = req.params;

    // Validate table exists (SQL injection prevention)
    const tableExists = await queryOne<{ exists: boolean }>(`
      SELECT EXISTS (
        SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = $1
      ) AS exists
    `, [tableName]);

    if (!tableExists?.exists) {
      res.status(404).json({ error: 'Table not found' });
      return;
    }

    const columns = await query<{
      column_name: string;
      data_type: string;
      is_nullable: string;
      column_default: string | null;
      character_maximum_length: number | null;
    }>(`
      SELECT column_name, data_type, is_nullable, column_default, character_maximum_length
      FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = $1
      ORDER BY ordinal_position
    `, [tableName]);

    // Get primary key columns
    const pkColumns = await query<{ column_name: string }>(`
      SELECT kcu.column_name
      FROM information_schema.table_constraints tc
      JOIN information_schema.key_column_usage kcu
        ON tc.constraint_name = kcu.constraint_name
        AND tc.table_schema = kcu.table_schema
      WHERE tc.constraint_type = 'PRIMARY KEY'
        AND tc.table_schema = 'public'
        AND tc.table_name = $1
    `, [tableName]);

    const pkSet = new Set(pkColumns.map(pk => pk.column_name));

    res.json({
      tableName,
      columns: columns.map(c => ({
        name: c.column_name,
        type: c.data_type,
        nullable: c.is_nullable === 'YES',
        default: c.column_default,
        maxLength: c.character_maximum_length,
        isPrimaryKey: pkSet.has(c.column_name),
      })),
    });
  } catch (error) {
    console.error('Admin table schema error:', error);
    res.status(500).json({ error: 'Failed to fetch table schema' });
  }
});

// GET /admin/tables/:tableName - Browse table data (paginated)
router.get('/tables/:tableName', async (req: Request, res: Response) => {
  try {
    const { tableName } = req.params;

    // Validate table exists (SQL injection prevention)
    const tableExists = await queryOne<{ exists: boolean }>(`
      SELECT EXISTS (
        SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = $1
      ) AS exists
    `, [tableName]);

    if (!tableExists?.exists) {
      res.status(404).json({ error: 'Table not found' });
      return;
    }

    const limit = Math.min(parseInt(req.query.limit as string) || 50, 200);
    const offset = parseInt(req.query.offset as string) || 0;
    const sort = req.query.sort as string | undefined;
    const order = (req.query.order as string)?.toUpperCase() === 'ASC' ? 'ASC' : 'DESC';
    const search = req.query.search as string | undefined;

    // Get column names for validation and search
    const colResult = await query<{ column_name: string; data_type: string }>(`
      SELECT column_name, data_type FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = $1
      ORDER BY ordinal_position
    `, [tableName]);

    const columnNames = colResult.map(c => c.column_name);
    const textColumns = colResult
      .filter(c => ['text', 'character varying', 'varchar', 'char', 'uuid'].includes(c.data_type))
      .map(c => c.column_name);

    // Validate sort column
    const sortColumn = sort && columnNames.includes(sort) ? sort : null;

    // Build query
    let sql = `SELECT * FROM ${tableName}`;
    const params: any[] = [];

    // Search across text columns
    if (search && textColumns.length > 0) {
      const searchConditions = textColumns.map((col, i) => {
        params.push(`%${search}%`);
        return `${col}::text ILIKE $${params.length}`;
      });
      sql += ` WHERE (${searchConditions.join(' OR ')})`;
    }

    // Sort
    if (sortColumn) {
      sql += ` ORDER BY ${sortColumn} ${order}`;
    }

    // Pagination
    params.push(limit);
    sql += ` LIMIT $${params.length}`;
    params.push(offset);
    sql += ` OFFSET $${params.length}`;

    const rows = await query(sql, params);

    // Get total count
    let countSql = `SELECT COUNT(*) AS count FROM ${tableName}`;
    const countParams: any[] = [];
    if (search && textColumns.length > 0) {
      const searchConditions = textColumns.map((col) => {
        countParams.push(`%${search}%`);
        return `${col}::text ILIKE $${countParams.length}`;
      });
      countSql += ` WHERE (${searchConditions.join(' OR ')})`;
    }
    const totalResult = await queryOne<{ count: string }>(countSql, countParams);

    // Truncate large text fields at 500 chars
    const truncatedRows = rows.map((row: any) => {
      const truncated: any = {};
      for (const [key, value] of Object.entries(row)) {
        if (typeof value === 'string' && value.length > 500) {
          truncated[key] = value.substring(0, 500) + '...';
        } else {
          truncated[key] = value;
        }
      }
      return truncated;
    });

    res.json({
      tableName,
      columns: columnNames,
      rows: truncatedRows,
      total: parseInt(totalResult?.count || '0'),
      limit,
      offset,
    });
  } catch (error) {
    console.error('Admin table data error:', error);
    res.status(500).json({ error: 'Failed to fetch table data' });
  }
});

// PUT /admin/tables/:tableName/:rowId - Update a row
router.put('/tables/:tableName/:rowId', async (req: Request, res: Response) => {
  try {
    const { tableName, rowId } = req.params;
    const updates = req.body;

    // Validate table exists
    const tables = await query(
      `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name = $1`,
      [tableName]
    );
    if (tables.length === 0) {
      res.status(404).json({ error: 'Table not found' });
      return;
    }

    // Get primary key column
    const pkResult = await query(
      `SELECT a.attname FROM pg_index i
       JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
       WHERE i.indrelid = $1::regclass AND i.indisprimary`,
      [tableName]
    );

    const pkColumn = pkResult.length > 0 ? pkResult[0].attname : 'id';

    // Build update query
    const setClauses: string[] = [];
    const values: any[] = [];
    let paramIdx = 1;

    for (const [col, val] of Object.entries(updates)) {
      if (col === pkColumn) continue; // Don't update PK
      setClauses.push(`"${col}" = $${paramIdx++}`);
      values.push(val);
    }

    if (setClauses.length === 0) {
      res.status(400).json({ error: 'No columns to update' });
      return;
    }

    values.push(rowId);
    const result = await query(
      `UPDATE "${tableName}" SET ${setClauses.join(', ')} WHERE "${pkColumn}" = $${paramIdx} RETURNING *`,
      values
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'Row not found' });
      return;
    }

    res.json({ success: true, row: result[0] });
  } catch (error) {
    console.error('Update row error:', error);
    res.status(500).json({ error: 'Failed to update row' });
  }
});

// DELETE /admin/tables/:tableName/:rowId - Delete a row
router.delete('/tables/:tableName/:rowId', async (req: Request, res: Response) => {
  try {
    const { tableName, rowId } = req.params;

    // Validate table exists
    const tables = await query(
      `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name = $1`,
      [tableName]
    );
    if (tables.length === 0) {
      res.status(404).json({ error: 'Table not found' });
      return;
    }

    // Get primary key column
    const pkResult = await query(
      `SELECT a.attname FROM pg_index i
       JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
       WHERE i.indrelid = $1::regclass AND i.indisprimary`,
      [tableName]
    );

    const pkColumn = pkResult.length > 0 ? pkResult[0].attname : 'id';

    const result = await query(
      `DELETE FROM "${tableName}" WHERE "${pkColumn}" = $1 RETURNING *`,
      [rowId]
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'Row not found' });
      return;
    }

    res.json({ success: true });
  } catch (error) {
    console.error('Delete row error:', error);
    res.status(500).json({ error: 'Failed to delete row' });
  }
});

// POST /admin/query - Run arbitrary SQL query
router.post('/query', async (req: Request, res: Response) => {
  try {
    const { sql } = req.body;

    if (!sql || typeof sql !== 'string') {
      res.status(400).json({ error: 'SQL query is required' });
      return;
    }

    // Basic safety check - allow all queries but warn on destructive ones
    const normalized = sql.trim().toLowerCase();
    const isModifying = normalized.startsWith('update') || normalized.startsWith('delete') ||
                        normalized.startsWith('insert') || normalized.startsWith('drop') ||
                        normalized.startsWith('alter') || normalized.startsWith('truncate');

    const result = await query(sql);

    res.json({
      rows: Array.isArray(result) ? result : [],
      rowCount: Array.isArray(result) ? result.length : 0,
      isModifying,
    });
  } catch (error: any) {
    res.status(400).json({ error: error.message || 'Query failed' });
  }
});

// GET /admin/db/health - Database health check
router.get('/db/health', async (req: Request, res: Response) => {
  try {
    const health = await queryOne<{
      db_size: string;
      active_connections: string;
      max_connections: string;
      uptime: string;
    }>(`
      SELECT
        pg_size_pretty(pg_database_size(current_database())) AS db_size,
        (SELECT count(*) FROM pg_stat_activity WHERE state = 'active') AS active_connections,
        (SELECT setting FROM pg_settings WHERE name = 'max_connections') AS max_connections,
        (SELECT date_trunc('second', current_timestamp - pg_postmaster_start_time()))::text AS uptime
    `);

    res.json({
      dbSize: health?.db_size || 'unknown',
      activeConnections: parseInt(health?.active_connections || '0'),
      maxConnections: parseInt(health?.max_connections || '100'),
      uptime: health?.uptime || 'unknown',
    });
  } catch (error) {
    console.error('Admin db health error:', error);
    res.status(500).json({ error: 'Failed to fetch database health' });
  }
});

// GET /admin/sync-groups - List sync groups (users with their devices)
router.get('/sync-groups', async (req: Request, res: Response) => {
  try {
    // VPS doesn't have a sync_groups table - instead, each user is effectively a sync group
    const groups = await query<{
      uid: string;
      phone: string | null;
      created_at: Date;
      device_count: string;
    }>(`
      SELECT
        u.uid,
        u.phone,
        u.created_at,
        (SELECT COUNT(*) FROM user_devices d WHERE d.user_id = u.uid) AS device_count
      FROM users u
      ORDER BY u.created_at DESC
    `);

    // Get subscription info
    const subs = await query<{ user_id: string; plan: string }>(`
      SELECT user_id, plan FROM user_subscriptions
    `);
    const subMap = new Map(subs.map(s => [s.user_id, s.plan]));

    res.json({
      success: true,
      groups: groups.map(g => ({
        syncGroupId: g.uid,
        plan: subMap.get(g.uid) || 'free',
        deviceCount: parseInt(g.device_count),
        deviceLimit: (subMap.get(g.uid) || 'free') === 'free' ? 2 : 10,
        createdAt: new Date(g.created_at).getTime(),
        masterDevice: g.phone || g.uid,
      })),
    });
  } catch (error) {
    console.error('Admin sync-groups error:', error);
    res.status(500).json({ error: 'Failed to fetch sync groups' });
  }
});

// ============================================
// USER LOOKUP
// ============================================

// GET /admin/user-lookup?q=<query>
router.get('/user-lookup', async (req: Request, res: Response) => {
  try {
    const q = (req.query.q as string || '').trim();
    if (!q) { res.status(400).json({ error: 'Query parameter q is required' }); return; }

    const searchParam = `%${q}%`;
    const users = await query<{
      uid: string; phone: string | null; email: string | null; firebase_uid: string | null;
      created_at: Date; updated_at: Date;
    }>(`
      SELECT uid, phone, email, firebase_uid, created_at, updated_at FROM users
      WHERE uid ILIKE $1 OR email ILIKE $1 OR phone ILIKE $1 OR firebase_uid ILIKE $1
      LIMIT 20
    `, [searchParam]);

    const results = await Promise.all(users.map(async (u) => {
      const devices = await query<{ id: string; device_type: string; name: string; last_seen: Date }>(
        'SELECT id, device_type, name, last_seen FROM user_devices WHERE user_id = $1 ORDER BY last_seen DESC', [u.uid]
      );
      const msgCount = await queryOne<{ count: string }>('SELECT COUNT(*) FROM user_messages WHERE user_id = $1', [u.uid]);
      const lastDevice = devices.length > 0 ? devices[0] : null;
      return {
        uid: u.uid, phone: u.phone, email: u.email, firebaseUid: u.firebase_uid,
        createdAt: u.created_at, updatedAt: u.updated_at,
        devices: devices.map(d => ({ id: d.id, type: d.device_type, name: d.name, lastSeen: d.last_seen })),
        messageCount: parseInt(msgCount?.count || '0'),
        lastActivity: lastDevice?.last_seen || u.updated_at,
      };
    }));

    res.json({ results, count: results.length });
  } catch (error) {
    console.error('Admin user-lookup error:', error);
    res.status(500).json({ error: 'Failed to lookup user' });
  }
});

// ============================================
// PENDING DELETIONS
// ============================================

// GET /admin/pending-deletions
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

// POST /admin/process-deletion/:userId - Immediately delete a user
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

    // Delete all user data
    await deleteUserData(userId);

    console.log(`[Admin] Processed deletion for user: ${userId}`);
    res.json({ success: true, message: 'User data deleted' });
  } catch (error) {
    console.error('Admin process deletion error:', error);
    res.status(500).json({ error: 'Failed to process deletion' });
  }
});

// POST /admin/cancel-deletion/:userId - Admin cancels a user's pending deletion
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

// ============================================
// ACTIVE SESSIONS
// ============================================

// GET /admin/sessions
router.get('/sessions', async (req: Request, res: Response) => {
  try {
    const online = getOnlineUsers();
    const userIds = online.map(u => u.userId);

    // Get device details for online users
    let deviceMap: Record<string, any[]> = {};
    if (userIds.length > 0) {
      const placeholders = userIds.map((_, i) => `$${i + 1}`).join(',');
      const devices = await query<{ user_id: string; id: string; device_type: string; name: string; last_seen: Date }>(
        `SELECT user_id, id, device_type, name, last_seen FROM user_devices WHERE user_id IN (${placeholders})`,
        userIds
      );
      for (const d of devices) {
        if (!deviceMap[d.user_id]) deviceMap[d.user_id] = [];
        deviceMap[d.user_id].push({ id: d.id, type: d.device_type, name: d.name, lastSeen: d.last_seen });
      }
    }

    res.json({
      onlineCount: online.length,
      users: online.map(u => ({
        uid: u.userId,
        wsDeviceCount: u.deviceCount,
        wsDevices: u.devices,
        devices: deviceMap[u.userId] || [],
      })),
    });
  } catch (error) {
    console.error('Admin sessions error:', error);
    res.status(500).json({ error: 'Failed to fetch sessions' });
  }
});

// ============================================
// E2EE KEY HEALTH
// ============================================

// GET /admin/e2ee/health
router.get('/e2ee/health', async (req: Request, res: Response) => {
  try {
    const safeCount = async (sql: string, params?: any[]): Promise<number> => {
      try {
        const r = await queryOne<{ count: string }>(sql, params);
        return parseInt(r?.count || '0');
      } catch { return 0; }
    };

    const [totalKeys, usersWithKeys, totalUsers, staleKeys] = await Promise.all([
      safeCount('SELECT COUNT(*) as count FROM user_e2ee_keys'),
      safeCount('SELECT COUNT(DISTINCT user_id) as count FROM user_e2ee_keys'),
      safeCount('SELECT COUNT(*) as count FROM users'),
      safeCount(`SELECT COUNT(*) as count FROM user_e2ee_keys WHERE updated_at < NOW() - INTERVAL '30 days'`),
    ]);

    res.json({
      totalKeys,
      usersWithKeys,
      usersWithout: totalUsers - usersWithKeys,
      staleKeys,
      totalUsers,
    });
  } catch (error) {
    console.error('Admin E2EE health error:', error);
    res.status(500).json({ error: 'Failed to fetch E2EE health' });
  }
});

// POST /admin/cleanup/e2ee - Clean up unused E2EE keys
router.post('/cleanup/e2ee', async (req: Request, res: Response) => {
  try {
    const results: Record<string, number> = {};

    // 1. Remove stale device keys (not updated in 90+ days)
    try {
      const stale = await query(
        `DELETE FROM user_e2ee_keys WHERE updated_at < NOW() - INTERVAL '90 days' RETURNING *`
      );
      results.staleDeviceKeys = stale.length;
    } catch { results.staleDeviceKeys = 0; }

    // 2. Remove orphaned device keys (user no longer exists)
    try {
      const orphaned = await query(
        `DELETE FROM user_e2ee_keys WHERE user_id NOT IN (SELECT uid FROM users) RETURNING *`
      );
      results.orphanedDeviceKeys = orphaned.length;
    } catch { results.orphanedDeviceKeys = 0; }

    // 3. Remove orphaned public keys (user no longer exists)
    try {
      const orphanedPub = await query(
        `DELETE FROM e2ee_public_keys WHERE uid NOT IN (SELECT uid FROM users) RETURNING *`
      );
      results.orphanedPublicKeys = orphanedPub.length;
    } catch { results.orphanedPublicKeys = 0; }

    // 4. Remove device keys for devices that no longer exist
    try {
      const noDevice = await query(
        `DELETE FROM user_e2ee_keys k
         WHERE NOT EXISTS (
           SELECT 1 FROM user_devices d
           WHERE d.user_id = k.user_id AND d.device_id = k.device_id
         ) RETURNING *`
      );
      results.keysForMissingDevices = noDevice.length;
    } catch { results.keysForMissingDevices = 0; }

    // 5. Remove public keys for devices that no longer exist
    try {
      const noPubDevice = await query(
        `DELETE FROM e2ee_public_keys pk
         WHERE NOT EXISTS (
           SELECT 1 FROM user_devices d
           WHERE d.user_id = pk.uid AND d.device_id = pk.device_id
         ) RETURNING *`
      );
      results.publicKeysForMissingDevices = noPubDevice.length;
    } catch { results.publicKeysForMissingDevices = 0; }

    // 6. Remove expired key exchange requests (older than 7 days)
    try {
      const expiredReqs = await query(
        `DELETE FROM e2ee_key_requests WHERE created_at < NOW() - INTERVAL '7 days' RETURNING *`
      );
      results.expiredKeyRequests = expiredReqs.length;
    } catch { results.expiredKeyRequests = 0; }

    // 7. Remove expired key exchange responses (older than 7 days)
    try {
      const expiredResps = await query(
        `DELETE FROM e2ee_key_responses WHERE created_at < NOW() - INTERVAL '7 days' RETURNING *`
      );
      results.expiredKeyResponses = expiredResps.length;
    } catch { results.expiredKeyResponses = 0; }

    const totalCleaned = Object.values(results).reduce((a, b) => a + b, 0);

    console.log(`[Admin] E2EE cleanup: ${totalCleaned} items removed`, results);

    res.json({
      success: true,
      totalCleaned,
      breakdown: results,
    });
  } catch (error) {
    console.error('Admin E2EE cleanup error:', error);
    res.status(500).json({ error: 'Failed to cleanup E2EE keys' });
  }
});

// ============================================
// SERVER LOGS
// ============================================

// GET /admin/logs?limit=100&level=error&search=text
router.get('/logs', async (req: Request, res: Response) => {
  try {
    const limit = parseInt(req.query.limit as string) || 100;
    const level = req.query.level as string | undefined;
    const search = req.query.search as string | undefined;

    const result = getLogs(limit, level, search);
    res.json(result);
  } catch (error) {
    console.error('Admin logs error:', error);
    res.status(500).json({ error: 'Failed to fetch logs' });
  }
});

// DELETE /admin/logs
router.delete('/logs', async (req: Request, res: Response) => {
  try {
    clearLogs();
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: 'Failed to clear logs' });
  }
});

// ============================================
// SYSTEM ALERTS
// ============================================

// GET /admin/system/alerts
router.get('/system/alerts', async (req: Request, res: Response) => {
  try {
    const alerts: Array<{ severity: 'critical' | 'warning' | 'info'; title: string; message: string }> = [];

    // Check DB connections
    try {
      const dbHealth = await queryOne<{ active: string; max: string }>(`
        SELECT
          (SELECT count(*) FROM pg_stat_activity WHERE state = 'active') as active,
          (SELECT setting FROM pg_settings WHERE name = 'max_connections') as max
      `);
      const active = parseInt(dbHealth?.active || '0');
      const max = parseInt(dbHealth?.max || '100');
      const pct = (active / max) * 100;
      if (pct > 80) {
        alerts.push({ severity: 'critical', title: 'DB Connections High', message: `${active}/${max} connections in use (${pct.toFixed(0)}%)` });
      } else if (pct > 60) {
        alerts.push({ severity: 'warning', title: 'DB Connections Elevated', message: `${active}/${max} connections in use (${pct.toFixed(0)}%)` });
      }
    } catch {}

    // Check Redis
    try {
      const redisOk = await checkRedisHealth();
      if (!redisOk) {
        alerts.push({ severity: 'critical', title: 'Redis Down', message: 'Redis health check failed' });
      }
    } catch {
      alerts.push({ severity: 'critical', title: 'Redis Unreachable', message: 'Could not connect to Redis' });
    }

    // Users with 0 devices
    try {
      const r = await queryOne<{ count: string }>('SELECT COUNT(*) as count FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = u.uid)');
      const count = parseInt(r?.count || '0');
      if (count > 10) {
        alerts.push({ severity: 'warning', title: 'Users Without Devices', message: `${count} users have no registered devices` });
      }
    } catch {}

    // Stale devices (not seen in 30 days)
    try {
      const r = await queryOne<{ count: string }>(`SELECT COUNT(*) as count FROM user_devices WHERE last_seen < NOW() - INTERVAL '30 days'`);
      const count = parseInt(r?.count || '0');
      if (count > 0) {
        alerts.push({ severity: 'info', title: 'Stale Devices', message: `${count} devices not seen in 30+ days` });
      }
    } catch {}

    // Check maintenance mode
    try {
      const enabled = await getCache<string>('maintenance:enabled');
      if (enabled === 'true') {
        alerts.push({ severity: 'warning', title: 'Maintenance Mode Active', message: 'Non-admin API requests are returning 503' });
      }
    } catch {}

    res.json({ alerts });
  } catch (error) {
    console.error('Admin alerts error:', error);
    res.status(500).json({ error: 'Failed to fetch alerts' });
  }
});

// ============================================
// MAINTENANCE MODE
// ============================================

// GET /admin/maintenance
router.get('/maintenance', async (req: Request, res: Response) => {
  try {
    const enabled = await getCache<string>('maintenance:enabled');
    const message = await getCache<string>('maintenance:message');
    res.json({ enabled: enabled === 'true', message: message || '' });
  } catch (error) {
    console.error('Admin get maintenance error:', error);
    res.status(500).json({ error: 'Failed to get maintenance status' });
  }
});

// POST /admin/maintenance
router.post('/maintenance', async (req: Request, res: Response) => {
  try {
    const { enabled, message } = req.body;
    if (enabled) {
      await setCache('maintenance:enabled', 'true');
      if (message) {
        await setCache('maintenance:message', message);
      }
      console.log(`[Admin] Maintenance mode ENABLED: ${message || '(no message)'}`);
    } else {
      await deleteCache('maintenance:enabled');
      await deleteCache('maintenance:message');
      console.log('[Admin] Maintenance mode DISABLED');
    }
    res.json({ enabled: !!enabled, message: message || '' });
  } catch (error) {
    console.error('Admin set maintenance error:', error);
    res.status(500).json({ error: 'Failed to set maintenance mode' });
  }
});

export default router;
