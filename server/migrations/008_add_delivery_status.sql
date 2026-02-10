-- Add delivery_status column to user_messages for tracking SMS/MMS delivery confirmation
-- Values: NULL (unknown/not tracked), 'sending', 'sent', 'delivered', 'failed'
ALTER TABLE user_messages ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(20);

-- Add delivery_status to outgoing messages table as well
ALTER TABLE user_outgoing_messages ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(20);
