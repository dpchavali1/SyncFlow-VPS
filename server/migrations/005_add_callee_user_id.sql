-- Add callee_user_id to user_syncflow_calls for cross-user call routing
ALTER TABLE user_syncflow_calls ADD COLUMN IF NOT EXISTS callee_user_id VARCHAR(128);
CREATE INDEX IF NOT EXISTS idx_syncflow_calls_callee ON user_syncflow_calls(callee_user_id, status);
