#!/usr/bin/env bash
# Minimal image identity record (Stage2 B0-08 slice A, design 方案 §10.4).
#
#   bash code/lab/scripts/image-identity.sh --out /tmp/images.json
#   bash code/lab/scripts/image-identity.sh --out /tmp/images.json \
#        --containers "app-1 app-2 app-3 chaos-controller" --project java-learn-lab
#   # or as a library, right after a start/rebuild:
#   source code/lab/scripts/image-identity.sh
#   lab_record_image_identity code/lab/k6/reports/<run>/image-identity.json
#
# Containers are given as LOGICAL names, never as a hard-coded docker container
# name, because the same service is spelled differently across stacks: the lab
# stack pins `container_name: jl-app-1`, a stack without `container_name` gets
# the project-directory prefix (`some-layer-app-1`). Each spec resolves in this
# order: real container name/id -> lab alias `jl-<spec>` -> compose SERVICE name
# via the `com.docker.compose.service` label.
#
# Scope is the MINIMAL LOOP agreed in Stage2 B0-08 (confirm #8): read
# imageTag + imageId once after a start/rebuild with
#   docker inspect --format '{{.Image}}'      (image id, sha256 digest)
#   docker image inspect --format '{{.RepoTags}}'  (the tag pointing at it)
# and write them into the run manifest / report. This script deliberately does
# NOT verify where the image came from, does NOT archive build logs and does NOT
# pin a base-image digest - full provenance stays a Future item.
#
# Degradation contract: no Docker, an unreachable daemon or a missing container
# never fails the caller. The affected fields become the literal string
# "unavailable" (or "missing"), one WARN per problem is written to stderr and
# the exit status is 0, so an archive is never lost because of this record.
#
# No dependencies beyond docker, git-free: only bash + coreutils are used.
# Deliberately NO `set -euo pipefail` here: this file is also meant to be
# sourced, and the CLI block below turns the options on for itself only.

# Default key containers, as LOGICAL service names of the lab stack: the app
# tier plus the chaos controller. Each name is resolved to the real container
# (see the header), so the same list works for a stack that pins
# `container_name: jl-app-1` and for one that lets Compose prefix the services.
# Override with IMAGE_IDENTITY_CONTAINERS when a stack has more or fewer.
IMAGE_IDENTITY_CONTAINERS="${IMAGE_IDENTITY_CONTAINERS:-app-1 app-2 app-3 chaos-controller}"

# Optional Compose project used to narrow the service-label lookup
# (`com.docker.compose.project`). Empty = search every project, which is what a
# single-stack host wants. Never required: the label lookup runs project-scoped
# first and then unscoped, so a wrong value here cannot lose a container.
IMAGE_IDENTITY_PROJECT="${IMAGE_IDENTITY_PROJECT:-}"

# The unavailable marker is also what a reader should look for in a manifest.
IMAGE_IDENTITY_UNAVAILABLE="unavailable"

lab_ii_warn() {
  printf 'WARN: %s\n' "$*" >&2
}

# Strip characters that would break the JSON we hand-assemble (a tag or a
# digest never legitimately contains them).
lab_ii_sanitize() {
  printf '%s' "$1" | tr -d '\r\n"\\'
}

# One `docker inspect` field for one container; empty string when it fails.
lab_ii_inspect() { # <format> <container>
  docker inspect -f "$1" "$2" 2>/dev/null | tr -d '\r' | head -n 1 || true
}

# One `docker image inspect` field for one image id; empty string on failure.
lab_ii_image_inspect() { # <format> <image-id>
  docker image inspect -f "$1" "$2" 2>/dev/null | tr -d '\r' | head -n 1 || true
}

# Container ids of a Compose SERVICE, newest last. Optional second argument
# narrows the search to one project. Empty when nothing matches or the daemon
# is not answering - `docker ps` is the only command here that can list.
lab_ii_ps_service() { # <service> [project]
  local service="$1" project="${2:-}" out
  if [[ -n "$project" ]]; then
    out="$(docker ps -aq \
      --filter "label=com.docker.compose.project=$project" \
      --filter "label=com.docker.compose.service=$service" 2>/dev/null \
      | tr -d '\r' | tail -n 1 || true)"
  else
    out="$(docker ps -aq \
      --filter "label=com.docker.compose.service=$service" 2>/dev/null \
      | tr -d '\r' | tail -n 1 || true)"
  fi
  printf '%s' "${out//[[:space:]]/}"
}

# Resolve one logical spec to a real `docker inspect` target.
# Prints "how<TAB>target" (empty target when nothing matched). `how` is the
# evidence a manifest reader needs to trust the row: container | labAlias |
# composeService | unresolved.
# The order is NOT accidental: `read` treats a TAB as IFS whitespace, so an
# EMPTY FIRST FIELD would be swallowed and the caller would read "unresolved"
# as the target. Keeping the non-empty marker first makes the empty
# (unresolved) case survive the round trip; an empty last field is preserved.
lab_ii_resolve() { # <spec>
  local spec="$1" image_id target="" how="unresolved" id=""
  image_id="$(lab_ii_inspect '{{.Image}}' "$spec")"
  if [[ -n "$image_id" ]]; then
    printf '%s\t%s' "container" "$spec"
    return 0
  fi
  # The lab stack pins container_name: jl-<service>, so the plain service name
  # is not a container name but the alias is.
  image_id="$(lab_ii_inspect '{{.Image}}' "jl-$spec")"
  if [[ -n "$image_id" ]]; then
    printf '%s\t%s' "labAlias" "jl-$spec"
    return 0
  fi
  # A stack that does not set container_name lets Compose prefix the service
  # with the project directory, so only the service label survives.
  if [[ -n "$IMAGE_IDENTITY_PROJECT" ]]; then
    id="$(lab_ii_ps_service "$spec" "$IMAGE_IDENTITY_PROJECT")"
  fi
  if [[ -z "$id" ]]; then
    id="$(lab_ii_ps_service "$spec")"
  fi
  if [[ -n "$id" ]] && [[ -n "$(lab_ii_inspect '{{.Image}}' "$id")" ]]; then
    printf '%s\t%s' "composeService" "$id"
    return 0
  fi
  printf '%s\t%s' "unresolved" ""
}

# "tag<TAB>id<TAB>state<TAB>project<TAB>status<TAB>container<TAB>resolvedBy".
# status is ok | missing | unavailable so the caller can tell them apart.
# Every field is non-empty by construction (a caller splits this on TAB, and
# `read` collapses adjacent TABs, so a blank middle field would shift all the
# fields after it).
lab_ii_container_fields() { # <container>
  local spec="$1" image_id image_tag state project status target="" how="unresolved" name
  if ! command -v docker >/dev/null 2>&1; then
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s' "$IMAGE_IDENTITY_UNAVAILABLE" "$IMAGE_IDENTITY_UNAVAILABLE" \
      "$IMAGE_IDENTITY_UNAVAILABLE" "$IMAGE_IDENTITY_UNAVAILABLE" "unavailable" \
      "$(lab_ii_sanitize "$spec")" "noDocker"
    return 0
  fi
  IFS=$'\t' read -r how target <<<"$(lab_ii_resolve "$spec")"
  if [[ -z "$target" ]]; then
    lab_ii_warn "no container matches $spec (tried the container name, the jl- alias and the com.docker.compose.service label); recording image identity as missing."
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s' "$IMAGE_IDENTITY_UNAVAILABLE" "$IMAGE_IDENTITY_UNAVAILABLE" \
      "$IMAGE_IDENTITY_UNAVAILABLE" "$IMAGE_IDENTITY_UNAVAILABLE" "missing" \
      "$(lab_ii_sanitize "$spec")" "$(lab_ii_sanitize "${how:-unresolved}")"
    return 0
  fi
  image_id="$(lab_ii_inspect '{{.Image}}' "$target")"
  name="$(lab_ii_inspect '{{.Name}}' "$target")"
  name="${name#/}"
  [[ -n "$name" ]] || name="$target"
  state="$(lab_ii_inspect '{{.State.Status}}' "$target")"
  project="$(lab_ii_inspect '{{ index .Config.Labels "com.docker.compose.project" }}' "$target")"
  image_tag="$(lab_ii_image_inspect '{{if .RepoTags}}{{index .RepoTags 0}}{{end}}' "$image_id")"
  if [[ -z "$image_tag" ]]; then
    # A locally built image that was never tagged has no RepoTags: fall back to
    # the image reference the container was created with. Still a tag, no guess.
    image_tag="$(lab_ii_inspect '{{.Config.Image}}' "$target")"
    if [[ -z "$image_tag" ]]; then
      image_tag="$IMAGE_IDENTITY_UNAVAILABLE"
      lab_ii_warn "no tag resolvable for container $name; imageTag recorded as \"$IMAGE_IDENTITY_UNAVAILABLE\"."
    fi
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s' \
    "$(lab_ii_sanitize "${image_tag:-$IMAGE_IDENTITY_UNAVAILABLE}")" \
    "$(lab_ii_sanitize "${image_id:-$IMAGE_IDENTITY_UNAVAILABLE}")" \
    "$(lab_ii_sanitize "${state:-unknown}")" "$(lab_ii_sanitize "${project:-unknown}")" "ok" \
    "$(lab_ii_sanitize "$name")" "$(lab_ii_sanitize "${how:-container}")"
}

# Write the JSON record for the given containers to stdout.
lab_image_identity_json() { # <container>...
  local -a names=("$@")
  local generated_at method="docker inspect --format {{.Image}} + docker image inspect --format {{.RepoTags}}"
  local docker_ok=true
  generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if ! command -v docker >/dev/null 2>&1; then
    docker_ok=false
    lab_ii_warn "docker is not in PATH; every imageTag/imageId is recorded as \"$IMAGE_IDENTITY_UNAVAILABLE\"."
  elif ! docker info >/dev/null 2>&1; then
    docker_ok=false
    lab_ii_warn "docker daemon is not reachable; every imageTag/imageId is recorded as \"$IMAGE_IDENTITY_UNAVAILABLE\"."
  fi

  printf '{\n'
  printf '  "schemaVersion": 1,\n'
  printf '  "generatedAt": "%s",\n' "$(lab_ii_sanitize "$generated_at")"
  printf '  "method": "%s",\n' "$(lab_ii_sanitize "$method")"
  printf '  "nameResolution": "%s",\n' 'container name -> jl-<service> alias -> com.docker.compose.service label'
  printf '  "composeProject": "%s",\n' "$(lab_ii_sanitize "${IMAGE_IDENTITY_PROJECT:-}")"
  printf '  "dockerAvailable": %s,\n' "$([[ $docker_ok == true ]] && printf true || printf false)"
  printf '  "note": "minimal loop only: tag/id recorded, no build provenance, no base digest pin",\n'
  printf '  "containers": {\n'

  local index=0 name tag id state project status container resolved_by
  for name in "${names[@]}"; do
    IFS=$'\t' read -r tag id state project status container resolved_by \
      <<<"$(lab_ii_container_fields "$name")"
    if [[ "$status" != "ok" && "$docker_ok" == true ]]; then
      lab_ii_warn "image identity for $name is $status."
    fi
    printf '    "%s": {"container": "%s", "requested": "%s", "resolvedBy": "%s", "imageTag": "%s", "imageId": "%s", "state": "%s", "composeProject": "%s", "status": "%s"}' \
      "$(lab_ii_sanitize "$name")" "$(lab_ii_sanitize "$container")" "$(lab_ii_sanitize "$name")" \
      "$(lab_ii_sanitize "$resolved_by")" "$(lab_ii_sanitize "$tag")" \
      "$(lab_ii_sanitize "$id")" "$(lab_ii_sanitize "$state")" "$(lab_ii_sanitize "$project")" \
      "$(lab_ii_sanitize "$status")"
    index=$(( index + 1 ))
    if (( index < ${#names[@]} )); then
      printf ',\n'
    else
      printf '\n'
    fi
  done
  printf '  }\n'
  printf '}\n'
}

# Record the image identity of the given containers into a file.
#   lab_record_image_identity <output-json> [container]...
# Never returns non-zero for a Docker problem (degradation contract above);
# a genuine I/O failure does, because the caller cannot write its archive then.
lab_record_image_identity() { # <output-file> [container]...
  local output="$1"
  shift
  local -a names=("$@")
  if (( ${#names[@]} == 0 )); then
    read -r -a names <<<"$IMAGE_IDENTITY_CONTAINERS"
  fi
  local tmp json_dir
  json_dir="$(dirname "$output")"
  mkdir -p "$json_dir"
  tmp="$output.tmp.$$"
  lab_image_identity_json "${names[@]}" >"$tmp"
  mv -f "$tmp" "$output"
  # Summary straight from the written file: no second Docker round per container.
  sed -n 's/^    "\([^"]*\)": {"container": "\([^"]*\)", .*"imageTag": "\([^"]*\)".*"imageId": "\([^"]*\)".*"status": "\([^"]*\)".*/Image identity: \1 -> \2 tag=\3 id=\4 (\5)/p' "$output"
  printf 'Image identity recorded: %s\n' "$output"
}

# --- CLI mode (also usable as a "record once after start/rebuild" command) ---
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  set -euo pipefail
  OUTPUT="${IMAGE_IDENTITY_OUT:-}"
  CONTAINERS_RAW="${IMAGE_IDENTITY_CONTAINERS:-}"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --out)
        [[ $# -ge 2 ]] || { printf 'ERROR: --out requires a value.\n' >&2; exit 2; }
        OUTPUT="$2"
        shift 2
        ;;
      --out=*)
        OUTPUT="${1#*=}"
        shift
        ;;
      --containers)
        [[ $# -ge 2 ]] || { printf 'ERROR: --containers requires a space-separated list.\n' >&2; exit 2; }
        CONTAINERS_RAW="$2"
        shift 2
        ;;
      --containers=*)
        CONTAINERS_RAW="${1#*=}"
        shift
        ;;
      --project)
        [[ $# -ge 2 ]] || { printf 'ERROR: --project requires a value.\n' >&2; exit 2; }
        IMAGE_IDENTITY_PROJECT="$2"
        shift 2
        ;;
      --project=*)
        IMAGE_IDENTITY_PROJECT="${1#*=}"
        shift
        ;;
      -h|--help)
        sed -n '1,33p' "${BASH_SOURCE[0]}"
        exit 0
        ;;
      *)
        printf 'ERROR: unknown argument "%s" (see --help).\n' "$1" >&2
        exit 2
        ;;
    esac
  done
  if [[ -z "$OUTPUT" ]]; then
    printf 'ERROR: --out <file> is required (or set IMAGE_IDENTITY_OUT).\n' >&2
    exit 2
  fi
  read -r -a cli_containers <<<"$CONTAINERS_RAW"
  lab_record_image_identity "$OUTPUT" "${cli_containers[@]}"
fi
