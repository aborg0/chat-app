#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REMOVE_VOLUMES="${REMOVE_VOLUMES:-false}"

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

ARGS=(down)
if [[ "$REMOVE_VOLUMES" == "true" ]]; then
  ARGS+=("-v")
fi

cd "$PROJECT_ROOT"
echo "Stopping local stack with ${COMPOSE_CMD[*]} ${ARGS[*]}"
"${COMPOSE_CMD[@]}" "${ARGS[@]}"
echo "Local stack stopped."
