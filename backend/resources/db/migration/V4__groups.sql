-- User-defined groups for group-based chapter visibility
CREATE TABLE groups (
  id BIGSERIAL PRIMARY KEY,
  owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_groups_owner ON groups(owner_user_id);

CREATE TABLE group_members (
  group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (group_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id);

-- Junction table: which groups have access to a chapter (used when visibility = 'group')
CREATE TABLE chapter_group_access (
  chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  PRIMARY KEY (chapter_id, group_id)
);

CREATE INDEX IF NOT EXISTS idx_chapter_group_access_chapter ON chapter_group_access(chapter_id);
CREATE INDEX IF NOT EXISTS idx_chapter_group_access_group ON chapter_group_access(group_id);

-- Add 'group' as valid visibility value
ALTER TABLE chapters
  DROP CONSTRAINT IF EXISTS chk_chapters_visibility;

ALTER TABLE chapters
  ADD CONSTRAINT chk_chapters_visibility
  CHECK (visibility IN ('private', 'individuals', 'authenticated', 'group', 'public'));
