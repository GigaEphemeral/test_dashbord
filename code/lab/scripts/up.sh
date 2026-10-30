#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/code/lab/docker"
ENV_FILE="$DOCKER_DIR/.env"
ENV_EXAMPLE="$DOCKER_DIR/.env.example"
DASHBOARD_DIR="$ROOT_DIR/code/lab/dashboard"
DASHBOARD_DIST_INDEX="$DASHBOARD_DIR/dist/index.html"

usage() {
  cat <<'EOF'
Usage: up.sh [--profile dev|ha|chaos-proxy|dual-jdk]

Start the java-learn-lab infrastructure stack.

Profiles:
  dev   Nginx, three app instances (app-1/2 JDK21, app-3 JDK8), MySQL, Redis, RabbitMQ, exporters,
        Prometheus, Grafana, Tempo, OTel Collector and cAdvisor (default)
  ha    the dev stack plus the Redis Sentinel topology from compose.ha.yml
        (1 master + 2 replicas + 3 Sentinels + 1 exporter per data node,
        all gated behind the "ha" compose profile, reachable on jl-net only)
  chaos-proxy
        the dev stack plus compose.toxiproxy.yml: one Toxiproxy between the
        app tier and Redis/MySQL (gated behind the "chaos-proxy" profile,
        jl-net only). The three app containers are recreated against the
        proxies, so use it as demo preparation, not as a runtime toggle.
        It is a dev-stack overlay and does NOT combine with ha / db-ha.
  dual-jdk
        the dev stack (16 services) plus the JDK 8 shell
        jl-seckill-app-jdk8, gated behind the "dual-jdk" compose profile,
        jl-net only, no host port. D7 dashboard (uid ha-d7) and Prometheus
        job seckill-app-jdk8. Total 17 services; the JDK 8 container is
        stopped by down.sh and is not part of the default render.

MySQL InnoDB Cluster (design §8.3) is started by db-ha-up.sh, not here.
EOF
}

PROFILE="dev"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: --profile requires a value (dev, ha, chaos-proxy or dual-jdk)." >&2
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

# dev always renders base+dev; ha overlays compose.ha.yml on top of it, so the
# Sentinel topology is additive and the dev service definitions stay byte-identical.
# chaos-proxy overlays compose.toxiproxy.yml on the SAME dev stack and selects its
# profile, so only the three app containers get the proxy endpoints; the option is
# deliberately exclusive: the overlay rewrites the app datastore links and must not
# be mixed with the ha / db-ha topologies (those own redis/mysql endpoints).
COMPOSE_FILES=(-f "$DOCKER_DIR/compose.base.yml" -f "$DOCKER_DIR/compose.dev.yml")
ACTIVE_PROFILES=()
case "$PROFILE" in
  dev)
    ;;
  ha)
    if [[ ! -f "$DOCKER_DIR/compose.ha.yml" ]]; then
      echo "ERROR: --profile ha needs $DOCKER_DIR/compose.ha.yml." >&2
      exit 1
    fi
    COMPOSE_FILES+=(-f "$DOCKER_DIR/compose.ha.yml")
    ACTIVE_PROFILES=(--profile ha)
    ;;
  chaos-proxy)
    if [[ ! -f "$DOCKER_DIR/compose.toxiproxy.yml" ]]; then
      echo "ERROR: --profile chaos-proxy needs $DOCKER_DIR/compose.toxiproxy.yml." >&2
      exit 1
    fi
    COMPOSE_FILES+=(-f "$DOCKER_DIR/compose.toxiproxy.yml")
    ACTIVE_PROFILES=(--profile chaos-proxy)
    ;;
  dual-jdk)
    # The JDK 8 shell is defined in compose.base.yml behind its own profile, so
    # no overlay file is needed: the default render stays at 16 services and
    # this profile renders 17.
    ACTIVE_PROFILES=(--profile dual-jdk)
    ;;
  db-ha)
    echo "ERROR: --profile db-ha is not an up.sh profile: the MySQL InnoDB Cluster is" >&2
    echo "       started by db-ha-up.sh, and chaos-proxy is a dev-stack overlay only." >&2
    exit 2
    ;;
  *,*|*+*)
    echo "ERROR: '$PROFILE' looks like a profile combination. up.sh takes exactly one" >&2
    echo "       profile: dev, ha, chaos-proxy or dual-jdk; chaos-proxy does not combine" >&2
    echo "       with ha / db-ha, and dual-jdk already includes the whole dev stack." >&2
    exit 2
    ;;
  *)
    echo "ERROR: unknown profile '$PROFILE'. Available: dev, ha, chaos-proxy, dual-jdk." >&2
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

# Every key declared in .env.example must exist in the local .env. Only key names
# are printed: values may be lab credentials and must never reach the log.
missing_keys=()
while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
  line="${raw_line%$'\r'}"
  [[ "$line" =~ ^[[:space:]]*$ ]] && continue
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  if [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*= ]]; then
    key="${BASH_REMATCH[1]}"
    if ! grep -Eq "^[[:space:]]*${key}[[:space:]]*=" "$ENV_FILE"; then
      missing_keys+=("$key")
    fi
  fi
done < "$ENV_EXAMPLE"

if (( ${#missing_keys[@]} > 0 )); then
  echo "ERROR: $ENV_FILE is missing keys required by .env.example:" >&2
  printf '  - %s\n' "${missing_keys[@]}" >&2
  echo "Add the missing keys to $ENV_FILE (copy the names from $ENV_EXAMPLE); values are not printed." >&2
  exit 1
fi

# Load lab defaults for display below. Do not export; compose gets them via --env-file.
# shellcheck disable=SC1090
source "$ENV_FILE"

compose() {
  docker compose \
    --project-name java-learn-lab \
    --project-directory "$DOCKER_DIR" \
    --env-file "$ENV_FILE" \
    "${COMPOSE_FILES[@]}" \
    "$@"
}

print_diagnostics() {
  echo "--- docker compose ps -a ---" >&2
  compose ps -a >&2 || true
  echo "--- docker compose logs (last 50 lines) ---" >&2
  compose logs --tail=50 >&2 || true
}

# The Vue console is a static build mounted into Nginx. dist is gitignored, so
# build it before `compose up` (a missing dist would become an empty directory)
# and refresh it whenever a tracked input is newer.
needs_dashboard_build=false
if [[ ! -f "$DASHBOARD_DIST_INDEX" ]]; then
  needs_dashboard_build=true
elif [[ -n "$(find \
      "$DASHBOARD_DIR/src" \
      "$DASHBOARD_DIR/index.html" \
      "$DASHBOARD_DIR/package.json" \
      "$DASHBOARD_DIR/package-lock.json" \
      "$DASHBOARD_DIR/vite.config.js" \
      -newer "$DASHBOARD_DIST_INDEX" -print -quit 2>/dev/null)" ]]; then
  needs_dashboard_build=true
fi

if $needs_dashboard_build; then
  if ! command -v npm >/dev/null 2>&1; then
    echo "ERROR: npm is required to build the lab console but was not found in PATH." >&2
    exit 1
  fi
  if [[ ! -f "$DASHBOARD_DIR/package-lock.json" ]]; then
    echo "ERROR: missing $DASHBOARD_DIR/package-lock.json; the console build must be reproducible." >&2
    exit 1
  fi
  echo "Building the lab console (Vue 3 + Vite)..."
  if ! (cd "$DASHBOARD_DIR" && npm ci --no-audit --no-fund && npm run build); then
    echo "ERROR: lab console build failed. Check npm/registry connectivity and retry." >&2
    echo "Hint: this lab was bootstrapped with https://registry.npmmirror.com when the default registry was slow." >&2
    exit 1
  fi
  if [[ ! -f "$DASHBOARD_DIST_INDEX" ]]; then
    echo "ERROR: npm run build finished but $DASHBOARD_DIST_INDEX is missing." >&2
    exit 1
  fi
  echo "Lab console built into $DASHBOARD_DIR/dist."
fi

echo "Starting java-learn-lab infrastructure (profile: $PROFILE)..."
if ! compose "${ACTIVE_PROFILES[@]}" up -d --wait --wait-timeout 240; then
  echo "ERROR: the stack did not become healthy within the timeout." >&2
  print_diagnostics
  exit 1
fi

# `compose up --wait` only waits on services that declare a healthcheck. The OTel
# Collector image is distroless and cannot define one, so a crash-looping service
# can still leave `--wait` with exit code 0. Check every service from the rendered
# compose model explicitly (docker inspect works on all platforms/Compose versions).
services=()
while IFS= read -r service; do
  if [[ -n "$service" ]]; then
    services+=("$service")
  fi
done < <(compose config --services)

if (( ${#services[@]} == 0 )); then
  echo "ERROR: 'docker compose config --services' returned no services." >&2
  print_diagnostics
  exit 1
fi

not_running=""
for service in "${services[@]}"; do
  # Every service in compose.base.yml sets container_name: jl-<service>.
  container="jl-$service"
  state="$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || true)"
  # chaos-proxy adds one one-shot service by design: toxiproxy-init must have
  # RUN and exited 0 (the app tier waits for it), so it is the only container
  # that is allowed - and required - not to be running.
  if [[ "$PROFILE" == "chaos-proxy" && "$container" == "jl-toxiproxy-init" ]]; then
    exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$container" 2>/dev/null || true)"
    if [[ "$state" != "exited" || "$exit_code" != "0" ]]; then
      echo "ERROR: $container must have exited 0 (state=${state:-missing}, exit=${exit_code:-?})." >&2
      not_running="$not_running $container"
    fi
    continue
  fi
  if [[ "$state" != "running" ]]; then
    echo "ERROR: $container is not running (state=${state:-missing})." >&2
    not_running="$not_running $container"
  fi
done
if [[ -n "$not_running" ]]; then
  echo "ERROR: containers not running after 'compose up --wait':$not_running" >&2
  print_diagnostics
  exit 1
fi
echo "Post-wait state check passed: all ${#services[@]} containers are running."

# Profile-gated services (the "ha" Sentinel topology, the "dual-jdk" JDK 8
# shell) are deliberately absent from the enumeration above, so the dev count
# and the dev-path output stay exactly as before. Enumerate them explicitly
# with the profile selected, minus the default set, and check each container
# the same way.
if [[ "$PROFILE" == "ha" || "$PROFILE" == "dual-jdk" ]]; then
  profile_services=()
  while IFS= read -r service; do
    if [[ -z "$service" ]]; then
      continue
    fi
    is_default=false
    for base_service in "${services[@]}"; do
      if [[ "$base_service" == "$service" ]]; then
        is_default=true
        break
      fi
    done
    if [[ "$is_default" == "false" ]]; then
      profile_services+=("$service")
    fi
  done < <(compose --profile "$PROFILE" config --services)

  if (( ${#profile_services[@]} == 0 )); then
    echo "ERROR: 'docker compose --profile $PROFILE config --services' listed no extra services." >&2
    print_diagnostics
    exit 1
  fi

  profile_not_running=""
  for service in "${profile_services[@]}"; do
    container="jl-$service"
    state="$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || true)"
    health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null || true)"
    if [[ "$state" != "running" ]]; then
      echo "ERROR: $container is not running (state=${state:-missing})." >&2
      profile_not_running="$profile_not_running $container"
    elif [[ "$health" == "unhealthy" ]]; then
      echo "ERROR: $container is unhealthy after 'compose up --wait'." >&2
      profile_not_running="$profile_not_running $container"
    fi
  done
  if [[ -n "$profile_not_running" ]]; then
    echo "ERROR: $PROFILE-profile containers not healthy after 'compose up --wait':$profile_not_running" >&2
    print_diagnostics
    exit 1
  fi
  echo "Post-wait state check passed: all ${#profile_services[@]} $PROFILE-profile containers are running."
fi

# Bind-mounted configs are not part of the Compose service hash, so reused
# containers would otherwise keep their stale in-memory config (and app DNS).
# Validate the mounted config first; never reload a broken file over a working
# in-memory config.
if ! test_output="$(docker exec jl-nginx nginx -t 2>&1)"; then
  echo "ERROR: nginx config test failed; not reloading:" >&2
  printf '%s\n' "$test_output" >&2
  print_diagnostics
  exit 1
fi
if ! reload_output="$(docker exec jl-nginx nginx -s reload 2>&1)"; then
  echo "ERROR: nginx failed to reload the mounted configuration: $reload_output" >&2
  print_diagnostics
  exit 1
fi
if ! docker exec jl-prometheus wget -qO- --post-data='' \
    http://127.0.0.1:9090/-/reload >/dev/null 2>&1; then
  echo "ERROR: prometheus failed to reload /etc/prometheus/prometheus.yml." >&2
  print_diagnostics
  exit 1
fi

# Background traffic (Task 23) is a profile-gated k6 container: create it without
# starting it, so the console can start/stop it via /lab/traffic/** while the
# default stack pays nothing for it. Non-fatal: without it the console just
# reports the traffic container as missing, and verify/bench never enumerate it
# because it stays outside the default profile.
if ! traffic_output="$(compose --profile traffic create traffic 2>&1)"; then
  echo "WARN: could not create the jl-traffic background container; the console's" \
       "background traffic will report it as missing." >&2
  printf '%s\n' "$traffic_output" >&2
fi

echo
echo "java-learn-lab dev stack is up:"
echo "  Nginx health  : http://127.0.0.1:8080/health"
echo "  Lab console   : http://127.0.0.1:8080/lab/ (token: LAB_TOKEN in code/lab/docker/.env)"
echo "  RabbitMQ mgmt : http://127.0.0.1:15672 (user: ${RABBITMQ_DEFAULT_USER:-lab}, pass: ${RABBITMQ_DEFAULT_PASS:-lab})"
echo "  MySQL (dev)   : 127.0.0.1:3306 (db: ${MYSQL_DATABASE:-seckill}, user: ${MYSQL_USER:-seckill})"
echo "  Redis (dev)   : 127.0.0.1:6379"
echo "  Grafana       : http://127.0.0.1:3000 (user: ${GRAFANA_ADMIN_USER:-admin}, pass: ${GRAFANA_ADMIN_PASSWORD:-admin})"
echo "  Traffic       : jl-traffic is created but stopped; start it from the console or with 'docker start jl-traffic'"
echo "  App tier      : jl-app-1/2 (JDK 21) + jl-app-3 (JDK 8), one Nginx upstream and one run node list"
echo
echo "Prometheus, Tempo, OTel Collector, cAdvisor and chaos-controller are internal to jl-net."
echo "Verify with: bash $SCRIPT_DIR/verify.sh"

# Sentinel topology summary (ha profile only). Nothing here is a dev-path output.
if [[ "$PROFILE" == "ha" ]]; then
  echo
  echo "Redis Sentinel topology (compose profile ha, jl-net + jl-sentinel-net, no host ports):"
  echo "  master name   : ${REDIS_HA_MASTER_NAME:-mymaster} (initial master ${REDIS_HA_REDIS_1_IP:-172.28.190.11}:${REDIS_HA_NODE_PORT:-6379})"
  echo "  data nodes    : jl-redis-1 (master), jl-redis-2 (replica, --replicaof)"
  echo "  pinned IPs    : nodes ${REDIS_HA_REDIS_1_IP:-172.28.190.11}/.2, sentinels ${REDIS_HA_SENTINEL_1_IP:-172.28.190.21}/.2 on subnet ${REDIS_HA_SUBNET:-172.28.190.0/24}"
  echo "  sentinels     : jl-sentinel-1, jl-sentinel-2 on ${REDIS_HA_SENTINEL_PORT:-26379} (quorum ${REDIS_HA_QUORUM:-2})"
  echo "  failover knobs: down-after ${REDIS_HA_DOWN_AFTER_MS:-5000}ms, failover-timeout ${REDIS_HA_FAILOVER_TIMEOUT_MS:-15000}ms, parallel-syncs ${REDIS_HA_PARALLEL_SYNC:-1}"
  echo "  exporters     : jl-redis-1-exporter, jl-redis-2-exporter on 9121 (not scraped by prometheus.yml yet)"
  echo "  dev jl-redis  : stays single-node for dev-only tools; this overlay switches the app tier to Sentinel"
  echo
  echo "Read-only topology view : bash $SCRIPT_DIR/sentinel-status.sh"
  echo "Sentinel verify checks  : REDIS_HA_VERIFY=auto bash $SCRIPT_DIR/verify.sh (skipped while jl-sentinel-1 is absent)"
fi

# Toxiproxy tier summary (chaos-proxy profile only). Nothing here is a dev/ha output.
if [[ "$PROFILE" == "chaos-proxy" ]]; then
  echo
  echo "Toxiproxy network-fault tier (compose profile chaos-proxy, jl-net only):"
  echo "  control API   : http://toxiproxy:${TOXIPROXY_API_PORT:-8474} (loopback http://127.0.0.1:${TOXIPROXY_API_PORT:-8474})"
  echo "  redis proxy   : toxiproxy:${TOXIPROXY_REDIS_PORT:-16379} -> jl-redis:6379 (app REDIS_HOST/REDIS_PORT)"
  echo "  mysql proxy   : toxiproxy:${TOXIPROXY_MYSQL_PORT:-13306} -> jl-mysql:3306 (both app Hikari pools)"
  echo "  app tier      : jl-app-1/2/3 were recreated with MYSQL_MODE=standalone behind the proxies"
  echo "  chaos URL     : CHAOS_TOXIPROXY_URL is injected into chaos-controller (Java actions: step-39)"
  echo
  echo "Proxy names   : redis, mysql (the chaos-controller actions address them by name)"
  echo "Back to direct datastore access : bash $SCRIPT_DIR/up.sh --profile dev (recreates the app tier)"
  echo "After restarting jl-toxiproxy   : docker start jl-toxiproxy-init (recreates the two proxies)"
fi

# D7 dual-JDK summary (dual-jdk profile only). Nothing here is a dev-path output.
if [[ "$PROFILE" == "dual-jdk" ]]; then
  echo
  echo "D7 dual-JDK comparison (compose profile dual-jdk, jl-net only, 17 services):"
  echo "  jdk8 service  : jl-seckill-app-jdk8 (seckill-app-jdk8:8080, no host port)"
  echo "  profile       : ${JDK8_APP_PROFILE:-mysql} (shared MySQL seed data; JDK8_APP_PROFILE=demo for H2)"
  echo "  heap cap      : ${JDK8_JAVA_TOOL_OPTIONS:--Xmx512m} (JDK 21 instances: ${APP_JAVA_TOOL_OPTIONS:--Xmx512m})"
  echo "  prometheus    : job seckill-app-jdk8, target seckill-app-jdk8:8080"
  echo "  dashboard     : Grafana D7 (uid ha-d7)"
  echo
  echo "k6 dual target: bash $SCRIPT_DIR/../k6/run.sh d7                      # JDK 21 via Nginx"
  echo "                BASE_URL=http://seckill-app-jdk8:8080 bash $SCRIPT_DIR/../k6/run.sh d7  # JDK 8"
  echo "Stop the JDK 8 shell without touching the dev stack: docker stop jl-seckill-app-jdk8"
fi
