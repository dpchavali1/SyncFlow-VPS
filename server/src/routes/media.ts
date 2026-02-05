import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const mediaStatusSchema = z.object({
  playing: z.boolean(),
  title: z.string().max(500).optional(),
  artist: z.string().max(255).optional(),
  album: z.string().max(255).optional(),
  position: z.number().optional(),
  duration: z.number().optional(),
});

const mediaCommandSchema = z.object({
  action: z.enum(['play', 'pause', 'next', 'previous', 'volume', 'stop']),
  volume: z.number().min(0).max(100).optional(),
});

// GET /media/status - Get media playback status
router.get('/status', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const status = await queryOne(
      `SELECT playing, title, artist, album, position, duration, updated_at
       FROM user_media_status
       WHERE user_id = $1`,
      [userId]
    );

    res.json({
      playing: status?.playing || false,
      title: status?.title || null,
      artist: status?.artist || null,
      album: status?.album || null,
      position: status?.position || 0,
      duration: status?.duration || 0,
      updatedAt: status?.updated_at ? new Date(status.updated_at).getTime() : null,
    });
  } catch (error) {
    console.error('Get media status error:', error);
    res.status(500).json({ error: 'Failed to get media status' });
  }
});

// POST /media/status - Update media playback status
router.post('/status', async (req: Request, res: Response) => {
  try {
    const body = mediaStatusSchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO user_media_status (user_id, playing, title, artist, album, position, duration, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         playing = EXCLUDED.playing,
         title = EXCLUDED.title,
         artist = EXCLUDED.artist,
         album = EXCLUDED.album,
         position = EXCLUDED.position,
         duration = EXCLUDED.duration,
         updated_at = NOW()`,
      [userId, body.playing, body.title, body.artist, body.album, body.position, body.duration]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Update media status error:', error);
    res.status(500).json({ error: 'Failed to update media status' });
  }
});

// GET /media/commands - Get pending media commands
router.get('/commands', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const commands = await query(
      `SELECT id, action, volume_level, timestamp
       FROM user_media_commands
       WHERE user_id = $1 AND processed = FALSE
       ORDER BY timestamp DESC
       LIMIT 10`,
      [userId]
    );

    res.json({
      commands: commands.map(c => ({
        id: c.id,
        action: c.action,
        volume: c.volume_level,
        timestamp: parseInt(c.timestamp),
      })),
    });
  } catch (error) {
    console.error('Get media commands error:', error);
    res.status(500).json({ error: 'Failed to get media commands' });
  }
});

// POST /media/commands - Create media command
router.post('/commands', async (req: Request, res: Response) => {
  try {
    const body = mediaCommandSchema.parse(req.body);
    const userId = req.userId!;
    const commandId = `media_${Date.now()}_${Math.random().toString(36).substring(7)}`;

    await query(
      `INSERT INTO user_media_commands (id, user_id, action, volume_level, timestamp)
       VALUES ($1, $2, $3, $4, $5)`,
      [commandId, userId, body.action, body.volume, Date.now()]
    );

    res.json({ id: commandId, success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Create media command error:', error);
    res.status(500).json({ error: 'Failed to create media command' });
  }
});

// PUT /media/commands/:id/processed - Mark command as processed
router.put('/commands/:id/processed', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    await query(
      `UPDATE user_media_commands SET processed = TRUE
       WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Mark media command processed error:', error);
    res.status(500).json({ error: 'Failed to mark command processed' });
  }
});

export default router;
