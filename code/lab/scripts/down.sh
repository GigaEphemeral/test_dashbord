#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"
ENV_EXAMPLE="$DOCKER_DIR/.env.example"

usage() {
  cat <<'EOF'
Usage: down.sh [--purge]

Stop and remove the java-learn-lab containers and network.
Named volumes are preserved by default so the stack can be restarted for debugging.

Both the on-demand "traffic" and "ha" (Redis Sentinel) compose profiles are included, so
profile-gated containers such as jl-traffic and jl-redis-1..3 / jl-sentinel-1..3 never
linger after the stack is down.

  --purge   Also remove the named volumes jl-mysql-data, jl-redis-data,
            jl-rabbitmq-data, jl-prometheus-data, jl-grafana-data and
            jl-tempo-data. This deletes all local lab data (databases,
            metrics, dashboards state and traces). The Sentinel topology has no
            named volumes (its /data is tmpfs), so --purge does not add any.
EOF
}

PURGE=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --purge)
      PURGE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! -f "$ENV_FILE" ]]; then
  if [[ -f "$ENV_EXAMPLE" ]]; then
    echo "WARN: $ENV_FILE is missing; using .env.example only to resolve compose variables." >&2
    ENV_FILE="$ENV_EXAMPLE"
  else
    echo "ERROR: neither $ENV_FILE nor $ENV_EXAMPLE exists." >&2
    exit 1
  fi
fi

COMPOSE_FILES=(-f "$DOCKER_DIR/compose.base.yml" -f "$DOCKER_DIR/compose.dev.yml")
# The Sentinel topology lives in compose.ha.yml behind the "ha" profile. Include the
# file plus its profile so `down` removes those containers too; if the file is absent
# (an older checkout) the dev-only behaviour is unchanged.
HA_PROFILES=()
if [[ -f "$DOCKER_DIR/compose.ha.yml" ]]; then
  COMPOSE_FILES+=(-f "$DOCKER_DIR/compose.ha.yml")
  HA_PROFILES=(--profile ha)
fi

compose() {
  docker compose \
    --project-name java-learn-lab \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    "${COMPOSE_FILES[@]}" \
    "$@"
}

if $PURGE; then
  echo "WARNING: --purge removes the containers, the network AND the named volumes" >&2
  echo "         jl-mysql-data, jl-redis-data, jl-rabbitmq-data, jl-prometheus-data," >&2
  echo "         jl-grafana-data, jl-tempo-data (all local lab data)." >&2
  compose "${HA_PROFILES[@]}" --profile traffic down -v
  echo "java-learn-lab stopped; containers, network and named volumes removed."
else
  # The traffic profile is included so the created-but-stopped jl-traffic
  # container (Task 23 background traffic) never lingers after the stack is down.
  compose "${HA_PROFILES[@]}" --profile traffic down
  echo "java-learn-lab stopped; named volumes preserved (use --purge to delete them)."
fi
