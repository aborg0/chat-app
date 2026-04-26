CREATE TABLE IF NOT EXISTS chapter_user_preferences (
  chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  is_important BOOLEAN NOT NULL DEFAULT FALSE,
  mute_level VARCHAR(20) NOT NULL DEFAULT 'none',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (chapter_id, user_id),
  CONSTRAINT chk_chapter_user_preferences_mute_level CHECK (mute_level IN ('none', 'soft', 'hard'))
);

CREATE INDEX IF NOT EXISTS idx_chapter_user_preferences_user_updated
  ON chapter_user_preferences(user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chapter_user_preferences_user_important_updated
  ON chapter_user_preferences(user_id, is_important, updated_at DESC);

CREATE TABLE IF NOT EXISTS message_reads (
  message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  read_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_message_reads_user_chapter_message_desc
  ON message_reads(user_id, chapter_id, message_id DESC);

CREATE INDEX IF NOT EXISTS idx_message_reads_chapter_user_message_desc
  ON message_reads(chapter_id, user_id, message_id DESC);
