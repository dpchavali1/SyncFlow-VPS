/**
 * Usage tracking, plan limits, and subscription management.
 *
 * Plan tiers:
 *   - Free: 500 MB/month upload, 100 MB storage, 50 MB max file size
 *   - Pro (monthly/yearly/lifetime): 2 GB/month upload, 1 GB storage, 1 GB max file size
 *
 * Usage is tracked per-user in user_usage (storage_bytes, bandwidth_bytes_month).
 * The GET /usage endpoint derives monthly totals from actual DB queries across
 * MMS attachments, file transfers, and photos rather than relying solely on the
 * accumulated counter, which can drift.
 *
 * Subscriptions are managed via Apple App Store IAP. The server stores plan
 * status in user_subscriptions but does not handle payment processing.
 */

import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';
import { DeleteObjectCommand } from '@aws-sdk/client-s3';
import { s3Client, R2_BUCKET } from '../services/r2';

// Resolve effective plan by checking subscription + expiry
export async function getEffectivePlan(userId: string): Promise<{ plan: string; expiresAt: Date | null }> {
  const sub = await queryOne(
    'SELECT plan, expires_at FROM user_subscriptions WHERE user_id = $1',
    [userId]
  );
  if (!sub) return { plan: 'free', expiresAt: null };

  const plan = sub.plan || 'free';
  const expiresAt = sub.expires_at ? new Date(sub.expires_at) : null;

  // Check if expired
  if (expiresAt && expiresAt < new Date() && plan !== 'free') {
    return { plan: 'free', expiresAt };
  }

  return { plan, expiresAt };
}

// Plan limits by tier
export function getPlanLimits(plan: string) {
  const GB = 1024 * 1024 * 1024;
  const MB = 1024 * 1024;

  switch (plan) {
    case '3year':
    case 'lifetime':
      return { monthlyUploadLimit: 6 * GB, storageLimit: 3 * GB, maxFileSize: 2 * GB, maxDevices: 5 };
    case 'yearly':
    case 'pro_yearly':
      return { monthlyUploadLimit: 4 * GB, storageLimit: 2 * GB, maxFileSize: 1 * GB, maxDevices: 5 };
    case 'monthly':
    case 'pro_monthly':
      return { monthlyUploadLimit: 2 * GB, storageLimit: 1 * GB, maxFileSize: 500 * MB, maxDevices: 5 };
    default: // free
      return { monthlyUploadLimit: 200 * MB, storageLimit: 100 * MB, maxFileSize: 50 * MB, maxDevices: 2 };
  }
}

const router = Router();

// ---- Apply authentication and rate limiting to all routes ----
router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const recordUsageSchema = z.object({
  bytes: z.number(),
  category: z.string(), // mms, file, photo
  countsTowardStorage: z.boolean().default(true),
});

// Auto-reset bandwidth counter if last_reset is older than 30 days.
// Returns the (possibly updated) last_reset timestamp.
async function ensureMonthlyReset(userId: string): Promise<Date> {
  const usage = await queryOne(
    'SELECT last_reset FROM user_usage WHERE user_id = $1',
    [userId]
  );

  const lastReset = usage?.last_reset ? new Date(usage.last_reset) : null;
  const thirtyDaysAgo = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);

  if (!lastReset || lastReset < thirtyDaysAgo) {
    // Reset the counter and set last_reset to now
    await query(
      `INSERT INTO user_usage (user_id, bandwidth_bytes_month, last_reset, updated_at)
       VALUES ($1, 0, NOW(), NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         bandwidth_bytes_month = 0,
         last_reset = NOW(),
         updated_at = NOW()`,
      [userId]
    );
    return new Date();
  }

  return lastReset;
}

// GET /usage - Get user usage
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    // Auto-reset monthly counter if overdue
    const lastReset = await ensureMonthlyReset(userId);
    const nextReset = new Date(lastReset.getTime() + 30 * 24 * 60 * 60 * 1000);

    // Get usage data (after potential reset)
    const usage = await queryOne(
      `SELECT storage_bytes, bandwidth_bytes_month, message_count, last_reset, updated_at
       FROM user_usage
       WHERE user_id = $1`,
      [userId]
    );

    // Get effective plan (checks expiry)
    const { plan: effectivePlan, expiresAt: planExpiresAt } = await getEffectivePlan(userId);

    // Get subscription metadata (started_at) separately
    const subscription = await queryOne(
      `SELECT started_at FROM user_subscriptions WHERE user_id = $1`,
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

    // Calculate monthly uploads since last reset (per-category breakdown)
    const resetTs = lastReset.toISOString();

    const mmsBytes = await queryOne(
      `SELECT COALESCE(SUM(
        (SELECT COALESCE(SUM((part->>'fileSize')::bigint), 0)
         FROM jsonb_array_elements(mms_parts) AS part
         WHERE part->>'fileSize' IS NOT NULL)
      ), 0) as total
       FROM user_messages
       WHERE user_id = $1 AND is_mms = true AND mms_parts IS NOT NULL
         AND created_at > $2`,
      [userId, resetTs]
    );

    const fileBytes = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total
       FROM user_file_transfers
       WHERE user_id = $1 AND status = 'completed'
         AND created_at > $2
         AND r2_key NOT LIKE $3`,
      [userId, resetTs, `%/mms/%`]
    );

    const photoBytes = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total
       FROM user_photos
       WHERE user_id = $1
         AND synced_at > $2`,
      [userId, resetTs]
    );

    const monthlyMms = parseInt(mmsBytes?.total || '0');
    const monthlyFiles = parseInt(fileBytes?.total || '0');
    const monthlyPhotos = parseInt(photoBytes?.total || '0');
    const monthlyTotal = monthlyMms + monthlyFiles + monthlyPhotos;

    // Compute actual total storage from DB (not time-limited)
    const totalPhotoStorage = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total FROM user_photos WHERE user_id = $1`,
      [userId]
    );
    const totalMmsStorage = await queryOne(
      `SELECT COALESCE(SUM(
        (SELECT COALESCE(SUM((part->>'fileSize')::bigint), 0)
         FROM jsonb_array_elements(mms_parts) AS part
         WHERE part->>'fileSize' IS NOT NULL)
      ), 0) as total
       FROM user_messages
       WHERE user_id = $1 AND is_mms = true AND mms_parts IS NOT NULL`,
      [userId]
    );
    const totalFileStorage = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total
       FROM user_file_transfers
       WHERE user_id = $1 AND status = 'completed' AND r2_key NOT LIKE $2`,
      [userId, `%/mms/%`]
    );
    const actualStorageBytes = parseInt(totalPhotoStorage?.total || '0')
      + parseInt(totalMmsStorage?.total || '0')
      + parseInt(totalFileStorage?.total || '0');

    const limits = getPlanLimits(effectivePlan);

    res.json({
      success: true,
      usage: {
        plan: effectivePlan,
        planExpiresAt: planExpiresAt ? planExpiresAt.getTime() : null,
        trialStartedAt: subscription?.started_at
          ? new Date(subscription.started_at).getTime()
          : null,
        storageBytes: actualStorageBytes,
        monthlyUploadBytes: monthlyTotal,
        monthlyMmsBytes: monthlyMms,
        monthlyFileBytes: monthlyFiles,
        monthlyPhotoBytes: monthlyPhotos,
        messageCount: parseInt(msgCount?.count || '0'),
        contactCount: parseInt(contactCount?.count || '0'),
        lastUpdatedAt: usage?.updated_at
          ? new Date(usage.updated_at).getTime()
          : null,
        monthlyUploadLimit: limits.monthlyUploadLimit,
        storageLimit: limits.storageLimit,
        maxFileSize: limits.maxFileSize,
        maxDevices: limits.maxDevices,
        monthlyResetDate: nextReset.getTime(),
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

// POST /usage/check-limit - Pre-upload limit check
router.post('/check-limit', async (req: Request, res: Response) => {
  try {
    const { bytes } = req.body;
    const userId = req.userId!;

    if (typeof bytes !== 'number' || bytes <= 0) {
      res.status(400).json({ error: 'bytes must be a positive number' });
      return;
    }

    // Auto-reset monthly counter if overdue
    const lastReset = await ensureMonthlyReset(userId);
    const resetTs = lastReset.toISOString();

    const { plan: effectivePlan } = await getEffectivePlan(userId);
    const limits = getPlanLimits(effectivePlan);

    // Compute actual monthly uploads since last reset
    const monthlyMms = await queryOne(
      `SELECT COALESCE(SUM(
        (SELECT COALESCE(SUM((part->>'fileSize')::bigint), 0)
         FROM jsonb_array_elements(mms_parts) AS part
         WHERE part->>'fileSize' IS NOT NULL)
      ), 0) as total
       FROM user_messages
       WHERE user_id = $1 AND is_mms = true AND mms_parts IS NOT NULL AND created_at > $2`,
      [userId, resetTs]
    );
    const monthlyFiles = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total
       FROM user_file_transfers
       WHERE user_id = $1 AND status = 'completed' AND created_at > $2 AND r2_key NOT LIKE $3`,
      [userId, resetTs, `%/mms/%`]
    );
    const monthlyPhotos = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total FROM user_photos WHERE user_id = $1 AND synced_at > $2`,
      [userId, resetTs]
    );
    const currentMonthly = parseInt(monthlyMms?.total || '0')
      + parseInt(monthlyFiles?.total || '0')
      + parseInt(monthlyPhotos?.total || '0');

    // Compute actual storage from DB tables
    const photoStorage = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total FROM user_photos WHERE user_id = $1`,
      [userId]
    );
    const mmsStorage = await queryOne(
      `SELECT COALESCE(SUM(
        (SELECT COALESCE(SUM((part->>'fileSize')::bigint), 0)
         FROM jsonb_array_elements(mms_parts) AS part
         WHERE part->>'fileSize' IS NOT NULL)
      ), 0) as total
       FROM user_messages
       WHERE user_id = $1 AND is_mms = true AND mms_parts IS NOT NULL`,
      [userId]
    );
    const fileStorage = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total
       FROM user_file_transfers
       WHERE user_id = $1 AND status = 'completed' AND r2_key NOT LIKE $2`,
      [userId, `%/mms/%`]
    );
    const currentStorage = parseInt(photoStorage?.total || '0')
      + parseInt(mmsStorage?.total || '0')
      + parseInt(fileStorage?.total || '0');

    if (bytes > limits.maxFileSize) {
      res.status(413).json({
        error: `File too large. Max ${Math.round(limits.maxFileSize / (1024 * 1024))} MB for your plan.`,
        upgrade: true,
      });
      return;
    }

    if (currentMonthly + bytes > limits.monthlyUploadLimit) {
      res.status(429).json({
        error: 'Monthly upload limit reached',
        limit: limits.monthlyUploadLimit,
        current: currentMonthly,
        upgrade: true,
      });
      return;
    }

    if (currentStorage + bytes > limits.storageLimit) {
      res.status(429).json({
        error: 'Storage limit reached. Delete existing data or upgrade to Pro.',
        limit: limits.storageLimit,
        current: currentStorage,
        upgrade: true,
      });
      return;
    }

    res.json({
      allowed: true,
      remainingMonthly: limits.monthlyUploadLimit - currentMonthly,
      remainingStorage: limits.storageLimit - currentStorage,
    });
  } catch (error) {
    console.error('Check limit error:', error);
    res.status(500).json({ error: 'Failed to check limits' });
  }
});

// POST /usage/reset-storage - Reset storage usage and delete R2 objects
router.post('/reset-storage', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    let r2Deleted = 0;

    // Collect R2 keys from MMS attachments
    const mmsMessages = await query<{ mms_parts: any }>(
      `SELECT mms_parts FROM user_messages
       WHERE user_id = $1 AND is_mms = true AND mms_parts IS NOT NULL`,
      [userId]
    );

    const r2Keys: string[] = [];
    for (const msg of mmsMessages) {
      const parts = Array.isArray(msg.mms_parts) ? msg.mms_parts : [];
      for (const part of parts) {
        const key = part.r2Key || part.fileKey || part.r2_key;
        if (key) r2Keys.push(key);
      }
    }

    // Collect R2 keys from photos
    const photos = await query<{ r2_key: string }>(
      `SELECT r2_key FROM user_photos WHERE user_id = $1`,
      [userId]
    );
    for (const photo of photos) {
      if (photo.r2_key) r2Keys.push(photo.r2_key);
    }

    // Delete R2 objects
    if (s3Client && r2Keys.length > 0) {
      for (const key of r2Keys) {
        try {
          await s3Client.send(new DeleteObjectCommand({
            Bucket: R2_BUCKET,
            Key: key,
          }));
          r2Deleted++;
        } catch (e) {}
      }
    }

    // Clear mms_parts references from messages
    await query(
      `UPDATE user_messages SET mms_parts = NULL
       WHERE user_id = $1 AND is_mms = true AND mms_parts IS NOT NULL`,
      [userId]
    );

    // Delete photo records
    await query(`DELETE FROM user_photos WHERE user_id = $1`, [userId]);

    // Reset storage counter
    await query(
      `UPDATE user_usage SET storage_bytes = 0, updated_at = NOW()
       WHERE user_id = $1`,
      [userId]
    );

    console.log(`[Usage] Reset storage for user ${userId}: deleted ${r2Deleted}/${r2Keys.length} R2 objects`);
    res.json({ success: true, r2Deleted, totalKeys: r2Keys.length });
  } catch (error) {
    console.error('Reset storage usage error:', error);
    res.status(500).json({ error: 'Failed to reset storage usage' });
  }
});

// POST /usage/recalculate-storage - Recalculate storage_bytes from actual DB contents
router.post('/recalculate-storage', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    // Sum actual photo sizes
    const photoBytes = await queryOne(
      `SELECT COALESCE(SUM(file_size), 0) as total FROM user_photos WHERE user_id = $1`,
      [userId]
    );

    // Sum actual MMS attachment sizes
    const mmsBytes = await queryOne(
      `SELECT COALESCE(SUM(
        (SELECT COALESCE(SUM((part->>'fileSize')::bigint), 0)
         FROM jsonb_array_elements(mms_parts) AS part
         WHERE part->>'fileSize' IS NOT NULL)
      ), 0) as total
       FROM user_messages
       WHERE user_id = $1 AND is_mms = true AND mms_parts IS NOT NULL`,
      [userId]
    );

    const actualStorage = parseInt(photoBytes?.total || '0') + parseInt(mmsBytes?.total || '0');

    await query(
      `UPDATE user_usage SET storage_bytes = $2, updated_at = NOW() WHERE user_id = $1`,
      [userId, actualStorage]
    );

    console.log(`[Usage] Recalculated storage for user ${userId}: ${actualStorage} bytes`);
    res.json({ success: true, storageBytes: actualStorage });
  } catch (error) {
    console.error('Recalculate storage error:', error);
    res.status(500).json({ error: 'Failed to recalculate storage' });
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
      `SELECT plan, status, started_at, expires_at
       FROM user_subscriptions
       WHERE user_id = $1`,
      [userId]
    );

    if (!subscription) {
      const freeLimits = getPlanLimits('free');
      res.json({
        plan: 'free',
        status: 'active',
        features: {
          maxDevices: freeLimits.maxDevices,
          maxFileSize: freeLimits.maxFileSize,
          mediaControl: false,
        },
      });
      return;
    }

    const isPro = subscription.plan !== 'free' && subscription.status === 'active';
    const subLimits = getPlanLimits(subscription.plan || 'free');

    res.json({
      plan: subscription.plan,
      status: subscription.status,
      startedAt: subscription.started_at ? new Date(subscription.started_at).getTime() : null,
      expiresAt: subscription.expires_at ? new Date(subscription.expires_at).getTime() : null,
      features: {
        maxDevices: subLimits.maxDevices,
        maxFileSize: subLimits.maxFileSize,
        mediaControl: isPro,
      },
    });
  } catch (error) {
    console.error('Get subscription error:', error);
    res.status(500).json({ error: 'Failed to get subscription' });
  }
});

export default router;
