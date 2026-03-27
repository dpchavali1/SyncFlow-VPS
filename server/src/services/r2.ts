/**
 * Shared Cloudflare R2 (S3-compatible) client singleton.
 *
 * Every module that needs to interact with R2 should import from here instead
 * of creating its own S3Client instance.  The client is `null` when the R2
 * endpoint env var is not configured (local dev without R2).
 */

import { S3Client } from '@aws-sdk/client-s3';
import { config } from '../config';

export const s3Client: S3Client | null = config.r2.endpoint
  ? new S3Client({
      region: 'auto',
      endpoint: config.r2.endpoint,
      credentials: {
        accessKeyId: config.r2.accessKeyId,
        secretAccessKey: config.r2.secretAccessKey,
      },
    })
  : null;

export const R2_BUCKET = config.r2.bucketName;
