import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const findPhoneRequestSchema = z.object({
  action: z.enum(['ring', 'stop']),
});

// GET /find-phone/requests - Get pending find phone requests
router.get('/requests', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const requests = await query(
      `SELECT id, action, status, source_device, timestamp
       FROM user_find_phone_requests
       WHERE user_id = $1 AND status IN ('pending', 'ringing')
       ORDER BY timestamp DESC
       LIMIT 10`,
      [userId]
    );

    res.json({
      requests: requests.map(r => ({
        id: r.id,
        action: r.action,
        status: r.status,
        sourceDevice: r.source_device,
        timestamp: parseInt(r.timestamp),
      })),
    });
  } catch (error) {
    console.error('Get find phone requests error:', error);
    res.status(500).json({ error: 'Failed to get find phone requests' });
  }
});

// POST /find-phone/request - Create find phone request
router.post('/request', async (req: Request, res: Response) => {
  try {
    const body = findPhoneRequestSchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;
    const requestId = `findphone_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_find_phone_requests (id, user_id, action, status, source_device, timestamp)
       VALUES ($1, $2, $3, 'pending', $4, $5)`,
      [requestId, userId, body.action, deviceId, Date.now()]
    );

    res.json({ id: requestId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create find phone request error:', error);
    res.status(500).json({ error: 'Failed to create find phone request' });
  }
});

// PUT /find-phone/requests/:id/status - Update request status
router.put('/requests/:id/status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { status } = req.body;
    const userId = req.userId!;

    if (!['pending', 'ringing', 'stopped'].includes(status)) {
      res.status(400).json({ error: 'Invalid status' });
      return;
    }

    await query(
      `UPDATE user_find_phone_requests SET status = $1
       WHERE id = $2 AND user_id = $3`,
      [status, id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Update find phone request status error:', error);
    res.status(500).json({ error: 'Failed to update request status' });
  }
});

// DELETE /find-phone/requests/:id - Delete request
router.delete('/requests/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_find_phone_requests WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Delete find phone request error:', error);
    res.status(500).json({ error: 'Failed to delete request' });
  }
});

export default router;
