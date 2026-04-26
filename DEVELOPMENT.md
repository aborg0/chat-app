# Development Guide

This file captures the practical local development workflow for this repository.

## Prerequisites

- JDK 21
- SBT 1.12.5
- Podman (preferred) or Docker
- Node.js (for Playwright)

## Common local workflows

### Start the app and observability stack

Windows PowerShell:

```powershell
./scripts/start-services.ps1
```

Linux/macOS:

```bash
./scripts/start-services.sh
```

### Build all Scala modules

```bash
sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "clean;sharedJVM/compile;sharedJS/compile;backend/compile;backendIt/compile;frontend/compile"
```

### Run tests

Backend unit tests:

```bash
sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backend/test"
```

Backend integration tests (Testcontainers):

```bash
sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backendIt/test"
```

Frontend tests:

```bash
sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "frontend/test"
```

Playwright E2E:

```bash
npm install
npm test
```

## Service endpoints

- Backend API: http://localhost:8080
- OpenAPI JSON: http://localhost:8080/openapi.json
- Swagger UI: http://localhost:8080/swagger
- Frontend: http://localhost:8081
- Grafana: http://localhost:3000
- Prometheus: http://localhost:9090

## CI workflows

- Build workflow: `.github/workflows/build.yml`
- Integration tests workflow: `.github/workflows/integration-tests.yml`

## Troubleshooting

- If backend integration tests fail early, verify your container runtime is running.
- If frontend static assets are stale, rebuild frontend via `./scripts/build-frontend.ps1` (Windows) or `./scripts/build-frontend.sh` (Linux/macOS).
- If service startup is slow, run the local health script:
  - `./scripts/check-local-health.ps1`
  - `./scripts/check-local-health.sh`
