ALTER TABLE sessions
  ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

UPDATE sessions
SET expires_at = COALESCE(expires_at, NOW() + INTERVAL '30 days');

ALTER TABLE sessions
  ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sessions_user_active_expires_created_token
  ON sessions(user_id, active, expires_at, created_at DESC, session_token DESC);
