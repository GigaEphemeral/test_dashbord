#!/usr/bin/env bash
# Tear down ONLY the DB-HA tier (MySQL InnoDB Cluster + Router + its exporters).
#
# Why not `down.sh`: `docker compose down` removes every container of the project
# AND the shared jl-net network, so it would take the always-on dev stack (and the
# app's Redis/RabbitMQ) with it. This script therefore:
#   * derives the service list from the rendered compose model (base+dev+mysql with
#     and without the profile), so it can never drift from compose.mysql.yml,
#   * refuses to touch a service that is also in the always-on list,
#   * refuses any service or volume name outside its allowlist regex,
#   * uses stop + rm (never `down`), so jl-net and the dev containers survive,
#   * keeps named volumes unless --purge is given explicitly,
#   * and finally re-lists the lab containers to prove nothing else disappeared.
#
# `stop` before `rm` is not cosmetic: InnoDB needs a clean shutdown, otherwise the
# next start runs crash recovery on each node before Group Replication can even be
# evaluated.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"
ENV_EXAMPLE="$DOCKER_DIR/.env.example"
BASE_COMPOSE="$DOCKER_DIR/compose.base.yml"
DEV_COMPOSE="$DOCKER_DIR/compose.dev.yml"
MYSQL_COMPOSE="$DOCKER_DIR/compose.mysql.yml"

env_default() { # <key> <fallback> - reads .env without sourcing it (no secrets exported)
  local key="$1" fallback="$2" value=""
  if [[ -n "${!key:-}" ]]; then
    printf '%s' "${!key}"
    return 0
  fi
  if [[ -f "$ENV_FILE" ]]; then
    value="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true)"
    value="${value%$'\r'}"
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"
    if [[ -n "$value" ]]; then
      printf '%s' "$value"
      return 0
    fi
  fi
  printf '%s' "$fallback"
}

COMPOSE_PROJECT="$(env_default DB_HA_COMPOSE_PROJECT java-learn-lab)"
PROFILE="$(env_default DB_HA_PROFILE db-ha)"
PREFIX="$(env_default DB_HA_CONTAINER_PREFIX jl-)"
STOP_TIMEOUT="$(env_default DB_HA_STOP_TIMEOUT_SECONDS 30)"
# Belt and braces on top of the model diff: a name outside this list aborts the run.
ALLOWED_SERVICES_RE="$(env_default DB_HA_ALLOWED_SERVICES_REGEX \
  '^(mysql-[123]|mysql-[123]-exporter|mysql-router|mysql-router-init|mysql-cluster-init)$')"
# --purge deletes only these volumes; jl-mysql-data (the dev single-node MySQL) can
# never match, which is the point.
ALLOWED_VOLUMES_RE="$(env_default DB_HA_ALLOWED_VOLUMES_REGEX \
  '^jl-mysql-[123]-data$|^jl-mysql-router-data$')"

usage() {
  cat <<'EOF'
Usage: db-ha-down.sh [--purge] [--dry-run] [--profile db-ha|ha] [-h|--help]

Stop and remove the DB-HA tier containers only. jl-net and the always-on dev stack
are never touched - this script deliberately does not call `docker compose down`.

  --purge          also delete the cluster volumes jl-mysql-1/2/3-data and
                   jl-mysql-router-data. Destroys all cluster data and forces a
                   re-bootstrap (dba.createCluster + clone) on the next db-ha-up.sh.
  --dry-run        print exactly what would be stopped/removed/deleted, do nothing.
  --profile db-ha  profile whose services are owned by this tier (default db-ha)

Timeouts and guards are configurable: DB_HA_STOP_TIMEOUT_SECONDS,
DB_HA_ALLOWED_SERVICES_REGEX, DB_HA_ALLOWED_VOLUMES_REGEX.
EOF
}

PURGE=false
DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --purge) PURGE=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --profile)
      if [[ $# -lt 2 ]]; then echo "ERROR: --profile requires a value (db-ha|ha)." >&2; exit 2; fi
      PROFILE="$2"; shift 2 ;;
    --profile=*) PROFILE="${1#*=}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$PROFILE" in
  db-ha|ha) ;;
  *) echo "ERROR: unknown profile '$PROFILE'." >&2; exit 2 ;;
esac

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker CLI not found in PATH." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running or not reachable." >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  if [[ -f "$ENV_EXAMPLE" ]]; then
    printf 'WARN: %s is missing; using .env.example to resolve compose variables.\n' "$ENV_FILE" >&2
    ENV_FILE="$ENV_EXAMPLE"
  else
    echo "ERROR: neither $ENV_FILE nor $ENV_EXAMPLE exists." >&2
    exit 1
  fi
fi

for required in "$BASE_COMPOSE" "$DEV_COMPOSE" "$MYSQL_COMPOSE"; do
  if [[ ! -f "$required" ]]; then
    echo "ERROR: missing $required - the service list cannot be derived from the model." >&2
    exit 1
  fi
done

compose() {
  docker compose \
    --project-name "$COMPOSE_PROJECT" \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$BASE_COMPOSE" \
    -f "$DEV_COMPOSE" \
    -f "$MYSQL_COMPOSE" \
    --profile "$PROFILE" \
    "$@"
}

compose_never() {
  docker compose \
    --project-name "$COMPOSE_PROJECT" \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$BASE_COMPOSE" \
    -f "$DEV_COMPOSE" \
    -f "$MYSQL_COMPOSE" \
    "$@"
}

# Only the DB-HA file, on its own: used for the volume list so the dev tier's
# volumes (jl-mysql-data, jl-redis-data, ...) never even reach the guard check.
compose_mysql_only() {
  docker compose \
    --project-name "$COMPOSE_PROJECT" \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    -f "$MYSQL_COMPOSE" \
    --profile "$PROFILE" \
    "$@"
}

# --- derive the scoped service list -----------------------------------------
always_on="$(compose_never config --services 2>/dev/null | sort)"
with_profile="$(compose config --services 2>/dev/null | sort)"
if [[ -z "$with_profile" ]]; then
  echo "ERROR: the compose model rendered no services." >&2
  exit 1
fi
db_ha_services="$(comm -13 <(printf '%s\n' "$always_on") <(printf '%s\n' "$with_profile"))"
if [[ -z "$db_ha_services" ]]; then
  echo "Nothing to do: profile '$PROFILE' adds no services beyond the always-on stack."
  exit 0
fi

services=()
while IFS= read -r service; do
  [[ -n "$service" ]] || continue
  # Guard 1: never a service that is also part of the always-on stack.
  if printf '%s\n' "$always_on" | grep -Fxq "$service"; then
    echo "ERROR: refusing to continue - '$service' is also an always-on service." >&2
    echo "That means a profile-gated name collided with compose.base.yml/compose.dev.yml." >&2
    exit 1
  fi
  # Guard 2: stay inside this tier's allowlist.
  if [[ ! "$service" =~ $ALLOWED_SERVICES_RE ]]; then
    echo "ERROR: refusing to stop '$service': it is outside DB_HA_ALLOWED_SERVICES_REGEX." >&2
    echo "If this tier legitimately gained a service, widen the regex in .env - do not remove the guard." >&2
    exit 1
  fi
  services+=("$service")
done <<<"$db_ha_services"

echo "DB-HA services in scope (${#services[@]}): ${services[*]}"
if $PURGE; then
  echo "Mode: containers + cluster volumes (--purge). The app/dev tier is NOT touched."
else
  echo "Mode: containers only; named volumes preserved (use --purge to delete them)."
fi

# --- volumes in scope (only consulted with --purge) --------------------------
volume_names=()
if $PURGE; then
  while IFS= read -r volume; do
    [[ -n "$volume" ]] || continue
    if [[ ! "$volume" =~ $ALLOWED_VOLUMES_RE ]]; then
      # Never delete what this tier does not own. Skipping (rather than aborting)
      # is deliberate: `config --volumes` can also report sources that are not
      # named volumes of this tier, and refusing the whole run over one of those
      # would make --purge unusable.
      printf 'SKIP: %s is outside DB_HA_ALLOWED_VOLUMES_REGEX - not deleted.\n' "$volume" >&2
      continue
    fi
    volume_names+=("$volume")
  done < <(compose_mysql_only config --volumes 2>/dev/null | sort -u)
  if (( ${#volume_names[@]} == 0 )); then
    echo "WARN: the model declares no volumes for this tier; --purge will delete nothing." >&2
  else
    echo "Volumes in scope (${#volume_names[@]}): ${volume_names[*]}"
  fi
fi

lab_containers() {
  docker ps -a --filter "label=lab=java-learn" --format '{{.Names}}' 2>/dev/null | tr -d '\r' | sort || true
}

if $DRY_RUN; then
  echo
  echo "DRY RUN - no container or volume will be changed."
  for service in "${services[@]}"; do
    printf 'would stop and remove: %s\n' "${PREFIX}${service}"
  done
  if $PURGE; then
    for volume in "${volume_names[@]}"; do
      printf 'would delete volume:   %s\n' "$volume"
    done
  fi
  echo
  echo "Current lab containers (must be a superset of what remains afterwards):"
  lab_containers | sed 's/^/  /'
  exit 0
fi

before_containers="$(lab_containers)"

# --- stop, then remove -------------------------------------------------------
# `stop` first for a clean InnoDB shutdown; the compose default (10s) is too short
# for a primary under load, hence DB_HA_STOP_TIMEOUT_SECONDS.
echo "Stopping ${#services[@]} DB-HA container(s) (grace ${STOP_TIMEOUT}s)..."
if ! compose stop -t "$STOP_TIMEOUT" "${services[@]}"; then
  echo "ERROR: 'compose stop' failed. Nothing was removed; inspect with:" >&2
  echo "       docker compose --profile $PROFILE ps -a ${services[*]}" >&2
  exit 1
fi

echo "Removing DB-HA containers..."
if ! compose rm -f -s "${services[@]}"; then
  echo "ERROR: 'compose rm' failed after a successful stop; the containers are stopped but" >&2
  echo "       still present. Re-run this script - it is safe to repeat." >&2
  exit 1
fi

# --- optional volume purge ---------------------------------------------------
if $PURGE; then
  for volume in ${volume_names[@]+"${volume_names[@]}"}; do
    if docker volume inspect "$volume" >/dev/null 2>&1; then
      echo "Deleting volume $volume ..."
      docker volume rm "$volume" >/dev/null
    else
      echo "Volume $volume does not exist; skipped."
    fi
  done
fi

# --- proof that nothing else moved -------------------------------------------
after_containers="$(lab_containers)"
disappeared="$(comm -23 <(printf '%s\n' "$before_containers") <(printf '%s\n' "$after_containers"))"
expected="${services[*]}"
unexpected=""
while IFS= read -r name; do
  [[ -n "$name" ]] || continue
  bare="${name#"$PREFIX"}"
  if [[ " $expected " != *" $bare "* ]]; then
    unexpected="$unexpected $name"
  fi
done <<<"$disappeared"
if [[ -n "$unexpected" ]]; then
  echo "ERROR: containers disappeared that are not part of the DB-HA tier:$unexpected" >&2
  echo "This script did not remove them (it names its services explicitly), but the" >&2
  echo "always-on stack changed during the run - check for a concurrent up.sh/down.sh." >&2
  exit 1
fi

echo
echo "DB-HA tier is down. Always-on dev stack untouched (jl-net and its containers survive)."
if $PURGE; then
  echo "Cluster volumes were deleted: the next db-ha-up.sh re-bootstraps from scratch (clone recovery)."
else
  echo "Named volumes preserved, so the next db-ha-up.sh rejoins the existing group."
fi
echo "Verify: bash $SCRIPT_DIR/db-cluster-status.sh   (expect 'no node answered')"
