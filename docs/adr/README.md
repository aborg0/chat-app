# Architectural Decision Records

This directory records architectural decisions that define the current chat application design.

## ADR Index

- `ADR-0001-layered-backend-boundaries.md`: backend layering and allowed dependency directions
- `ADR-0002-shared-protocol-as-contract.md`: shared API payloads as the contract between frontend and backend
- `ADR-0003-interaction-driven-read-state.md`: unread divider, interaction-triggered auto-read, and unread barrier semantics
- `ADR-0004-bounded-offline-message-support.md`: offline cache plus a single pending offline message per user
- `ADR-0005-optimistic-concurrency-for-message-edits.md`: message versioning and conflict detection for edits
- `ADR-0006-source-level-architecture-fitness-functions.md`: source-level architecture checks due ArchUnit and Java 25 incompatibility

## Usage

When a change request materially alters one of these decisions:

1. update the relevant ADR
2. update `docs/SYSTEM_SPECIFICATION.md` if the externally visible design changed
3. update tests and architecture checks as needed

For new decisions, start from `docs/adr/TEMPLATE.md`.

