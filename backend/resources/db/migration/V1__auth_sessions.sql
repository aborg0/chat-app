CREATE TABLE IF NOT EXISTS users (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(200) NOT NULL UNIQUE,
  password_hash TEXT NULL,
  is_admin BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS auth_identities (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider VARCHAR(100) NOT NULL,
  provider_user_id VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_auth_identity UNIQUE (provider, provider_user_id)
);

CREATE TABLE IF NOT EXISTS sessions (
  session_token VARCHAR(200) PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id VARCHAR(200) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_active ON sessions(user_id, active);
CREATE INDEX IF NOT EXISTS idx_sessions_user_active_created_token ON sessions(user_id, active, created_at DESC, session_token DESC);

CREATE TABLE IF NOT EXISTS messages (
  id BIGSERIAL PRIMARY KEY,
  author_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  content TEXT NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_messages_author_created ON messages(author_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_author_id_desc ON messages(author_user_id, id DESC);

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_messages_content_trgm ON messages USING gin (content gin_trgm_ops);

CREATE TABLE IF NOT EXISTS message_edits (
  id BIGSERIAL PRIMARY KEY,
  message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  previous_content TEXT NOT NULL,
  new_content TEXT NOT NULL,
  edited_by_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  edited_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_message_edits_message_version ON message_edits(message_id, version);

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGSERIAL PRIMARY KEY,
  actor_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  action VARCHAR(120) NOT NULL,
  target_user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
  message_id BIGINT NULL REFERENCES messages(id) ON DELETE SET NULL,
  details TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_actor_created ON audit_log(actor_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_target_created ON audit_log(target_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_target_id_desc ON audit_log(target_user_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_message_id_desc ON audit_log(message_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_target_message_id_desc ON audit_log(target_user_id, message_id, id DESC);
