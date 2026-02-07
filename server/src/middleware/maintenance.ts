import { Request, Response, NextFunction } from 'express';
import { getCache } from '../services/redis';

export async function maintenanceMiddleware(req: Request, res: Response, next: NextFunction) {
  // Always allow admin routes and health check
  if (req.path.startsWith('/api/admin') || req.path === '/health') {
    return next();
  }

  try {
    const enabled = await getCache<string>('maintenance:enabled');
    if (enabled === 'true') {
      const message = await getCache<string>('maintenance:message') || 'System is undergoing maintenance. Please try again later.';
      return res.status(503).json({ maintenance: true, message });
    }
  } catch {
    // If Redis fails, don't block requests
  }

  next();
}
