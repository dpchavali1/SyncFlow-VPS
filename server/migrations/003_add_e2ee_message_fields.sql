-- Add E2EE payload fields for VPS message sync
ALTER TABLE user_messages
    ADD COLUMN IF NOT EXISTS encrypted_body TEXT,
    ADD COLUMN IF NOT EXISTS encrypted_nonce TEXT,
    ADD COLUMN IF NOT EXISTS key_map JSONB;
