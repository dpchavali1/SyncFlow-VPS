import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, transaction } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';
import { broadcastToUser, broadcastToDevice, broadcastToAllDevicesExcept, getConnectedDeviceCount } from '../services/websocket';
import { getCache, setCache } from '../services/redis';
import { config } from '../config';
import { normalizePhoneNumber } from '../utils/phoneNumber';
import { sendCallNotification, sendCallRequestNotification, isFCMInitialized } from '../services/push';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const getCallsSchema = z.object({
  limit: z.coerce.number().min(1).max(500).default(100),
  before: z.coerce.number().optional(),
  after: z.coerce.number().optional(),
  type: z.enum(['incoming', 'outgoing', 'missed', 'rejected', 'blocked', 'voicemail']).optional(),
});

const syncCallSchema = z.object({
  id: z.string().min(1),
  phoneNumber: z.string().min(1).max(50),
  contactName: z.string().max(255).optional(),
  callType: z.enum(['incoming', 'outgoing', 'missed', 'rejected', 'blocked', 'voicemail']),
  callDate: z.number(),
  duration: z.number().default(0),
  simSubscriptionId: z.number().optional(),
});

const syncCallsSchema = z.object({
  calls: z.array(syncCallSchema).max(500),
});

const requestCallSchema = z.object({
  phoneNumber: z.string().min(1).max(50),
  simSubscriptionId: z.number().optional(),
});

const registerPhoneSchema = z.object({
  phoneNumber: z.string().min(1).max(50),
});

// GET /calls - Get call history with pagination
router.get('/', async (req: Request, res: Response) => {
  try {
    const params = getCallsSchema.parse(req.query);
    const userId = req.userId!;

    let queryText = `
      SELECT id, phone_number, contact_name, call_type, call_date, duration, sim_subscription_id
      FROM user_call_history
      WHERE user_id = $1
    `;
    const queryParams: any[] = [userId];
    let paramIndex = 2;

    if (params.type) {
      queryText += ` AND call_type = $${paramIndex++}`;
      queryParams.push(params.type);
    }

    if (params.after) {
      queryText += ` AND call_date > $${paramIndex++}`;
      queryParams.push(params.after);
    }

    if (params.before) {
      queryText += ` AND call_date < $${paramIndex++}`;
      queryParams.push(params.before);
    }

    queryText += ` ORDER BY call_date DESC LIMIT $${paramIndex}`;
    queryParams.push(params.limit);

    const calls = await query(queryText, queryParams);

    res.json({
      calls: calls.map((c) => ({
        id: c.id,
        phoneNumber: c.phone_number,
        contactName: c.contact_name,
        callType: c.call_type,
        callDate: parseInt(c.call_date),
        duration: c.duration,
        simSubscriptionId: c.sim_subscription_id,
      })),
      hasMore: calls.length === params.limit,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Get calls error:', error);
    res.status(500).json({ error: 'Failed to get call history' });
  }
});

// POST /calls/sync - Sync call history from device
router.post('/sync', async (req: Request, res: Response) => {
  try {
    const body = syncCallsSchema.parse(req.body);
    const userId = req.userId!;

    let synced = 0;
    let skipped = 0;

    for (const call of body.calls) {
      try {
        await query(
          `INSERT INTO user_call_history
           (id, user_id, phone_number, contact_name, call_type, call_date, duration, sim_subscription_id)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
           ON CONFLICT (id) DO UPDATE SET
             contact_name = EXCLUDED.contact_name,
             duration = EXCLUDED.duration`,
          [
            call.id,
            userId,
            normalizePhoneNumber(call.phoneNumber),
            call.contactName,
            call.callType,
            call.callDate,
            call.duration,
            call.simSubscriptionId,
          ]
        );
        synced++;
      } catch (e) {
        skipped++;
      }
    }

    res.json({ synced, skipped, total: body.calls.length });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Sync calls error:', error);
    res.status(500).json({ error: 'Failed to sync call history' });
  }
});

// POST /calls/request - Request a call (from desktop)
router.post('/request', async (req: Request, res: Response) => {
  try {
    const body = requestCallSchema.parse(req.body);
    const userId = req.userId!;
    const requestId = `call_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    const normalizedPhone = normalizePhoneNumber(body.phoneNumber);
    const now = Date.now();

    await query(
      `INSERT INTO user_call_requests
       (id, user_id, phone_number, status, requested_at, sim_subscription_id)
       VALUES ($1, $2, $3, 'pending', $4, $5)`,
      [requestId, userId, normalizedPhone, now, body.simSubscriptionId]
    );

    // Broadcast call request to Android via WebSocket
    broadcastToUser(userId, 'calls', {
      type: 'call_request',
      data: {
        id: requestId,
        phoneNumber: normalizedPhone,
        status: 'pending',
        requestedAt: now,
        simSubscriptionId: body.simSubscriptionId || null,
      },
    });

    // Send FCM push to wake Android if app is in background
    sendCallRequestNotification(userId, requestId, normalizedPhone, req.deviceId ?? null).catch(err => {
      console.error('[Calls] FCM push for call request failed:', err);
    });

    res.json({
      id: requestId,
      status: 'pending',
      message: 'Call request queued',
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Request call error:', error);
    res.status(500).json({ error: 'Failed to request call' });
  }
});

// GET /calls/requests - Get pending call requests (for Android to pick up)
router.get('/requests', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const requests = await query(
      `SELECT id, phone_number, status, requested_at, sim_subscription_id
       FROM user_call_requests
       WHERE user_id = $1 AND status = 'pending'
       ORDER BY requested_at ASC
       LIMIT 10`,
      [userId]
    );

    res.json({
      requests: requests.map((r) => ({
        id: r.id,
        phoneNumber: r.phone_number,
        status: r.status,
        requestedAt: parseInt(r.requested_at),
        simSubscriptionId: r.sim_subscription_id,
      })),
    });
  } catch (error) {
    console.error('Get call requests error:', error);
    res.status(500).json({ error: 'Failed to get call requests' });
  }
});

// PUT /calls/requests/:id/status - Update call request status
router.put('/requests/:id/status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { status } = req.body;
    const userId = req.userId!;

    if (!['dialing', 'completed', 'failed'].includes(status)) {
      res.status(400).json({ error: 'Invalid status' });
      return;
    }

    await query(
      `UPDATE user_call_requests SET status = $1 WHERE id = $2 AND user_id = $3`,
      [status, id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Update call request status error:', error);
    res.status(500).json({ error: 'Failed to update status' });
  }
});

// GET /calls/count - Get call count
router.get('/count', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const result = await query(
      `SELECT COUNT(*) as count FROM user_call_history WHERE user_id = $1`,
      [userId]
    );

    res.json({ count: parseInt(result[0]?.count || '0') });
  } catch (error) {
    console.error('Get call count error:', error);
    res.status(500).json({ error: 'Failed to get count' });
  }
});

// POST /calls/register - Register phone number for video calling
router.post('/register', async (req: Request, res: Response) => {
  try {
    const body = registerPhoneSchema.parse(req.body);
    const userId = req.userId!;

    // Normalize to E.164 format
    const normalizedPhone = normalizePhoneNumber(body.phoneNumber);

    await transaction(async (client) => {
      // Prevent concurrent register calls from tripping the unique constraint.
      await client.query('LOCK TABLE user_phone_registry IN EXCLUSIVE MODE');

      // Remove any existing registration for this phone number (from any user).
      await client.query(
        `DELETE FROM user_phone_registry WHERE phone_number = $1 AND user_id <> $2`,
        [normalizedPhone, userId]
      );

      // Insert or update the user's registration.
      await client.query(
        `INSERT INTO user_phone_registry (user_id, phone_number, registered_at)
         VALUES ($1, $2, $3)
         ON CONFLICT (user_id) DO UPDATE SET
           phone_number = EXCLUDED.phone_number,
           registered_at = EXCLUDED.registered_at`,
        [userId, normalizedPhone, Date.now()]
      );

      // Also update the users table phone column for admin visibility
      await client.query(
        `UPDATE users SET phone = $1 WHERE uid = $2`,
        [normalizedPhone, userId]
      );
    });

    console.log(`Phone number registered: ${normalizedPhone} for user: ${userId}`);

    res.json({
      success: true,
      phoneNumber: normalizedPhone,
      message: 'Phone number registered for video calling',
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Register phone error:', error);
    res.status(500).json({ error: 'Failed to register phone number' });
  }
});

// GET /calls/my-phone - Get current user's registered phone number
router.get('/my-phone', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const result = await query<{ phone_number: string }>(
      `SELECT phone_number FROM user_phone_registry WHERE user_id = $1`,
      [userId]
    );

    if (result.length === 0) {
      res.json({ phoneNumber: null });
      return;
    }

    res.json({ phoneNumber: result[0].phone_number });
  } catch (error) {
    console.error('Get my phone error:', error);
    res.status(500).json({ error: 'Failed to get phone number' });
  }
});

// GET /calls/lookup/:phoneNumber - Look up user by phone number (for video calling)
router.get('/lookup/:phoneNumber', async (req: Request, res: Response) => {
  try {
    const { phoneNumber } = req.params;

    // Normalize to E.164 format for lookup
    const normalizedPhone = normalizePhoneNumber(phoneNumber);

    const result = await query(
      `SELECT user_id FROM user_phone_registry WHERE phone_number = $1`,
      [normalizedPhone]
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'User not found' });
      return;
    }

    res.json({
      userId: result[0].user_id,
      phoneNumber: normalizedPhone,
    });
  } catch (error) {
    console.error('Lookup phone error:', error);
    res.status(500).json({ error: 'Failed to lookup phone number' });
  }
});

// ==================== Active Calls ====================

// POST /calls/active - Sync active call state
router.post('/active', async (req: Request, res: Response) => {
  try {
    const { id, phoneNumber, contactName, state, callType, timestamp } = req.body;
    const userId = req.userId!;

    if (!id || !state) {
      res.status(400).json({ error: 'id and state are required' });
      return;
    }

    await query(
      `INSERT INTO user_active_calls
       (id, user_id, phone_number, contact_name, call_state, call_type, started_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())
       ON CONFLICT (id) DO UPDATE SET
         call_state = EXCLUDED.call_state,
         updated_at = NOW()`,
      [id, userId, normalizePhoneNumber(phoneNumber), contactName, state, callType, timestamp || Date.now()]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Sync active call error:', error);
    res.status(500).json({ error: 'Failed to sync active call' });
  }
});

// GET /calls/active - Get active calls
router.get('/active', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const calls = await query(
      `SELECT id, phone_number, contact_name, call_state, call_type, started_at, updated_at
       FROM user_active_calls
       WHERE user_id = $1 AND call_state != 'ended'`,
      [userId]
    );

    res.json({
      calls: calls.map(c => ({
        id: c.id,
        phoneNumber: c.phone_number,
        contactName: c.contact_name,
        state: c.call_state,
        callType: c.call_type,
        startedAt: c.started_at ? parseInt(c.started_at) : null,
      })),
    });
  } catch (error) {
    console.error('Get active calls error:', error);
    res.status(500).json({ error: 'Failed to get active calls' });
  }
});

// PUT /calls/active/:id/state - Update active call state
router.put('/active/:id/state', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { state } = req.body;
    const userId = req.userId!;

    if (!state) {
      res.status(400).json({ error: 'state is required' });
      return;
    }

    await query(
      `UPDATE user_active_calls SET call_state = $1, updated_at = NOW()
       WHERE id = $2 AND user_id = $3`,
      [state, id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Update active call state error:', error);
    res.status(500).json({ error: 'Failed to update call state' });
  }
});

// DELETE /calls/active/:id - Clear active call
router.delete('/active/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_active_calls WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Clear active call error:', error);
    res.status(500).json({ error: 'Failed to clear active call' });
  }
});

// ==================== Call Commands ====================

// GET /calls/commands - Get pending call commands
router.get('/commands', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const commands = await query(
      `SELECT id, call_id, command, phone_number, timestamp
       FROM user_call_commands
       WHERE user_id = $1 AND processed = FALSE
       ORDER BY timestamp DESC
       LIMIT 10`,
      [userId]
    );

    res.json({
      commands: commands.map(c => ({
        id: c.id,
        callId: c.call_id,
        command: c.command,
        phoneNumber: c.phone_number,
        timestamp: parseInt(c.timestamp),
      })),
    });
  } catch (error) {
    console.error('Get call commands error:', error);
    res.status(500).json({ error: 'Failed to get call commands' });
  }
});

// POST /calls/commands - Create call command
router.post('/commands', async (req: Request, res: Response) => {
  try {
    const { callId, command, phoneNumber } = req.body;
    const userId = req.userId!;

    if (!command) {
      res.status(400).json({ error: 'command is required' });
      return;
    }

    if (!['answer', 'reject', 'end', 'hold', 'unhold', 'make_call'].includes(command)) {
      res.status(400).json({ error: 'Invalid command' });
      return;
    }

    const commandId = `cmd_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_call_commands (id, user_id, call_id, command, phone_number, timestamp)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [commandId, userId, callId, command, phoneNumber, Date.now()]
    );

    res.json({ id: commandId, success: true });
  } catch (error) {
    console.error('Create call command error:', error);
    res.status(500).json({ error: 'Failed to create call command' });
  }
});

// PUT /calls/commands/:id/processed - Mark command as processed
router.put('/commands/:id/processed', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `UPDATE user_call_commands SET processed = TRUE
       WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Mark call command processed error:', error);
    res.status(500).json({ error: 'Failed to mark command processed' });
  }
});

// ==================== TURN Credentials ====================

// GET /calls/turn-credentials - Get Cloudflare TURN credentials for WebRTC
router.get('/turn-credentials', async (req: Request, res: Response) => {
  try {
    // Check Redis cache first (12h TTL)
    const cached = await getCache<{ iceServers: any[] }>('turn_credentials');
    if (cached) {
      res.json(cached);
      return;
    }

    const { turnKeyId, turnApiToken } = config.cloudflare;
    if (!turnKeyId || !turnApiToken) {
      res.status(503).json({ error: 'TURN credentials not configured' });
      return;
    }

    const response = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${turnKeyId}/credentials/generate`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${turnApiToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ ttl: 86400 }),
      }
    );

    if (!response.ok) {
      console.error('Cloudflare TURN API error:', response.status, await response.text());
      res.status(502).json({ error: 'Failed to get TURN credentials' });
      return;
    }

    const data = await response.json() as { iceServers: { urls: string[]; username: string; credential: string } | { urls: string[]; username: string; credential: string }[] };
    // Cloudflare returns iceServers as a single object; normalize to array
    const iceServers = Array.isArray(data.iceServers) ? data.iceServers : [data.iceServers];
    const result = { iceServers };

    // Cache for 12 hours
    await setCache('turn_credentials', result, 43200);

    res.json(result);
  } catch (error) {
    console.error('Get TURN credentials error:', error);
    res.status(500).json({ error: 'Failed to get TURN credentials' });
  }
});

// ==================== SyncFlow Calls (WebRTC) ====================

const createSyncFlowCallSchema = z.object({
  calleeId: z.string().min(1),
  calleeName: z.string().optional(),
  callerName: z.string().optional(),
  callerPlatform: z.string().optional(),
  callType: z.enum(['audio', 'video']).default('audio'),
});

// GET /calls/syncflow/pending - Get ringing SyncFlow calls where current user is callee
// Used as fallback when FCM push notifications fail to wake the app
router.get('/syncflow/pending', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const sixtySecondsAgo = Date.now() - 60_000;

    const calls = await query(
      `SELECT c.id, c.caller_id, c.callee_id, c.status, c.started_at,
              u.display_name AS caller_name, u.phone AS caller_phone
       FROM user_syncflow_calls c
       LEFT JOIN users u ON u.uid = c.user_id
       WHERE c.callee_user_id = $1
         AND c.status = 'ringing'
         AND c.started_at > $2
       ORDER BY c.started_at DESC
       LIMIT 5`,
      [userId, sixtySecondsAgo]
    );

    res.json({
      calls: calls.map(c => ({
        id: c.id,
        callerId: c.caller_id,
        calleeId: c.callee_id,
        callerName: c.caller_name || 'SyncFlow Device',
        callerPhone: c.caller_phone || '',
        status: c.status,
        startedAt: c.started_at ? parseInt(c.started_at) : null,
      })),
    });
  } catch (error) {
    console.error('Get pending SyncFlow calls error:', error);
    res.status(500).json({ error: 'Failed to get pending calls' });
  }
});

// POST /calls/syncflow - Create a new SyncFlow call
router.post('/syncflow', async (req: Request, res: Response) => {
  try {
    const body = createSyncFlowCallSchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;
    const callId = `sf_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    // Look up callee's userId by phone number or device ID
    const callerDeviceId = deviceId || userId;
    let calleeUserId: string | null = null;
    const normalizedCalleeId = normalizePhoneNumber(body.calleeId);

    console.log(`[CALL] Creating call: caller=${userId} (device=${callerDeviceId}), callee=${body.calleeId} (normalized=${normalizedCalleeId}), type=${body.callType}`);

    // Try phone_uid_mapping first (exact E.164 match)
    const phoneMapping = await query(
      `SELECT uid FROM phone_uid_mapping WHERE phone_number = $1`,
      [normalizedCalleeId]
    );
    console.log(`[CALL] phone_uid_mapping lookup for '${normalizedCalleeId}': ${phoneMapping.length} results`);
    if (phoneMapping.length > 0) {
      calleeUserId = phoneMapping[0].uid;
    }

    // Try user_phone_registry as fallback (exact E.164 match)
    if (!calleeUserId) {
      const phoneRegistry = await query(
        `SELECT user_id FROM user_phone_registry WHERE phone_number = $1`,
        [normalizedCalleeId]
      );
      console.log(`[CALL] user_phone_registry lookup for '${normalizedCalleeId}': ${phoneRegistry.length} results`);
      if (phoneRegistry.length > 0) {
        calleeUserId = phoneRegistry[0].user_id;
      }
    }

    console.log(`[CALL] Resolved calleeUserId=${calleeUserId}, callerUserId=${userId}, same=${calleeUserId === userId}`);

    // Insert call record with resolved callee_user_id for cross-user signaling
    await query(
      `INSERT INTO user_syncflow_calls (id, user_id, caller_id, callee_id, callee_user_id, status, started_at)
       VALUES ($1, $2, $3, $4, $5, 'ringing', $6)`,
      [callId, userId, callerDeviceId, body.calleeId, calleeUserId, Date.now()]
    );

    const callNotification = {
      type: 'syncflow_call_incoming',
      data: {
        callId,
        callerId: callerDeviceId,
        callerName: body.callerName || 'SyncFlow Device',
        callerPlatform: body.callerPlatform || 'unknown',
        calleeId: body.calleeId,
        calleeName: body.calleeName || '',
        callType: body.callType,
        startedAt: Date.now(),
      },
    };

    if (calleeUserId && calleeUserId !== userId) {
      // Cross-user call: broadcast to callee's devices
      console.log(`[CALL] Cross-user call: broadcasting to callee ${calleeUserId}`);
      broadcastToUser(calleeUserId, 'calls', callNotification);
    } else if (calleeUserId === null) {
      // Callee not found - still broadcast to same user's other devices as fallback
      console.log(`[CALL] Callee not found in DB, falling back to same-user broadcast`);
      broadcastToAllDevicesExcept(userId, callerDeviceId, 'calls', callNotification);
    } else {
      // Same-user call (Mac → Android): broadcast to all except caller device
      console.log(`[CALL] Same-user call: broadcasting to all devices except ${callerDeviceId}`);
      broadcastToAllDevicesExcept(userId, callerDeviceId, 'calls', callNotification);
    }

    // Send FCM push notification as fallback — the callee device may be offline
    // (WebSocket disconnected). Send for ALL call types including same-user calls,
    // since the Android app deduplicates via SyncFlowCallService.
    let fcmSent = false;
    if (calleeUserId) {
      const excludeDeviceId = calleeUserId === userId ? callerDeviceId : null;
      sendCallNotification(
        calleeUserId,
        {
          callId,
          callerName: body.callerName || 'SyncFlow Device',
          callType: body.callType,
        },
        excludeDeviceId
      ).catch(err => console.error('[CALL] FCM notification failed:', err));
      fcmSent = true;
    }

    // Return diagnostics to help debug notification delivery
    const calleeDeviceCount = calleeUserId ? getConnectedDeviceCount(calleeUserId) : 0;
    const callerDeviceCount = getConnectedDeviceCount(userId);
    console.log(`[CALL] Diagnostics: calleeUserId=${calleeUserId}, calleeWsDevices=${calleeDeviceCount}, callerWsDevices=${callerDeviceCount}, fcmInitialized=${isFCMInitialized()}, fcmSent=${fcmSent}`);

    res.json({
      callId,
      status: 'ringing',
      _debug: {
        calleeFound: !!calleeUserId,
        calleeIsSameUser: calleeUserId === userId,
        calleeWsDevices: calleeDeviceCount,
        callerWsDevices: callerDeviceCount,
        fcmInitialized: isFCMInitialized(),
        fcmSent,
      },
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create SyncFlow call error:', error);
    res.status(500).json({ error: 'Failed to create call' });
  }
});

// PUT /calls/syncflow/:id/status - Update SyncFlow call status
router.put('/syncflow/:id/status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { status } = req.body;
    const userId = req.userId!;

    if (!['ringing', 'active', 'ended', 'rejected', 'missed', 'failed'].includes(status)) {
      res.status(400).json({ error: 'Invalid status. Must be: ringing, active, ended, rejected, missed, failed' });
      return;
    }

    const updates: string[] = ['status = $1'];
    const params: any[] = [status, id];

    // Set ended_at for all terminal statuses
    if (['ended', 'rejected', 'missed', 'failed'].includes(status)) {
      updates.push('ended_at = $3');
      params.push(Date.now());
    }

    // Allow both caller (user_id) and callee (callee_user_id) to update status
    console.log(`[CALL] Status update: callId=${id}, status=${status}, by user=${userId}`);
    const paramIdx = params.length + 1;
    await query(
      `UPDATE user_syncflow_calls SET ${updates.join(', ')}
       WHERE id = $2 AND (user_id = $${paramIdx} OR callee_user_id = $${paramIdx})`,
      [...params, userId]
    );

    // Broadcast status change to BOTH caller and callee
    const callRow = await query<{ user_id: string; callee_user_id: string | null }>(
      `SELECT user_id, callee_user_id FROM user_syncflow_calls WHERE id = $1`,
      [id]
    );
    const statusMsg = {
      type: 'syncflow_call_status',
      data: { callId: id, status },
    };
    if (callRow.length > 0) {
      broadcastToUser(callRow[0].user_id, 'calls', statusMsg);
      if (callRow[0].callee_user_id && callRow[0].callee_user_id !== callRow[0].user_id) {
        broadcastToUser(callRow[0].callee_user_id, 'calls', statusMsg);
      }
    }

    res.json({ success: true });
  } catch (error) {
    console.error('Update SyncFlow call status error:', error);
    res.status(500).json({ error: 'Failed to update call status' });
  }
});

// GET /calls/syncflow/:id - Get SyncFlow call details
router.get('/syncflow/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    // Allow both the caller (user_id) and the callee to access call details
    const result = await query(
      `SELECT id, caller_id, callee_id, status, started_at, ended_at
       FROM user_syncflow_calls
       WHERE id = $1 AND (
         user_id = $2
         OR callee_id IN (SELECT phone_number FROM user_phone_registry WHERE user_id = $2)
       )`,
      [id, userId]
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'Call not found' });
      return;
    }

    const call = result[0];
    res.json({
      id: call.id,
      callerId: call.caller_id,
      calleeId: call.callee_id,
      status: call.status,
      startedAt: call.started_at ? parseInt(call.started_at) : null,
      endedAt: call.ended_at ? parseInt(call.ended_at) : null,
    });
  } catch (error) {
    console.error('Get SyncFlow call error:', error);
    res.status(500).json({ error: 'Failed to get call' });
  }
});

// ==================== WebRTC Signaling ====================

const signalingSchema = z.object({
  callId: z.string().min(1),
  signalType: z.enum(['offer', 'answer', 'ice-candidate']),
  signalData: z.any(),
  toDevice: z.string().optional(),
});

// Helper: get the OTHER user in a call (for cross-user signaling)
async function getOtherCallUser(callId: string, myUserId: string): Promise<string | null> {
  const rows = await query<{ user_id: string; callee_user_id: string | null }>(
    `SELECT user_id, callee_user_id FROM user_syncflow_calls WHERE id = $1`,
    [callId]
  );
  if (rows.length === 0) {
    console.log(`[SIGNAL] getOtherCallUser: no call found for ${callId}`);
    return null;
  }
  const { user_id: callerId, callee_user_id: calleeId } = rows[0];
  console.log(`[SIGNAL] getOtherCallUser: callId=${callId}, caller=${callerId}, callee=${calleeId}, me=${myUserId}`);
  if (myUserId === callerId) return calleeId;
  if (myUserId === calleeId) return callerId;
  return null;
}

// POST /calls/signaling - Send a WebRTC signal
router.post('/signaling', async (req: Request, res: Response) => {
  try {
    const body = signalingSchema.parse(req.body);
    const userId = req.userId!;
    const fromDevice = req.deviceId || '';

    await query(
      `INSERT INTO user_webrtc_signaling (user_id, call_id, signal_type, signal_data, from_device, to_device)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [userId, body.callId, body.signalType, JSON.stringify(body.signalData), fromDevice, body.toDevice || null]
    );

    // Relay signal via WebSocket — route to the OTHER user for cross-user calls
    const signalMessage = {
      type: 'webrtc_signal',
      data: {
        callId: body.callId,
        signalType: body.signalType,
        signalData: body.signalData,
        fromDevice,
      },
    };

    // Find the other participant in this call
    const otherUserId = await getOtherCallUser(body.callId, userId);
    console.log(`[SIGNAL] POST signaling: user=${userId}, callId=${body.callId}, type=${body.signalType}, otherUser=${otherUserId}`);

    if (otherUserId && otherUserId !== userId) {
      // Cross-user call: send to the other user's devices
      console.log(`[SIGNAL] Routing ${body.signalType} to other user ${otherUserId}`);
      broadcastToUser(otherUserId, 'calls', signalMessage);
    }
    // Also broadcast to same-user's other devices (same-user calls or multi-device)
    broadcastToAllDevicesExcept(userId, fromDevice, 'calls', signalMessage);

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Send signal error:', error);
    res.status(500).json({ error: 'Failed to send signal' });
  }
});

// GET /calls/signaling/:callId - Get pending signals for a call
router.get('/signaling/:callId', async (req: Request, res: Response) => {
  try {
    const { callId } = req.params;
    const userId = req.userId!;
    const deviceId = req.deviceId || '';

    // Return signals NOT sent by this device.
    // We filter by from_device (not user_id) because same-user calls
    // (e.g. Mac <-> Android on same account) share the same user_id,
    // so user_id != $2 would return zero signals for same-user calls.
    const signals = await query(
      `SELECT id, signal_type, signal_data, from_device, to_device, created_at
       FROM user_webrtc_signaling
       WHERE call_id = $1 AND (from_device != $2 OR from_device IS NULL)
       ORDER BY created_at ASC`,
      [callId, deviceId]
    );
    console.log(`[SIGNAL] GET signaling: callId=${callId}, user=${userId}, device=${deviceId}, found=${signals.length} signals (types: ${signals.map(s => s.signal_type).join(',')})`);

    res.json({
      signals: signals.map((s) => ({
        id: s.id,
        signalType: s.signal_type,
        signalData: typeof s.signal_data === 'string' ? JSON.parse(s.signal_data) : s.signal_data,
        fromDevice: s.from_device,
        toDevice: s.to_device,
        createdAt: s.created_at,
      })),
    });
  } catch (error) {
    console.error('Get signals error:', error);
    res.status(500).json({ error: 'Failed to get signals' });
  }
});

// DELETE /calls/signaling/:callId - Clean up signals after call ends
router.delete('/signaling/:callId', async (req: Request, res: Response) => {
  try {
    const { callId } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_webrtc_signaling WHERE user_id = $1 AND call_id = $2`,
      [userId, callId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Delete signals error:', error);
    res.status(500).json({ error: 'Failed to delete signals' });
  }
});

export default router;
