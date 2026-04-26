# ADR-0005: Optimistic Concurrency for Message Edits

## Status

Accepted

## Context

Message edits can originate from multiple clients or from offline flows that later reconnect. Last-write-wins without version checking would silently overwrite concurrent user changes.

## Decision

Message editing uses optimistic concurrency.

- message payloads carry a `version`
- edit requests may include `expectedVersion`
- the backend rejects mismatched versions as conflicts
- each successful edit increments the stored message version
- edit history is recorded separately from the current message row
- optional `clientEditedAtEpochMillis` is persisted to preserve client-side edit timing, including offline-originated edits

## Consequences

- concurrent edits are surfaced instead of silently lost
- clients that support editing should carry version information through their workflows
- conflict handling becomes an explicit product and API concern
- history and current-state storage can evolve independently
