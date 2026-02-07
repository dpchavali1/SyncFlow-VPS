import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, transaction } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

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
            call.phoneNumber,
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

    await query(
      `INSERT INTO user_call_requests
       (id, user_id, phone_number, status, requested_at, sim_subscription_id)
       VALUES ($1, $2, $3, 'pending', $4, $5)`,
      [requestId, userId, body.phoneNumber, Date.now(), body.simSubscriptionId]
    );

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

    // Normalize phone number (remove spaces, dashes, keep + and digits)
    const normalizedPhone = body.phoneNumber.replace(/[\s\-()]/g, '');

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

    // Normalize phone number for lookup
    const normalizedPhone = phoneNumber.replace(/[\s\-()]/g, '');

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
      [id, userId, phoneNumber, contactName, state, callType, timestamp || Date.now()]
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

export default router;
