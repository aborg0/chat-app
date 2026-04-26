-- Migrate visibility semantics:
-- members -> individuals
UPDATE chapters
SET visibility = 'individuals'
WHERE visibility = 'members';

-- Enforce allowed visibility values.
ALTER TABLE chapters
  DROP CONSTRAINT IF EXISTS chk_chapters_visibility;

ALTER TABLE chapters
  ADD CONSTRAINT chk_chapters_visibility
  CHECK (visibility IN ('private', 'individuals', 'authenticated', 'group', 'public'));

-- A message belongs to exactly one chapter.
CREATE UNIQUE INDEX IF NOT EXISTS uq_chapter_messages_message ON chapter_messages(message_id);
