-- Widen address column on spam messages to accommodate long sender IDs
-- Zod schema allows up to 500 chars but column was VARCHAR(50)

ALTER TABLE user_spam_messages ALTER COLUMN address TYPE VARCHAR(500);
