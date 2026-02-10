-- SyncFlow VPS Database Schema
-- PostgreSQL 16

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =====================================================
-- CORE TABLES
-- =====================================================

-- Users table
CREATE TABLE users (
    uid VARCHAR(128) PRIMARY KEY,
    phone VARCHAR(20),
    email VARCHAR(255),
    display_name VARCHAR(255),
    deletion_requested_at BIGINT, -- epoch ms, NULL = not requested
    deletion_reason TEXT,
    deletion_scheduled_for BIGINT, -- epoch ms, 30 days after request
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_email ON users(email);

-- User profiles
CREATE TABLE user_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone VARCHAR(20),
    email VARCHAR(255),
    display_name VARCHAR(255),
    avatar_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- User devices
CREATE TABLE user_devices (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    name VARCHAR(255),
    device_type VARCHAR(50), -- 'android', 'macos', 'web'
    model VARCHAR(255),
    os_version VARCHAR(50),
    app_version VARCHAR(50),
    fcm_token TEXT,
    paired_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_seen TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    is_active BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_user_devices_user ON user_devices(user_id);

-- User SIMs
CREATE TABLE user_sims (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    subscription_id INTEGER,
    display_name VARCHAR(255),
    carrier_name VARCHAR(255),
    phone_number VARCHAR(20),
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_user_sims_user ON user_sims(user_id);

-- =====================================================
-- MESSAGING TABLES
-- =====================================================

-- User messages
CREATE TABLE user_messages (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    thread_id INTEGER,
    address VARCHAR(50) NOT NULL,
    contact_name VARCHAR(255),
    body TEXT,
    date BIGINT NOT NULL, -- Unix timestamp in milliseconds
    type INTEGER, -- 1=received, 2=sent
    read BOOLEAN DEFAULT FALSE,
    is_mms BOOLEAN DEFAULT FALSE,
    mms_parts JSONB, -- For MMS attachments
    sim_subscription_id INTEGER,
    encrypted BOOLEAN DEFAULT FALSE,
    encrypted_body TEXT,
    encrypted_nonce TEXT,
    key_map JSONB,
    e2ee_device_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_user_messages_user_date ON user_messages(user_id, date DESC);
CREATE INDEX idx_user_messages_user_thread ON user_messages(user_id, thread_id);
CREATE INDEX idx_user_messages_address ON user_messages(user_id, address);

-- Outgoing messages (queued to send)
CREATE TABLE user_outgoing_messages (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    address VARCHAR(500) NOT NULL,
    body TEXT,
    timestamp BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'pending', -- pending, sent, failed
    sim_subscription_id INTEGER,
    is_mms BOOLEAN DEFAULT FALSE,
    attachments JSONB,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_outgoing_user_status ON user_outgoing_messages(user_id, status, timestamp);

-- Spam messages
CREATE TABLE user_spam_messages (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    address VARCHAR(500) NOT NULL,
    body TEXT,
    date BIGINT NOT NULL,
    spam_score FLOAT,
    spam_reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_spam_user_date ON user_spam_messages(user_id, date DESC);

-- Scheduled messages
CREATE TABLE user_scheduled_messages (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    address VARCHAR(50) NOT NULL,
    body TEXT,
    scheduled_time BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'pending', -- pending, sent, cancelled
    sim_subscription_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_scheduled_user_time ON user_scheduled_messages(user_id, scheduled_time, status);

-- Message reactions
CREATE TABLE user_message_reactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    message_id VARCHAR(128) NOT NULL,
    device_id VARCHAR(128),
    reaction VARCHAR(10), -- emoji
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_reactions_message ON user_message_reactions(user_id, message_id);

-- Read receipts
CREATE TABLE user_read_receipts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    message_id VARCHAR(128) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_read_receipts ON user_read_receipts(user_id, message_id);

-- =====================================================
-- CONTACT TABLES
-- =====================================================

-- User contacts
CREATE TABLE user_contacts (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    display_name VARCHAR(255),
    phone_numbers JSONB, -- Array of phone numbers
    emails JSONB, -- Array of emails
    photo_uri TEXT,
    photo_thumbnail TEXT, -- Base64 thumbnail
    starred BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_contacts_user_name ON user_contacts(user_id, display_name);

-- User groups (group messaging)
CREATE TABLE user_groups (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    name VARCHAR(255),
    members JSONB, -- Array of member addresses
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_groups_user ON user_groups(user_id);

-- Spam whitelist/blocklist
CREATE TABLE user_spam_lists (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50) NOT NULL,
    list_type VARCHAR(20) NOT NULL, -- 'whitelist' or 'blocklist'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, phone_number, list_type)
);

CREATE INDEX idx_spam_lists_user ON user_spam_lists(user_id, list_type);

-- =====================================================
-- CALL TABLES
-- =====================================================

-- Call history
CREATE TABLE user_call_history (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50) NOT NULL,
    contact_name VARCHAR(255),
    call_type VARCHAR(20), -- incoming, outgoing, missed, rejected, blocked, voicemail
    call_date BIGINT NOT NULL,
    duration INTEGER DEFAULT 0, -- seconds
    sim_subscription_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_call_history_user_date ON user_call_history(user_id, call_date DESC);
CREATE INDEX idx_call_history_phone ON user_call_history(user_id, phone_number);

-- Call requests (to initiate calls from desktop)
CREATE TABLE user_call_requests (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending', -- pending, dialing, completed, failed
    requested_at BIGINT NOT NULL,
    sim_subscription_id INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_call_requests_user ON user_call_requests(user_id, status);

-- Active calls (current call state)
CREATE TABLE user_active_calls (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50),
    call_state VARCHAR(20), -- ringing, active, holding, ended
    call_type VARCHAR(20), -- incoming, outgoing
    started_at BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_active_calls_user ON user_active_calls(user_id);

-- SyncFlow calls (WebRTC calls between devices)
CREATE TABLE user_syncflow_calls (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    caller_id VARCHAR(128),
    callee_id VARCHAR(128),
    status VARCHAR(20), -- pending, ringing, active, ended
    started_at BIGINT,
    ended_at BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_syncflow_calls_user ON user_syncflow_calls(user_id, status);

-- WebRTC signaling
CREATE TABLE user_webrtc_signaling (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    call_id VARCHAR(128) NOT NULL,
    signal_type VARCHAR(20), -- offer, answer, ice-candidate
    signal_data JSONB,
    from_device VARCHAR(128),
    to_device VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_webrtc_call ON user_webrtc_signaling(user_id, call_id);

-- Phone number registry for video calling
CREATE TABLE user_phone_registry (
    user_id VARCHAR(128) PRIMARY KEY REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50) NOT NULL,
    registered_at BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_phone_registry_phone ON user_phone_registry(phone_number);

-- =====================================================
-- E2EE TABLES
-- =====================================================

-- User E2EE key backups (private keys, encrypted)
CREATE TABLE user_e2ee_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    encrypted_key TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, device_id)
);

-- Public E2EE keys (global, for other users to encrypt to)
CREATE TABLE e2ee_public_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uid VARCHAR(128) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    public_key TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(uid, device_id)
);

CREATE INDEX idx_e2ee_public_uid ON e2ee_public_keys(uid);

-- E2EE key exchange requests
CREATE TABLE e2ee_key_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    requesting_device VARCHAR(128) NOT NULL,
    target_device VARCHAR(128) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_e2ee_requests_user ON e2ee_key_requests(user_id, status);

-- E2EE key exchange responses
CREATE TABLE e2ee_key_responses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    request_id UUID REFERENCES e2ee_key_requests(id),
    encrypted_key TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- =====================================================
-- SYNC & FILE TABLES
-- =====================================================

-- File transfers
CREATE TABLE user_file_transfers (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    file_name VARCHAR(255),
    file_size BIGINT,
    file_type VARCHAR(100),
    storage_url TEXT,
    status VARCHAR(20) DEFAULT 'pending', -- pending, uploading, completed, failed
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_file_transfers_user ON user_file_transfers(user_id, timestamp DESC);

-- Photos
CREATE TABLE user_photos (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    file_name VARCHAR(255),
    storage_url TEXT,
    r2_key TEXT,
    file_size BIGINT,
    content_type VARCHAR(100),
    photo_metadata JSONB,
    thumbnail TEXT, -- Base64 thumbnail
    taken_at BIGINT,
    synced_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_photos_user ON user_photos(user_id, taken_at DESC);

-- Voicemails
CREATE TABLE user_voicemails (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    phone_number VARCHAR(50),
    duration INTEGER,
    storage_url TEXT,
    transcription TEXT,
    date BIGINT NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_voicemails_user ON user_voicemails(user_id, date DESC);

-- Clipboard sync
CREATE TABLE user_clipboard (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    content TEXT,
    content_type VARCHAR(50), -- text, image, file
    source_device VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- Continuity state (current context per device)
CREATE TABLE user_continuity_state (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    current_conversation VARCHAR(128),
    current_contact VARCHAR(128),
    state_data JSONB,
    timestamp BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, device_id)
);

-- =====================================================
-- NOTIFICATION & STATUS TABLES
-- =====================================================

-- Mirrored notifications
CREATE TABLE user_notifications (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    app_package VARCHAR(255),
    app_name VARCHAR(255),
    title TEXT,
    body TEXT,
    timestamp BIGINT NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_notifications_user ON user_notifications(user_id, timestamp DESC);

-- DND status
CREATE TABLE user_dnd_status (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    enabled BOOLEAN DEFAULT FALSE,
    until_time BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- Hotspot status
CREATE TABLE user_hotspot_status (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    enabled BOOLEAN DEFAULT FALSE,
    ssid VARCHAR(255),
    connected_devices INTEGER DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- Media status
CREATE TABLE user_media_status (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    playing BOOLEAN DEFAULT FALSE,
    title VARCHAR(255),
    artist VARCHAR(255),
    album VARCHAR(255),
    app_name VARCHAR(255),
    package_name VARCHAR(255),
    volume INTEGER DEFAULT 0,
    max_volume INTEGER DEFAULT 15,
    has_permission BOOLEAN DEFAULT FALSE,
    position INTEGER,
    duration INTEGER,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- FCM tokens
CREATE TABLE fcm_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uid VARCHAR(128) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    token TEXT NOT NULL,
    platform VARCHAR(20), -- android, ios, web
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(uid, device_id)
);

CREATE INDEX idx_fcm_uid ON fcm_tokens(uid);

-- =====================================================
-- GLOBAL TABLES
-- =====================================================

-- Recovery codes
CREATE TABLE recovery_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code_hash VARCHAR(128) NOT NULL UNIQUE,
    user_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    used_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_recovery_user ON recovery_codes(user_id);

-- Phone to UID mapping
CREATE TABLE phone_uid_mapping (
    phone_number VARCHAR(50) PRIMARY KEY,
    uid VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Pairing requests
CREATE TABLE pairing_requests (
    token VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128),
    device_id VARCHAR(128),
    device_name VARCHAR(255),
    device_type VARCHAR(50),
    status VARCHAR(20) DEFAULT 'pending', -- pending, completed, expired
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_pairing_expires ON pairing_requests(expires_at);

-- Sync groups
CREATE TABLE sync_groups (
    id VARCHAR(128) PRIMARY KEY,
    owner_uid VARCHAR(128) NOT NULL,
    plan VARCHAR(50) DEFAULT 'free',
    device_limit INTEGER DEFAULT 2,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Crash reports
CREATE TABLE crash_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uid VARCHAR(128),
    device_id VARCHAR(128),
    error_message TEXT,
    stack_trace TEXT,
    app_version VARCHAR(50),
    os_version VARCHAR(50),
    device_model VARCHAR(255),
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_crashes_uid ON crash_reports(uid, timestamp DESC);

-- Deleted accounts (audit trail)
CREATE TABLE deleted_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    deletion_reason VARCHAR(255),
    deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    deleted_by VARCHAR(128) -- admin uid if admin-deleted
);

CREATE INDEX idx_deleted_user ON deleted_accounts(user_id);

-- User subscriptions
CREATE TABLE user_subscriptions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    plan VARCHAR(50) DEFAULT 'free',
    status VARCHAR(20) DEFAULT 'active',
    started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE,
    stripe_customer_id VARCHAR(128),
    stripe_subscription_id VARCHAR(128),
    UNIQUE(user_id)
);

-- User usage tracking
CREATE TABLE user_usage (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    storage_bytes BIGINT DEFAULT 0,
    bandwidth_bytes_month BIGINT DEFAULT 0,
    message_count INTEGER DEFAULT 0,
    last_reset TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- =====================================================
-- REAL-TIME TRIGGERS
-- =====================================================

-- Generic notify function
CREATE OR REPLACE FUNCTION notify_change()
RETURNS TRIGGER AS $$
DECLARE
    channel_name TEXT;
    payload JSONB;
BEGIN
    channel_name := TG_TABLE_NAME || '_' || COALESCE(NEW.user_id, OLD.user_id);

    IF TG_OP = 'DELETE' THEN
        payload := jsonb_build_object(
            'action', 'deleted',
            'id', OLD.id
        );
    ELSE
        payload := jsonb_build_object(
            'action', LOWER(TG_OP),
            'id', NEW.id,
            'data', row_to_json(NEW)
        );
    END IF;

    PERFORM pg_notify(channel_name, payload::text);

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    ELSE
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Apply triggers to real-time tables
CREATE TRIGGER messages_notify AFTER INSERT OR UPDATE OR DELETE ON user_messages
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER contacts_notify AFTER INSERT OR UPDATE OR DELETE ON user_contacts
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER call_history_notify AFTER INSERT OR UPDATE OR DELETE ON user_call_history
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER notifications_notify AFTER INSERT OR UPDATE OR DELETE ON user_notifications
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER devices_notify AFTER INSERT OR UPDATE OR DELETE ON user_devices
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER e2ee_keys_notify AFTER INSERT OR UPDATE OR DELETE ON user_e2ee_keys
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER outgoing_messages_notify AFTER INSERT OR UPDATE OR DELETE ON user_outgoing_messages
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER active_calls_notify AFTER INSERT OR UPDATE OR DELETE ON user_active_calls
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER webrtc_signaling_notify AFTER INSERT OR UPDATE ON user_webrtc_signaling
FOR EACH ROW EXECUTE FUNCTION notify_change();

CREATE TRIGGER clipboard_notify AFTER INSERT OR UPDATE ON user_clipboard
FOR EACH ROW EXECUTE FUNCTION notify_change();

-- =====================================================
-- HELPER FUNCTIONS
-- =====================================================

-- Function to clean up expired pairing requests
CREATE OR REPLACE FUNCTION cleanup_expired_pairings()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM pairing_requests WHERE expires_at < NOW();
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply updated_at triggers
CREATE TRIGGER users_updated_at BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER user_profiles_updated_at BEFORE UPDATE ON user_profiles
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER user_messages_updated_at BEFORE UPDATE ON user_messages
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER user_contacts_updated_at BEFORE UPDATE ON user_contacts
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- =====================================================
-- GRANTS (run after creating syncflow user)
-- =====================================================

-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO syncflow;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO syncflow;
-- GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO syncflow;
