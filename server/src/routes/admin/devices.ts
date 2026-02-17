/**
 * Admin sub-router: Devices & Pairing.
 *
 * Endpoints:
 *   GET /devices            — List all registered devices (paginated, sorted by last_seen)
 *   GET /pairing-requests   — List pending pairing requests (token truncated for security)
 */

import { Router, Request, Response } from 'express';
import { query, queryOne } from '../../services/database';

const router = Router();

// ── GET /devices — List all devices (paginated) ────────────────────────────

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

// ── GET /pairing-requests — Pending pairing requests ────────────────────────

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

export default router;
