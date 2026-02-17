/**
 * Admin sub-router: Analytics, Stats & Overview.
 *
 * Provides system-wide counters, time-series analytics (DAU, retention, bandwidth),
 * feature usage tracking, cost estimation, and account deletion history.
 *
 * Endpoints:
 *   GET /stats                  — Quick system-wide counters (users, devices, messages, etc.)
 *   GET /overview               — Comprehensive overview with orphan detection and DB size
 *   GET /analytics/dashboard    — 30-day DAU, new signups, message volume time series
 *   GET /analytics/bandwidth    — Monthly bandwidth totals, top consumers, daily trend
 *   GET /analytics/retention    — 1d/7d/30d/90d active users + churn rate
 *   GET /analytics/features     — Per-feature usage (user count + record count)
 *   GET /analytics/costs        — Cost estimation (R2 storage + bandwidth + DB size)
 *   GET /account-deletions      — List of deleted accounts (audit trail)
 */

import { Router, Request, Response } from 'express';
import { query, queryOne } from '../../services/database';

const router = Router();

// ── GET /stats — Quick system-wide counters ─────────────────────────────────

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

// ── GET /overview — Comprehensive system overview ───────────────────────────

router.get('/overview', async (req: Request, res: Response) => {
  try {
    // Helpers for safely counting/summing from tables that may not exist
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

// ── GET /analytics/dashboard — 30-day DAU, signups, message volume ──────────

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

    // New user signups per day
    const newUsers = await query<{ date: string; count: string }>(`
      SELECT DATE(created_at) as date, COUNT(*) as count
      FROM users
      WHERE created_at > NOW() - INTERVAL '30 days'
      GROUP BY DATE(created_at)
      ORDER BY date ASC
    `);

    // Message volume per day
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

// ── GET /analytics/bandwidth — Monthly bandwidth, top consumers, trend ──────

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

    // Daily bandwidth trend (30 days)
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

// ── GET /analytics/retention — Active users + churn rate ────────────────────

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

    const churnRate = totalUsers > 0 ? parseFloat(((totalUsers - active30d) / totalUsers).toFixed(4)) : 0;

    res.json({ active1d, active7d, active30d, active90d, churnRate, totalUsers });
  } catch (error) {
    console.error('Admin analytics retention error:', error);
    res.status(500).json({ error: 'Failed to fetch retention analytics' });
  }
});

// ── GET /analytics/features — Per-feature usage ─────────────────────────────

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

// ── GET /analytics/costs — Cost estimation (R2 + bandwidth + DB) ────────────

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

    // R2 egress: first 10GB free, then $0.045/GB
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

// ── GET /account-deletions — Deleted account audit trail ────────────────────

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

export default router;
