import { Request, Response, NextFunction } from 'express';
import { checkRateLimit } from '../services/redis';
import { config } from '../config';

interface RateLimitOptions {
  windowMs?: number;
  maxRequests?: number;
  keyPrefix?: string;
  keyGenerator?: (req: Request) => string;
  skip?: (req: Request) => boolean;
}

export function rateLimit(options: RateLimitOptions = {}) {
  const {
    windowMs = config.rateLimit.windowMs,
    maxRequests = config.rateLimit.maxRequests,
    keyPrefix = 'rl',
    keyGenerator = (req) => req.ip || 'unknown',
    skip = () => false,
  } = options;

  const windowSeconds = Math.ceil(windowMs / 1000);

  return async (
    req: Request,
    res: Response,
    next: NextFunction
  ): Promise<void> => {
    if (skip(req)) {
      next();
      return;
    }

    const key = `${keyPrefix}:${keyGenerator(req)}`;

    try {
      const { allowed, remaining, resetIn } = await checkRateLimit(
        key,
        maxRequests,
        windowSeconds
      );

      // Set rate limit headers
      res.setHeader('X-RateLimit-Limit', maxRequests);
      res.setHeader('X-RateLimit-Remaining', remaining);
      res.setHeader('X-RateLimit-Reset', Math.ceil(Date.now() / 1000) + resetIn);

      if (!allowed) {
        res.status(429).json({
          error: 'Too many requests',
          retryAfter: resetIn,
        });
        return;
      }

      next();
    } catch (error) {
      // If Redis fails, allow the request but log
      console.error('Rate limit check failed:', error);
      next();
    }
  };
}

// Preset rate limiters
export const authRateLimit = rateLimit({
  windowMs: 60000, // 1 minute
  maxRequests: 10,
  keyPrefix: 'rl:auth',
});

export const apiRateLimit = rateLimit({
  windowMs: 60000,
  // Increase to accommodate initial sync bursts (messages/contacts/calls) from mobile + desktop.
  maxRequests: 1000,
  keyPrefix: 'rl:api',
  keyGenerator: (req) => req.userId || req.ip || 'unknown',
});

export const adminRateLimit = rateLimit({
  windowMs: 60000,
  maxRequests: 50,
  keyPrefix: 'rl:admin',
});
