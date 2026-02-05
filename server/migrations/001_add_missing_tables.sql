-- Migration: Add missing tables for full feature parity
-- Date: 2026-02-05

-- =====================================================
-- DND STATUS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_dnd_status (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    enabled BOOLEAN DEFAULT FALSE,
    until_time BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- =====================================================
-- HOTSPOT STATUS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_hotspot_status (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    enabled BOOLEAN DEFAULT FALSE,
    ssid VARCHAR(255),
    connected_devices INTEGER DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- =====================================================
-- MEDIA STATUS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_media_status (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    playing BOOLEAN DEFAULT FALSE,
    title VARCHAR(500),
    artist VARCHAR(255),
    album VARCHAR(255),
    position INTEGER DEFAULT 0,
    duration INTEGER DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- =====================================================
-- TYPING INDICATORS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_typing_indicators (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    conversation_address VARCHAR(500) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    device_name VARCHAR(255),
    is_typing BOOLEAN DEFAULT TRUE,
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, conversation_address, device_id)
);

CREATE INDEX IF NOT EXISTS idx_typing_user ON user_typing_indicators(user_id, conversation_address);

-- =====================================================
-- SHARED LINKS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_shared_links (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    url TEXT NOT NULL,
    title VARCHAR(500),
    source_device VARCHAR(128),
    status VARCHAR(20) DEFAULT 'pending', -- pending, opened, dismissed
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_shared_links_user ON user_shared_links(user_id, status, timestamp DESC);

-- =====================================================
-- PHONE STATUS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_phone_status (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    battery_level INTEGER,
    is_charging BOOLEAN DEFAULT FALSE,
    signal_strength INTEGER,
    wifi_name VARCHAR(255),
    connection_type VARCHAR(50), -- wifi, mobile, none
    mobile_data_type VARCHAR(50), -- 5G, LTE, 4G, 3G, 2G
    timestamp BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- =====================================================
-- DND COMMANDS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_dnd_commands (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL, -- enable, disable, toggle
    duration_minutes INTEGER, -- optional duration for timed DND
    processed BOOLEAN DEFAULT FALSE,
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dnd_commands_user ON user_dnd_commands(user_id, processed, timestamp DESC);

-- =====================================================
-- MEDIA COMMANDS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_media_commands (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL, -- play, pause, next, previous, volume
    volume_level INTEGER, -- 0-100 for volume action
    processed BOOLEAN DEFAULT FALSE,
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_media_commands_user ON user_media_commands(user_id, processed, timestamp DESC);

-- =====================================================
-- HOTSPOT COMMANDS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_hotspot_commands (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL, -- enable, disable, toggle
    processed BOOLEAN DEFAULT FALSE,
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hotspot_commands_user ON user_hotspot_commands(user_id, processed, timestamp DESC);

-- =====================================================
-- FIND MY PHONE REQUESTS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_find_phone_requests (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL, -- ring, stop
    status VARCHAR(20) DEFAULT 'pending', -- pending, ringing, stopped
    source_device VARCHAR(128),
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_find_phone_user ON user_find_phone_requests(user_id, status, timestamp DESC);

-- =====================================================
-- CALL COMMANDS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_call_commands (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    call_id VARCHAR(128),
    command VARCHAR(50) NOT NULL, -- answer, reject, end, make_call, hold, unhold
    phone_number VARCHAR(50),
    processed BOOLEAN DEFAULT FALSE,
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_call_commands_user ON user_call_commands(user_id, processed, timestamp DESC);

-- =====================================================
-- CLIPBOARD
-- =====================================================
CREATE TABLE IF NOT EXISTS user_clipboard (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    content TEXT,
    content_type VARCHAR(50) DEFAULT 'text',
    source_device VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- =====================================================
-- CONTINUITY STATE
-- =====================================================
CREATE TABLE IF NOT EXISTS user_continuity_state (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    current_conversation VARCHAR(500),
    current_contact VARCHAR(255),
    state_data JSONB,
    timestamp BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_continuity_user ON user_continuity_state(user_id);

-- =====================================================
-- E2EE PUBLIC KEYS
-- =====================================================
CREATE TABLE IF NOT EXISTS e2ee_public_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uid VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    device_id VARCHAR(128),
    public_key TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(uid, device_id)
);

CREATE INDEX IF NOT EXISTS idx_e2ee_public_keys_user ON e2ee_public_keys(uid);

-- =====================================================
-- USER E2EE KEYS (Device-specific encrypted keys)
-- =====================================================
CREATE TABLE IF NOT EXISTS user_e2ee_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    encrypted_key TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_user_e2ee_keys_user ON user_e2ee_keys(user_id);

-- =====================================================
-- E2EE KEY REQUESTS
-- =====================================================
CREATE TABLE IF NOT EXISTS e2ee_key_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    requesting_device VARCHAR(128),
    target_device VARCHAR(128),
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_e2ee_key_requests_user ON e2ee_key_requests(user_id, target_device, status);

-- =====================================================
-- E2EE KEY RESPONSES
-- =====================================================
CREATE TABLE IF NOT EXISTS e2ee_key_responses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    request_id UUID REFERENCES e2ee_key_requests(id) ON DELETE CASCADE,
    encrypted_key TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_e2ee_key_responses_request ON e2ee_key_responses(request_id);

-- =====================================================
-- NOTIFICATIONS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_notifications (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    app_package VARCHAR(255),
    app_name VARCHAR(255),
    title VARCHAR(1000),
    body TEXT,
    timestamp BIGINT,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user ON user_notifications(user_id, timestamp DESC);

-- =====================================================
-- VOICEMAILS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_voicemails (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50),
    duration INTEGER,
    storage_url TEXT,
    transcription TEXT,
    date BIGINT,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_voicemails_user ON user_voicemails(user_id, date DESC);

-- =====================================================
-- SCHEDULED MESSAGES
-- =====================================================
CREATE TABLE IF NOT EXISTS user_scheduled_messages (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    address VARCHAR(500) NOT NULL,
    recipient_name VARCHAR(255),
    body TEXT NOT NULL,
    scheduled_time BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    sim_subscription_id INTEGER,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scheduled_messages_user ON user_scheduled_messages(user_id, status, scheduled_time);

-- =====================================================
-- READ RECEIPTS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_read_receipts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    message_id VARCHAR(128),
    message_key VARCHAR(255),
    device_id VARCHAR(128),
    conversation_address VARCHAR(500),
    read_device_name VARCHAR(255),
    read_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_read_receipts_user ON user_read_receipts(user_id, read_at DESC);

-- =====================================================
-- FILE TRANSFERS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_file_transfers (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    file_name VARCHAR(500) NOT NULL,
    file_size BIGINT,
    r2_key VARCHAR(500),
    content_type VARCHAR(100),
    source_device VARCHAR(128),
    status VARCHAR(20) DEFAULT 'pending',
    download_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_file_transfers_user ON user_file_transfers(user_id, status, created_at DESC);

-- =====================================================
-- PHOTOS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_photos (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    file_name VARCHAR(500),
    storage_url TEXT,
    r2_key VARCHAR(500),
    file_size BIGINT,
    content_type VARCHAR(100),
    photo_metadata JSONB,
    taken_at BIGINT,
    synced_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_photos_user ON user_photos(user_id, taken_at DESC);

-- =====================================================
-- SPAM MESSAGES
-- =====================================================
CREATE TABLE IF NOT EXISTS user_spam_messages (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    address VARCHAR(500),
    body TEXT,
    date BIGINT,
    spam_score DECIMAL(3, 2),
    spam_reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_spam_messages_user ON user_spam_messages(user_id, date DESC);

-- =====================================================
-- SPAM LISTS (whitelist/blocklist)
-- =====================================================
CREATE TABLE IF NOT EXISTS user_spam_lists (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50) NOT NULL,
    list_type VARCHAR(20) NOT NULL, -- whitelist, blocklist
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, phone_number, list_type)
);

CREATE INDEX IF NOT EXISTS idx_spam_lists_user ON user_spam_lists(user_id, list_type);

-- =====================================================
-- ACTIVE CALLS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_active_calls (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50),
    contact_name VARCHAR(255),
    call_state VARCHAR(50),
    call_type VARCHAR(50),
    started_at BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_active_calls_user ON user_active_calls(user_id, call_state);

-- =====================================================
-- USER USAGE
-- =====================================================
CREATE TABLE IF NOT EXISTS user_usage (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    storage_bytes BIGINT DEFAULT 0,
    bandwidth_bytes_month BIGINT DEFAULT 0,
    message_count INTEGER DEFAULT 0,
    last_reset TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- =====================================================
-- USER SUBSCRIPTIONS
-- =====================================================
CREATE TABLE IF NOT EXISTS user_subscriptions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    plan VARCHAR(50) DEFAULT 'free',
    status VARCHAR(20) DEFAULT 'active',
    started_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    stripe_customer_id VARCHAR(128),
    stripe_subscription_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- =====================================================
-- PHONE REGISTRY (for video calling lookup)
-- =====================================================
CREATE TABLE IF NOT EXISTS user_phone_registry (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50) NOT NULL,
    registered_at BIGINT,
    UNIQUE(user_id),
    UNIQUE(phone_number)
);

CREATE INDEX IF NOT EXISTS idx_phone_registry_number ON user_phone_registry(phone_number);

-- =====================================================
-- CALL REQUESTS (desktop-initiated calls)
-- =====================================================
CREATE TABLE IF NOT EXISTS user_call_requests (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    sim_subscription_id INTEGER,
    requested_at BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_call_requests_user ON user_call_requests(user_id, status, requested_at DESC);

-- =====================================================
-- UPDATE EXISTING TABLES (only if they already exist)
-- =====================================================

-- Extend address columns to 500 chars for group MMS (for tables that might already exist)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'user_messages') THEN
        ALTER TABLE user_messages ALTER COLUMN address TYPE VARCHAR(500);
    END IF;
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'user_outgoing_messages') THEN
        ALTER TABLE user_outgoing_messages ALTER COLUMN address TYPE VARCHAR(500);
    END IF;
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

-- =====================================================
-- REAL-TIME TRIGGERS FOR NEW TABLES
-- =====================================================

-- Create notify_change function if it doesn't exist
CREATE OR REPLACE FUNCTION notify_change() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('data_changes',
        json_build_object(
            'table', TG_TABLE_NAME,
            'operation', TG_OP,
            'user_id', COALESCE(NEW.user_id, OLD.user_id)
        )::text
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- Drop and recreate triggers to avoid errors on re-run
DROP TRIGGER IF EXISTS typing_notify ON user_typing_indicators;
CREATE TRIGGER typing_notify AFTER INSERT OR UPDATE OR DELETE ON user_typing_indicators
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS shared_links_notify ON user_shared_links;
CREATE TRIGGER shared_links_notify AFTER INSERT OR UPDATE OR DELETE ON user_shared_links
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS phone_status_notify ON user_phone_status;
CREATE TRIGGER phone_status_notify AFTER INSERT OR UPDATE ON user_phone_status
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS dnd_commands_notify ON user_dnd_commands;
CREATE TRIGGER dnd_commands_notify AFTER INSERT OR UPDATE ON user_dnd_commands
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS media_commands_notify ON user_media_commands;
CREATE TRIGGER media_commands_notify AFTER INSERT OR UPDATE ON user_media_commands
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS hotspot_commands_notify ON user_hotspot_commands;
CREATE TRIGGER hotspot_commands_notify AFTER INSERT OR UPDATE ON user_hotspot_commands
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS find_phone_notify ON user_find_phone_requests;
CREATE TRIGGER find_phone_notify AFTER INSERT OR UPDATE ON user_find_phone_requests
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS call_commands_notify ON user_call_commands;
CREATE TRIGGER call_commands_notify AFTER INSERT OR UPDATE ON user_call_commands
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS dnd_status_notify ON user_dnd_status;
CREATE TRIGGER dnd_status_notify AFTER INSERT OR UPDATE ON user_dnd_status
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS hotspot_status_notify ON user_hotspot_status;
CREATE TRIGGER hotspot_status_notify AFTER INSERT OR UPDATE ON user_hotspot_status
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS media_status_notify ON user_media_status;
CREATE TRIGGER media_status_notify AFTER INSERT OR UPDATE ON user_media_status
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS clipboard_notify ON user_clipboard;
CREATE TRIGGER clipboard_notify AFTER INSERT OR UPDATE ON user_clipboard
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS continuity_notify ON user_continuity_state;
CREATE TRIGGER continuity_notify AFTER INSERT OR UPDATE ON user_continuity_state
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS notifications_notify ON user_notifications;
CREATE TRIGGER notifications_notify AFTER INSERT OR UPDATE ON user_notifications
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS voicemails_notify ON user_voicemails;
CREATE TRIGGER voicemails_notify AFTER INSERT OR UPDATE ON user_voicemails
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS file_transfers_notify ON user_file_transfers;
CREATE TRIGGER file_transfers_notify AFTER INSERT OR UPDATE ON user_file_transfers
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS scheduled_messages_notify ON user_scheduled_messages;
CREATE TRIGGER scheduled_messages_notify AFTER INSERT OR UPDATE ON user_scheduled_messages
FOR EACH ROW EXECUTE FUNCTION notify_change();

DROP TRIGGER IF EXISTS active_calls_notify ON user_active_calls;
CREATE TRIGGER active_calls_notify AFTER INSERT OR UPDATE ON user_active_calls
FOR EACH ROW EXECUTE FUNCTION notify_change();
