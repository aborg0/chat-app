#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_PORT="${1:-8081}"
NO_OBSERVABILITY="${NO_OBSERVABILITY:-false}"

OTEL_DIR="$PROJECT_ROOT/.otel"
OTEL_AGENT_PATH="$OTEL_DIR/opentelemetry-javaagent.jar"
OTEL_AGENT_VERSION="2.10.0"
OTEL_AGENT_URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"

if [[ "$NO_OBSERVABILITY" != "true" ]]; then
  if command -v podman >/dev/null 2>&1; then
    COMPOSE_CMD=(podman compose)
  elif command -v podman-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(podman-compose)
  elif command -v docker >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
  else
    COMPOSE_CMD=()
  fi

  if [[ ${#COMPOSE_CMD[@]} -gt 0 ]]; then
    echo "Starting local observability stack (Postgres + OTel + Grafana)..."
    (
      cd "$PROJECT_ROOT"
      "${COMPOSE_CMD[@]}" up -d postgres otel-collector prometheus loki tempo grafana >/dev/null
    )
  else
    echo "Neither podman compose, podman-compose, nor docker compose is available. Skipping observability stack startup." >&2
  fi

  if [[ ! -f "$OTEL_AGENT_PATH" ]]; then
    mkdir -p "$OTEL_DIR"
    echo "Downloading OpenTelemetry Java agent v${OTEL_AGENT_VERSION}..."
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "$OTEL_AGENT_URL" -o "$OTEL_AGENT_PATH"
    elif command -v wget >/dev/null 2>&1; then
      wget -q "$OTEL_AGENT_URL" -O "$OTEL_AGENT_PATH"
    else
      echo "Either curl or wget is required to download the OpenTelemetry Java agent." >&2
      exit 1
    fi
  fi
fi

"$SCRIPT_DIR/build-frontend.sh"

if command -v python3 >/dev/null 2>&1; then
  PYTHON_CMD="python3"
elif command -v python >/dev/null 2>&1; then
  PYTHON_CMD="python"
else
  echo "Neither python3 nor python is available on PATH." >&2
  exit 1
fi

(
  cd "$PROJECT_ROOT"
  if [[ "$NO_OBSERVABILITY" != "true" ]]; then
    export JAVA_TOOL_OPTIONS="-javaagent:$OTEL_AGENT_PATH"
    export OTEL_SERVICE_NAME="chat-app-backend"
    export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
    export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318"
    export OTEL_TRACES_EXPORTER="otlp"
    export OTEL_METRICS_EXPORTER="otlp"
    export OTEL_LOGS_EXPORTER="otlp"
    export OTEL_RESOURCE_ATTRIBUTES="deployment.environment=local,service.namespace=chat-app"
  fi
  sbt -Dsbt.color=false -Dsbt.log.noformat=true -Dsbt.supershell=false backend/run
) &
BACKEND_PID=$!

(
  cd "$PROJECT_ROOT/frontend/resources"
  "$PYTHON_CMD" -m http.server "$FRONTEND_PORT"
) &
FRONTEND_PID=$!

echo "Backend PID: $BACKEND_PID (http://localhost:8080)"
echo "Frontend static server PID: $FRONTEND_PID (http://localhost:$FRONTEND_PORT)"
if [[ "$NO_OBSERVABILITY" != "true" ]]; then
  echo "Grafana: http://localhost:3000 (admin/admin)"
  echo "Prometheus: http://localhost:9090"
  echo "Tempo: http://localhost:3200"
  echo "Loki: http://localhost:3100"
fi
echo "Press Ctrl+C to stop both processes."

cleanup() {
  kill "$BACKEND_PID" "$FRONTEND_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
wait
