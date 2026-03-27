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
      console.error('Rate limit check failed:', error);
      // Fail closed for auth endpoints (prevent brute-force when Redis is down)
      if (keyPrefix.includes('auth')) {
        res.status(503).json({ error: 'Service temporarily unavailable' });
        return;
      }
      // Fail open for non-auth endpoints to avoid blocking legitimate users
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

// Higher limit for polling endpoints (e.g., pairing status polled every 2s)
export const pollingRateLimit = rateLimit({
  windowMs: 60000,
  maxRequests: 60,
  keyPrefix: 'rl:poll',
});

export const adminRateLimit = rateLimit({
  windowMs: 60000,
  maxRequests: 50,
  keyPrefix: 'rl:admin',
});
