import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
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
  endpoint: config.r2?.endpoint || process.env.R2_ENDPOINT,
  credentials: {
    accessKeyId: config.r2?.accessKeyId || process.env.R2_ACCESS_KEY_ID || '',
    secretAccessKey: config.r2?.secretAccessKey || process.env.R2_SECRET_ACCESS_KEY || '',
  },
});

const BUCKET_NAME = config.r2?.bucketName || process.env.R2_BUCKET_NAME || 'syncflow-files';

// Validation schemas
const uploadUrlSchema = z.object({
  fileName: z.string().max(255),
  contentType: z.string().max(100),
  fileSize: z.number().max(1024 * 1024 * 1024), // 1GB max
  transferType: z.string().default('files'),
});

const confirmUploadSchema = z.object({
  fileKey: z.string(),
  fileSize: z.number(),
  transferType: z.string().default('files'),
});

const createTransferSchema = z.object({
  fileName: z.string().max(255),
  fileSize: z.number(),
  contentType: z.string().max(100),
  r2Key: z.string(),
  source: z.string().default('android'),
  status: z.string().default('pending'),
});

// GET /file-transfers - List file transfers
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);

    const transfers = await query(
      `SELECT id, file_name, file_size, file_type, content_type, r2_key,
              download_url, source_device, status, timestamp
       FROM user_file_transfers
       WHERE user_id = $1
       ORDER BY timestamp DESC
       LIMIT $2`,
      [userId, limit]
    );

    res.json({
      transfers: transfers.map(t => ({
        id: t.id,
        fileName: t.file_name,
        fileSize: parseInt(t.file_size) || 0,
        contentType: t.content_type || t.file_type || 'application/octet-stream',
        r2Key: t.r2_key,
        downloadUrl: t.download_url,
        source: t.source_device,
        status: t.status,
        timestamp: parseInt(t.timestamp),
      })),
    });
  } catch (error) {
    console.error('Get file transfers error:', error);
    res.status(500).json({ error: 'Failed to get file transfers' });
  }
});

// POST /file-transfers/upload-url - Get presigned upload URL
router.post('/upload-url', async (req: Request, res: Response) => {
  try {
    const body = uploadUrlSchema.parse(req.body);
    const userId = req.userId!;

    // Generate file key - deterministic for MMS (prevents duplicate uploads),
    // timestamp-based for other transfers (allows multiple versions)
    const fileKey = body.transferType === 'mms'
      ? `${userId}/${body.transferType}/${body.fileName}`
      : `${userId}/${body.transferType}/${Date.now()}_${body.fileName}`;

    // Create presigned URL for upload
    const command = new PutObjectCommand({
      Bucket: BUCKET_NAME,
      Key: fileKey,
      ContentType: body.contentType,
    });

    const uploadUrl = await getSignedUrl(s3Client, command, { expiresIn: 3600 }); // 1 hour

    res.json({
      uploadUrl,
      fileKey,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Get upload URL error:', error);
    res.status(500).json({ error: 'Failed to get upload URL' });
  }
});

// POST /file-transfers/download-url - Get presigned download URL
router.post('/download-url', async (req: Request, res: Response) => {
  try {
    const { fileKey } = req.body;
    const userId = req.userId!;

    if (!fileKey) {
      res.status(400).json({ error: 'fileKey is required' });
      return;
    }

    // Verify the file belongs to this user
    if (!fileKey.startsWith(`${userId}/`)) {
      res.status(403).json({ error: 'Access denied' });
      return;
    }

    // Create presigned URL for download
    const command = new GetObjectCommand({
      Bucket: BUCKET_NAME,
      Key: fileKey,
    });

    const downloadUrl = await getSignedUrl(s3Client, command, { expiresIn: 3600 }); // 1 hour

    res.json({ downloadUrl });
  } catch (error) {
    console.error('Get download URL error:', error);
    res.status(500).json({ error: 'Failed to get download URL' });
  }
});

// POST /file-transfers/confirm-upload - Confirm upload completed
router.post('/confirm-upload', async (req: Request, res: Response) => {
  try {
    const body = confirmUploadSchema.parse(req.body);
    const userId = req.userId!;
    const deviceId = req.deviceId;

    // Extract filename from fileKey
    const fileName = body.fileKey.split('/').pop() || body.fileKey;
    const transferId = `transfer_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_file_transfers
       (id, user_id, file_name, file_size, r2_key, source_device, status, timestamp)
       VALUES ($1, $2, $3, $4, $5, $6, 'completed', $7)`,
      [transferId, userId, fileName, body.fileSize, body.fileKey, deviceId, Date.now()]
    );

    res.json({ success: true, id: transferId });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Confirm upload error:', error);
    res.status(500).json({ error: 'Failed to confirm upload' });
  }
});

// POST /file-transfers - Create file transfer record
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = createTransferSchema.parse(req.body);
    const userId = req.userId!;
    const transferId = `transfer_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_file_transfers
       (id, user_id, file_name, file_size, content_type, r2_key, source_device, status, timestamp)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
      [transferId, userId, body.fileName, body.fileSize, body.contentType,
       body.r2Key, body.source, body.status, Date.now()]
    );

    res.json({ success: true, id: transferId });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create file transfer error:', error);
    res.status(500).json({ error: 'Failed to create file transfer' });
  }
});

// PUT /file-transfers/:id/status - Update transfer status
router.put('/:id/status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { status, error: errorMsg } = req.body;
    const userId = req.userId!;

    if (!['pending', 'uploading', 'completed', 'failed', 'downloaded'].includes(status)) {
      res.status(400).json({ error: 'Invalid status' });
      return;
    }

    await query(
      `UPDATE user_file_transfers SET status = $1
       WHERE id = $2 AND user_id = $3`,
      [status, id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Update file transfer status error:', error);
    res.status(500).json({ error: 'Failed to update transfer status' });
  }
});

// DELETE /file-transfers/:id - Delete file transfer
router.delete('/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    // Get the file transfer to get the r2Key
    const transfer = await queryOne(
      `SELECT r2_key FROM user_file_transfers WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    if (transfer?.r2_key) {
      // Delete from R2
      try {
        const command = new DeleteObjectCommand({
          Bucket: BUCKET_NAME,
          Key: transfer.r2_key,
        });
        await s3Client.send(command);
      } catch (r2Error) {
        console.error('R2 delete error:', r2Error);
        // Continue with database deletion even if R2 fails
      }
    }

    // Delete from database
    await query(
      `DELETE FROM user_file_transfers WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Delete file transfer error:', error);
    res.status(500).json({ error: 'Failed to delete file transfer' });
  }
});

// POST /file-transfers/delete-file - Delete file from R2
router.post('/delete-file', async (req: Request, res: Response) => {
  try {
    const { fileKey } = req.body;
    const userId = req.userId!;

    if (!fileKey) {
      res.status(400).json({ error: 'fileKey is required' });
      return;
    }

    // Verify the file belongs to this user
    if (!fileKey.startsWith(`${userId}/`)) {
      res.status(403).json({ error: 'Access denied' });
      return;
    }

    const command = new DeleteObjectCommand({
      Bucket: BUCKET_NAME,
      Key: fileKey,
    });

    await s3Client.send(command);

    res.json({ success: true });
  } catch (error) {
    console.error('Delete R2 file error:', error);
    res.status(500).json({ error: 'Failed to delete file' });
  }
});

export default router;
