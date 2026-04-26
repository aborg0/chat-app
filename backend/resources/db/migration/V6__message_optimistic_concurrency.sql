ALTER TABLE messages
  ADD COLUMN version INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN client_edited_at TIMESTAMPTZ NULL;

CREATE INDEX idx_messages_id_version ON messages(id, version);
CREATE INDEX idx_messages_updated_version ON messages(updated_at DESC, version DESC);
