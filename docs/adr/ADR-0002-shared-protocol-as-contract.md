# ADR-0002: Shared Protocol as Frontend-Backend Contract

## Status

Accepted

## Context

The frontend and backend exchange a growing set of request and response payloads. Duplicating these shapes in both runtimes increases drift risk, especially for message lifecycle fields such as versioning and client edit timestamps.

## Decision

The `shared` module is the authoritative contract for API payload shapes used by both the frontend and backend.

Changes to message workflows, chapter preferences, unread state payloads, or session payloads should be reflected there first, then implemented in the backend routes and frontend client code.

## Consequences

- payload drift is reduced because both runtimes compile against the same case classes
- protocol changes become explicit review points for change requests
- frontend and backend can evolve together around a stable shared model
- any incompatible contract change requires coordinated updates across both runtimes and tests
