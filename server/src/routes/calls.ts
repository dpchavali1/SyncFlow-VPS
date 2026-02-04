import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
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

export default router;
