import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';
import { broadcastToUser } from '../services/websocket';
import { normalizePhoneNumber } from '../utils/phoneNumber';
import { sendOutgoingMessageNotification } from '../services/push';
import { S3Client, GetObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { config } from '../config';

// R2/S3 Client for generating presigned download URLs for MMS attachments
const s3Client = new S3Client({
  region: 'auto',
  endpoint: config.r2?.endpoint || process.env.R2_ENDPOINT,
  credentials: {
    accessKeyId: config.r2?.accessKeyId || process.env.R2_ACCESS_KEY_ID || '',
    secretAccessKey: config.r2?.secretAccessKey || process.env.R2_SECRET_ACCESS_KEY || '',
  },
});
const R2_BUCKET = config.r2?.bucketName || process.env.R2_BUCKET_NAME || 'syncflow-files';

const router = Router();

// All routes require authentication
router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const getMessagesSchema = z.object({
  limit: z.coerce.number().min(1).max(500).default(100),
  before: z.coerce.number().optional(), // timestamp for pagination
  after: z.coerce.number().optional(), // timestamp for incremental sync
  threadId: z.coerce.number().optional(),
});

const createMessageSchema = z.object({
  id: z.string().min(1),
  threadId: z.number().optional(),
  address: z.string().min(1).max(500), // Increased for group MMS, long addresses
  contactName: z.string().max(255).optional(),
  body: z.string().max(10000).optional(),
  date: z.number(),
  type: z.number(), // 1=received, 2=sent
  read: z.boolean().default(false),
  isMms: z.boolean().default(false),
  mmsParts: z.any().optional(),
  encrypted: z.boolean().default(false),
  encryptedBody: z.string().optional(),
  encryptedNonce: z.string().optional(),
  keyMap: z.record(z.string()).optional(),
  deliveryStatus: z.string().optional(), // 'sending', 'sent', 'delivered', 'failed'
});

const syncMessagesSchema = z.object({
  messages: z.array(createMessageSchema).max(500),
});

const sendMessageSchema = z.object({
  address: z.string().min(1).max(500), // Increased for group MMS, long addresses
  body: z.string().max(1600).default(''),
  simSubscriptionId: z.number().optional(),
  isMms: z.boolean().default(false),
  attachments: z.array(z.object({
    fileKey: z.string(),
    contentType: z.string(),
    fileName: z.string(),
  })).optional(),
});

// GET /messages - Get messages with pagination
router.get('/', async (req: Request, res: Response) => {
  try {
    const params = getMessagesSchema.parse(req.query);
    const userId = req.userId!;

    let queryText = `
      SELECT id, thread_id, address, contact_name, body, date, type, read,
             is_mms, mms_parts, encrypted, encrypted_body, encrypted_nonce, key_map, delivery_status, created_at
      FROM user_messages
      WHERE user_id = $1
    `;
    const queryParams: any[] = [userId];
    let paramIndex = 2;

    if (params.threadId) {
      queryText += ` AND thread_id = $${paramIndex++}`;
      queryParams.push(params.threadId);
    }

    if (params.after) {
      queryText += ` AND date > $${paramIndex++}`;
      queryParams.push(params.after);
    }

    if (params.before) {
      queryText += ` AND date < $${paramIndex++}`;
      queryParams.push(params.before);
    }

    queryText += ` ORDER BY date DESC LIMIT $${paramIndex}`;
    queryParams.push(params.limit);

    const messages = await query(queryText, queryParams);

    res.json({
      messages: messages.map((m) => {
        // Ensure mmsParts is always a parsed array (not a JSON string)
        let mmsParts = m.mms_parts;
        if (typeof mmsParts === 'string') {
          try { mmsParts = JSON.parse(mmsParts); } catch { mmsParts = null; }
        }
        // Ensure keyMap is always a parsed object
        let keyMap = m.key_map;
        if (typeof keyMap === 'string') {
          try { keyMap = JSON.parse(keyMap); } catch { keyMap = null; }
        }
        return {
          id: m.id,
          threadId: m.thread_id,
          address: m.address,
          contactName: m.contact_name,
          body: m.body,
          date: parseInt(m.date),
          type: m.type,
          read: m.read,
          isMms: m.is_mms,
          mmsParts,
          encrypted: m.encrypted,
          encryptedBody: m.encrypted_body,
          encryptedNonce: m.encrypted_nonce,
          keyMap,
          deliveryStatus: m.delivery_status,
        };
      }),
      hasMore: messages.length === params.limit,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Get messages error:', error);
    res.status(500).json({ error: 'Failed to get messages' });
  }
});

// POST /messages/sync - Sync messages from device (batch)
router.post('/sync', async (req: Request, res: Response) => {
  try {
    const body = syncMessagesSchema.parse(req.body);
    const userId = req.userId!;

    let synced = 0;
    let skipped = 0;
    const syncedMessages: any[] = [];

    for (const msg of body.messages) {
      try {
        await query(
          `INSERT INTO user_messages
           (id, user_id, thread_id, address, contact_name, body, date, type, read, is_mms, mms_parts, encrypted, encrypted_body, encrypted_nonce, key_map, delivery_status)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)
           ON CONFLICT (id) DO UPDATE SET
             thread_id = EXCLUDED.thread_id,
             address = EXCLUDED.address,
             contact_name = EXCLUDED.contact_name,
             body = EXCLUDED.body,
             date = EXCLUDED.date,
             type = EXCLUDED.type,
             read = EXCLUDED.read,
             is_mms = EXCLUDED.is_mms,
             mms_parts = COALESCE(EXCLUDED.mms_parts, user_messages.mms_parts),
             encrypted = EXCLUDED.encrypted,
             encrypted_body = EXCLUDED.encrypted_body,
             encrypted_nonce = EXCLUDED.encrypted_nonce,
             key_map = EXCLUDED.key_map,
             delivery_status = COALESCE(EXCLUDED.delivery_status, user_messages.delivery_status),
             updated_at = NOW()`,
          [
            msg.id,
            userId,
            msg.threadId,
            normalizePhoneNumber(msg.address),
            msg.contactName,
            msg.body,
            msg.date,
            msg.type,
            msg.read,
            msg.isMms,
            msg.mmsParts ? JSON.stringify(msg.mmsParts) : null,
            msg.encrypted,
            msg.encryptedBody ?? null,
            msg.encryptedNonce ?? null,
            msg.keyMap ? JSON.stringify(msg.keyMap) : null,
            msg.deliveryStatus ?? null,
          ]
        );
        synced++;
        syncedMessages.push(msg);
      } catch (e) {
        skipped++;
      }
    }

    // Broadcast synced messages to other connected devices via WebSocket
    if (syncedMessages.length > 0) {
      // Send in batches to avoid overwhelming the WebSocket
      const batchSize = 50;
      for (let i = 0; i < syncedMessages.length; i += batchSize) {
        const batch = syncedMessages.slice(i, i + batchSize);
        broadcastToUser(userId, 'messages', {
          type: 'messages_synced',
          data: {
            messages: batch.map((m) => ({
              id: m.id,
              threadId: m.threadId,
              address: m.address,
              contactName: m.contactName,
              body: m.body,
              date: m.date,
              type: m.type,
              read: m.read,
              isMms: m.isMms,
              mmsParts: m.mmsParts,
              encrypted: m.encrypted,
              encryptedBody: m.encryptedBody,
              encryptedNonce: m.encryptedNonce,
              keyMap: m.keyMap,
              deliveryStatus: m.deliveryStatus,
            })),
            total: batch.length,
          },
        });
      }
    }

    res.json({ synced, skipped, total: body.messages.length });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Sync messages error:', error);
    res.status(500).json({ error: 'Failed to sync messages' });
  }
});

// POST /messages/send - Queue message to send
router.post('/send', async (req: Request, res: Response) => {
  try {
    const body = sendMessageSchema.parse(req.body);
    const userId = req.userId!;
    const messageId = `out_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_outgoing_messages
       (id, user_id, address, body, timestamp, status, sim_subscription_id, is_mms, attachments)
       VALUES ($1, $2, $3, $4, $5, 'pending', $6, $7, $8)`,
      [messageId, userId, body.address, body.body, Date.now(), body.simSubscriptionId,
       body.isMms, body.attachments ? JSON.stringify(body.attachments) : null]
    );

    // Send FCM push to wake Android device for immediate delivery
    sendOutgoingMessageNotification(userId, messageId, req.deviceId ?? null).catch(err => {
      console.error('[Messages] FCM push for outgoing message failed:', err);
    });

    res.json({
      id: messageId,
      status: 'pending',
      message: body.isMms ? 'MMS queued for sending' : 'Message queued for sending',
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Send message error:', error);
    res.status(500).json({ error: 'Failed to queue message' });
  }
});

// GET /messages/outgoing - Get pending outgoing messages (for Android to pick up)
router.get('/outgoing', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const messages = await query(
      `SELECT id, address, body, timestamp, status, sim_subscription_id, is_mms, attachments
       FROM user_outgoing_messages
       WHERE user_id = $1 AND status = 'pending'
       ORDER BY timestamp ASC
       LIMIT 50`,
      [userId]
    );

    // Resolve fileKey → presigned download URL for each attachment
    const resolved = await Promise.all(messages.map(async (m) => {
      let attachments = m.attachments;
      if (typeof attachments === 'string') {
        try { attachments = JSON.parse(attachments); } catch { attachments = null; }
      }

      // Generate presigned download URLs for R2-stored attachments
      if (Array.isArray(attachments)) {
        attachments = await Promise.all(attachments.map(async (att: any) => {
          if (att.fileKey && !att.url) {
            try {
              const command = new GetObjectCommand({ Bucket: R2_BUCKET, Key: att.fileKey });
              const url = await getSignedUrl(s3Client, command, { expiresIn: 3600 });
              return { ...att, url };
            } catch (err) {
              console.error(`[Messages] Failed to generate download URL for ${att.fileKey}:`, err);
              return att;
            }
          }
          return att;
        }));
      }

      return {
        id: m.id,
        address: m.address,
        body: m.body,
        timestamp: parseInt(m.timestamp),
        simSubscriptionId: m.sim_subscription_id,
        isMms: m.is_mms || false,
        attachments,
      };
    }));

    res.json({ messages: resolved });
  } catch (error) {
    console.error('Get outgoing messages error:', error);
    res.status(500).json({ error: 'Failed to get outgoing messages' });
  }
});

// PUT /messages/outgoing/:id/status - Update outgoing message status
router.put('/outgoing/:id/status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { status, error: errorMsg } = req.body;
    const userId = req.userId!;

    if (!['sending', 'sent', 'delivered', 'failed'].includes(status)) {
      res.status(400).json({ error: 'Invalid status' });
      return;
    }

    await query(
      `UPDATE user_outgoing_messages
       SET status = $1, error_message = $2, delivery_status = $1
       WHERE id = $3 AND user_id = $4`,
      [status, errorMsg, id, userId]
    );

    // Broadcast status change to all connected devices (Mac, Web)
    broadcastToUser(userId, 'messages', {
      type: 'outgoing_status_changed',
      data: { id, status },
    });

    res.json({ success: true });
  } catch (error) {
    console.error('Update outgoing status error:', error);
    res.status(500).json({ error: 'Failed to update status' });
  }
});

// PUT /messages/:id/delivery-status - Update delivery status of a synced message
router.put('/:id/delivery-status', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const { status } = req.body;
    const userId = req.userId!;

    if (!['sending', 'sent', 'delivered', 'failed'].includes(status)) {
      res.status(400).json({ error: 'Invalid delivery status' });
      return;
    }

    await query(
      `UPDATE user_messages SET delivery_status = $1, updated_at = NOW()
       WHERE id = $2 AND user_id = $3`,
      [status, id, userId]
    );

    // Broadcast delivery status change to all connected devices
    broadcastToUser(userId, 'messages', {
      type: 'delivery_status_changed',
      data: { id, deliveryStatus: status },
    });

    res.json({ success: true });
  } catch (error) {
    console.error('Update delivery status error:', error);
    res.status(500).json({ error: 'Failed to update delivery status' });
  }
});

// PUT /messages/:id/read - Mark message as read
router.put('/:id/read', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `UPDATE user_messages SET read = true, updated_at = NOW()
       WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Mark read error:', error);
    res.status(500).json({ error: 'Failed to mark as read' });
  }
});

// DELETE /messages - Delete messages by IDs
router.delete('/', async (req: Request, res: Response) => {
  try {
    const { messageIds } = req.body;
    const userId = req.userId!;

    if (!Array.isArray(messageIds) || messageIds.length === 0) {
      res.status(400).json({ error: 'messageIds array is required' });
      return;
    }

    if (messageIds.length > 500) {
      res.status(400).json({ error: 'Maximum 500 messages per request' });
      return;
    }

    const placeholders = messageIds.map((_: string, i: number) => `$${i + 2}`).join(', ');
    const result = await query(
      `DELETE FROM user_messages WHERE user_id = $1 AND id IN (${placeholders}) RETURNING id`,
      [userId, ...messageIds]
    );

    const deletedIds = result.map((r: any) => r.id);

    // Broadcast deletions to other connected devices
    if (deletedIds.length > 0) {
      broadcastToUser(userId, 'messages', {
        type: 'messages_deleted',
        data: { messageIds: deletedIds },
      });
    }

    res.json({ success: true, deleted: deletedIds.length });
  } catch (error) {
    console.error('Delete messages error:', error);
    res.status(500).json({ error: 'Failed to delete messages' });
  }
});

// GET /messages/count - Get message count (for sync status)
router.get('/count', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const result = await queryOne<{ count: string }>(
      `SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1`,
      [userId]
    );

    res.json({ count: parseInt(result?.count || '0') });
  } catch (error) {
    console.error('Get count error:', error);
    res.status(500).json({ error: 'Failed to get count' });
  }
});

export default router;
