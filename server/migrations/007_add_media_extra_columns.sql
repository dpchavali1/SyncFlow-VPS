-- Add missing media status columns for app name, volume, etc.
ALTER TABLE user_media_status
  ADD COLUMN IF NOT EXISTS app_name VARCHAR(255),
  ADD COLUMN IF NOT EXISTS package_name VARCHAR(255),
  ADD COLUMN IF NOT EXISTS volume INTEGER DEFAULT 0,
  ADD COLUMN IF NOT EXISTS max_volume INTEGER DEFAULT 15,
  ADD COLUMN IF NOT EXISTS has_permission BOOLEAN DEFAULT FALSE;
