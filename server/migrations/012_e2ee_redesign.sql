-- E2EE Redesign: device signing keys, key versioning, key backup, repair rate limiting
-- Phase 1: Foundation schema changes

-- Device signing keys (per-device ECDSA P-256)
ALTER TABLE user_devices ADD COLUMN IF NOT EXISTS device_signing_key TEXT;

-- Key versioning for rotation (Phase 2, schema-ready now)
ALTER TABLE user_e2ee_keys ADD COLUMN IF NOT EXISTS key_version INTEGER DEFAULT 1;

-- Signature metadata for key exchange verification (Phase 3)
ALTER TABLE user_e2ee_keys ADD COLUMN IF NOT EXISTS signature TEXT;
ALTER TABLE user_e2ee_keys ADD COLUMN IF NOT EXISTS signing_device_id VARCHAR(128);

-- Key request expiry
ALTER TABLE e2ee_key_requests ADD COLUMN IF NOT EXISTS expires_at
  TIMESTAMP WITH TIME ZONE DEFAULT (NOW() + INTERVAL '24 hours');

-- Key backup storage (Phase 4, schema-ready now)
CREATE TABLE IF NOT EXISTS e2ee_key_backups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    encrypted_backup TEXT NOT NULL,
    salt TEXT NOT NULL,
    iterations INTEGER NOT NULL,
    key_version INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, key_version)
);

-- Repair rate limiting log
CREATE TABLE IF NOT EXISTS e2ee_repair_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(128) NOT NULL,
    device_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Clean up orphaned E2EE keys before adding FK constraints
DELETE FROM user_e2ee_keys WHERE device_id NOT IN (SELECT id FROM user_devices);
DELETE FROM e2ee_public_keys WHERE device_id IS NOT NULL
  AND device_id NOT IN (SELECT id FROM user_devices);
