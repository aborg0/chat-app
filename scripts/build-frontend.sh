#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

echo "Building frontend bundle with sbt frontend/fastLinkJS..."
sbt -Dsbt.color=false -Dsbt.log.noformat=true -Dsbt.supershell=false frontend/fastLinkJS

TARGET_ROOT="$PROJECT_ROOT/frontend/target"
RESOURCE_JS="$PROJECT_ROOT/frontend/resources/frontend.js"

CANDIDATE="$(find "$TARGET_ROOT" -type f -name frontend.js | head -n 1 || true)"

if [[ -z "$CANDIDATE" ]]; then
  CANDIDATE="$(find "$TARGET_ROOT" -type f -name '*.js' | grep -E 'frontend|main' | head -n 1 || true)"
fi

if [[ -z "$CANDIDATE" ]]; then
  echo "Could not find generated frontend JavaScript bundle under frontend/target" >&2
  exit 1
fi

cp "$CANDIDATE" "$RESOURCE_JS"
echo "Frontend bundle copied to $RESOURCE_JS"
