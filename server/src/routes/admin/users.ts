/**
 * Admin sub-router: User Management & Lookup.
 *
 * Endpoints:
 *   GET    /users                           — List/search users (paginated)
 *   GET    /users/:userId                   — Single user detail (devices, counts, subscription)
 *   GET    /users/:userId/messages           — Browse a user's messages (paginated)
 *   DELETE /users/:userId                   — Delete user and all associated data
 *   POST   /users/bulk-delete               — Bulk-delete inactive users (by inactiveDays)
 *   POST   /users/:userId/plan              — Set subscription plan (free/monthly/yearly/lifetime)
 *   POST   /users/:userId/recalculate-storage — Recompute storage usage from photos + transfers + MMS
 *   GET    /user-lookup?q=<query>           — Search users by uid/email/phone/firebase_uid
 */

import { Router, Request, Response } from 'express';
import { query, queryOne } from '../../services/database';
import { deleteUserData } from './helpers';

const router = Router();

// ── GET /users — List/search users (paginated) ─────────────────────────────

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

    // Check which optional tables exist (user_subscriptions, user_photos, user_file_transfers)
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
        ${hasTransfers ? `COALESCE((SELECT SUM(file_size) FROM user_file_transfers ft WHERE ft.user_id = u.uid), 0)` : '0'} +
        COALESCE((SELECT SUM((part->>'fileSize')::bigint) FROM user_messages m, jsonb_array_elements(m.mms_parts) AS part WHERE m.user_id = u.uid AND m.mms_parts IS NOT NULL), 0) as storage_bytes,
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

// ── GET /users/:userId — Single user detail ─────────────────────────────────

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

// ── GET /users/:userId/messages — Browse user messages (paginated) ──────────

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

// ── DELETE /users/:userId — Delete user and all data ────────────────────────

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

// ── POST /users/bulk-delete — Bulk-delete inactive users ────────────────────

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

// ── POST /users/:userId/plan — Set subscription plan ────────────────────────

router.post('/users/:userId/plan', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const { plan, expiresAt } = req.body;
    const validPlans = ['free', 'monthly', 'yearly', 'lifetime'];
    if (!validPlans.includes(plan)) { res.status(400).json({ error: `plan must be one of: ${validPlans.join(', ')}` }); return; }

    const user = await queryOne<{ uid: string }>('SELECT uid FROM users WHERE uid = $1', [userId]);
    if (!user) { res.status(404).json({ error: 'User not found' }); return; }

    await query(`
      INSERT INTO user_subscriptions (user_id, plan, status, expires_at, started_at)
      VALUES ($1, $2, 'active', $3, NOW())
      ON CONFLICT (user_id) DO UPDATE SET plan = $2, expires_at = $3, started_at = NOW()
    `, [userId, plan, expiresAt || null]);

    res.json({ success: true, userId, plan, expiresAt: expiresAt || null });
  } catch (error) {
    console.error('Admin set plan error:', error);
    res.status(500).json({ error: 'Failed to set user plan' });
  }
});

// ── POST /users/:userId/recalculate-storage — Recompute storage usage ───────

router.post('/users/:userId/recalculate-storage', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const photoStorage = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM(file_size), 0) as total_size FROM user_photos WHERE user_id = $1`, [userId]);
    const transferStorage = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM(file_size), 0) as total_size FROM user_file_transfers WHERE user_id = $1`, [userId]);
    const mmsStorage = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM((part->>'fileSize')::bigint), 0) as total_size FROM user_messages m, jsonb_array_elements(m.mms_parts) AS part WHERE m.user_id = $1 AND m.mms_parts IS NOT NULL`, [userId]);

    const totalBytes = parseInt(photoStorage?.total_size || '0') + parseInt(transferStorage?.total_size || '0') + parseInt(mmsStorage?.total_size || '0');
    await query(`INSERT INTO user_usage (user_id, storage_bytes, updated_at) VALUES ($1, $2, NOW()) ON CONFLICT (user_id) DO UPDATE SET storage_bytes = $2, updated_at = NOW()`, [userId, totalBytes]);

    res.json({ success: true, userId, storage: { photos: { count: parseInt(photoStorage?.count || '0'), sizeBytes: parseInt(photoStorage?.total_size || '0') }, fileTransfers: { count: parseInt(transferStorage?.count || '0'), sizeBytes: parseInt(transferStorage?.total_size || '0') }, mmsAttachments: { count: parseInt(mmsStorage?.count || '0'), sizeBytes: parseInt(mmsStorage?.total_size || '0') }, totalStorageBytes: totalBytes } });
  } catch (error) {
    console.error('Admin recalculate storage error:', error);
    res.status(500).json({ error: 'Failed to recalculate storage' });
  }
});

// ── GET /user-lookup — Search users by uid/email/phone/firebase_uid ─────────

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

export default router;
