[![Build](https://github.com/aborg0/chat-app/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/aborg0/chat-app/actions/workflows/build.yml) [![Integration Tests](https://github.com/aborg0/chat-app/actions/workflows/integration-tests.yml/badge.svg?branch=main)](https://github.com/aborg0/chat-app/actions/workflows/integration-tests.yml)

# Chat Application

Chat application with a Scala backend (ZIO + zio-http), PostgreSQL persistence, and a Scala.js frontend (Laminar).

## What it includes

- User authentication and session handling
- Chapter and group management
- Messaging with search and edit/history support
- Basic observability stack for local development (OpenTelemetry, Prometheus, Loki, Tempo, Grafana)

## Tech stack

- Scala 3.8.3
- SBT 1.12.5
- ZIO 2.x and zio-http 3.x
- Scala.js + Laminar
- PostgreSQL

## Quick start

### Prerequisites

- JDK 21
- SBT 1.12.5
- Podman (preferred) or Docker
- Node.js (for Playwright tests)

### Start full local stack

Windows PowerShell:

```powershell
./scripts/start-services.ps1
```

Linux/macOS:

```bash
./scripts/start-services.sh
```

### Local endpoints

- Backend API: http://localhost:8080
- OpenAPI JSON: http://localhost:8080/openapi.json
- Swagger UI: http://localhost:8080/swagger
- Frontend: http://localhost:8081
- Grafana: http://localhost:3000
- Prometheus: http://localhost:9090

## Build and test

Run full build and tests that are part of CI:

```bash
sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "clean;sharedJVM/compile;sharedJS/compile;backend/compile;backendIt/compile;frontend/compile;sharedJVM/test;sharedJS/test;backend/test;frontend/test"
```

Run backend integration tests (requires container runtime):

```bash
sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backendIt/test"
```

Run Playwright E2E tests:

```bash
npm install
npm test
```

## CI

GitHub Actions runs:

- Build workflow on every push to main and pull request targeting main
- Integration test workflow on every push to main, pull request targeting main, and nightly schedule

Workflow file: .github/workflows/build.yml
Workflow file: .github/workflows/integration-tests.yml

## Useful docs

- docs/SYSTEM_SPECIFICATION.md
- docs/ARCHITECTURE.md
- docs/adr/README.md
- DEVELOPMENT.md
- e2e/README.md
- PLAYWRIGHT_QUICKSTART.md

## Project layout

```text
chat-app/
  backend/      # JVM backend
  backend-it/   # backend integration tests
  frontend/     # Scala.js frontend
  shared/       # shared protocol/model sources
  docs/         # architecture and ADRs
  e2e/          # Playwright end-to-end tests
```
