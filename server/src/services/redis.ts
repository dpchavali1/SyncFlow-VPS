import Redis from 'ioredis';
import { config } from '../config';

// Create Redis client
export const redis = new Redis({
  host: config.redis.host,
  port: config.redis.port,
  password: config.redis.password || undefined,
  maxRetriesPerRequest: 3,
  retryStrategy: (times) => {
    if (times > 3) {
      console.error('Redis connection failed after 3 retries');
      return null;
    }
    return Math.min(times * 200, 2000);
  },
});

redis.on('connect', () => {
  console.log('Redis connected');
});

redis.on('error', (err) => {
  console.error('Redis error:', err.message);
});

// Cache helpers
export async function getCache<T>(key: string): Promise<T | null> {
  const data = await redis.get(key);
  if (!data) return null;
  try {
    return JSON.parse(data) as T;
  } catch {
    return data as unknown as T;
  }
}

export async function setCache(
  key: string,
  value: any,
  ttlSeconds?: number
): Promise<void> {
  const data = typeof value === 'string' ? value : JSON.stringify(value);
  if (ttlSeconds) {
    await redis.setex(key, ttlSeconds, data);
  } else {
    await redis.set(key, data);
  }
}

export async function deleteCache(key: string): Promise<void> {
  await redis.del(key);
}

// Rate limiting
export async function checkRateLimit(
  key: string,
  maxRequests: number,
  windowSeconds: number
): Promise<{ allowed: boolean; remaining: number; resetIn: number }> {
  const current = await redis.incr(key);

  if (current === 1) {
    await redis.expire(key, windowSeconds);
  }

  const ttl = await redis.ttl(key);
  const allowed = current <= maxRequests;
  const remaining = Math.max(0, maxRequests - current);

  return { allowed, remaining, resetIn: ttl > 0 ? ttl : windowSeconds };
}

// Session management
export async function setSession(
  userId: string,
  deviceId: string,
  sessionData: any,
  ttlSeconds: number = 86400 * 7 // 7 days
): Promise<void> {
  const key = `session:${userId}:${deviceId}`;
  await setCache(key, sessionData, ttlSeconds);
}

export async function getSession(
  userId: string,
  deviceId: string
): Promise<any | null> {
  const key = `session:${userId}:${deviceId}`;
  return getCache(key);
}

export async function deleteSession(
  userId: string,
  deviceId: string
): Promise<void> {
  const key = `session:${userId}:${deviceId}`;
  await deleteCache(key);
}

// Health check
export async function checkRedisHealth(): Promise<boolean> {
  try {
    const result = await redis.ping();
    return result === 'PONG';
  } catch {
    return false;
  }
}
