CREATE TABLE chapters (
  id BIGSERIAL PRIMARY KEY,
  owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title VARCHAR(500) NOT NULL,
  parent_chapter_id BIGINT NULL REFERENCES chapters(id) ON DELETE SET NULL,
  visibility VARCHAR(20) NOT NULL DEFAULT 'private', -- private | members | public
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chapters_owner ON chapters(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_chapters_parent ON chapters(parent_chapter_id);

CREATE TABLE chapter_members (
  chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL DEFAULT 'viewer', -- viewer | editor
  invited_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (chapter_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_chapter_members_user ON chapter_members(user_id);

CREATE TABLE chapter_messages (
  chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  added_by_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (chapter_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_chapter_messages_chapter ON chapter_messages(chapter_id, added_at DESC);
CREATE INDEX IF NOT EXISTS idx_chapter_messages_message ON chapter_messages(message_id);

CREATE TABLE share_links (
  token VARCHAR(100) PRIMARY KEY,
  owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  chapter_id BIGINT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  message_id BIGINT NULL REFERENCES messages(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NULL,
  CONSTRAINT chk_share_links_target CHECK (
    (chapter_id IS NOT NULL AND message_id IS NULL) OR
    (chapter_id IS NULL AND message_id IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS idx_share_links_owner ON share_links(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_share_links_chapter ON share_links(chapter_id);
CREATE INDEX IF NOT EXISTS idx_share_links_message ON share_links(message_id);
