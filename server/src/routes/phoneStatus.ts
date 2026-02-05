import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const phoneStatusSchema = z.object({
  batteryLevel: z.number().min(0).max(100).optional(),
  isCharging: z.boolean().optional(),
  signalStrength: z.number().min(0).max(4).optional(),
  wifiName: z.string().max(255).optional().nullable(),
  connectionType: z.string().max(50).optional(), // wifi, mobile, none
  mobileDataType: z.string().max(50).optional(), // 5G, LTE, 4G, 3G, 2G
});

// GET /phone-status - Get phone status
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const status = await queryOne(
      `SELECT battery_level, is_charging, signal_strength, wifi_name,
              connection_type, mobile_data_type, timestamp, updated_at
       FROM user_phone_status
       WHERE user_id = $1`,
      [userId]
    );

    if (!status) {
      res.json({
        batteryLevel: null,
        isCharging: null,
        signalStrength: null,
        wifiName: null,
        connectionType: null,
        mobileDataType: null,
        timestamp: null,
      });
      return;
    }

    res.json({
      batteryLevel: status.battery_level,
      isCharging: status.is_charging,
      signalStrength: status.signal_strength,
      wifiName: status.wifi_name,
      connectionType: status.connection_type,
      mobileDataType: status.mobile_data_type,
      timestamp: status.timestamp ? parseInt(status.timestamp) : null,
    });
  } catch (error) {
    console.error('Get phone status error:', error);
    res.status(500).json({ error: 'Failed to get phone status' });
  }
});

// POST /phone-status - Update phone status
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = phoneStatusSchema.parse(req.body);
    const userId = req.userId!;
    const timestamp = Date.now();

    await query(
      `INSERT INTO user_phone_status (user_id, battery_level, is_charging, signal_strength,
                                       wifi_name, connection_type, mobile_data_type, timestamp, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         battery_level = COALESCE(EXCLUDED.battery_level, user_phone_status.battery_level),
         is_charging = COALESCE(EXCLUDED.is_charging, user_phone_status.is_charging),
         signal_strength = COALESCE(EXCLUDED.signal_strength, user_phone_status.signal_strength),
         wifi_name = EXCLUDED.wifi_name,
         connection_type = COALESCE(EXCLUDED.connection_type, user_phone_status.connection_type),
         mobile_data_type = EXCLUDED.mobile_data_type,
         timestamp = EXCLUDED.timestamp,
         updated_at = NOW()`,
      [userId, body.batteryLevel, body.isCharging, body.signalStrength,
       body.wifiName, body.connectionType, body.mobileDataType, timestamp]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update phone status error:', error);
    res.status(500).json({ error: 'Failed to update phone status' });
  }
});

export default router;
