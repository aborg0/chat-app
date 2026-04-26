# Chat Application

This project is a chat application built using ZIO for the backend, PostgreSQL as the database, and Laminar for the frontend. The application allows users to log in, create hierarchical chapters, manage groups, send messages, and receive notifications about new messages. It is designed to support up to 800 concurrent users and includes observability features for metrics and tracing.

## Features

- User authentication via username/password, OAuth, SAML, Passkeys, and social logins.
- Creation and management of hierarchical chapters.
- Group creation and membership management.
- Real-time messaging functionality.
- Notifications for new messages and events.
- Device management, allowing users to check logged-in devices and log out from others.
- Modular architecture with a focus on scalability and maintainability.

## Architecture and Design Records

- `docs/SYSTEM_SPECIFICATION.md` describes the current implemented design and its operational constraints.
- `docs/ARCHITECTURE.md` describes enforced backend architecture rules.
- `docs/adr/README.md` indexes the architectural decision records for the current design.

## Project Structure

```
chat-app
├── backend                # Backend application using ZIO
│   ├── src
│   │   ├── main
│   │   │   └── scala
│   │   │       └── com
│   │   │           └── example
│   │   │               ├── app
│   │   │               ├── auth
│   │   │               ├── chapters
│   │   │               ├── groups
│   │   │               ├── messaging
│   │   │               ├── notifications
│   │   │               ├── sessions
│   │   │               └── observability
│   │   └── test
│   └── resources
├── frontend               # Frontend application using Laminar
│   ├── src
│   │   └── main
│   │       └── scala
│   │           └── com
│   │               └── example
│   └── resources
├── shared                 # Shared code between frontend and backend
│   └── src
├── project                # SBT project configuration
├── build.sbt             # Build configuration
└── README.md             # Project documentation
```

## Getting Started

### Prerequisites

- Scala 3.8.3
- SBT 1.12.5
- PostgreSQL 18.3

### Setup Instructions

1. Clone the repository:
   ```
   git clone <repository-url>
   cd chat-app
   ```

2. Configure the PostgreSQL database in `backend/resources/application.conf`.
   - Runtime config is read via zio-config from this file (`db.*`, `http.*`).

3. Run database migrations:
   ```
    sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backend/run"
   ```

4. Start the backend server:
   ```
    sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backend/run"
   ```

5. Start the frontend application:
   ```
    sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "frontend/fastLinkJS"
   ```

### One-command Local Startup (Backend + Frontend + Observability)

Use the provided scripts to build frontend assets, start PostgreSQL/observability components via podman-compose (fallback to docker compose), and then start backend + frontend static hosting together.

- Windows PowerShell:
  ```
  ./scripts/start-services.ps1
  ```

- Linux/macOS:
  ```
  ./scripts/start-services.sh
  ```

Notes:
- These scripts build `frontend.js` and place it into `frontend/resources/frontend.js`.
- By default the scripts start `postgres`, `otel-collector`, `prometheus`, `loki`, `tempo`, and `grafana` via podman-compose (or docker compose if podman-compose is unavailable).
- Backend runs with the OpenTelemetry Java agent and exports OTLP to `http://localhost:4318`.
- Frontend browser telemetry is initialized from `frontend/resources/otel.js` and reports client traces to the same collector.
- To skip observability startup:
   - PowerShell: `./scripts/start-services.ps1 -NoObservability`
   - Bash: `NO_OBSERVABILITY=true ./scripts/start-services.sh`

Endpoints:
- Backend API: `http://localhost:8080`
- OpenAPI JSON: `http://localhost:8080/openapi.json`
- Swagger UI: `http://localhost:8080/swagger`
- Frontend: `http://localhost:8081`
- Grafana: `http://localhost:3000` (`admin` / `admin`)
- Prometheus: `http://localhost:9090`
- Tempo: `http://localhost:3200`
- Loki: `http://localhost:3100`

### Trace Context Header

Backend and clients use the W3C `traceparent` header for request correlation.

- Clients should send `traceparent` on API requests.
- If a request does not include `traceparent` (or includes an invalid value), backend generates one.
- Backend always returns a `traceparent` header in the HTTP response.

Key local observability config files:
- Collector: `observability/collector-config.yml`
- Prometheus: `observability/prometheus.yml`
- Loki: `observability/loki-config.yml`
- Tempo: `observability/tempo.yml`

### Container Compose Startup

`docker-compose.yml` now includes postgres + observability stack (OpenTelemetry Collector, Prometheus, Loki, Tempo, Grafana), and optionally backend/frontend containers.

1. Build frontend assets for static serving:
   - Windows: `./scripts/build-frontend.ps1`
   - Linux/macOS: `./scripts/build-frontend.sh`
2. Build backend image from sbt-jib tar output:
   - Windows: `./scripts/build-backend-image.ps1`
   - Linux/macOS: `./scripts/build-backend-image.sh`
   - default image tag: `localhost/chat-app-backend:latest`
   - implementation uses `sbt-jib` (`backend/jibTarImageBuild`) and loads tar into container runtime
3. Start compose:
   ```
   podman-compose up -d
   ```

If you prefer Docker CLI compose:
```
docker compose up -d
```

### Fast Local Cycle (Recommended)

One command to build frontend bundle + backend image and start the full stack:

- Windows PowerShell:
   ```
   ./scripts/up-local-compose.ps1
   ```

- Linux/macOS:
   ```
   ./scripts/up-local-compose.sh
   ```

Stop the stack:

- Windows PowerShell:
   ```
   ./scripts/down-local-compose.ps1
   ```

- Linux/macOS:
   ```
   ./scripts/down-local-compose.sh
   ```

Remove volumes as well:
- PowerShell: `./scripts/down-local-compose.ps1 -RemoveVolumes`
- Bash: `REMOVE_VOLUMES=true ./scripts/down-local-compose.sh`

Both scripts prefer `podman compose`, then `podman-compose`, then `docker compose`.
They build frontend and backend image first, then run compose with `up -d`.
PowerShell supports forcing a full rebuild:
- `./scripts/up-local-compose.ps1 -ForceRebuild`

### Health Check

After startup, run a readiness check across Postgres, backend, frontend, and observability services.

- Windows PowerShell:
   ```
   ./scripts/check-local-health.ps1
   ```

- Linux/macOS:
   ```
   ./scripts/check-local-health.sh
   ```

Optional timeout tuning:
- PowerShell: `./scripts/check-local-health.ps1 -TimeoutSeconds 240 -PollSeconds 5`
- Bash: `./scripts/check-local-health.sh 240 5`

Services:
- Backend: `http://localhost:8080`
- Frontend: `http://localhost:8081`
- Postgres: `localhost:5432`
- Grafana: `http://localhost:3000`

### End-to-End Testing with Playwright

The application includes comprehensive Playwright E2E tests for UI and API validation. Tests cover multi-user messaging, chapter visibility, and the reported issue where messages from other users don't appear in real-time on the original user's UI.

**Quick Start:**
```bash
npm install
npm test
```

**For More Details:**
- [Playwright Quick Start Guide](./PLAYWRIGHT_QUICKSTART.md)
- [Detailed E2E Test Documentation](./e2e/README.md)

**Key Test Files:**
- `e2e/multi-user-messaging.spec.ts` - Multi-user scenarios
- `e2e/visibility-issue.spec.ts` - Tests for the reported visibility issue
- `e2e/ui-tests.spec.ts` - Frontend UI interaction tests
- `e2e/integration-tests.spec.ts` - Data consistency and API tests

**Important:** The test suite includes tests that reproduce the reported issue where messages from User2 don't automatically appear in User1's UI. See `visibility-issue.spec.ts` for details.

### Migration Source Of Truth

Database migrations now have a single source of truth: `backend/resources/db/migration/V1__auth_sessions.sql`.

- `Migrations.scala` only loads and executes the resource script.
- This avoids drift between embedded SQL and resource SQL.

### Usage

- Access the frontend application in your browser using the static host of your choice and load the generated Scala.js bundle.
- Users can log in, create chapters, manage groups, and send messages.

## Definition of Done

The MVP is done when all of the following are true:

- Authentication and session APIs are available over HTTP:
   - `POST /auth/register`
   - `POST /auth/login`
   - `POST /auth/social-login`
   - `GET /sessions`
   - `POST /sessions/logout-others`
- Users can sign in from multiple devices, list active sessions, and log out other devices after re-authentication.
- Frontend Laminar app supports:
   - password login/register
   - social login
   - viewing active sessions
   - logging out other devices
   - authenticated navigation shell for messaging, chapters, groups, and devices
- Messaging requirements are implemented:
   - users can search messages
   - users can open deep links to specific messages
   - users can edit messages
   - users can view message edit history
   - users can delete messages
- Admin and audit requirements are implemented:
   - admin users can read other users' messages
   - admin users can read other users' message history
   - all admin reads and writes are audited (who, what, when, target)
- Test coverage requirements are met:
   - unit tests for auth/session core logic
    - integration tests in dedicated `backendIt` subproject using Testcontainers PostgreSQL
   - integration tests that simulate user interactions over HTTP
   - no test regressions in CI/local runs

## Test Commands

- Unit tests (backend):
   ```
   sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backend/test"
   ```

- Integration tests (separate subproject):
   ```
   sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backendIt/test"
   ```

- Specific integration suites:
   ```
   sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backendIt/testOnly com.example.integration.AuthSessionIntegrationSpec com.example.integration.MessagingIntegrationSpec"
   ```

## Contributing

Contributions are welcome! Please open an issue or submit a pull request for any enhancements or bug fixes.

### Updating the design docs during feature work

When a change request alters how the system works, update the design documents in the same change set.

- Update `docs/SYSTEM_SPECIFICATION.md` when user-visible behavior, system boundaries, core flows, invariants, or operational constraints changed.
- Update `docs/adr/README.md` and the relevant ADR when an architectural decision changed or when a new enduring decision was introduced.
- Start new decisions from `docs/adr/TEMPLATE.md`.
- Update `docs/ARCHITECTURE.md` when backend layer rules or architecture fitness checks changed.

As a rule of thumb:

- behavior and flow changes belong in the system specification
- enduring technical decisions and tradeoffs belong in ADRs
- enforceable backend boundary rules belong in the architecture document and tests

Pull requests should explicitly state whether these documents were reviewed and whether updates were needed.

## License

This project is licensed under the MIT License. See the LICENSE file for details.