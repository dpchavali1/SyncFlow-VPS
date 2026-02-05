import { Router, Request, Response } from 'express';
import { query, queryOne } from '../services/database';
import { authenticate, requireAdmin } from '../middleware/auth';

const router = Router();

// Apply authentication and admin check to all routes
router.use(authenticate);
router.use(requireAdmin);

// GET /admin/stats - Get overall stats
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

// GET /admin/users - List all users
router.get('/users', async (req: Request, res: Response) => {
  try {
    const limit = parseInt(req.query.limit as string) || 100;
    const offset = parseInt(req.query.offset as string) || 0;

    const users = await query<{
      uid: string;
      created_at: Date;
      updated_at: Date;
      device_count: string;
      message_count: string;
    }>(`
      SELECT
        u.uid,
        u.created_at,
        u.updated_at,
        (SELECT COUNT(*) FROM user_devices d WHERE d.user_id = u.uid) as device_count,
        (SELECT COUNT(*) FROM user_messages m WHERE m.user_id = u.uid) as message_count
      FROM users u
      ORDER BY u.created_at DESC
      LIMIT $1 OFFSET $2
    `, [limit, offset]);

    const total = await queryOne<{ count: string }>('SELECT COUNT(*) FROM users');

    res.json({
      users: users.map(u => ({
        id: u.uid,
        createdAt: u.created_at,
        updatedAt: u.updated_at,
        deviceCount: parseInt(u.device_count),
        messageCount: parseInt(u.message_count),
      })),
      total: parseInt(total?.count || '0'),
      limit,
      offset,
    });
  } catch (error) {
    console.error('Admin users error:', error);
    res.status(500).json({ error: 'Failed to fetch users' });
  }
});

// GET /admin/users/:userId - Get user details
router.get('/users/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;

    const user = await queryOne<{
      uid: string;
      created_at: Date;
      updated_at: Date;
    }>('SELECT uid, created_at, updated_at FROM users WHERE uid = $1', [userId]);

    if (!user) {
      res.status(404).json({ error: 'User not found' });
      return;
    }

    const devices = await query<{
      id: string;
      device_type: string;
      name: string;
      paired_at: Date;
      last_seen: Date;
    }>('SELECT id, device_type, name, paired_at, last_seen FROM user_devices WHERE user_id = $1 ORDER BY paired_at DESC', [userId]);

    const messageCount = await queryOne<{ count: string }>(
      'SELECT COUNT(*) FROM user_messages WHERE user_id = $1',
      [userId]
    );

    const contactCount = await queryOne<{ count: string }>(
      'SELECT COUNT(*) FROM user_contacts WHERE user_id = $1',
      [userId]
    );

    res.json({
      id: user.uid,
      createdAt: user.created_at,
      updatedAt: user.updated_at,
      devices: devices.map(d => ({
        id: d.id,
        type: d.device_type,
        name: d.name,
        createdAt: d.paired_at,
        lastSeen: d.last_seen,
      })),
      messageCount: parseInt(messageCount?.count || '0'),
      contactCount: parseInt(contactCount?.count || '0'),
    });
  } catch (error) {
    console.error('Admin user details error:', error);
    res.status(500).json({ error: 'Failed to fetch user details' });
  }
});

// GET /admin/users/:userId/messages - Get user messages
router.get('/users/:userId/messages', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;
    const limit = parseInt(req.query.limit as string) || 50;
    const offset = parseInt(req.query.offset as string) || 0;

    const messages = await query<{
      id: string;
      address: string;
      body: string;
      date: string;
      type: number;
      read: boolean;
      is_mms: boolean;
    }>(`
      SELECT id, address, body, date, type, read, is_mms
      FROM user_messages
      WHERE user_id = $1
      ORDER BY date DESC
      LIMIT $2 OFFSET $3
    `, [userId, limit, offset]);

    const total = await queryOne<{ count: string }>(
      'SELECT COUNT(*) FROM user_messages WHERE user_id = $1',
      [userId]
    );

    res.json({
      messages: messages.map(m => ({
        id: m.id,
        address: m.address,
        body: m.body,
        date: m.date,
        type: m.type,
        read: m.read,
        isMms: m.is_mms,
      })),
      total: parseInt(total?.count || '0'),
      limit,
      offset,
    });
  } catch (error) {
    console.error('Admin user messages error:', error);
    res.status(500).json({ error: 'Failed to fetch messages' });
  }
});

// GET /admin/devices - List all devices
router.get('/devices', async (req: Request, res: Response) => {
  try {
    const limit = parseInt(req.query.limit as string) || 100;
    const offset = parseInt(req.query.offset as string) || 0;

    const devices = await query<{
      id: string;
      user_id: string;
      device_type: string;
      name: string;
      paired_at: Date;
      last_seen: Date;
    }>(`
      SELECT id, user_id, device_type, name, paired_at, last_seen
      FROM user_devices
      ORDER BY last_seen DESC
      LIMIT $1 OFFSET $2
    `, [limit, offset]);

    const total = await queryOne<{ count: string }>('SELECT COUNT(*) FROM user_devices');

    res.json({
      devices: devices.map(d => ({
        id: d.id,
        userId: d.user_id,
        type: d.device_type,
        name: d.name,
        createdAt: d.paired_at,
        lastSeen: d.last_seen,
      })),
      total: parseInt(total?.count || '0'),
      limit,
      offset,
    });
  } catch (error) {
    console.error('Admin devices error:', error);
    res.status(500).json({ error: 'Failed to fetch devices' });
  }
});

// GET /admin/pairing-requests - List pending pairing requests
router.get('/pairing-requests', async (req: Request, res: Response) => {
  try {
    const requests = await query<{
      token: string;
      device_id: string;
      device_name: string;
      device_type: string;
      status: string;
      created_at: Date;
      expires_at: Date;
    }>(`
      SELECT token, device_id, device_name, device_type, status, created_at, expires_at
      FROM pairing_requests
      WHERE status = 'pending' AND expires_at > NOW()
      ORDER BY created_at DESC
    `);

    res.json({
      requests: requests.map(r => ({
        token: r.token.substring(0, 8) + '...', // Only show partial token
        deviceId: r.device_id,
        deviceName: r.device_name,
        deviceType: r.device_type,
        status: r.status,
        createdAt: r.created_at,
        expiresAt: r.expires_at,
      })),
    });
  } catch (error) {
    console.error('Admin pairing requests error:', error);
    res.status(500).json({ error: 'Failed to fetch pairing requests' });
  }
});

// DELETE /admin/users/:userId - Delete user and all data
router.delete('/users/:userId', async (req: Request, res: Response) => {
  try {
    const { userId } = req.params;

    // Delete in order to respect foreign keys (CASCADE will handle most, but explicit is cleaner)
    await query('DELETE FROM user_messages WHERE user_id = $1', [userId]);
    await query('DELETE FROM user_contacts WHERE user_id = $1', [userId]);
    await query('DELETE FROM user_call_history WHERE user_id = $1', [userId]);
    await query('DELETE FROM user_devices WHERE user_id = $1', [userId]);
    await query('DELETE FROM users WHERE uid = $1', [userId]);

    res.json({ success: true, message: 'User and all data deleted' });
  } catch (error) {
    console.error('Admin delete user error:', error);
    res.status(500).json({ error: 'Failed to delete user' });
  }
});

export default router;
