-- Migration: Add firebase_uid column to users table
-- Date: 2026-02-05
-- Purpose: Allow Android app to authenticate using existing Firebase UIDs

-- Add firebase_uid column if it doesn't exist
ALTER TABLE users ADD COLUMN IF NOT EXISTS firebase_uid VARCHAR(128);

-- Create index for fast Firebase UID lookups
CREATE INDEX IF NOT EXISTS idx_users_firebase_uid ON users(firebase_uid);

-- Ensure existing users have firebase_uid set to their uid (for migration)
UPDATE users SET firebase_uid = uid WHERE firebase_uid IS NULL;
