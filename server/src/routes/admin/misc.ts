/**
 * Admin sub-router: Crash Reports, Sessions, E2EE Health, Logs, Alerts & Maintenance.
 *
 * Groups smaller admin features that don't warrant their own file into a single
 * "miscellaneous" router. Each section is clearly delimited with comments.
 *
 * Endpoints:
 *   GET    /crashes              — List crash reports (paginated, filterable by resolved)
 *   POST   /crashes/:id/resolve  — Mark a crash report as resolved
 *   DELETE /crashes/:id          — Delete a crash report
 *   GET    /sessions             — List currently connected WebSocket sessions
 *   GET    /e2ee/health          — E2EE key inventory (total, stale, orphaned)
 *   POST   /cleanup/e2ee         — Clean up stale/orphaned E2EE keys
 *   GET    /logs                 — Retrieve server logs (filterable by level/search)
 *   DELETE /logs                 — Clear all in-memory logs
 *   GET    /system/alerts        — System health alerts (DB connections, Redis, stale devices)
 *   GET    /maintenance          — Get maintenance mode status
 *   POST   /maintenance          — Enable/disable maintenance mode (via Redis)
 */

import { Router, Request, Response } from 'express';
import { query, queryOne } from '../../services/database';
import { getOnlineUsers } from '../../services/websocket';
import { getCache, setCache, deleteCache, checkRedisHealth } from '../../services/redis';
import { getLogs, clearLogs } from '../../services/logger';

const router = Router();

// ═══════════════════════════════════════════════════════════════════════════
// CRASH REPORTS
// ═══════════════════════════════════════════════════════════════════════════

// ── GET /crashes — List crash reports ───────────────────────────────────────

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

    let crashes: any[], totalCount: { count: string } | null, unresolvedCount: { count: string } | null, byVersion: { app_version: string; count: string }[];
    try {
      crashes = await query(sql, params);
      totalCount = await queryOne<{ count: string }>('SELECT COUNT(*) as count FROM crash_reports');
      unresolvedCount = await queryOne<{ count: string }>(
        'SELECT COUNT(*) as count FROM crash_reports WHERE resolved = false OR resolved IS NULL'
      );
      byVersion = await query<{ app_version: string; count: string }>(`SELECT app_version, COUNT(*) as count FROM crash_reports WHERE app_version IS NOT NULL GROUP BY app_version ORDER BY count DESC`);
    } catch (columnError: any) {
      // If 'resolved' or 'app_version' column doesn't exist (42703), add them and retry
      if (columnError.code === '42703') {
        await query('ALTER TABLE crash_reports ADD COLUMN IF NOT EXISTS resolved BOOLEAN DEFAULT false');
        await query('ALTER TABLE crash_reports ADD COLUMN IF NOT EXISTS app_version TEXT');
        crashes = await query('SELECT * FROM crash_reports ORDER BY created_at DESC LIMIT $1 OFFSET $2', [limit, offset]);
        totalCount = await queryOne<{ count: string }>('SELECT COUNT(*) as count FROM crash_reports');
        unresolvedCount = await queryOne<{ count: string }>('SELECT COUNT(*) as count FROM crash_reports WHERE resolved = false OR resolved IS NULL');
        byVersion = await query<{ app_version: string; count: string }>(`SELECT app_version, COUNT(*) as count FROM crash_reports WHERE app_version IS NOT NULL GROUP BY app_version ORDER BY count DESC`);
      } else {
        throw columnError;
      }
    }

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

// ── POST /crashes/:id/resolve — Mark crash resolved ─────────────────────────

router.post('/crashes/:id/resolve', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;

    try {
      await query('UPDATE crash_reports SET resolved = true WHERE id = $1', [id]);
    } catch (columnError: any) {
      // If the resolved column doesn't exist (error code 42703), add it first
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

// ── DELETE /crashes/:id — Delete a crash report ─────────────────────────────

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

// ═══════════════════════════════════════════════════════════════════════════
// ACTIVE SESSIONS
// ═══════════════════════════════════════════════════════════════════════════

// ── GET /sessions — Currently connected WebSocket sessions ──────────────────

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

// ═══════════════════════════════════════════════════════════════════════════
// E2EE KEY HEALTH
// ═══════════════════════════════════════════════════════════════════════════

// ── GET /e2ee/health — E2EE key inventory ───────────────────────────────────

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

// ── POST /cleanup/e2ee — Clean up stale/orphaned E2EE keys ─────────────────

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

// ═══════════════════════════════════════════════════════════════════════════
// SERVER LOGS
// ═══════════════════════════════════════════════════════════════════════════

// ── GET /logs — Retrieve in-memory server logs ──────────────────────────────

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

// ── DELETE /logs — Clear all in-memory logs ─────────────────────────────────

router.delete('/logs', async (req: Request, res: Response) => {
  try {
    clearLogs();
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: 'Failed to clear logs' });
  }
});

// ═══════════════════════════════════════════════════════════════════════════
// SYSTEM ALERTS
// ═══════════════════════════════════════════════════════════════════════════

// ── GET /system/alerts — System health alerts ───────────────────────────────

router.get('/system/alerts', async (req: Request, res: Response) => {
  try {
    const alerts: Array<{ severity: 'critical' | 'warning' | 'info'; title: string; message: string }> = [];

    // Check DB connection utilization
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

    // Check Redis health
    try {
      const redisOk = await checkRedisHealth();
      if (!redisOk) {
        alerts.push({ severity: 'critical', title: 'Redis Down', message: 'Redis health check failed' });
      }
    } catch {
      alerts.push({ severity: 'critical', title: 'Redis Unreachable', message: 'Could not connect to Redis' });
    }

    // Users with 0 devices (potential orphans from abandoned pairing)
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

// ═══════════════════════════════════════════════════════════════════════════
// MAINTENANCE MODE
// ═══════════════════════════════════════════════════════════════════════════

// ── GET /maintenance — Get maintenance mode status ──────────────────────────

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

// ── POST /maintenance — Toggle maintenance mode ─────────────────────────────

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
