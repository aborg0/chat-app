# Backend Audit (2026-04-26)

## Scope

This document captures the current backend state before UI restructuring, with focus on:
- API availability
- Service/module wiring
- Database schema and index coverage
- Gaps vs. expected functionality
- Proposal for chapter importance, mute/soft-mute, and message read/unread tracking

## Runtime and Layer Wiring

The backend uses ZIO environment wiring via layers:
- Database layer in `Database.layer(...)`
- Service layers in Auth/Sessions/Messaging/Chapters/Groups modules
- Route environment in `ApiRoutes.AppEnv`

Main startup composes routes with:
- `Database.layer`
- `SessionsModule.layer`
- `AuthModule.layer`
- `MessagingModule.layer`
- `ChaptersModule.layer`
- `GroupsModule.layer`

## API Surface (Current)

### Auth and Sessions
- POST /auth/register
- POST /auth/login
- POST /auth/social-login
- GET /sessions
- POST /sessions/logout-others

### Messaging and Admin Audit
- POST /messages
- GET /messages/search
- GET /messages/by-id
- PUT /messages/by-id
- DELETE /messages/by-id
- GET /messages/history
- POST /messages/share-link
- GET /admin/audit

### Chapters
- POST /chapters
- GET /chapters
- GET /chapters/{id}
- DELETE /chapters/{id}
- PUT /chapters/{id}/visibility
- POST /chapters/{id}/members
- DELETE /chapters/{id}/members/{userId}
- POST /chapters/{id}/messages
- DELETE /chapters/{id}/messages/{messageId}
- POST /chapters/{id}/share-link
- POST /chapters/{id}/group-access
- DELETE /chapters/{id}/group-access/{groupId}
- GET /chapters/{id}/group-access

### Groups
- POST /groups
- GET /groups
- DELETE /groups/{id}
- POST /groups/{id}/members
- DELETE /groups/{id}/members/{userId}
- GET /groups/{id}/members

### Public
- GET /share/{token}
- GET /health

## Availability Check (What Works vs Gaps)

### Implemented and available in code
- Password registration/login and social login
- Multi-session listing + logout other sessions
- Message CRUD-like workflow (create/search/get/edit/history/delete)
- Admin audit read/write events for cross-user message access
- Chapters and groups services, routes, and shared DTOs

### Important gaps or inconsistencies
1. Migration execution does not match codebase migrations:
   - Runtime `Migrations.scala` executes only `db/migration/V1__auth_sessions.sql`.
   - Running local compose DB currently contains only V1 tables (users/sessions/messages/etc.), no chapter/group tables.
   - This means chapter/group endpoints are not actually available in a fresh runtime database unless schema is created externally.

2. Chapter visibility mismatch:
   - DB constraint allows `group` visibility.
   - `ChaptersModule.updateVisibility` currently allows only: private, individuals, authenticated, public.
   - Result: route-level request to set `group` visibility fails validation.

3. Permission check gap in group access listing:
   - `GroupsModule.listChapterGroupAccess` does not enforce read/owner permission on chapter before listing group IDs.

4. Placeholder modules:
   - `NotificationsModule` and `ObservabilityModule` are currently no-op placeholders (`ZIO.unit`).

5. README feature overstatement:
   - README advertises OAuth, SAML, Passkeys, notifications. Current backend exposes password + social login only; notifications are not implemented.

## Database Index Audit

## Expected indexes from migration files

### V1
- sessions: idx_sessions_user_active, idx_sessions_user_active_created_token
- messages: idx_messages_author_created, idx_messages_author_id_desc, idx_messages_content_trgm
- message_edits: idx_message_edits_message_version
- audit_log: idx_audit_log_actor_created, idx_audit_log_target_created, idx_audit_log_target_id_desc, idx_audit_log_message_id_desc, idx_audit_log_target_message_id_desc

### V2/V3/V4 (should exist when fully migrated)
- chapters: idx_chapters_owner, idx_chapters_parent
- chapter_members: idx_chapter_members_user
- chapter_messages: idx_chapter_messages_chapter, idx_chapter_messages_message, uq_chapter_messages_message
- share_links: idx_share_links_owner, idx_share_links_chapter, idx_share_links_message
- groups: idx_groups_owner
- group_members: idx_group_members_user
- chapter_group_access: idx_chapter_group_access_chapter, idx_chapter_group_access_group

## Runtime DB check (compose instance)

Observed present indexes (runtime):
- Only V1-related indexes are present.
- No chapter/group/share-link indexes are present in the running compose database.

Observed usage counters (`pg_stat_user_indexes`) in current runtime are near-zero except:
- sessions_pkey and users indexes have scans
- idx_sessions_user_active_created_token has scans

Note: near-zero counters are expected in low-traffic/dev startup and do not prove an index is globally unused.

## Static query-to-index fit analysis

Likely useful:
- `idx_sessions_user_active_created_token` (matches session pagination query)
- `idx_messages_author_id_desc` (matches message search by author + id-desc cursor)
- `idx_messages_content_trgm` (supports ILIKE search)
- `idx_message_edits_message_version` (message history)
- `idx_audit_log_target_id_desc`, `idx_audit_log_message_id_desc`, `idx_audit_log_target_message_id_desc` (audit filters)

Possibly redundant or low-value (verify with production stats before dropping):
- `idx_sessions_user_active` appears covered by prefix of `idx_sessions_user_active_created_token`
- `idx_messages_author_created` not aligned with current ORDER BY/id-cursor usage
- `idx_audit_log_actor_created` currently has no matching read path
- `idx_audit_log_target_created` may be superseded by id-desc indexes used in cursor pagination

Recommendation: do not remove indexes solely from dev stats. Confirm with sustained workload and `EXPLAIN (ANALYZE, BUFFERS)` for real queries.

## Proposed Backend Additions for Requested Product Features

User request summary:
- Mark chapters as important/liked
- Mute chapter: no unread indicator and no notifications
- Soft-mute chapter: keep unread count, suppress notifications
- Track message read state per user
- Allow unread from a chosen message onward

### Data model proposal

1. `chapter_user_preferences`
- `chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE`
- `user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `importance SMALLINT NOT NULL DEFAULT 0` (0=normal, 1=important/favorite)
- `mute_level VARCHAR(20) NOT NULL DEFAULT 'none'` (`none|soft|hard`)
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- Primary key: `(chapter_id, user_id)`
- Index: `(user_id, importance, updated_at DESC)`

2. `message_reads`
- `message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE`
- `chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE`
- `user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `read_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- Primary key: `(message_id, user_id)`
- Indexes:
  - `(user_id, chapter_id, message_id DESC)`
  - `(chapter_id, user_id, message_id DESC)`

3. Optional optimization table for unread counters:
- `chapter_user_read_state(chapter_id, user_id, last_read_message_id, updated_at)`
- Useful if unread counts must be very fast at scale.

### API proposal

Chapter preferences:
- PUT /chapters/{id}/preferences
  - payload: `{ importance: "normal|important", muteLevel: "none|soft|hard" }`
- GET /chapters/{id}/preferences
- GET /chapters/preferences (all for current user)

Read/unread:
- POST /chapters/{id}/messages/{messageId}/read
- POST /chapters/{id}/messages/{messageId}/unread-from
  - semantics: mark this message and newer as unread for current user
- GET /chapters/{id}/unread-count
- Optional bulk endpoint for list views:
  - POST /chapters/unread-counts

Notification behavior:
- `muteLevel=hard`: suppress notifications and unread badge count
- `muteLevel=soft`: suppress notifications, keep unread badge count
- `muteLevel=none`: normal behavior

### Service changes

- Add `ChapterPreferencesService` (or extend `ChaptersService`) for preference upsert/query.
- Add `ReadStateService` for mark-read/unread-from/unread-count.
- Include mute-level checks in notification fanout path.
- Ensure chapter access checks are applied to preference/read-state endpoints.

### Index and query notes for new features

- Unread count query should use chapter and user key plus message_id cursor to avoid table scans.
- Keep message ordering monotonic via `message_id` for unread-from semantics.
- Validate with `EXPLAIN (ANALYZE, BUFFERS)` before finalizing index set.

## Suggested Next Steps (Before UI Restructure)

1. Fix migration runner to execute all migration files (V1..V4 and onward) in deterministic order, preferably via Flyway.
2. Add integration tests for chapters/groups endpoints against migrated schema.
3. Fix chapter visibility validation to include `group`.
4. Add authorization guard for `listChapterGroupAccess`.
5. Align README claims with implemented auth/notifications features.
6. Implement preferences + read-state schema/API first, then reshape UI to Slack-like experience based on these APIs.
