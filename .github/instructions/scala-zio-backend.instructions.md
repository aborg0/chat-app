---
description: "Use when writing backend Scala code: ZIO services, ZLayer wiring, HTTP routes with zio-http, JDBC database access, Flyway migrations, or integration tests. Covers ZIO environment patterns, layer composition, and JDBC conventions for this project."
applyTo: "backend/src/**/*.scala,backend-it/src/**/*.scala"
---

# Backend Scala / ZIO Conventions

## ZIO Environment — Services and Layers

Every service must expose a `ZLayer` companion value, not a constructor or `def live`:

```scala
object SessionsModule {
  trait SessionsService { ... }

  final class LiveSessionsService(db: Database) extends SessionsService { ... }

  val layer: URLayer[Database, SessionsService] = ZLayer {
    ZIO.serviceWith[Database](new LiveSessionsService(_))
  }
}
```

- Use `URLayer` (no error) when construction cannot fail.
- Use intersection types `A & B` in layer requirements, not tuples.
- `AuthModule.layer` depends on two services: `URLayer[Database & SessionsService, AuthService]`.

## ZIO Environment — Route Handlers

Routes are `Routes[AppEnv, Response]` where `AppEnv` is a type alias for the intersection of all required services. Handlers access services via `ZIO.serviceWithZIO`:

```scala
type AppEnv = AuthService & SessionsService & MessagingService & ChaptersService & GroupsService

def routes: Routes[AppEnv, Response] = Routes(
  Method.POST / "auth" / "login" -> handler { (req: Request) =>
    asHttpError(handleLogin(req))
  },
  ...
)

private def handleLogin(req: Request): ZIO[AuthService, Throwable, Response] =
  for {
    payload <- decodeBody[LoginRequest](req)
    auth    <- ZIO.serviceWithZIO[AuthService](_.loginPassword(...))
  } yield Response.json(...)
```

- Each handler method declares only the services it actually uses in its return type.
- Never pass services as method parameters — use `ZIO.serviceWithZIO` instead.
- `asHttpError` must be polymorphic: `def asHttpError[R](effect: ZIO[R, Throwable, Response]): ZIO[R, Response, Response]`.

## Main Wiring

`Main.scala` uses `.provide(...)` to supply all layers to the running server:

```scala
Server.serve(ApiRoutes.routes).provide(
  Server.defaultWith(...),
  Database.layer(url, user, password),
  SessionsModule.layer,
  AuthModule.layer,
  MessagingModule.layer,
  ChaptersModule.layer,
  GroupsModule.layer
)
```

Do **not** manually instantiate services or thread them through function parameters.

## JDBC Access Pattern

All DB access goes through `db.withConnection { connection => ... }`. Use `try/finally` to close every statement, and close `ResultSet` before the statement:

```scala
db.withConnection { connection =>
  val stmt = connection.prepareStatement("SELECT ...")
  try {
    stmt.setLong(1, id)
    val rs = stmt.executeQuery()
    val result = ...
    rs.close()
    result
  } finally {
    stmt.close()
  }
}
```

- Use `RETURNING id, created_at` on INSERT statements to get generated values without a second query.
- Use `ON CONFLICT DO NOTHING` for idempotent inserts.

## Scala 3 Syntax

Use modern Scala 3 constructs throughout:

```scala
if !condition then throw ...      // not if(!condition)
while rs.next() do { ... }        // not while (rs.next()) { }
val layer: URLayer[A & B, C] = ZLayer { ... }
```

## Database Migrations (Flyway)

- Files in `backend/resources/db/migration/`, named `V{n}__{description}.sql`.
- Each migration is forward-only — never modify an existing migration file.
- Always add `IF NOT EXISTS` / `IF EXISTS` guards where supported.
- Add indexes alongside new tables.
- Use `ALTER TABLE ... DROP CONSTRAINT IF EXISTS` before re-adding a constraint.

## Error Classification in HTTP Handlers

The `asHttpError` function maps error messages to HTTP status codes by substring:

- Contains `"Missing header"` or `"Invalid or inactive session token"` → 401 Unauthorized
- Contains `"Admin rights"` or `"Not allowed"` → 403 Forbidden
- Everything else → 400 Bad Request

When throwing domain errors from service code, use these exact prefixes so routing is correct.

## Integration Tests

Integration tests in `backend-it/` use testcontainers (`PostgreSQLContainer`) with real Postgres. The fixture composes production layers using `ZLayer.scoped`, with the container lifecycle managed via `ZIO.acquireRelease`:

```scala
final case class Fixture(routes: Routes[Any, Response])

private val fixtureLayer: ZLayer[Any, Throwable, Fixture] = ZLayer.scoped {
  for {
    container <- ZIO.acquireRelease(
      ZIO.attemptBlocking { val c = new PostgreSQLContainer("postgres:18.3-alpine"); c.start(); c }
    )(c => ZIO.succeed(c.stop()))
    _ <- Migrations.migrate(container.getJdbcUrl, container.getUsername, container.getPassword)
    db        = new JdbcDatabase(container.getJdbcUrl, container.getUsername, container.getPassword)
    dbLayer   = ZLayer.succeed[Database](db)
    sessLayer = dbLayer >>> SessionsModule.layer
    authLayer = (dbLayer ++ sessLayer) >>> AuthModule.layer
    msgLayer  = dbLayer >>> MessagingModule.layer
    chapLayer = dbLayer >>> ChaptersModule.layer
    grpLayer  = dbLayer >>> GroupsModule.layer
    allLayers = sessLayer ++ authLayer ++ msgLayer ++ chapLayer ++ grpLayer
    env      <- allLayers.build
  } yield Fixture(ApiRoutes.routes.provideEnvironment(env))
}
```

- Always use `ZLayer` composition in tests — never call `new LiveXxxService(db)` directly.
- Use `ZLayer.succeed[Database](db)` to lift the concrete `JdbcDatabase` into the layer graph.
- Use `allLayers.build` (inside `ZLayer.scoped`) to obtain a `ZEnvironment`, then call `ApiRoutes.routes.provideEnvironment(env)` to get `Routes[Any, Response]`.
- If a test needs a direct service reference (e.g., for a helper that bypasses HTTP), extract it from the environment with `env.get[ServiceType]` and store it in `Fixture`.
- Always run `mcp_chatty-metals_compile-module` for both `backend` and `backendIt` after changes, then run both integration test suites to verify.
