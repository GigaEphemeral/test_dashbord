#!/usr/bin/env bash
# Zero-dependency tests for image-identity.sh (Stage2 B0-08 slice A).
# Nothing here touches the real Docker daemon: a mock `docker` is placed on
# PATH, and the degradation path is exercised by removing it again.
#
# Run: bash code/lab/scripts/test-image-identity.sh
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$DIR/image-identity.sh"
BENCH="$DIR/bench.sh"
TUNE="$DIR/tune.sh"
failures=0
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/image-identity-test.XXXXXX")"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; failures=$((failures + 1)); }

cleanup() { rm -rf "$TMP_ROOT"; }
trap cleanup EXIT

# --- mock docker --------------------------------------------------------------
MOCK_BIN="$TMP_ROOT/mockbin"
mkdir -p "$MOCK_BIN"
cat >"$MOCK_BIN/docker" <<'MOCK'
#!/usr/bin/env bash
# Mock of the only three docker calls image-identity.sh makes.
set -u
mode="container"
if [[ "${1:-}" == "image" ]]; then mode="image"; shift; fi
if [[ "${1:-}" == "info" ]]; then exit 0; fi
if [[ "${1:-}" != "inspect" ]]; then exit 1; fi
shift
if [[ "${1:-}" != "-f" ]]; then exit 1; fi
fmt="$2"; target="$3"
if [[ "$mode" == "image" ]]; then
  case "$target" in
    sha256:aaaa*) printf 'java-learn-lab/seckill-app:jdk21\n' ;;
    sha256:bbbb*) printf 'java-learn-lab/chaos-controller:jdk21\n' ;;
    sha256:cccc*) printf '\n' ;;   # built locally, never tagged: no RepoTags
    *) exit 1 ;;
  esac
  exit 0
fi
case "$target" in
  jl-app-1)
    case "$fmt" in
      '{{.Image}}') printf 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1111\n' ;;
      '{{.State.Status}}') printf 'running\n' ;;
      '{{.Config.Image}}') printf 'java-learn-lab/seckill-app:jdk21\n' ;;
      *Labels*) printf 'java-learn-lab\n' ;;
      *) exit 1 ;;
    esac ;;
  jl-app-2)
    case "$fmt" in
      '{{.Image}}') printf 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc2222\n' ;;
      '{{.State.Status}}') printf 'running\n' ;;
      '{{.Config.Image}}') printf 'java-learn-lab/seckill-app:local-build-no-tag\n' ;;
      *Labels*) printf 'java-learn-lab\n' ;;
      *) exit 1 ;;
    esac ;;
  jl-chaos-controller)
    case "$fmt" in
      '{{.Image}}') printf 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb3333\n' ;;
      '{{.State.Status}}') printf 'running\n' ;;
      '{{.Config.Image}}') printf 'java-learn-lab/chaos-controller:jdk21\n' ;;
      *Labels*) printf 'java-learn-lab\n' ;;
      *) exit 1 ;;
    esac ;;
  *) exit 1 ;;   # unknown container, exactly like `docker inspect` on a missing name
esac
MOCK
chmod +x "$MOCK_BIN/docker"

# --- 1. tag + id read from the mock daemon -----------------------------------
OUT="$TMP_ROOT/images.json"
if PATH="$MOCK_BIN:$PATH" bash "$LIB" --out "$OUT" \
  --containers "jl-app-1 jl-app-2 jl-chaos-controller" >"$TMP_ROOT/record.log" 2>"$TMP_ROOT/record.err"; then
  pass "CLI records with the mock docker (exit 0)"
else
  fail "CLI records with the mock docker (exit 0)"
  cat "$TMP_ROOT/record.err" >&2
fi
if grep -q '^Image identity recorded:' "$TMP_ROOT/record.log"; then
  pass "CLI prints the 'recorded' line and the per-container summary"
else
  fail "CLI prints the 'recorded' line and the per-container summary"
fi

# --- 2. JSON validity + field values -----------------------------------------
node -e '
const assert = require("node:assert");
const m = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"));
assert.strictEqual(m.schemaVersion, 1);
assert.strictEqual(m.dockerAvailable, true);
assert.match(m.method, /docker inspect --format \{\{\.Image\}\}/);
const c = m.containers;
assert.strictEqual(c["jl-app-1"].imageId, "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1111");
assert.strictEqual(c["jl-app-1"].imageTag, "java-learn-lab/seckill-app:jdk21");
assert.strictEqual(c["jl-app-1"].status, "ok");
assert.strictEqual(c["jl-app-1"].composeProject, "java-learn-lab");
// untagged local build: tag falls back to the image reference of the container
assert.strictEqual(c["jl-app-2"].imageTag, "java-learn-lab/seckill-app:local-build-no-tag");
assert.strictEqual(c["jl-chaos-controller"].imageTag, "java-learn-lab/chaos-controller:jdk21");
' "$OUT" 2>"$TMP_ROOT/json.err" \
  && pass "manifest JSON is valid and carries imageTag + imageId per container" \
  || { fail "manifest JSON is valid and carries imageTag + imageId per container"; cat "$TMP_ROOT/json.err" >&2; }

# --- 2b. LOGICAL service names resolve on the lab stack ----------------------
# The lab stack pins `container_name: jl-app-1`, so the Compose SERVICE name
# (`app-1`) is not a container name: it has to come back through the jl- alias.
OUT_LOGICAL="$TMP_ROOT/images-logical.json"
if PATH="$MOCK_BIN:$PATH" bash "$LIB" --out "$OUT_LOGICAL" \
  --containers "app-1 chaos-controller" --project java-learn-lab >"$TMP_ROOT/logical.log" 2>&1; then
  pass "logical service names record with the mock docker (exit 0)"
else
  fail "logical service names record with the mock docker (exit 0)"
  cat "$TMP_ROOT/logical.log" >&2
fi
node -e '
const assert = require("node:assert");
const m = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"));
assert.match(m.nameResolution, /com\.docker\.compose\.service/);
assert.strictEqual(m.composeProject, "java-learn-lab");
// keyed by the requested logical name, resolved to the real container
assert.strictEqual(m.containers["app-1"].requested, "app-1");
assert.strictEqual(m.containers["app-1"].container, "jl-app-1");
assert.strictEqual(m.containers["app-1"].resolvedBy, "labAlias");
assert.strictEqual(m.containers["app-1"].status, "ok");
assert.strictEqual(m.containers["app-1"].imageTag, "java-learn-lab/seckill-app:jdk21");
assert.strictEqual(m.containers["chaos-controller"].container, "jl-chaos-controller");
assert.strictEqual(m.containers["chaos-controller"].resolvedBy, "labAlias");
// a real container name still resolves as itself (no jl- double prefix)
' "$OUT_LOGICAL" 2>"$TMP_ROOT/logical-json.err" \
  && pass "logical name -> jl-<service> alias, with requested/container/resolvedBy in the record" \
  || { fail "logical name -> jl-<service> alias, with requested/container/resolvedBy in the record"; cat "$TMP_ROOT/logical-json.err" >&2; }
if PATH="$MOCK_BIN:$PATH" bash "$LIB" --out "$TMP_ROOT/images-plain.json" \
  --containers "jl-app-1" >/dev/null 2>&1 \
  && grep -q '"container": "jl-app-1", "requested": "jl-app-1", "resolvedBy": "container"' "$TMP_ROOT/images-plain.json"; then
  pass "an explicit container name still resolves as itself (resolvedBy=container)"
else
  fail "an explicit container name still resolves as itself (resolvedBy=container)"
fi

# --- 2c. a stack WITHOUT container_name resolves on the service label --------
# Same service names, but the containers are Compose-prefixed (`mylayer-app-1`)
# and no `jl-` alias exists, so only `com.docker.compose.service` survives.
LABEL_BIN="$TMP_ROOT/labelbin"
mkdir -p "$LABEL_BIN"
cat >"$LABEL_BIN/docker" <<'LABEL'
#!/usr/bin/env bash
# Mock of a stack whose services are prefixed (no container_name in the file).
set -u
if [[ "${1:-}" == "image" ]]; then
  shift
  if [[ "${1:-}" == "inspect" && "${2:-}" == "-f" ]]; then
    case "$4" in
      sha256:dddd*dddd1111) printf 'java-learn-lab/seckill-app:jdk21\n'; exit 0 ;;
      sha256:eeee*eeee2222) printf '\n'; exit 0 ;;   # untagged local build
    esac
  fi
  exit 1
fi
if [[ "${1:-}" == "info" ]]; then exit 0; fi
if [[ "${1:-}" == "ps" ]]; then
  shift
  proj=""; svc=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --filter)
        shift
        case "${1:-}" in
          label=com.docker.compose.project=*) proj="${1#label=com.docker.compose.project=}" ;;
          label=com.docker.compose.service=*) svc="${1#label=com.docker.compose.service=}" ;;
        esac ;;
    esac
    shift
  done
  [[ "$proj" == "mylayer" || -z "$proj" ]] || exit 0   # other projects: nothing
  case "$svc" in
    app-1) printf 'cafe1111\n' ;;
    chaos-controller) printf 'cafe2222\n' ;;
  esac
  exit 0
fi
if [[ "${1:-}" != "inspect" || "${2:-}" != "-f" ]]; then exit 1; fi
fmt="$3"; target="$4"
case "$target" in
  cafe1111)
    case "$fmt" in
      '{{.Image}}') printf 'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd1111\n' ;;
      '{{.Name}}') printf '/mylayer-app-1\n' ;;
      '{{.State.Status}}') printf 'running\n' ;;
      *Labels*) printf 'mylayer\n' ;;
      *) exit 1 ;;
    esac ;;
  cafe2222)
    case "$fmt" in
      '{{.Image}}') printf 'sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee2222\n' ;;
      '{{.Name}}') printf '/mylayer-chaos-controller-1\n' ;;
      '{{.State.Status}}') printf 'running\n' ;;
      '{{.Config.Image}}') printf 'mylayer/chaos-controller:local-build-no-tag\n' ;;
      *Labels*) printf 'mylayer\n' ;;
      *) exit 1 ;;
    esac ;;
  *) exit 1 ;;   # jl-app-1 / app-1 do NOT exist as container names here
esac
LABEL
chmod +x "$LABEL_BIN/docker"
OUT_LABEL="$TMP_ROOT/images-label-stack.json"
if PATH="$LABEL_BIN:$PATH" bash "$LIB" --out "$OUT_LABEL" \
  --containers "app-1 chaos-controller" --project mylayer >"$TMP_ROOT/label.log" 2>&1; then
  pass "a stack without container_name records through the service label (exit 0)"
else
  fail "a stack without container_name records through the service label (exit 0)"
  cat "$TMP_ROOT/label.log" >&2
fi
node -e '
const assert = require("node:assert");
const m = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"));
assert.strictEqual(m.containers["app-1"].container, "mylayer-app-1");
assert.strictEqual(m.containers["app-1"].resolvedBy, "composeService");
assert.strictEqual(m.containers["app-1"].status, "ok");
assert.strictEqual(m.containers["app-1"].composeProject, "mylayer");
assert.strictEqual(m.containers["app-1"].imageId, "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd1111");
// untagged local build reached by label: imageTag falls back to .Config.Image
assert.strictEqual(m.containers["chaos-controller"].container, "mylayer-chaos-controller-1");
assert.strictEqual(m.containers["chaos-controller"].imageTag, "mylayer/chaos-controller:local-build-no-tag");
' "$OUT_LABEL" 2>"$TMP_ROOT/label-json.err" \
  && pass "com.docker.compose.service label resolves the logical name; jl- prefix not required" \
  || { fail "com.docker.compose.service label resolves the logical name; jl- prefix not required"; cat "$TMP_ROOT/label-json.err" >&2; }
# a wrong --project must not lose the container: the label lookup retries unscoped
if PATH="$LABEL_BIN:$PATH" bash "$LIB" --out "$TMP_ROOT/images-wrong-project.json" \
  --containers "app-1" --project somebody-elses-stack >/dev/null 2>&1 \
  && grep -q '"container": "mylayer-app-1", "requested": "app-1", "resolvedBy": "composeService"' "$TMP_ROOT/images-wrong-project.json"; then
  pass "a wrong --project still resolves (unscoped label retry), never a silent miss"
else
  fail "a wrong --project still resolves (unscoped label retry), never a silent miss"
fi

# --- 3. missing container degrades, never fails ------------------------------
OUT2="$TMP_ROOT/images-missing.json"
PATH="$MOCK_BIN:$PATH" bash "$LIB" --out "$OUT2" --containers "jl-app-1 jl-gone" \
  >"$TMP_ROOT/miss.log" 2>"$TMP_ROOT/miss.err"
if [[ $? -eq 0 ]]; then
  pass "an absent container does not fail the record"
else
  fail "an absent container does not fail the record"
fi
if grep -q '"status": "missing"' "$OUT2" && grep -q '"imageId": "unavailable"' "$OUT2"; then
  pass "absent container -> status=missing, imageTag/imageId=unavailable"
else
  fail "absent container -> status=missing, imageTag/imageId=unavailable"
fi
if grep -q '"container": "jl-gone", "requested": "jl-gone", "resolvedBy": "unresolved"' "$OUT2"; then
  pass "absent container keeps its own name in the record (row is still attributable)"
else
  fail "absent container keeps its own name in the record (row is still attributable)"
fi
if grep -qi 'WARN' "$TMP_ROOT/miss.err"; then
  pass "absent container emits a WARN"
else
  fail "absent container emits a WARN"
fi
# a logical name nothing can resolve degrades the same way (no jl- guessing loop)
OUT_UNRESOLVED="$TMP_ROOT/images-unresolved.json"
PATH="$MOCK_BIN:$PATH" bash "$LIB" --out "$OUT_UNRESOLVED" --containers "app-9" \
  >"$TMP_ROOT/unres.log" 2>&1
if grep -q '"app-9": {"container": "app-9", "requested": "app-9", "resolvedBy": "unresolved"' \
  "$OUT_UNRESOLVED" && grep -q '"status": "missing"' "$OUT_UNRESOLVED"; then
  pass "an unknown logical service -> status=missing, exit 0, no infinite alias chain"
else
  fail "an unknown logical service -> status=missing, exit 0, no infinite alias chain"
  cat "$OUT_UNRESOLVED" >&2
fi

# --- 4a. daemon unreachable: unavailable everywhere, exit 0 ------------------
DOWN_BIN="$TMP_ROOT/downbin"
mkdir -p "$DOWN_BIN"
cat >"$DOWN_BIN/docker" <<'DOWN'
#!/usr/bin/env bash
# Mock of an installed docker CLI with a dead daemon: every call fails.
exit 1
DOWN
chmod +x "$DOWN_BIN/docker"
OUT3="$TMP_ROOT/images-down.json"
PATH="$DOWN_BIN:$PATH" bash "$LIB" --out "$OUT3" --containers "jl-app-1" \
  >"$TMP_ROOT/down.log" 2>"$TMP_ROOT/down.err"
status=$?
if [[ $status -eq 0 ]]; then
  pass "an unreachable daemon still exits 0 (archive is never lost)"
else
  fail "an unreachable daemon still exits 0 (archive is never lost) (exit $status)"
fi
if grep -q '"dockerAvailable": false' "$OUT3" \
  && grep -q '"imageTag": "unavailable"' "$OUT3" \
  && grep -q '"imageId": "unavailable"' "$OUT3"; then
  pass "unreachable daemon -> dockerAvailable=false, tag/id=unavailable"
else
  fail "unreachable daemon -> dockerAvailable=false, tag/id=unavailable"
  cat "$OUT3" >&2
fi
if grep -qi 'WARN' "$TMP_ROOT/down.err"; then
  pass "unreachable daemon emits a WARN"
else
  fail "unreachable daemon emits a WARN"
fi

# --- 4b. no docker in PATH at all --------------------------------------------
# PATH minus the directory that provides the real docker CLI; the copied mock
# dirs are NOT on this PATH, so `command -v docker` must fail.
docker_dir="$(dirname "$(command -v docker 2>/dev/null || true)")"
NO_DOCKER_PATH=""
if [[ -n "$docker_dir" && "$docker_dir" != "." ]]; then
  NO_DOCKER_PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -v -F "$docker_dir" | paste -sd: -)"
else
  NO_DOCKER_PATH="$PATH"
fi
if PATH="$NO_DOCKER_PATH" bash -c 'command -v docker' >/dev/null 2>&1; then
  echo "SKIP: docker is still reachable after filtering PATH; 4b not exercisable here"
else
  OUT4="$TMP_ROOT/images-nodocker.json"
  PATH="$NO_DOCKER_PATH" bash "$LIB" --out "$OUT4" --containers "jl-app-1" \
    >"$TMP_ROOT/nod.log" 2>"$TMP_ROOT/nod.err"
  status=$?
  if [[ $status -eq 0 ]]; then
    pass "no docker in PATH still exits 0 (archive is never lost)"
  else
    fail "no docker in PATH still exits 0 (archive is never lost) (exit $status)"
  fi
  if grep -q '"dockerAvailable": false' "$OUT4" \
    && grep -q '"imageTag": "unavailable"' "$OUT4" \
    && grep -q '"imageId": "unavailable"' "$OUT4" \
    && grep -q '"resolvedBy": "noDocker"' "$OUT4" \
    && grep -qi 'WARN' "$TMP_ROOT/nod.err"; then
    pass "no docker in PATH -> dockerAvailable=false, tag/id=unavailable, WARN"
  else
    fail "no docker in PATH -> dockerAvailable=false, tag/id=unavailable, WARN"
    cat "$OUT4" >&2
  fi
fi

# --- 5. wiring: bench.sh archives it, rebuild stays explicit -----------------
if grep -q 'image-identity.sh' "$BENCH" && grep -q 'image-identity.json' "$BENCH"; then
  pass "bench.sh records the image identity into the run archive"
else
  fail "bench.sh records the image identity into the run archive"
fi
if grep -q -- '--image-identity' "$BENCH"; then
  pass "bench.sh forwards the record to bench-report.js (manifest field)"
else
  fail "bench.sh forwards the record to bench-report.js (manifest field)"
fi
if grep -q -- '--force-recreate' "$TUNE"; then
  pass "the app rebuild (tune.sh) keeps an explicit --force-recreate"
else
  fail "the app rebuild (tune.sh) keeps an explicit --force-recreate"
fi

# --- 6. every changed shell file stays syntax-clean --------------------------
for script in "$LIB" "$BENCH" "$TUNE" "$BASH_SOURCE"; do
  if bash -n "$script" 2>/dev/null; then
    pass "bash -n $(basename "$script")"
  else
    fail "bash -n $(basename "$script")"
  fi
done

echo
if (( failures == 0 )); then
  echo "image-identity tests: ALL PASS"
else
  printf 'image-identity tests: %d FAILURE(S)\n' "$failures" >&2
  exit 1
fi
