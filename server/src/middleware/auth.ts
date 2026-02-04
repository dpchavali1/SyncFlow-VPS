import { Request, Response, NextFunction } from 'express';
import { verifyToken, TokenPayload, updateDeviceLastSeen } from '../services/auth';

// Extend Express Request type
declare global {
  namespace Express {
    interface Request {
      user?: TokenPayload;
      userId?: string;
      deviceId?: string;
    }
  }
}

// Authentication middleware
export function authenticate(
  req: Request,
  res: Response,
  next: NextFunction
): void {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Missing or invalid authorization header' });
    return;
  }

  const token = authHeader.substring(7);
  const payload = verifyToken(token);

  if (!payload) {
    res.status(401).json({ error: 'Invalid or expired token' });
    return;
  }

  if (payload.type !== 'access') {
    res.status(401).json({ error: 'Invalid token type' });
    return;
  }

  req.user = payload;
  req.userId = payload.pairedUid || payload.sub; // Use pairedUid if available
  req.deviceId = payload.deviceId;

  // Update device last seen (async, don't wait)
  updateDeviceLastSeen(payload.deviceId).catch(() => {});

  next();
}

// Optional authentication (doesn't fail if no token)
export function optionalAuth(
  req: Request,
  res: Response,
  next: NextFunction
): void {
  const authHeader = req.headers.authorization;

  if (authHeader && authHeader.startsWith('Bearer ')) {
    const token = authHeader.substring(7);
    const payload = verifyToken(token);

    if (payload && payload.type === 'access') {
      req.user = payload;
      req.userId = payload.pairedUid || payload.sub;
      req.deviceId = payload.deviceId;
    }
  }

  next();
}

// Admin-only middleware
export function requireAdmin(
  req: Request,
  res: Response,
  next: NextFunction
): void {
  if (!req.user?.admin) {
    res.status(403).json({ error: 'Admin access required' });
    return;
  }
  next();
}

// Validate user owns the resource
export function validateOwnership(userIdParam: string = 'userId') {
  return (req: Request, res: Response, next: NextFunction): void => {
    const resourceUserId = req.params[userIdParam];

    if (resourceUserId && resourceUserId !== req.userId && !req.user?.admin) {
      res.status(403).json({ error: 'Access denied' });
      return;
    }

    next();
  };
}
