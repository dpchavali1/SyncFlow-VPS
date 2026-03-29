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

// Rate limiting (atomic INCR + EXPIRE via Lua script to prevent orphaned keys)
const rateLimitScript = `
  local current = redis.call('INCR', KEYS[1])
  if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
  end
  local ttl = redis.call('TTL', KEYS[1])
  return {current, ttl}
`;

export async function checkRateLimit(
  key: string,
  maxRequests: number,
  windowSeconds: number
): Promise<{ allowed: boolean; remaining: number; resetIn: number }> {
  const result = await redis.eval(rateLimitScript, 1, key, windowSeconds) as [number, number];
  const current = result[0];
  const ttl = result[1];
  const allowed = current <= maxRequests;
  const remaining = Math.max(0, maxRequests - current);

  return { allowed, remaining, resetIn: ttl > 0 ? ttl : windowSeconds };
}

// Device blacklist (for JWT revocation on device removal)
export async function blacklistDevice(deviceId: string, ttlSeconds: number = 7 * 24 * 60 * 60): Promise<void> {
  await redis.setex(`blacklist:device:${deviceId}`, ttlSeconds, '1');
}

export async function isDeviceBlacklisted(deviceId: string): Promise<boolean> {
  const result = await redis.get(`blacklist:device:${deviceId}`);
  return result !== null;
}

export async function deleteBlacklistDevice(deviceId: string): Promise<void> {
  await redis.del(`blacklist:device:${deviceId}`);
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
