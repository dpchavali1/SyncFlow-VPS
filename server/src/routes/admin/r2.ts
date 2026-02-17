/**
 * Admin sub-router: R2 (Cloudflare) Storage Management.
 *
 * Provides analytics, file listing, age-based cleanup, and per-file deletion
 * for objects stored in R2 (S3-compatible). All endpoints also handle DB records
 * in user_photos and user_file_transfers.
 *
 * Endpoints:
 *   GET    /r2/analytics   — Storage totals, top users, largest/oldest files, cost estimate
 *   GET    /r2/files       — List R2-backed files (photos and/or transfers, paginated)
 *   POST   /r2/cleanup     — Delete old R2 files by age (olderThanDays) and type
 *   DELETE /r2/files/*     — Delete a specific R2 file by key (DB + R2)
 */

import { Router, Request, Response } from 'express';
import { DeleteObjectCommand } from '@aws-sdk/client-s3';
import { config } from '../../config';
import { query, queryOne } from '../../services/database';
import { s3Client } from './helpers';

const router = Router();

// ── GET /r2/analytics — Storage analytics + cost estimation ─────────────────

router.get('/r2/analytics', async (req: Request, res: Response) => {
  try {
    const photoStats = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM(file_size), 0) as total_size FROM user_photos`);
    const transferStats = await queryOne<{ count: string; total_size: string }>(`SELECT COUNT(*) as count, COALESCE(SUM(file_size), 0) as total_size FROM user_file_transfers`);

    // MMS attachments stored in R2
    let mmsCount = 0;
    try {
      const mmsStats = await queryOne<{ count: string }>(`SELECT COUNT(*) as count FROM user_messages, jsonb_array_elements(mms_parts) AS part WHERE mms_parts IS NOT NULL AND part->>'r2Key' IS NOT NULL`);
      mmsCount = parseInt(mmsStats?.count || '0');
    } catch { /* mms_parts column may not exist or be empty */ }

    // Top users by storage (union of photos + file transfers)
    const topUsers = await query<{ user_id: string; total_size: string; last_updated: Date }>(`
      SELECT combined.user_id, SUM(combined.total_size) as total_size, MAX(combined.last_updated) as last_updated FROM (
        SELECT user_id, COALESCE(SUM(file_size), 0) as total_size, MAX(synced_at) as last_updated FROM user_photos GROUP BY user_id
        UNION ALL
        SELECT user_id, COALESCE(SUM(file_size), 0) as total_size, MAX(created_at) as last_updated FROM user_file_transfers GROUP BY user_id
      ) combined GROUP BY combined.user_id ORDER BY total_size DESC LIMIT 20
    `);

    // Largest files across photos and transfers
    const largestPhotos = await query<{ r2_key: string; file_size: string; synced_at: Date }>(`SELECT r2_key, file_size, synced_at FROM user_photos WHERE r2_key IS NOT NULL ORDER BY file_size DESC LIMIT 10`);
    const largestTransfers = await query<{ r2_key: string; file_size: string; created_at: Date }>(`SELECT r2_key, file_size, created_at FROM user_file_transfers WHERE r2_key IS NOT NULL ORDER BY file_size DESC LIMIT 10`);

    // Oldest files
    const oldestPhotos = await query<{ r2_key: string; file_size: string; synced_at: Date }>(`SELECT r2_key, file_size, synced_at FROM user_photos WHERE r2_key IS NOT NULL ORDER BY synced_at ASC LIMIT 10`);
    const oldestTransfers = await query<{ r2_key: string; file_size: string; created_at: Date }>(`SELECT r2_key, file_size, created_at FROM user_file_transfers WHERE r2_key IS NOT NULL ORDER BY created_at ASC LIMIT 10`);

    const p = (v: string | undefined) => parseInt(v || '0');
    const photoCount = p(photoStats?.count);
    const photoSize = p(photoStats?.total_size);
    const transferCount = p(transferStats?.count);
    const transferSize = p(transferStats?.total_size);
    const totalSize = photoSize + transferSize;
    // R2 pricing: $0.015/GB/month
    const estimatedCost = (totalSize / (1024 * 1024 * 1024)) * 0.015;

    const allLargest = [
      ...largestPhotos.map(f => ({ key: f.r2_key, size: p(f.file_size), uploadedAt: f.synced_at })),
      ...largestTransfers.map(f => ({ key: f.r2_key, size: p(f.file_size), uploadedAt: f.created_at })),
    ].sort((a, b) => b.size - a.size).slice(0, 10);

    const allOldest = [
      ...oldestPhotos.map(f => ({ key: f.r2_key, size: p(f.file_size), uploadedAt: f.synced_at })),
      ...oldestTransfers.map(f => ({ key: f.r2_key, size: p(f.file_size), uploadedAt: f.created_at })),
    ].sort((a, b) => new Date(a.uploadedAt).getTime() - new Date(b.uploadedAt).getTime()).slice(0, 10);

    res.json({
      totalFiles: photoCount + transferCount + mmsCount,
      totalSize,
      estimatedCost,
      fileCounts: { files: transferCount, mms: mmsCount, photos: photoCount },
      sizeCounts: { files: transferSize, mms: 0, photos: photoSize },
      largestFiles: allLargest,
      oldestFiles: allOldest,
      userStorage: topUsers.map(u => ({ userId: u.user_id, storageBytes: p(u.total_size), lastUpdatedAt: u.last_updated })),
      totalUsersWithStorage: topUsers.length,
      r2Available: !!s3Client,
    });
  } catch (error) {
    console.error('Admin R2 analytics error:', error);
    res.status(500).json({ error: 'Failed to fetch R2 analytics' });
  }
});

// ── GET /r2/files — List R2-backed files (paginated) ────────────────────────

router.get('/r2/files', async (req: Request, res: Response) => {
  try {
    const type = req.query.type as string | undefined;
    const limit = parseInt(req.query.limit as string) || 100;
    const offset = parseInt(req.query.offset as string) || 0;
    const files: any[] = [];

    if (!type || type === 'photos') {
      const photos = await query<{ user_id: string; file_name: string; file_size: string; r2_key: string; created_at: Date }>(`
        SELECT user_id, file_name, file_size, r2_key, synced_at as created_at FROM user_photos ORDER BY synced_at DESC LIMIT $1 OFFSET $2
      `, [limit, offset]);
      files.push(...photos.map(p => ({ source: 'photos', userId: p.user_id, fileName: p.file_name, fileSize: parseInt(p.file_size || '0'), r2Key: p.r2_key, createdAt: p.created_at })));
    }
    if (!type || type === 'transfers') {
      const transfers = await query<{ user_id: string; file_name: string; file_size: string; r2_key: string; created_at: Date }>(`
        SELECT user_id, file_name, file_size, r2_key, created_at FROM user_file_transfers ORDER BY created_at DESC LIMIT $1 OFFSET $2
      `, [limit, offset]);
      files.push(...transfers.map(t => ({ source: 'transfers', userId: t.user_id, fileName: t.file_name, fileSize: parseInt(t.file_size || '0'), r2Key: t.r2_key, createdAt: t.created_at })));
    }

    res.json({ files, limit, offset });
  } catch (error) {
    console.error('Admin R2 files error:', error);
    res.status(500).json({ error: 'Failed to fetch R2 files' });
  }
});

// ── POST /r2/cleanup — Delete old R2 files by age ───────────────────────────

router.post('/r2/cleanup', async (req: Request, res: Response) => {
  try {
    const { olderThanDays, type } = req.body;
    if (!olderThanDays || olderThanDays < 1) { res.status(400).json({ error: 'olderThanDays required' }); return; }

    const results: Record<string, number> = {};

    if (!type || type === 'photos') {
      const old = await query<{ r2_key: string }>(`DELETE FROM user_photos WHERE synced_at < NOW() - INTERVAL '1 day' * $1 RETURNING r2_key`, [olderThanDays]);
      let r2Count = 0;
      if (s3Client) { for (const f of old) { if (f.r2_key) { try { await s3Client.send(new DeleteObjectCommand({ Bucket: config.r2.bucketName, Key: f.r2_key })); r2Count++; } catch (e) {} } } }
      results.photosDb = old.length; results.photosR2 = r2Count;
    }
    if (!type || type === 'transfers') {
      const old = await query<{ r2_key: string }>(`DELETE FROM user_file_transfers WHERE created_at < NOW() - INTERVAL '1 day' * $1 RETURNING r2_key`, [olderThanDays]);
      let r2Count = 0;
      if (s3Client) { for (const f of old) { if (f.r2_key) { try { await s3Client.send(new DeleteObjectCommand({ Bucket: config.r2.bucketName, Key: f.r2_key })); r2Count++; } catch (e) {} } } }
      results.transfersDb = old.length; results.transfersR2 = r2Count;
    }

    res.json({ success: true, olderThanDays, type: type || 'all', results, r2Available: !!s3Client });
  } catch (error) {
    console.error('Admin R2 cleanup error:', error);
    res.status(500).json({ error: 'Failed to cleanup R2 files' });
  }
});

// ── DELETE /r2/files/* — Delete a specific R2 file by key ───────────────────

router.delete('/r2/files/*', async (req: Request, res: Response) => {
  try {
    const r2Key = decodeURIComponent(req.params[0]);
    const photoDeleted = await query('DELETE FROM user_photos WHERE r2_key = $1 RETURNING *', [r2Key]);
    const transferDeleted = await query('DELETE FROM user_file_transfers WHERE r2_key = $1 RETURNING *', [r2Key]);
    let r2Deleted = false;
    if (s3Client) { try { await s3Client.send(new DeleteObjectCommand({ Bucket: config.r2.bucketName, Key: r2Key })); r2Deleted = true; } catch (e) {} }

    res.json({ success: true, r2Key, dbRecordsDeleted: photoDeleted.length + transferDeleted.length, r2Deleted });
  } catch (error) {
    console.error('Admin delete R2 file error:', error);
    res.status(500).json({ error: 'Failed to delete R2 file' });
  }
});

export default router;
