import jwt from 'jsonwebtoken';
import bcrypt from 'bcrypt';
import { v4 as uuidv4 } from 'uuid';
import { config } from '../config';
import { query, queryOne } from './database';
import { setCache, getCache, deleteCache } from './redis';

// Token payload interface
export interface TokenPayload {
  sub: string; // user ID
  deviceId: string;
  type: 'access' | 'refresh';
  admin?: boolean;
  pairedUid?: string; // for paired devices
}

// Generate JWT token
export function generateToken(
  payload: Omit<TokenPayload, 'type'>,
  type: 'access' | 'refresh' = 'access'
): string {
  const expiresIn = type === 'access' ? config.jwt.expiresIn : config.jwt.refreshExpiresIn;

  return jwt.sign(
    { ...payload, type },
    config.jwt.secret,
    { expiresIn } as jwt.SignOptions
  );
}

// Verify JWT token
export function verifyToken(token: string): TokenPayload | null {
  try {
    return jwt.verify(token, config.jwt.secret) as TokenPayload;
  } catch {
    return null;
  }
}

// Generate token pair (access + refresh)
export function generateTokenPair(
  userId: string,
  deviceId: string,
  options?: { admin?: boolean; pairedUid?: string }
): { accessToken: string; refreshToken: string } {
  const payload = {
    sub: userId,
    deviceId,
    admin: options?.admin,
    pairedUid: options?.pairedUid,
  };

  return {
    accessToken: generateToken(payload, 'access'),
    refreshToken: generateToken(payload, 'refresh'),
  };
}

// Create anonymous user
export async function createAnonymousUser(): Promise<{ userId: string; deviceId: string }> {
  const userId = uuidv4();
  const deviceId = uuidv4();

  await query(
    `INSERT INTO users (uid, created_at) VALUES ($1, NOW())`,
    [userId]
  );

  return { userId, deviceId };
}

// Create or get user by phone
export async function getOrCreateUserByPhone(phone: string): Promise<string> {
  // Check if user exists
  const existing = await queryOne<{ uid: string }>(
    `SELECT uid FROM users WHERE phone = $1`,
    [phone]
  );

  if (existing) {
    return existing.uid;
  }

  // Create new user
  const userId = uuidv4();
  await query(
    `INSERT INTO users (uid, phone, created_at) VALUES ($1, $2, NOW())`,
    [userId, phone]
  );

  return userId;
}

// Register device
export async function registerDevice(
  userId: string,
  deviceId: string,
  deviceInfo: {
    name?: string;
    type: 'android' | 'macos' | 'web';
    fcmToken?: string;
  }
): Promise<void> {
  await query(
    `INSERT INTO user_devices (id, user_id, name, device_type, fcm_token, paired_at, last_seen)
     VALUES ($1, $2, $3, $4, $5, NOW(), NOW())
     ON CONFLICT (id) DO UPDATE SET
       name = EXCLUDED.name,
       device_type = EXCLUDED.device_type,
       fcm_token = EXCLUDED.fcm_token,
       last_seen = NOW()`,
    [deviceId, userId, deviceInfo.name, deviceInfo.type, deviceInfo.fcmToken]
  );
}

// Update device last seen
export async function updateDeviceLastSeen(deviceId: string): Promise<void> {
  await query(
    `UPDATE user_devices SET last_seen = NOW() WHERE id = $1`,
    [deviceId]
  );
}

// Pairing: Create pairing request
export async function createPairingRequest(
  deviceId: string,
  deviceName: string,
  deviceType: string,
  tempUserId?: string
): Promise<string> {
  const token = uuidv4().replace(/-/g, '').substring(0, 16).toUpperCase();
  const expiresAt = new Date(Date.now() + 5 * 60 * 1000); // 5 minutes

  await query(
    `INSERT INTO pairing_requests (token, device_id, device_name, status, created_at, expires_at)
     VALUES ($1, $2, $3, 'pending', NOW(), $4)`,
    [token, deviceId, deviceName, expiresAt]
  );

  // Also cache in Redis for fast lookup (include tempUserId for cleanup)
  await setCache(`pairing:${token}`, { deviceId, deviceName, deviceType, tempUserId }, 300);

  return token;
}

// Pairing: Get pairing request
export async function getPairingRequest(token: string): Promise<{
  deviceId: string;
  deviceName: string;
  userId?: string;
  status: string;
} | null> {
  // Check Redis first
  const cached = await getCache<any>(`pairing:${token}`);
  if (cached) {
    return cached;
  }

  // Fall back to database
  const request = await queryOne<{
    device_id: string;
    device_name: string;
    user_id: string | null;
    status: string;
  }>(
    `SELECT device_id, device_name, user_id, status FROM pairing_requests
     WHERE token = $1 AND expires_at > NOW()`,
    [token]
  );

  if (!request) return null;

  return {
    deviceId: request.device_id,
    deviceName: request.device_name,
    userId: request.user_id || undefined,
    status: request.status,
  };
}

// Pairing: Complete pairing (Android approves)
export async function completePairing(
  token: string,
  userId: string
): Promise<boolean> {
  const result = await query(
    `UPDATE pairing_requests
     SET user_id = $1, status = 'approved'
     WHERE token = $2 AND status = 'pending' AND expires_at > NOW()
     RETURNING token`,
    [userId, token]
  );

  if (result.length === 0) return false;

  // Update Redis cache
  const cached = await getCache<any>(`pairing:${token}`);
  if (cached) {
    await setCache(`pairing:${token}`, { ...cached, userId, status: 'approved' }, 300);
  }

  return true;
}

// Pairing: Redeem pairing token (macOS/Web gets credentials)
export async function redeemPairingToken(
  token: string,
  clientTempUserId?: string
): Promise<{ userId: string; deviceId: string } | null> {
  const request = await getPairingRequest(token);

  if (!request || request.status !== 'approved' || !request.userId) {
    return null;
  }

  // Get the temp user ID from the request cache or client
  const tempUserId = (request as any).tempUserId || clientTempUserId;

  // Mark as redeemed
  await query(
    `UPDATE pairing_requests SET status = 'redeemed' WHERE token = $1`,
    [token]
  );

  // Clean up Redis
  await deleteCache(`pairing:${token}`);

  // Clean up the temporary anonymous user created during pair/initiate
  // Method 1: Use tracked tempUserId if available
  if (tempUserId && tempUserId !== request.userId) {
    try {
      await query('DELETE FROM user_devices WHERE user_id = $1', [tempUserId]);
      await query('DELETE FROM users WHERE uid = $1', [tempUserId]);
      console.log(`[Auth] Cleaned up temp pairing user: ${tempUserId}`);
    } catch (err) {
      console.warn(`[Auth] Failed to clean up temp user ${tempUserId}:`, err);
    }
  }

  // Method 2: Clean up ANY orphaned users with no devices and no messages
  // These are always leftover from pairing initiations
  try {
    const orphans = await query(
      `DELETE FROM users
       WHERE uid != $1
       AND uid NOT IN (SELECT DISTINCT user_id FROM user_devices)
       AND uid NOT IN (SELECT DISTINCT user_id FROM user_messages)
       RETURNING uid`,
      [request.userId]
    );
    if (orphans.length > 0) {
      console.log(`[Auth] Cleaned up ${orphans.length} orphaned user(s): ${orphans.map((o: any) => o.uid).join(', ')}`);
    }
  } catch (err) {
    console.warn('[Auth] Orphan cleanup failed:', err);
  }

  return {
    userId: request.userId,
    deviceId: request.deviceId,
  };
}

// Get or create user by Firebase UID (device fingerprint)
export async function getOrCreateUserByFirebaseUid(firebaseUid: string): Promise<string> {
  // Check if user exists with this Firebase UID (device fingerprint)
  const existing = await queryOne<{ uid: string }>(
    `SELECT uid FROM users WHERE firebase_uid = $1`,
    [firebaseUid]
  );

  if (existing) {
    console.log(`[Auth] Found existing user ${existing.uid} for device fingerprint ${firebaseUid.substring(0, 8)}...`);
    return existing.uid;
  }

  // Check if there's an orphaned user without firebase_uid that has data we should claim
  // This handles the case where anonymous auth was used before
  const orphanedUser = await queryOne<{ uid: string; message_count: string }>(
    `SELECT u.uid, COUNT(m.id) as message_count
     FROM users u
     LEFT JOIN user_messages m ON m.user_id = u.uid
     WHERE u.firebase_uid IS NULL
     GROUP BY u.uid
     ORDER BY COUNT(m.id) DESC
     LIMIT 1`,
    []
  );

  if (orphanedUser && parseInt(orphanedUser.message_count) > 0) {
    // Claim this orphaned user by setting the firebase_uid
    console.log(`[Auth] Claiming orphaned user ${orphanedUser.uid} with ${orphanedUser.message_count} messages for device ${firebaseUid.substring(0, 8)}...`);
    await query(
      `UPDATE users SET firebase_uid = $1 WHERE uid = $2`,
      [firebaseUid, orphanedUser.uid]
    );
    return orphanedUser.uid;
  }

  // Create new user with Firebase UID as the primary user ID
  console.log(`[Auth] Creating new user for device fingerprint ${firebaseUid.substring(0, 8)}...`);
  await query(
    `INSERT INTO users (uid, firebase_uid, created_at) VALUES ($1, $1, NOW())
     ON CONFLICT (uid) DO UPDATE SET firebase_uid = EXCLUDED.firebase_uid`,
    [firebaseUid]
  );

  return firebaseUid;
}

// Migrate all data from one user to another
export async function migrateUserData(fromUserId: string, toUserId: string): Promise<{ migrated: boolean; counts: Record<string, number> }> {
  const counts: Record<string, number> = {};

  try {
    // Migrate messages
    const msgResult = await query(
      `UPDATE user_messages SET user_id = $1 WHERE user_id = $2`,
      [toUserId, fromUserId]
    );
    counts.messages = msgResult.length || 0;

    // Migrate contacts
    const contactResult = await query(
      `UPDATE user_contacts SET user_id = $1 WHERE user_id = $2`,
      [toUserId, fromUserId]
    );
    counts.contacts = contactResult.length || 0;

    // Migrate call history
    const callResult = await query(
      `UPDATE user_call_history SET user_id = $1 WHERE user_id = $2`,
      [toUserId, fromUserId]
    );
    counts.calls = callResult.length || 0;

    // Delete old user's devices instead of migrating (they're stale)
    // The new user already has their own device from authentication
    const deviceResult = await query(
      `DELETE FROM user_devices WHERE user_id = $1 RETURNING id`,
      [fromUserId]
    );
    counts.devicesDeleted = deviceResult.length || 0;

    // Optionally delete the old user record
    await query(`DELETE FROM users WHERE uid = $1`, [fromUserId]);

    console.log(`[Auth] Migrated data from ${fromUserId} to ${toUserId}:`, counts);
    return { migrated: true, counts };
  } catch (error) {
    console.error(`[Auth] Failed to migrate data from ${fromUserId} to ${toUserId}:`, error);
    return { migrated: false, counts };
  }
}

// Hash password (for recovery codes)
export async function hashPassword(password: string): Promise<string> {
  return bcrypt.hash(password, 12);
}

// Verify password
export async function verifyPassword(
  password: string,
  hash: string
): Promise<boolean> {
  return bcrypt.compare(password, hash);
}
