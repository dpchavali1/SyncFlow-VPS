/**
 * Unified storage abstraction supporting both Cloudflare R2 (cloud) and local filesystem.
 *
 * In cloud mode (R2 configured), presigned URLs are generated against the S3-compatible
 * R2 endpoint.  In local mode (self-hosted without R2), files are stored on disk under
 * LOCAL_STORAGE_PATH and served through Express endpoints on this server.
 *
 * Factory function `createStorageBackend()` auto-detects which backend to use based on
 * config, or you can force it via STORAGE_BACKEND=r2|local.
 */

import { PutObjectCommand, GetObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { config } from '../config';
import { s3Client, R2_BUCKET } from './r2';
import path from 'path';
import fs from 'fs/promises';
import { Router, Request, Response } from 'express';
import jwt from 'jsonwebtoken';

// ---------------------------------------------------------------------------
// Storage backend interface
// ---------------------------------------------------------------------------

export interface StorageBackend {
  /** Return a URL the client can PUT/POST a file to. */
  getUploadUrl(key: string, contentType: string, expiresIn?: number): Promise<string>;
  /** Return a URL the client can GET to download a file. */
  getDownloadUrl(key: string, expiresIn?: number): Promise<string>;
  /** Remove a stored object by key. */
  deleteObject(key: string): Promise<void>;
  /** Whether the backend has all required configuration. */
  isConfigured(): boolean;
}

// ---------------------------------------------------------------------------
// R2 / S3-compatible cloud storage backend
// ---------------------------------------------------------------------------

class R2StorageBackend implements StorageBackend {
  isConfigured(): boolean {
    return s3Client !== null;
  }

  async getUploadUrl(key: string, contentType: string, expiresIn = 3600): Promise<string> {
    if (!s3Client) throw new Error('R2 storage is not configured');
    const command = new PutObjectCommand({
      Bucket: R2_BUCKET,
      Key: key,
      ContentType: contentType,
    });
    return getSignedUrl(s3Client, command, { expiresIn });
  }

  async getDownloadUrl(key: string, expiresIn = 3600): Promise<string> {
    if (!s3Client) throw new Error('R2 storage is not configured');
    const command = new GetObjectCommand({
      Bucket: R2_BUCKET,
      Key: key,
    });
    return getSignedUrl(s3Client, command, { expiresIn });
  }

  async deleteObject(key: string): Promise<void> {
    if (!s3Client) throw new Error('R2 storage is not configured');
    const command = new DeleteObjectCommand({
      Bucket: R2_BUCKET,
      Key: key,
    });
    await s3Client.send(command);
  }
}

// ---------------------------------------------------------------------------
// Local filesystem storage backend (self-hosted)
// ---------------------------------------------------------------------------

/** HMAC-based token for short-lived download URLs (avoids a full JWT library dep
 *  for the download path — but we already have jsonwebtoken, so we use it). */
interface FileToken {
  key: string;
  exp: number;
}

class LocalStorageBackend implements StorageBackend {
  private basePath: string;

  constructor() {
    this.basePath = path.resolve(config.storage.localPath);
  }

  isConfigured(): boolean {
    return true; // always available — just writes to disk
  }

  /**
   * For uploads, the client will POST the file body to our own server endpoint.
   * We return the URL of that endpoint with the desired key as a query parameter.
   */
  async getUploadUrl(key: string, contentType: string, _expiresIn = 3600): Promise<string> {
    const token = this.signFileToken(key, _expiresIn);
    const baseUrl = config.baseUrl.replace(/\/+$/, '');
    return `${baseUrl}/api/files/upload?key=${encodeURIComponent(key)}&contentType=${encodeURIComponent(contentType)}&token=${encodeURIComponent(token)}`;
  }

  /**
   * For downloads, generate a short-lived signed URL pointing at our download endpoint.
   */
  async getDownloadUrl(key: string, expiresIn = 3600): Promise<string> {
    const token = this.signFileToken(key, expiresIn);
    const baseUrl = config.baseUrl.replace(/\/+$/, '');
    return `${baseUrl}/api/files/download?key=${encodeURIComponent(key)}&token=${encodeURIComponent(token)}`;
  }

  async deleteObject(key: string): Promise<void> {
    const filePath = this.resolveKey(key);
    try {
      await fs.unlink(filePath);
    } catch (err: any) {
      // Ignore ENOENT — file already gone
      if (err.code !== 'ENOENT') throw err;
    }
  }

  // -- helpers --------------------------------------------------------------

  /** Resolve a storage key to an absolute filesystem path, preventing traversal. */
  resolveKey(key: string): string {
    // Normalize and prevent path traversal
    const sanitized = path.normalize(key).replace(/^(\.\.(\/|\\|$))+/, '');
    const resolved = path.join(this.basePath, sanitized);
    if (!resolved.startsWith(this.basePath)) {
      throw new Error('Invalid storage key — path traversal detected');
    }
    return resolved;
  }

  /** Ensure the parent directory for a file exists. */
  async ensureDir(filePath: string): Promise<void> {
    await fs.mkdir(path.dirname(filePath), { recursive: true });
  }

  /** Create a short-lived JWT token containing the file key. */
  private signFileToken(key: string, expiresInSeconds: number): string {
    return jwt.sign({ key } as FileToken, config.jwt.secret, {
      expiresIn: expiresInSeconds,
      algorithm: 'HS256',
    } as jwt.SignOptions);
  }

  /** Verify a file download/upload token and return the key. */
  verifyFileToken(token: string): string | null {
    try {
      const payload = jwt.verify(token, config.jwt.secret, {
        algorithms: ['HS256'],
      }) as FileToken;
      return payload.key || null;
    } catch {
      return null;
    }
  }
}

// ---------------------------------------------------------------------------
// Factory
// ---------------------------------------------------------------------------

export function createStorageBackend(): StorageBackend {
  const explicit = config.storage.backend;

  if (explicit === 'r2') {
    if (!s3Client) {
      console.error('[Storage] STORAGE_BACKEND=r2 but R2 is not configured. Check R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY.');
      process.exit(1);
    }
    console.log('[Storage] Using R2 cloud storage backend');
    return new R2StorageBackend();
  }

  if (explicit === 'local') {
    console.log(`[Storage] Using local filesystem storage backend (${config.storage.localPath})`);
    return new LocalStorageBackend();
  }

  // auto: prefer R2 if configured, otherwise fall back to local
  if (s3Client) {
    console.log('[Storage] Auto-detected R2 cloud storage backend');
    return new R2StorageBackend();
  }

  console.log(`[Storage] R2 not configured — falling back to local filesystem storage (${config.storage.localPath})`);
  return new LocalStorageBackend();
}

/** Singleton storage instance used throughout the server. */
export const storage = createStorageBackend();

// ---------------------------------------------------------------------------
// Express router for local file serving (only meaningful with LocalStorageBackend)
// ---------------------------------------------------------------------------

export function createFileRouter(): Router {
  const router = Router();

  // Only wire up these endpoints when using local storage
  if (!(storage instanceof LocalStorageBackend)) {
    // Return 404 for any requests — these endpoints are only for local storage mode
    router.all('*', (_req: Request, res: Response) => {
      res.status(404).json({ error: 'File endpoints are only available in local storage mode' });
    });
    return router;
  }

  const local = storage as LocalStorageBackend;

  // -----------------------------------------------------------------------
  // PUT /api/files/upload — authenticated file upload
  //
  // The upload URL returned by LocalStorageBackend contains a signed token
  // in the query string.  Clients PUT the raw file body to this URL.
  // We also accept POST with multipart or raw body.
  // -----------------------------------------------------------------------
  const handleUpload = async (req: Request, res: Response) => {
    try {
      const token = (req.query.token as string) || '';
      const key = (req.query.key as string) || '';

      if (!token || !key) {
        res.status(400).json({ error: 'Missing token or key query parameter' });
        return;
      }

      // Verify the signed token matches the key
      const tokenKey = local.verifyFileToken(token);
      if (!tokenKey || tokenKey !== key) {
        res.status(403).json({ error: 'Invalid or expired upload token' });
        return;
      }

      const filePath = local.resolveKey(key);
      await local.ensureDir(filePath);

      // Stream to disk with size limit and backpressure (no in-memory buffering)
      const MAX_UPLOAD_SIZE = 50 * 1024 * 1024; // 50 MB
      const contentLength = parseInt(req.headers['content-length'] || '0', 10);
      if (contentLength > MAX_UPLOAD_SIZE) {
        res.status(413).json({ error: `File too large. Maximum size is ${MAX_UPLOAD_SIZE / (1024 * 1024)} MB` });
        return;
      }

      const { createWriteStream } = await import('fs');
      let bytesWritten = 0;
      const writeStream = createWriteStream(filePath);

      await new Promise<void>((resolve, reject) => {
        req.on('data', (chunk: Buffer) => {
          bytesWritten += chunk.length;
          if (bytesWritten > MAX_UPLOAD_SIZE) {
            req.destroy();
            writeStream.destroy();
            fs.unlink(filePath).catch(() => {});
            reject(new Error('Upload exceeds size limit'));
            return;
          }
          if (!writeStream.write(chunk)) {
            req.pause();
            writeStream.once('drain', () => req.resume());
          }
        });
        req.on('end', () => { writeStream.end(); resolve(); });
        req.on('error', (err) => { writeStream.destroy(); reject(err); });
        writeStream.on('error', reject);
      });

      if (bytesWritten === 0) {
        await fs.unlink(filePath).catch(() => {});
        res.status(400).json({ error: 'Empty upload body' });
        return;
      }

      res.json({ success: true, key, size: bytesWritten });
    } catch (error: any) {
      console.error('[LocalStorage] Upload error:', error);
      res.status(500).json({ error: 'Upload failed' });
    }
  };

  router.put('/upload', handleUpload);
  router.post('/upload', handleUpload);

  // -----------------------------------------------------------------------
  // GET /api/files/download — signed-token file download
  //
  // The download URL contains a JWT token with the file key embedded.
  // No bearer auth required — the token IS the auth.
  // -----------------------------------------------------------------------
  router.get('/download', async (req: Request, res: Response) => {
    try {
      const token = (req.query.token as string) || '';
      const key = (req.query.key as string) || '';

      if (!token || !key) {
        res.status(400).json({ error: 'Missing token or key query parameter' });
        return;
      }

      const tokenKey = local.verifyFileToken(token);
      if (!tokenKey || tokenKey !== key) {
        res.status(403).json({ error: 'Invalid or expired download token' });
        return;
      }

      const filePath = local.resolveKey(key);

      // Check file exists
      try {
        await fs.access(filePath);
      } catch {
        res.status(404).json({ error: 'File not found' });
        return;
      }

      // Guess content type from extension
      const ext = path.extname(filePath).toLowerCase();
      const mimeTypes: Record<string, string> = {
        '.jpg': 'image/jpeg',
        '.jpeg': 'image/jpeg',
        '.png': 'image/png',
        '.gif': 'image/gif',
        '.webp': 'image/webp',
        '.mp4': 'video/mp4',
        '.mp3': 'audio/mpeg',
        '.ogg': 'audio/ogg',
        '.wav': 'audio/wav',
        '.pdf': 'application/pdf',
        '.txt': 'text/plain',
        '.json': 'application/json',
        '.vcf': 'text/vcard',
        '.3gp': 'video/3gpp',
        '.smil': 'application/smil+xml',
      };
      const contentType = mimeTypes[ext] || 'application/octet-stream';

      const stat = await fs.stat(filePath);
      res.setHeader('Content-Type', contentType);
      res.setHeader('Content-Length', stat.size);
      res.setHeader('Cache-Control', 'private, max-age=3600');

      // Stream the file
      const { createReadStream } = await import('fs');
      const stream = createReadStream(filePath);
      stream.pipe(res);
      stream.on('error', (err) => {
        console.error('[LocalStorage] Stream error:', err);
        if (!res.headersSent) {
          res.status(500).json({ error: 'Failed to read file' });
        }
      });
    } catch (error: any) {
      console.error('[LocalStorage] Download error:', error);
      if (!res.headersSent) {
        res.status(500).json({ error: 'Download failed' });
      }
    }
  });

  return router;
}
