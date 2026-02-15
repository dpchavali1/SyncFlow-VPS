import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { getEffectivePlan, getPlanLimits } from './usage';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';
import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { config } from '../config';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// R2/S3 Client configuration
const s3Client = new S3Client({
  region: 'auto',
  endpoint: config.r2.endpoint,
  credentials: {
    accessKeyId: config.r2.accessKeyId,
    secretAccessKey: config.r2.secretAccessKey,
  },
});

const BUCKET_NAME = config.r2.bucketName;

// Validation schemas
const confirmUploadSchema = z.object({
  fileId: z.string(),
  r2Key: z.string(),
  fileName: z.string().max(255),
  fileSize: z.number(),
  contentType: z.string().default('image/jpeg'),
  transferType: z.string().default('photo'),
  photoMetadata: z.object({
    width: z.number().optional(),
    height: z.number().optional(),
    takenAt: z.number().optional(),
    location: z.object({
      latitude: z.number(),
      longitude: z.number(),
    }).optional(),
  }).optional(),
});

// GET /photos - Get synced photos
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);
    const before = req.query.before ? parseInt(req.query.before as string) : null;

    let queryText = `
      SELECT id, file_name, storage_url, r2_key, file_size, content_type,
             photo_metadata, taken_at, synced_at
      FROM user_photos
      WHERE user_id = $1
    `;
    const params: any[] = [userId];

    if (before) {
      queryText += ` AND taken_at < $2`;
      params.push(before);
    }

    queryText += ` ORDER BY taken_at DESC LIMIT $${params.length + 1}`;
    params.push(limit);

    const photos = await query(queryText, params);

    res.json({
      photos: photos.map(p => ({
        id: p.id,
        fileName: p.file_name,
        storageUrl: p.storage_url,
        r2Key: p.r2_key,
        fileSize: parseInt(p.file_size) || 0,
        contentType: p.content_type,
        metadata: p.photo_metadata,
        takenAt: p.taken_at ? parseInt(p.taken_at) : null,
        syncedAt: p.synced_at ? new Date(p.synced_at).getTime() : null,
      })),
    });
  } catch (error) {
    console.error('Get photos error:', error);
    res.status(500).json({ error: 'Failed to get photos' });
  }
});

// GET /photos/synced-ids - Get list of synced photo IDs (MediaStore originalId)
router.get('/synced-ids', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    // Return originalId from photo_metadata (the Android MediaStore _ID)
    // so the Android client can check which photos are already synced
    const photos = await query(
      `SELECT photo_metadata->>'originalId' AS original_id
       FROM user_photos WHERE user_id = $1 AND photo_metadata->>'originalId' IS NOT NULL`,
      [userId]
    );

    res.json({
      ids: photos.map(p => {
        const numId = parseInt(p.original_id);
        return isNaN(numId) ? 0 : numId;
      }).filter(id => id > 0),
    });
  } catch (error) {
    console.error('Get synced photo IDs error:', error);
    res.status(500).json({ error: 'Failed to get synced photo IDs' });
  }
});

// POST /photos/upload-url - Get presigned upload URL for photo
router.post('/upload-url', async (req: Request, res: Response) => {
  try {
    const { fileName, contentType, fileSize } = req.body;
    const userId = req.userId!;

    if (!fileName) {
      res.status(400).json({ error: 'fileName is required' });
      return;
    }

    // Enforce upload limits
    const usage = await queryOne(
      'SELECT storage_bytes, bandwidth_bytes_month FROM user_usage WHERE user_id = $1',
      [userId]
    );
    const { plan: effectivePlan } = await getEffectivePlan(userId);
    const limits = getPlanLimits(effectivePlan);
    const currentMonthly = parseInt(usage?.bandwidth_bytes_month || '0');
    const currentStorage = parseInt(usage?.storage_bytes || '0');
    const uploadSize = fileSize || 0;

    if (uploadSize > limits.maxFileSize) {
      res.status(413).json({ error: `File too large. Max ${Math.round(limits.maxFileSize / (1024 * 1024))} MB for your plan.`, upgrade: true });
      return;
    }
    if (uploadSize > 0 && currentMonthly + uploadSize > limits.monthlyUploadLimit) {
      res.status(429).json({ error: 'Monthly upload limit reached. Upgrade to Pro for more.', upgrade: true });
      return;
    }
    if (uploadSize > 0 && currentStorage + uploadSize > limits.storageLimit) {
      res.status(429).json({ error: 'Storage limit reached. Delete existing data or upgrade to Pro.', upgrade: true });
      return;
    }

    // Generate unique file key
    const fileKey = `${userId}/photos/${Date.now()}_${fileName}`;

    // Create presigned URL for upload
    const command = new PutObjectCommand({
      Bucket: BUCKET_NAME,
      Key: fileKey,
      ContentType: contentType || 'image/jpeg',
    });

    const uploadUrl = await getSignedUrl(s3Client, command, { expiresIn: 3600 });

    res.json({
      uploadUrl,
      fileKey,
    });
  } catch (error) {
    console.error('Get photo upload URL error:', error);
    res.status(500).json({ error: 'Failed to get upload URL' });
  }
});

// POST /photos/confirm-upload - Confirm photo upload
router.post('/confirm-upload', async (req: Request, res: Response) => {
  try {
    const body = confirmUploadSchema.parse(req.body);
    const userId = req.userId!;

    // Use RETURNING xmax to detect insert (xmax=0) vs update (xmax>0)
    const upsertResult = await query<{ xmax: string; old_file_size: number | null }>(
      `INSERT INTO user_photos
       (id, user_id, file_name, r2_key, file_size, content_type, photo_metadata, taken_at, synced_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NOW())
       ON CONFLICT (id) DO UPDATE SET
         user_id = EXCLUDED.user_id,
         r2_key = EXCLUDED.r2_key,
         file_size = EXCLUDED.file_size,
         synced_at = NOW()
       RETURNING xmax::text, (CASE WHEN xmax::text != '0' THEN $5 ELSE NULL END)::bigint AS old_file_size`,
      [body.fileId, userId, body.fileName, body.r2Key, body.fileSize,
       body.contentType, body.photoMetadata ? JSON.stringify(body.photoMetadata) : null,
       body.photoMetadata?.takenAt]
    );

    const wasInsert = upsertResult.length > 0 && upsertResult[0].xmax === '0';

    // Record usage for photo upload
    if (body.fileSize > 0) {
      if (wasInsert) {
        // New photo: increment both storage and bandwidth
        await query(
          `INSERT INTO user_usage (user_id, storage_bytes, bandwidth_bytes_month, updated_at)
           VALUES ($1, $2, $2, NOW())
           ON CONFLICT (user_id) DO UPDATE SET
             storage_bytes = user_usage.storage_bytes + $2,
             bandwidth_bytes_month = user_usage.bandwidth_bytes_month + $2,
             updated_at = NOW()`,
          [userId, body.fileSize]
        );
      } else {
        // Re-sync: only increment bandwidth (storage already counted)
        await query(
          `INSERT INTO user_usage (user_id, storage_bytes, bandwidth_bytes_month, updated_at)
           VALUES ($1, 0, $2, NOW())
           ON CONFLICT (user_id) DO UPDATE SET
             bandwidth_bytes_month = user_usage.bandwidth_bytes_month + $2,
             updated_at = NOW()`,
          [userId, body.fileSize]
        );
      }
    }

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Confirm photo upload error:', error);
    res.status(500).json({ error: 'Failed to confirm upload' });
  }
});

// POST /photos/download-url - Get presigned download URL for photo
router.post('/download-url', async (req: Request, res: Response) => {
  try {
    const { r2Key, photoId } = req.body;
    const userId = req.userId!;

    let fileKey = r2Key;

    // If photoId provided, get r2Key from database
    if (!fileKey && photoId) {
      const photo = await query(
        `SELECT r2_key FROM user_photos WHERE id = $1 AND user_id = $2`,
        [photoId, userId]
      );
      if (photo.length > 0) {
        fileKey = photo[0].r2_key;
      }
    }

    if (!fileKey) {
      res.status(400).json({ error: 'r2Key or photoId is required' });
      return;
    }

    // Verify the file belongs to this user via DB (not path prefix, which breaks after user migration)
    const ownerCheck = await query(
      `SELECT 1 FROM user_photos WHERE user_id = $1 AND r2_key = $2`,
      [userId, fileKey]
    );
    if (ownerCheck.length === 0) {
      res.status(403).json({ error: 'Access denied' });
      return;
    }

    const command = new GetObjectCommand({
      Bucket: BUCKET_NAME,
      Key: fileKey,
    });

    const downloadUrl = await getSignedUrl(s3Client, command, { expiresIn: 3600 });

    res.json({ downloadUrl });
  } catch (error) {
    console.error('Get photo download URL error:', error);
    res.status(500).json({ error: 'Failed to get download URL' });
  }
});

// POST /photos/delete - Delete photo
router.post('/delete', async (req: Request, res: Response) => {
  try {
    const { photoId, r2Key } = req.body;
    const userId = req.userId!;

    if (!photoId) {
      res.status(400).json({ error: 'photoId is required' });
      return;
    }

    // Get photo info (r2Key + file_size) before deleting
    const photo = await query<{ r2_key: string; file_size: number }>(
      `SELECT r2_key, file_size FROM user_photos WHERE id = $1 AND user_id = $2`,
      [photoId, userId]
    );
    let fileKey = r2Key || (photo.length > 0 ? photo[0].r2_key : null);
    const fileSize = photo.length > 0 ? parseInt(String(photo[0].file_size)) || 0 : 0;

    // Delete from R2 if key exists (ownership verified via DB query above)
    if (fileKey) {
      try {
        const command = new DeleteObjectCommand({
          Bucket: BUCKET_NAME,
          Key: fileKey,
        });
        await s3Client.send(command);
      } catch (r2Error) {
        console.error('R2 delete error:', r2Error);
        // Continue with database deletion
      }
    }

    // Delete from database
    await query(
      `DELETE FROM user_photos WHERE id = $1 AND user_id = $2`,
      [photoId, userId]
    );

    // Decrement storage_bytes to reflect freed space
    if (fileSize > 0) {
      await query(
        `UPDATE user_usage
         SET storage_bytes = GREATEST(storage_bytes - $2, 0),
             updated_at = NOW()
         WHERE user_id = $1`,
        [userId, fileSize]
      );
    }

    res.json({ success: true });
  } catch (error) {
    console.error('Delete photo error:', error);
    res.status(500).json({ error: 'Failed to delete photo' });
  }
});

export default router;
