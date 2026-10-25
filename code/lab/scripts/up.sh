#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"
ENV_EXAMPLE="$DOCKER_DIR/.env.example"

usage() {
  cat <<'EOF'
Usage: up.sh [--profile dev]

Start the java-learn-lab dev infrastructure stack.

Profiles:
  dev   Nginx, MySQL, Redis, RabbitMQ and exporters (default)

The "ha" profile (MySQL InnoDB Cluster, Redis Sentinel, etc.) is available in M3.
EOF
}

PROFILE="dev"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: --profile requires a value (dev)." >&2
        exit 2
      fi
      PROFILE="$2"
      shift 2
      ;;
    --profile=*)
      PROFILE="${1#*=}"
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

case "$PROFILE" in
  dev)
    ;;
  ha)
    echo "ERROR: profile 'ha' is available in M3. Use --profile dev for the M0 infrastructure." >&2
    exit 2
    ;;
  *)
    echo "ERROR: unknown profile '$PROFILE'. Only 'dev' is available ('ha' arrives in M3)." >&2
    exit 2
    ;;
esac

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running or not reachable." >&2
  exit 1
fi

if [[ ! -f "$ENV_EXAMPLE" ]]; then
  echo "ERROR: missing $ENV_EXAMPLE" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$ENV_EXAMPLE" "$ENV_FILE"
  echo "WARN: $ENV_FILE was missing; created it from .env.example (lab-only defaults)."
fi

# Load lab defaults for display below. Do not export; compose gets them via --env-file.
# shellcheck disable=SC1090
source "$ENV_FILE"

compose() {
  docker compose \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$DOCKER_DIR/compose.base.yml" \
    -f "$DOCKER_DIR/compose.dev.yml" \
    "$@"
}

echo "Starting java-learn-lab infrastructure (profile: $PROFILE)..."
if ! compose up -d --wait --wait-timeout 240; then
  echo "ERROR: the stack did not become healthy within the timeout." >&2
  echo "--- docker compose ps ---" >&2
  compose ps >&2 || true
  echo "--- docker compose logs (last 50 lines) ---" >&2
  compose logs --tail=50 >&2 || true
  exit 1
fi

echo
echo "java-learn-lab dev stack is up:"
echo "  Nginx health  : http://127.0.0.1:8080/health"
echo "  RabbitMQ mgmt : http://127.0.0.1:15672 (user: ${RABBITMQ_DEFAULT_USER:-lab}, pass: ${RABBITMQ_DEFAULT_PASS:-lab})"
echo "  MySQL (dev)   : 127.0.0.1:3306 (db: ${MYSQL_DATABASE:-seckill}, user: ${MYSQL_USER:-seckill})"
echo "  Redis (dev)   : 127.0.0.1:6379"
echo
echo "Verify with: bash $SCRIPT_DIR/verify.sh"
