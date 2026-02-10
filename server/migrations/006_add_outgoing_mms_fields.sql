-- Migration 006: Add MMS fields to user_outgoing_messages
-- These columns are needed for the updated POST /messages/send endpoint

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'user_outgoing_messages' AND column_name = 'is_mms'
    ) THEN
        ALTER TABLE user_outgoing_messages ADD COLUMN is_mms BOOLEAN DEFAULT FALSE;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'user_outgoing_messages' AND column_name = 'attachments'
    ) THEN
        ALTER TABLE user_outgoing_messages ADD COLUMN attachments JSONB;
    END IF;
END $$;
