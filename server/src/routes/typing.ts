import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const typingStatusSchema = z.object({
  conversationAddress: z.string().max(500),
  isTyping: z.boolean().default(true),
  deviceName: z.string().max(255).optional(),
});

// GET /typing - Get typing indicators
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    // Get typing indicators from last 30 seconds
    const thirtySecondsAgo = Date.now() - 30000;

    const indicators = await query(
      `SELECT conversation_address, device_id, device_name, is_typing, timestamp
       FROM user_typing_indicators
       WHERE user_id = $1 AND timestamp > $2 AND is_typing = TRUE`,
      [userId, thirtySecondsAgo]
    );

    res.json({
      indicators: indicators.map(i => ({
        conversationAddress: i.conversation_address,
        deviceId: i.device_id,
        deviceName: i.device_name,
        isTyping: i.is_typing,
        timestamp: parseInt(i.timestamp),
      })),
    });
  } catch (error) {
    console.error('Get typing indicators error:', error);
    res.status(500).json({ error: 'Failed to get typing indicators' });
  }
});

// POST /typing - Update typing status
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = typingStatusSchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;

    await query(
      `INSERT INTO user_typing_indicators
       (user_id, conversation_address, device_id, device_name, is_typing, timestamp)
       VALUES ($1, $2, $3, $4, $5, $6)
       ON CONFLICT (user_id, conversation_address, device_id) DO UPDATE SET
         is_typing = EXCLUDED.is_typing,
         timestamp = EXCLUDED.timestamp`,
      [userId, body.conversationAddress, deviceId, body.deviceName, body.isTyping, Date.now()]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update typing status error:', error);
    res.status(500).json({ error: 'Failed to update typing status' });
  }
});

// DELETE /typing/:conversationAddress - Clear typing for conversation
router.delete('/:conversationAddress', async (req: Request, res: Response) => {
  try {
    const { conversationAddress } = req.params;
    const userId = req.userId!;
    const deviceId = req.deviceId;

    await query(
      `DELETE FROM user_typing_indicators
       WHERE user_id = $1 AND conversation_address = $2 AND device_id = $3`,
      [userId, conversationAddress, deviceId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Clear typing indicator error:', error);
    res.status(500).json({ error: 'Failed to clear typing indicator' });
  }
});

// DELETE /typing - Clear all typing indicators for user's device
router.delete('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const deviceId = req.deviceId;

    await query(
      `DELETE FROM user_typing_indicators
       WHERE user_id = $1 AND device_id = $2`,
      [userId, deviceId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Clear all typing indicators error:', error);
    res.status(500).json({ error: 'Failed to clear typing indicators' });
  }
});

export default router;
