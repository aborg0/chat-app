# Architectural Decision Records

This directory records architectural decisions that define the current chat application design.

## ADR Index

- `ADR-0001-layered-backend-boundaries.md`: backend layering and allowed dependency directions
- `ADR-0002-shared-protocol-as-contract.md`: shared API payloads as the contract between frontend and backend
- `ADR-0003-interaction-driven-read-state.md`: unread divider, interaction-triggered auto-read, and unread barrier semantics
- `ADR-0004-bounded-offline-message-support.md`: offline cache plus a single pending offline message per user
- `ADR-0005-optimistic-concurrency-for-message-edits.md`: message versioning and conflict detection for edits
- `ADR-0006-source-level-architecture-fitness-functions.md`: source-level architecture checks due ArchUnit and Java 25 incompatibility
- `ADR-0007-authentication-and-session-security-controls.md`: session token strength, session expiry, credential enumeration resistance, input validation, error sanitisation, and browser security headers
- `ADR-0008-database-least-privilege.md`: separate PostgreSQL roles for Flyway migrations (DDL) and runtime application access (DML only)
- `ADR-0009-oauth2-oidc-social-login.md`: OAuth 2.0 + OIDC for social login with cryptographic identity verification, PKCE, and state-binding
- `ADR-0010-skunk-runtime-with-jdbc-fallback.md`: adopt Skunk runtime access incrementally behind a runtime switch with JDBC fallback and dual-mode integration coverage

## Usage

When a change request materially alters one of these decisions:

1. update the relevant ADR
2. update `docs/SYSTEM_SPECIFICATION.md` if the externally visible design changed
3. update tests and architecture checks as needed

For new decisions, start from `docs/adr/TEMPLATE.md`.

