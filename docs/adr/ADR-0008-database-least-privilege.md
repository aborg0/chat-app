# ADR-0008: Database Least Privilege — Separate Migration and Application Roles

## Status

Accepted

## Context

The backend connects to PostgreSQL for two distinct purposes:

1. **Schema migrations** (Flyway): requires DDL rights — `CREATE TABLE`, `ALTER TABLE`, `CREATE INDEX`, `DROP CONSTRAINT`, and ownership of all objects it creates.
2. **Runtime access** (the live `Database` layer): only ever executes DML — `SELECT`, `INSERT`, `UPDATE`, `DELETE`. It never creates or alters schema objects.

The initial setup used a single database user for both roles. This violates the principle of least privilege: a compromised application process, or an exploited SQL-injection path that somehow escaped parameterisation, would have full DDL authority over the schema.

## Decision

Two distinct PostgreSQL roles are maintained:

| Role | Default user | Rights | Used by |
|---|---|---|---|
| Migration owner | `chat_app_migration` | Schema owner, DDL, DML | Flyway at startup |
| Application user | `chat_app_app` | `SELECT, INSERT, UPDATE, DELETE` on all tables; `USAGE, SELECT` on sequences | Live `JdbcDatabase` |

The migration role owns all schema objects. The application role is granted only the DML privileges it needs, using `DEFAULT PRIVILEGES` so that objects created by future migrations are covered automatically.

`AppConfig` supports two sets of credentials: `db.user`/`db.password` for the runtime connection, and the optional `db.migrationUser`/`db.migrationPassword` for Flyway. If migration credentials are absent, `Main` falls back to the runtime credentials (safe for local development without a split setup).

`docker-compose.yml` passes `DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD` as the Postgres superuser credentials and `DB_APP_USER`/`DB_APP_PASSWORD` as the restricted runtime credentials. An initialisation script (`postgres/init.sql`) creates the restricted user and grants DML rights when the Postgres container starts for the first time.

## Consequences

- A compromised runtime DB connection cannot alter schema objects, drop tables, or create new users.
- The composition root (`Main.scala`) is the only place that ever holds migration credentials; the `Database` layer and all service modules are initialised with the restricted application credentials.
- `application.conf` must document both credential sets so operators understand that two users are required in production.
- CI and local environments that cannot provision two users should set `DB_APP_USER` and `DB_APP_PASSWORD` to the same values as `DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD`; the fallback in `AppConfig` makes this transparent.
- When a new table is added by a migration, the `ALTER DEFAULT PRIVILEGES` grant ensures the application user gains DML rights on it automatically. No manual `GRANT` step is needed after migrations.

## Alternatives Considered

- **Single user, security enforced only at application layer**: rejected; defence in depth requires that a DB-level exploit does not grant DDL authority to the attacker.
- **Row-level security (RLS)**: considered as a longer-term complement, not a replacement. RLS can enforce per-user data isolation but does not replace the migration/application role split.
- **Connection pooling service (PgBouncer)**: orthogonal to this decision. If added, both users would be registered in the pool.
