# ADR-0010: Skunk Runtime with JDBC Fallback

## Status

Accepted

## Context

The backend historically used JDBC for all runtime database access.
After adopting asynchronous database access goals, we needed to introduce Skunk incrementally without breaking existing behavior.

Constraints:

- production and integration behavior had to remain stable during rollout
- Flyway migrations must remain JDBC-based due existing migration process and role separation (ADR-0008)
- service modules (`Sessions`, `Groups`, `Chapters`, `Messaging`) could not be migrated in one high-risk cutover
- tests needed to validate both the legacy fallback path and the live Skunk runtime path

## Decision

Adopt Skunk as an optional runtime database client, with controlled fallback to JDBC during migration.

- Runtime switch is controlled by configuration:
  - `DB_RUNTIME=jdbc` (default)
  - `DB_RUNTIME=skunk`
  - `DB_SKUNK_MAX_SESSIONS`
  - `DB_SKUNK_IDLE_TIME_MS`
- Introduce `SkunkSessionPool` as the runtime Skunk access abstraction.
- Keep JDBC as the fallback path inside migrated services via a dual-path helper pattern (`withSkunkOrJdbc`).
- Keep Flyway migrations on JDBC only.
- Migrate service modules incrementally while preserving endpoint behavior.
- Expand integration coverage to include:
  - `jdbc-fallback` suites (existing behavior)
  - focused `skunk-runtime` suites (live pool smoke/parity checks)

## Consequences

- Positive:
  - enables async-first runtime DB access with low rollout risk
  - supports feature-by-feature migration and verification
  - provides explicit rollback path by switching `DB_RUNTIME` back to `jdbc`

- Tradeoffs:
  - temporary dual implementation complexity in migrated services
  - additional test matrix (`jdbc-fallback` + `skunk-runtime`)
  - stricter Skunk type alignment requirements (e.g., `varchar(n)`/nullable column decoding)

- Follow-up obligations:
  - preserve parity checks for both runtime modes in integration tests
  - keep runtime and migration credential boundaries documented and enforced
  - when rollout confidence is sufficient, decide whether to retire JDBC runtime fallback in a future ADR

## Alternatives Considered

- Big-bang replacement of JDBC runtime with Skunk
  - rejected due high migration risk and limited rollback safety

- Keep JDBC runtime permanently and avoid Skunk
  - rejected because it does not meet async-first runtime direction

- Introduce Skunk only in new modules, not existing services
  - rejected because existing high-traffic paths (`sessions`, `messaging`, `chapters`) would remain unmigrated

## Notes

- Related ADRs:
  - ADR-0008 (database least privilege)
- Related configuration/docs:
  - `backend/resources/application.conf`
  - `backend/src/main/scala/com/example/app/AppConfig.scala`
  - `DEVELOPMENT.md`
  - `README.md`
