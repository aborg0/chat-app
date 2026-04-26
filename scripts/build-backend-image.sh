#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE_TAG="${1:-localhost/chat-app-backend:latest}"

if command -v podman >/dev/null 2>&1; then
  ENGINE="podman"
elif command -v docker >/dev/null 2>&1; then
  ENGINE="docker"
else
  echo "Neither podman nor docker is available on PATH." >&2
  exit 1
fi

cd "$PROJECT_ROOT"

IMAGE_TAR="$PROJECT_ROOT/backend/target/jib/chat-app-backend-image.tar"
IMAGE_TAR_SBT_PATH="backend/target/jib/chat-app-backend-image.tar"

if [[ -e "$IMAGE_TAR" ]]; then
  rm -rf "$IMAGE_TAR"
fi

echo "Building backend container tar with sbt backend/jibJavaTarImageBuild..."
sbt -Dsbt.color=false -Dsbt.log.noformat=true -Dsbt.supershell=false "backend/jibJavaTarImageBuild $IMAGE_TAR_SBT_PATH"

if [[ ! -f "$IMAGE_TAR" ]]; then
  echo "Expected Jib image tar was not found at $IMAGE_TAR" >&2
  exit 1
fi

echo "Loading backend image tar into $ENGINE..."
"$ENGINE" load -i "$IMAGE_TAR"

# podman load may create this alias; keep the expected local tag for compose.
LOADED_ALIAS="registry.hub.docker.com/localhost/chat-app-backend:latest"
if "$ENGINE" image inspect "$LOADED_ALIAS" >/dev/null 2>&1; then
  "$ENGINE" tag "$LOADED_ALIAS" "localhost/chat-app-backend:latest"
fi

if [[ "$IMAGE_TAG" != "localhost/chat-app-backend:latest" ]]; then
  "$ENGINE" tag localhost/chat-app-backend:latest "$IMAGE_TAG"
fi

echo "Backend image ready: $IMAGE_TAG"
