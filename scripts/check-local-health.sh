#!/usr/bin/env bash
set -euo pipefail

TIMEOUT_SECONDS="${1:-180}"
POLL_SECONDS="${2:-3}"

if [[ "$TIMEOUT_SECONDS" -lt 1 ]]; then
  echo "Timeout must be at least 1 second." >&2
  exit 2
fi
if [[ "$POLL_SECONDS" -lt 1 ]]; then
  echo "Poll interval must be at least 1 second." >&2
  exit 2
fi

declare -a NAMES=(
  "Postgres"
  "Backend"
  "Frontend"
  "Grafana"
  "Prometheus"
  "Loki"
  "Tempo"
)

declare -a MODES=(
  "tcp"
  "http"
  "http"
  "http"
  "http"
  "http"
  "http"
)

declare -a TARGETS=(
  "localhost:5432"
  "http://localhost:8080/health"
  "http://localhost:8081"
  "http://localhost:3000/api/health"
  "http://localhost:9090/-/ready"
  "http://localhost:3100/ready"
  "http://localhost:3200/ready"
)

check_http() {
  local url="$1"
  curl -fsS --max-time 4 "$url" >/dev/null 2>&1
}

check_tcp() {
  local hostport="$1"
  local host="${hostport%%:*}"
  local port="${hostport##*:}"
  timeout 2 bash -c "</dev/tcp/${host}/${port}" >/dev/null 2>&1
}

print_results() {
  local -n statuses_ref=$1
  printf "%-12s %-8s %s\n" "Service" "Ready" "Probe"
  for i in "${!NAMES[@]}"; do
    printf "%-12s %-8s %s\n" "${NAMES[$i]}" "${statuses_ref[$i]}" "${TARGETS[$i]}"
  done
}

start_ts="$(date +%s)"
end_ts=$((start_ts + TIMEOUT_SECONDS))

while :; do
  now="$(date +%s)"
  if [[ "$now" -ge "$end_ts" ]]; then
    break
  fi

  all_ready=true
  declare -a statuses=()

  for i in "${!NAMES[@]}"; do
    mode="${MODES[$i]}"
    target="${TARGETS[$i]}"

    if [[ "$mode" == "http" ]]; then
      if check_http "$target"; then
        statuses+=("yes")
      else
        statuses+=("no")
        all_ready=false
      fi
    else
      if check_tcp "$target"; then
        statuses+=("yes")
      else
        statuses+=("no")
        all_ready=false
      fi
    fi
  done

  if [[ "$all_ready" == "true" ]]; then
    echo "All services are ready."
    print_results statuses
    exit 0
  fi

  sleep "$POLL_SECONDS"
done

echo "Timed out waiting for local services to become ready." >&2
print_results statuses
exit 1
