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

  --purge   Also remove the named volumes jl-mysql-data, jl-redis-data and
            jl-rabbitmq-data. This deletes all local lab data.
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

compose() {
  docker compose \
    --project-name java-learn-lab \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$DOCKER_DIR/compose.base.yml" \
    -f "$DOCKER_DIR/compose.dev.yml" \
    "$@"
}

if $PURGE; then
  echo "WARNING: --purge removes the containers, the network AND the named volumes" >&2
  echo "         jl-mysql-data, jl-redis-data, jl-rabbitmq-data (all local lab data)." >&2
  compose down -v
  echo "java-learn-lab stopped; containers, network and named volumes removed."
else
  compose down
  echo "java-learn-lab stopped; named volumes preserved (use --purge to delete them)."
fi
