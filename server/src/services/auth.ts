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
    { expiresIn, algorithm: 'HS256' } as jwt.SignOptions
  );
}

// Verify JWT token (pinned to HS256 to prevent algorithm confusion attacks)
export function verifyToken(token: string): TokenPayload | null {
  try {
    return jwt.verify(token, config.jwt.secret, { algorithms: ['HS256'] }) as TokenPayload;
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
    signingKey?: string;
  }
): Promise<void> {
  await query(
    `INSERT INTO user_devices (id, user_id, name, device_type, fcm_token, device_signing_key, paired_at, last_seen)
     VALUES ($1, $2, $3, $4, $5, $6, NOW(), NOW())
     ON CONFLICT (id) DO UPDATE SET
       user_id = EXCLUDED.user_id,
       name = EXCLUDED.name,
       device_type = EXCLUDED.device_type,
       fcm_token = EXCLUDED.fcm_token,
       device_signing_key = COALESCE(EXCLUDED.device_signing_key, user_devices.device_signing_key),
       last_seen = NOW()`,
    [deviceId, userId, deviceInfo.name, deviceInfo.type, deviceInfo.fcmToken, deviceInfo.signingKey]
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

  // NOTE: Global orphan cleanup was removed from here — it was dangerously unscoped
  // and could delete legitimate new users who hadn't synced messages yet.
  // Orphan cleanup runs safely in the daily cleanup job with a proper grace period.

  return {
    userId: request.userId,
    deviceId: request.deviceId,
  };
}

// Get or create user by Firebase UID (device fingerprint)
// Returns { userId, migrationPerformed, existingUser }
//   - migrationPerformed: data was migrated from a legacy account
//   - existingUser: this fingerprint already had a user (i.e. reinstall, not first-time)
export async function getOrCreateUserByFirebaseUid(firebaseUid: string): Promise<{ userId: string; migrationPerformed: boolean; existingUser: boolean }> {
  // Step 1: Check if user exists with this device fingerprint
  const existing = await queryOne<{ uid: string }>(
    `SELECT uid FROM users WHERE firebase_uid = $1`,
    [firebaseUid]
  );

  if (existing) {
    console.log(`[Auth] Found existing user ${existing.uid} for device fingerprint ${firebaseUid.substring(0, 8)}...`);
    return { userId: existing.uid, migrationPerformed: false, existingUser: true };
  }

  // Step 2: Legacy/orphan user migration REMOVED.
  // Previously, Steps 2 and 3 searched globally for any legacy user (firebase_uid = uid)
  // or orphaned user (firebase_uid IS NULL) with the most messages and claimed them for
  // the new device. This was dangerously unscoped — any new device could inherit any
  // unrelated user's account and data. Use admin /auth/admin/migrate for manual migration.

  // Step 3: Create new user with a unique UUID (NOT the fingerprint as uid).
  // Previously uid = fingerprint, which caused collisions when two devices generated
  // the same fingerprint (same model + same ANDROID_ID). Now uid is always a fresh UUID,
  // and firebase_uid stores the fingerprint for lookup only.
  const userId = uuidv4();
  console.log(`[Auth] Creating new user ${userId} for device fingerprint ${firebaseUid.substring(0, 8)}...`);
  await query(
    `INSERT INTO users (uid, firebase_uid, created_at) VALUES ($1, $2, NOW())`,
    [userId, firebaseUid]
  );

  return { userId, migrationPerformed: false, existingUser: false };
}

// Migrate all data from one user to another (skips duplicates via WHERE NOT EXISTS)
export async function migrateUserData(fromUserId: string, toUserId: string): Promise<{ migrated: boolean; counts: Record<string, number> }> {
  const counts: Record<string, number> = {};

  try {
    // Helper: migrate rows from a table, skipping any with duplicate primary keys
    const migrateTable = async (table: string, idCol: string = 'id') => {
      const result = await query(
        `UPDATE ${table} SET user_id = $1 WHERE user_id = $2
         AND ${idCol} NOT IN (SELECT ${idCol} FROM ${table} WHERE user_id = $1)`,
        [toUserId, fromUserId]
      );
      return result.length || 0;
    };

    // Messages
    counts.messages = await migrateTable('user_messages');
    counts.outgoing_messages = await migrateTable('user_outgoing_messages');
    counts.spam_messages = await migrateTable('user_spam_messages');
    counts.scheduled_messages = await migrateTable('user_scheduled_messages');

    // Contacts & groups
    counts.contacts = await migrateTable('user_contacts');
    counts.groups = await migrateTable('user_groups');

    // Calls
    counts.calls = await migrateTable('user_call_history');
    counts.call_requests = await migrateTable('user_call_requests');

    // Files & photos
    counts.file_transfers = await migrateTable('user_file_transfers');
    counts.photos = await migrateTable('user_photos');
    counts.voicemails = await migrateTable('user_voicemails');

    // Notifications
    counts.notifications = await migrateTable('user_notifications');

    // Subscriptions (UNIQUE on user_id - only migrate if target doesn't have one)
    const subResult = await query(
      `UPDATE user_subscriptions SET user_id = $1 WHERE user_id = $2
       AND NOT EXISTS (SELECT 1 FROM user_subscriptions WHERE user_id = $1)`,
      [toUserId, fromUserId]
    );
    counts.subscriptions = subResult.length || 0;

    // Sync groups
    await query(
      `UPDATE sync_groups SET owner_uid = $1 WHERE owner_uid = $2
       AND id NOT IN (SELECT id FROM sync_groups WHERE owner_uid = $1)`,
      [toUserId, fromUserId]
    );

    // Phone-UID mapping
    await query(
      `UPDATE phone_uid_mapping SET uid = $1 WHERE uid = $2`,
      [toUserId, fromUserId]
    );

    // Delete old user's devices (stale from uninstalled app)
    const deviceResult = await query(
      `DELETE FROM user_devices WHERE user_id = $1 RETURNING id`,
      [fromUserId]
    );
    counts.devicesDeleted = deviceResult.length || 0;

    // Clean up any remaining old user data, then delete the old user (CASCADE handles FKs)
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
