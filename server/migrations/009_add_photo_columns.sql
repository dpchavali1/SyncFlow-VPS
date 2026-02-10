-- Add missing columns to user_photos
-- r2_key and file_size are referenced by admin.ts queries
-- content_type and photo_metadata are referenced by photos.ts confirm-upload

ALTER TABLE user_photos ADD COLUMN IF NOT EXISTS r2_key TEXT;
ALTER TABLE user_photos ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE user_photos ADD COLUMN IF NOT EXISTS content_type VARCHAR(100);
ALTER TABLE user_photos ADD COLUMN IF NOT EXISTS photo_metadata JSONB;
