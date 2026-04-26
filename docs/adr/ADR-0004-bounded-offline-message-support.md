# ADR-0004: Bounded Offline Message Support

## Status

Accepted

## Context

Users need limited offline support for viewing recent chapter content and drafting a message during connection loss. A full offline queue with generalized conflict resolution would add significantly more complexity than the current product needs justify.

## Decision

The system supports bounded offline messaging:

- cached chapter messages are stored locally per user and chapter
- exactly one pending offline message is supported per user
- the pending message may be edited offline before sync
- when connectivity returns, the frontend creates the message on the backend and associates it with the active chapter

The system does not currently support a multi-message offline outbox.

## Consequences

- users retain a useful offline fallback without a full sync engine
- implementation complexity and conflict surface stay bounded
- product behavior is intentionally constrained and should be communicated clearly in the UI
- expanding offline capabilities later will require a new ADR because it changes the storage, sync, and conflict model materially
