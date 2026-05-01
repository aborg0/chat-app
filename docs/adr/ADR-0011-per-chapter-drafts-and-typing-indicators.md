# ADR-0011: Per-Chapter Drafts and Real-Time Typing Indicators

## Status

Accepted

## Context

Users need to compose messages across multiple chapters without losing their work. Simultaneously, users want visibility into when others are actively typing in a chapter. The current architecture lacks:

1. **Multi-chapter draft support**: Users working on messages in different chapters cannot maintain independent drafts; switching chapters loses uncommitted text.
2. **Typing presence**: Users have no signal when another user is composing a message in the current chapter.
3. **Real-time collaboration affordances**: Without typing indicators, users may not know if they should wait before sending their own message.

The offline message support (ADR-0004) already allows one pending offline draft per chapter, but this is not leveraged for online composition and does not handle concurrent typing visibility.

## Decision

The system implements two complementary features:

### Per-Chapter Drafts

- **One draft per chapter per user, enforced**: Users may maintain at most one unsent draft message per chapter at any time.
- **Dual storage**: Drafts are persisted in the frontend's localStorage (for offline resilience and instant UI feedback) and synced to the backend's in-memory draft service (for multi-session awareness).
- **Draft unification with offline messages**: A pending offline draft (from ADR-0004) satisfies the single-draft constraint; the UI treats offline messages as drafts and does not allow additional drafts for the same chapter.
- **Session-scoped backend state**: Backend drafts are stored in-memory per session and are not persisted across server restarts; frontend localStorage serves as the source of truth after reconnection.
- **REST API for draft operations**: GET, PUT, DELETE draft endpoints support retrieval, update, and cleanup of drafts, with access control tied to chapter membership.

### Real-Time Typing Indicators

- **WebSocket subscription per chapter**: Users subscribe to a chapter's typing events via a WebSocket upgrade endpoint (`GET /typing/subscribe?chapterId=<id>`).
- **Ephemeral typing state**: Typing indicators reflect the current set of users actively composing in a chapter; events are not persisted.
- **Client-driven lifecycle**: The frontend sends explicit "started" and "stopped" messages to the backend when the user begins and ends typing; auto-stop occurs after 5 seconds of inactivity (UI sends "stopped" even if text remains).
- **Real username display**: Typing events broadcast the user's actual username (resolved via database lookup) rather than a placeholder, enabling human-readable presence ("Alice is typing...").
- **Background cleanup**: The backend removes typing entries older than a timeout window (6 seconds) to recover from disconnected clients that do not send explicit stop messages.

## Consequences

### Positive Outcomes

- Users can safely compose in multiple chapters without losing work; switching chapters no longer erases uncommitted text.
- Multi-session draft sync allows users to resume work on a draft from a different device or session (if online).
- Typing indicators provide real-time feedback that another user is composing, reducing message collision and clarifying conversational flow.
- Real usernames in typing indicators are immediately recognizable and eliminate ambiguous user-ID references.
- Drafts integrate naturally with existing offline message support; the UI treats offline drafts as a special draft status.

### Tradeoffs & Costs

- **In-memory backend state**: Drafts and typing state are lost on server restart. Users must re-establish drafts from localStorage after reconnection. Typing state resets and users see a clean slate in the UI. This is acceptable because:
  - Drafts are transient; they are not the source of truth (frontend localStorage is).
  - Typing state is inherently ephemeral; a server restart should not reconstruct old typing activity.
- **WebSocket connection overhead**: Each user subscribes to one WebSocket connection per active chapter. This adds connection lifecycle management (connect on chapter switch, disconnect on switch away or logout). The frontend handles this transparently.
- **Database lookups in WebSocket handler**: Resolving real usernames requires a database query per typing start event. This is acceptable because:
  - The lookup is cached in memory for the session.
  - Typing start events are relatively infrequent (not every keystroke).
- **Single draft per chapter constraint**: Users cannot have multiple independent drafts in the same chapter (e.g., one polished, one exploratory). This is mitigated by the offline pending message (which is also a draft) and can be revisited if use cases emerge.

### Follow-Up Obligations

1. **Testing**: Add integration tests for WebSocket typing lifecycle (subscribe → start → stop → cleanup) and per-chapter draft isolation.
2. **UI validation**: Confirm that draft persistence across chapter switches matches user expectations (auto-save, fetch on switch, localStorage fallback).
3. **Monitoring**: Log typing event frequency and WebSocket connection churn to detect edge cases or excessive database lookups.
4. **Documentation**: Update user-facing docs to explain draft behavior (per-chapter, auto-saved, synced across sessions) and typing indicators (real-time presence).

## Alternatives Considered

### 1. Database-Persisted Drafts
- **Why not**: Adds schema migration, introduces persistence latency, and complicates server restart recovery. The frontend localStorage already provides reliable offline caching. Backend drafts are best kept ephemeral and session-scoped.

### 2. Unlimited Drafts Per Chapter
- **Why not**: Complicates the UI (which draft to resume?), increases memory footprint on the backend, and diverges from the single-draft-per-chapter model established by offline messages. The constraint is simple and aligns with user expectations for one-active-message composition flow.

### 3. Placeholder Usernames in Typing ("user-<id>")
- **Why not**: Users cannot immediately identify who is typing without additional context. Real usernames provide immediate clarity. Database lookup cost is minimal compared to UX benefit.

### 4. Client-Side Only Typing Indicators (via Message Events)
- **Why not**: Requires sending typing updates as regular messages, polluting the message log. WebSocket is the correct transport for ephemeral, non-message metadata. This separation of concerns is cleaner and more scalable.

### 5. Server-Driven Typing Timeout (No Client Stop Events)
- **Why not**: Requires the server to maintain state for all connected users and time out entries asynchronously. Client-driven lifecycle (explicit "stopped" messages) is simpler, more responsive, and reduces server-side timer overhead. Background cleanup is still present as a safety net.

## Notes

- Related to ADR-0004 (Bounded Offline Message Support), which establishes the one-pending-offline-draft-per-chapter constraint that this ADR extends to online drafts.
- Implementation spans three modules: `DraftsModule` (backend in-memory store), `TypingModule` (backend WebSocket broadcast), `DraftStore` and `TypingIndicatorClient` (frontend localStorage and WebSocket subscription).
- Frontend integration in `ChatView` orchestrates draft auto-save on input, fetch on chapter switch, and typing state emission.
- Real username resolution happens in the WebSocket handler (`ApiRoutes.handleTypingSubscribe`) and queries the `users` table by `userId`.
