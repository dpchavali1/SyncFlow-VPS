import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const recordUsageSchema = z.object({
  bytes: z.number(),
  category: z.string(), // mms, file, photo
  countsTowardStorage: z.boolean().default(true),
});

// GET /usage - Get user usage
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    // Get usage data
    const usage = await queryOne(
      `SELECT storage_bytes, bandwidth_bytes_month, message_count, last_reset, updated_at
       FROM user_usage
       WHERE user_id = $1`,
      [userId]
    );

    // Get subscription data
    const subscription = await queryOne(
      `SELECT plan, status, started_at, expires_at
       FROM user_subscriptions
       WHERE user_id = $1`,
      [userId]
    );

    // Calculate message counts
    const msgCount = await queryOne(
      `SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1`,
      [userId]
    );

    const contactCount = await queryOne(
      `SELECT COUNT(*) as count FROM user_contacts WHERE user_id = $1`,
      [userId]
    );

    res.json({
      success: true,
      usage: {
        plan: subscription?.plan || 'free',
        planExpiresAt: subscription?.expires_at
          ? new Date(subscription.expires_at).getTime()
          : null,
        trialStartedAt: subscription?.started_at
          ? new Date(subscription.started_at).getTime()
          : null,
        storageBytes: parseInt(usage?.storage_bytes || '0'),
        monthlyUploadBytes: parseInt(usage?.bandwidth_bytes_month || '0'),
        monthlyMmsBytes: 0, // TODO: Track separately
        monthlyFileBytes: 0, // TODO: Track separately
        messageCount: parseInt(msgCount?.count || '0'),
        contactCount: parseInt(contactCount?.count || '0'),
        lastUpdatedAt: usage?.updated_at
          ? new Date(usage.updated_at).getTime()
          : null,
      },
    });
  } catch (error) {
    console.error('Get usage error:', error);
    res.status(500).json({ error: 'Failed to get usage' });
  }
});

// POST /usage/record - Record usage
router.post('/record', async (req: Request, res: Response) => {
  try {
    const body = recordUsageSchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO user_usage (user_id, storage_bytes, bandwidth_bytes_month, updated_at)
       VALUES ($1, $2, $2, NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         storage_bytes = CASE WHEN $3 THEN user_usage.storage_bytes + $2 ELSE user_usage.storage_bytes END,
         bandwidth_bytes_month = user_usage.bandwidth_bytes_month + $2,
         updated_at = NOW()`,
      [userId, body.bytes, body.countsTowardStorage]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Record usage error:', error);
    res.status(500).json({ error: 'Failed to record usage' });
  }
});

// POST /usage/reset-storage - Reset storage usage
router.post('/reset-storage', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    await query(
      `UPDATE user_usage SET storage_bytes = 0, updated_at = NOW()
       WHERE user_id = $1`,
      [userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Reset storage usage error:', error);
    res.status(500).json({ error: 'Failed to reset storage usage' });
  }
});

// POST /usage/reset-monthly - Reset monthly usage
router.post('/reset-monthly', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    await query(
      `UPDATE user_usage SET bandwidth_bytes_month = 0, last_reset = NOW(), updated_at = NOW()
       WHERE user_id = $1`,
      [userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Reset monthly usage error:', error);
    res.status(500).json({ error: 'Failed to reset monthly usage' });
  }
});

// GET /subscription - Get subscription status
router.get('/subscription', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const subscription = await queryOne(
      `SELECT plan, status, started_at, expires_at, stripe_customer_id, stripe_subscription_id
       FROM user_subscriptions
       WHERE user_id = $1`,
      [userId]
    );

    if (!subscription) {
      res.json({
        plan: 'free',
        status: 'active',
        features: {
          maxDevices: 2,
          maxFileSize: 50 * 1024 * 1024, // 50MB
          photoSync: false,
          mediaControl: false,
        },
      });
      return;
    }

    const isPro = subscription.plan !== 'free' && subscription.status === 'active';

    res.json({
      plan: subscription.plan,
      status: subscription.status,
      startedAt: subscription.started_at ? new Date(subscription.started_at).getTime() : null,
      expiresAt: subscription.expires_at ? new Date(subscription.expires_at).getTime() : null,
      features: {
        maxDevices: isPro ? 10 : 2,
        maxFileSize: isPro ? 1024 * 1024 * 1024 : 50 * 1024 * 1024, // 1GB vs 50MB
        photoSync: isPro,
        mediaControl: isPro,
      },
    });
  } catch (error) {
    console.error('Get subscription error:', error);
    res.status(500).json({ error: 'Failed to get subscription' });
  }
});

export default router;
