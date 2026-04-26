#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE_TAG="${1:-localhost/chat-app-backend:latest}"

"$SCRIPT_DIR/build-frontend.sh"
"$SCRIPT_DIR/build-backend-image.sh" "$IMAGE_TAG"

if command -v podman >/dev/null 2>&1; then
  COMPOSE_CMD=(podman compose)
elif command -v podman-compose >/dev/null 2>&1; then
  COMPOSE_CMD=(podman-compose)
elif command -v docker >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
else
  echo "Neither podman compose, podman-compose, nor docker compose is available." >&2
  exit 1
fi

cd "$PROJECT_ROOT"
echo "Starting full stack with ${COMPOSE_CMD[*]} up -d"
"${COMPOSE_CMD[@]}" up -d

echo "Local stack is starting."
echo "Frontend: http://localhost:8081"
echo "Backend: http://localhost:8080"
echo "Grafana: http://localhost:3000 (admin/admin)"
echo "Prometheus: http://localhost:9090"
echo "Tempo: http://localhost:3200"
echo "Loki: http://localhost:3100"
