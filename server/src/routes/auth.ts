import { Router, Request, Response } from 'express';
import { z } from 'zod';
import {
  createAnonymousUser,
  generateTokenPair,
  createPairingRequest,
  getPairingRequest,
  completePairing,
  redeemPairingToken,
  registerDevice,
  verifyToken,
  generateToken,
} from '../services/auth';
import { authenticate } from '../middleware/auth';
import { authRateLimit } from '../middleware/rateLimit';

const router = Router();

// Validation schemas
const initiatePairingSchema = z.object({
  deviceName: z.string().min(1).max(255),
  deviceType: z.enum(['android', 'macos', 'web']),
});

const completePairingSchema = z.object({
  token: z.string().min(1),
});

const redeemPairingSchema = z.object({
  token: z.string().min(1),
  deviceName: z.string().min(1).max(255).optional(),
  deviceType: z.enum(['android', 'macos', 'web']).optional(),
});

const refreshTokenSchema = z.object({
  refreshToken: z.string().min(1),
});

// POST /auth/anonymous - Create anonymous user and get tokens
router.post('/anonymous', authRateLimit, async (req: Request, res: Response) => {
  try {
    const { userId, deviceId } = await createAnonymousUser();
    const tokens = generateTokenPair(userId, deviceId);

    res.json({
      userId,
      deviceId,
      ...tokens,
    });
  } catch (error) {
    console.error('Anonymous auth error:', error);
    res.status(500).json({ error: 'Failed to create anonymous user' });
  }
});

// POST /auth/pair/initiate - Start pairing process (macOS/Web)
router.post('/pair/initiate', authRateLimit, async (req: Request, res: Response) => {
  try {
    const body = initiatePairingSchema.parse(req.body);

    // Create anonymous user for the new device
    const { userId, deviceId } = await createAnonymousUser();

    // Create pairing request
    const token = await createPairingRequest(
      deviceId,
      body.deviceName,
      body.deviceType
    );

    // Generate temporary tokens (will be replaced after pairing)
    const tokens = generateTokenPair(userId, deviceId);

    res.json({
      pairingToken: token,
      deviceId,
      tempUserId: userId,
      ...tokens,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Initiate pairing error:', error);
    res.status(500).json({ error: 'Failed to initiate pairing' });
  }
});

// GET /auth/pair/status/:token - Check pairing status
router.get('/pair/status/:token', async (req: Request, res: Response) => {
  try {
    const request = await getPairingRequest(req.params.token);

    if (!request) {
      res.status(404).json({ error: 'Pairing request not found or expired' });
      return;
    }

    res.json({
      status: request.status,
      deviceName: request.deviceName,
      approved: request.status === 'approved',
    });
  } catch (error) {
    console.error('Pairing status error:', error);
    res.status(500).json({ error: 'Failed to check pairing status' });
  }
});

// POST /auth/pair/complete - Android approves pairing
router.post(
  '/pair/complete',
  authenticate,
  authRateLimit,
  async (req: Request, res: Response) => {
    try {
      const body = completePairingSchema.parse(req.body);

      const success = await completePairing(body.token, req.userId!);

      if (!success) {
        res.status(400).json({ error: 'Invalid or expired pairing token' });
        return;
      }

      res.json({ success: true, message: 'Pairing approved' });
    } catch (error) {
      if (error instanceof z.ZodError) {
        res.status(400).json({ error: 'Invalid request', details: error.errors });
        return;
      }
      console.error('Complete pairing error:', error);
      res.status(500).json({ error: 'Failed to complete pairing' });
    }
  }
);

// POST /auth/pair/redeem - macOS/Web redeems approved pairing
router.post('/pair/redeem', authRateLimit, async (req: Request, res: Response) => {
  try {
    const body = redeemPairingSchema.parse(req.body);

    const result = await redeemPairingToken(body.token);

    if (!result) {
      res.status(400).json({ error: 'Pairing not approved or already redeemed' });
      return;
    }

    // Register the device under the paired user
    await registerDevice(result.userId, result.deviceId, {
      name: body.deviceName,
      type: body.deviceType || 'web',
    });

    // Generate tokens with pairedUid
    const tokens = generateTokenPair(result.userId, result.deviceId);

    res.json({
      userId: result.userId,
      deviceId: result.deviceId,
      ...tokens,
    });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Redeem pairing error:', error);
    res.status(500).json({ error: 'Failed to redeem pairing' });
  }
});

// POST /auth/refresh - Refresh access token
router.post('/refresh', async (req: Request, res: Response) => {
  try {
    const body = refreshTokenSchema.parse(req.body);

    const payload = verifyToken(body.refreshToken);

    if (!payload || payload.type !== 'refresh') {
      res.status(401).json({ error: 'Invalid refresh token' });
      return;
    }

    // Generate new access token
    const accessToken = generateToken({
      sub: payload.sub,
      deviceId: payload.deviceId,
      admin: payload.admin,
      pairedUid: payload.pairedUid,
    }, 'access');

    res.json({ accessToken });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Refresh token error:', error);
    res.status(500).json({ error: 'Failed to refresh token' });
  }
});

// GET /auth/me - Get current user info
router.get('/me', authenticate, async (req: Request, res: Response) => {
  res.json({
    userId: req.userId,
    deviceId: req.deviceId,
    admin: req.user?.admin || false,
  });
});

export default router;
