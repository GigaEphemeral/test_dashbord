#!/usr/bin/env bash
# Minimal image identity record (Stage2 B0-08 slice A, design 方案 §10.4).
#
#   bash code/lab/scripts/image-identity.sh --out /tmp/images.json
#   bash code/lab/scripts/image-identity.sh --containers "jl-app-1 jl-chaos-controller"
#   # or as a library, right after a start/rebuild:
#   source code/lab/scripts/image-identity.sh
#   lab_record_image_identity code/lab/k6/reports/<run>/image-identity.json
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

# Default key containers of the lab stack (compose `container_name` values):
# the app tier plus the chaos controller. Override with
# IMAGE_IDENTITY_CONTAINERS when a stack has more or fewer of them.
IMAGE_IDENTITY_CONTAINERS="${IMAGE_IDENTITY_CONTAINERS:-jl-app-1 jl-app-2 jl-app-3 jl-chaos-controller}"

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

# Resolve one container to "tag<TAB>id<TAB>state<TAB>project<TAB>status".
# status is ok | missing | unavailable so the caller can tell them apart.
lab_ii_container_fields() { # <container>
  local name="$1" image_id image_tag state project status fallback
  if ! command -v docker >/dev/null 2>&1; then
    printf '%s\t%s\t%s\t%s\t%s' "$IMAGE_IDENTITY_UNAVAILABLE" "$IMAGE_IDENTITY_UNAVAILABLE" \
      "$IMAGE_IDENTITY_UNAVAILABLE" "$IMAGE_IDENTITY_UNAVAILABLE" "unavailable"
    return 0
  fi
  image_id="$(lab_ii_inspect '{{.Image}}' "$name")"
  if [[ -z "$image_id" ]]; then
    lab_ii_warn "container $name not found or not inspectable; recording image identity as missing."
    printf '%s\t%s\t%s\t%s\t%s' "$IMAGE_IDENTITY_UNAVAILABLE" "$IMAGE_IDENTITY_UNAVAILABLE" \
      "$IMAGE_IDENTITY_UNAVAILABLE" "$IMAGE_IDENTITY_UNAVAILABLE" "missing"
    return 0
  fi
  state="$(lab_ii_inspect '{{.State.Status}}' "$name")"
  project="$(lab_ii_inspect '{{ index .Config.Labels "com.docker.compose.project" }}' "$name")"
  image_tag="$(lab_ii_image_inspect '{{if .RepoTags}}{{index .RepoTags 0}}{{end}}' "$image_id")"
  if [[ -z "$image_tag" ]]; then
    # A locally built image that was never tagged has no RepoTags: fall back to
    # the image reference the container was created with. Still a tag, no guess.
    image_tag="$(lab_ii_inspect '{{.Config.Image}}' "$name")"
    if [[ -z "$image_tag" ]]; then
      image_tag="$IMAGE_IDENTITY_UNAVAILABLE"
      lab_ii_warn "no tag resolvable for container $name; imageTag recorded as \"$IMAGE_IDENTITY_UNAVAILABLE\"."
    fi
  fi
  printf '%s\t%s\t%s\t%s\t%s' \
    "$(lab_ii_sanitize "$image_tag")" "$(lab_ii_sanitize "$image_id")" \
    "$(lab_ii_sanitize "${state:-unknown}")" "$(lab_ii_sanitize "${project:-unknown}")" "ok"
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
  printf '  "dockerAvailable": %s,\n' "$([[ $docker_ok == true ]] && printf true || printf false)"
  printf '  "note": "minimal loop only: tag/id recorded, no build provenance, no base digest pin",\n'
  printf '  "containers": {\n'

  local index=0 entry tag id state project status
  for name in "${names[@]}"; do
    IFS=$'\t' read -r tag id state project status <<<"$(lab_ii_container_fields "$name")"
    if [[ "$status" != "ok" && "$docker_ok" == true ]]; then
      lab_ii_warn "image identity for $name is $status."
    fi
    printf '    "%s": {"container": "%s", "imageTag": "%s", "imageId": "%s", "state": "%s", "composeProject": "%s", "status": "%s"}' \
      "$(lab_ii_sanitize "$name")" "$(lab_ii_sanitize "$name")" "$(lab_ii_sanitize "$tag")" \
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
  sed -n 's/^    "\([^"]*\)": {.*"imageTag": "\([^"]*\)".*"imageId": "\([^"]*\)".*"status": "\([^"]*\)".*/Image identity: \1 tag=\2 id=\3 (\4)/p' "$output"
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
      -h|--help)
        sed -n '1,30p' "${BASH_SOURCE[0]}"
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
