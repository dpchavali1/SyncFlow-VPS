import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const continuityStateSchema = z.object({
  deviceId: z.string(),
  deviceName: z.string().max(255),
  platform: z.string().max(50),
  type: z.string().max(50), // conversation, contact, etc.
  address: z.string().max(500).optional(),
  contactName: z.string().max(255).optional(),
  threadId: z.number().optional(),
  draft: z.string().max(5000).optional(),
  timestamp: z.number().optional(),
});

// GET /continuity - Get all continuity states
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const states = await query(
      `SELECT device_id, current_conversation, current_contact, state_data, timestamp, updated_at
       FROM user_continuity_state
       WHERE user_id = $1`,
      [userId]
    );

    res.json({
      states: states.map(s => ({
        deviceId: s.device_id,
        currentConversation: s.current_conversation,
        currentContact: s.current_contact,
        ...(s.state_data || {}),
        timestamp: s.timestamp ? parseInt(s.timestamp) : null,
        updatedAt: s.updated_at ? new Date(s.updated_at).getTime() : null,
      })),
    });
  } catch (error) {
    console.error('Get continuity states error:', error);
    res.status(500).json({ error: 'Failed to get continuity states' });
  }
});

// POST /continuity - Update continuity state
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = continuityStateSchema.parse(req.body);
    const userId = req.userId!;

    const stateData = {
      deviceName: body.deviceName,
      platform: body.platform,
      type: body.type,
      address: body.address,
      contactName: body.contactName,
      threadId: body.threadId,
      draft: body.draft,
    };

    await query(
      `INSERT INTO user_continuity_state
       (user_id, device_id, current_conversation, current_contact, state_data, timestamp, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, NOW())
       ON CONFLICT (user_id, device_id) DO UPDATE SET
         current_conversation = EXCLUDED.current_conversation,
         current_contact = EXCLUDED.current_contact,
         state_data = EXCLUDED.state_data,
         timestamp = EXCLUDED.timestamp,
         updated_at = NOW()`,
      [userId, body.deviceId, body.address, body.contactName,
       JSON.stringify(stateData), body.timestamp || Date.now()]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update continuity state error:', error);
    res.status(500).json({ error: 'Failed to update continuity state' });
  }
});

// DELETE /continuity/:deviceId - Clear continuity state for device
router.delete('/:deviceId', async (req: Request, res: Response) => {
  try {
    const { deviceId } = req.params;
    const userId = req.userId!;

    await query(
      `DELETE FROM user_continuity_state WHERE user_id = $1 AND device_id = $2`,
      [userId, deviceId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Clear continuity state error:', error);
    res.status(500).json({ error: 'Failed to clear continuity state' });
  }
});

export default router;
