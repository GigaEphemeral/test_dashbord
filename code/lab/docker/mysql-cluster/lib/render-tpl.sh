#!/usr/bin/env bash
# lab-mysql-cluster shared template renderer.
#
# Sourced by node-entrypoint.sh, cluster-init-entrypoint.sh and
# router-bootstrap-entrypoint.sh. It replaces every @@NAME@@ token in a template
# with the value of the environment variable NAME, and refuses to write output
# when a token has no value: a silently empty server_id or an empty
# group_replication_group_name would surface much later as a group that cannot
# form, which is the worst possible failure mode for this lab.
#
# POSIX-sh safe is not required: every caller is invoked through
# ${MYSQL_HA_SHELL_BIN} (bash by default).
set -euo pipefail

# render_template <template-file> <output-file>
#   Prints the substitution summary (token names only, never values: values may
#   be lab credentials) and exits non-zero on a missing variable.
render_template() {
  local tpl="$1" out="$2"
  local placeholders name value expr=""

  if [[ ! -f "$tpl" ]]; then
    printf 'ERROR: template not found: %s\n' "$tpl" >&2
    return 1
  fi

  placeholders="$(grep -o '@@[A-Za-z0-9_]*@@' "$tpl" | sort -u || true)"
  if [[ -z "$placeholders" ]]; then
    printf 'ERROR: template has no @@TOKEN@@ placeholders: %s\n' "$tpl" >&2
    return 1
  fi

  # One token per line; names never contain whitespace, so word splitting here
  # is intentional (shellcheck SC2066 would be right about the quoting rules).
  # shellcheck disable=SC2086
  for name in $placeholders; do
    name="${name#@@}"
    name="${name%@@}"
    if [[ -z "${!name+x}" ]]; then
      printf 'ERROR: %s requires environment variable %s (no default).\n' \
        "$(basename "$tpl")" "$name" >&2
      printf 'If %s appears only in a comment of the template, delete that literal\n' "$name" >&2
      printf 'example from the template: the scanner reads comments too, so prose\n' "$name" >&2
      printf 'tokens become required variables.\n' >&2
      return 1
    fi
    value="${!name}"
    if [[ -z "$value" ]]; then
      printf 'ERROR: environment variable %s is set but empty.\n' "$name" >&2
      return 1
    fi
    # '|' as sed delimiter (host:port and UUID values never contain it) and
    # '&' / '\' escaped inside the value so sed treats it as literal text.
    # '|' is escaped too: password tokens (exporter/monitor/app) can legally
    # contain it, and an unescaped '|' would truncate the s-expression.
    value="${value//\\/\\\\}"
    value="${value//&/\\&}"
    value="${value//|/\\|}"
    expr+="-e s|@@${name}@@|${value}|g "
  done

  mkdir -p "$(dirname "$out")"
  # shellcheck disable=SC2086 # expr is a built-up word list of -e expressions.
  sed $expr "$tpl" >"$out.tmp"
  # Anything left over means a token name did not match an env var name.
  if grep -q '@@[A-Za-z0-9_]*@@' "$out.tmp"; then
    printf 'ERROR: unsubstituted placeholders left in %s: %s\n' "$out" \
      "$(grep -o '@@[A-Za-z0-9_]*@@' "$out.tmp" | sort -u | tr '\n' ' ')" >&2
    rm -f "$out.tmp"
    return 1
  fi
  mv "$out.tmp" "$out"
  printf 'rendered %s -> %s (%s tokens)\n' \
    "$(basename "$tpl")" "$out" "$(printf '%s\n' "$placeholders" | wc -l | tr -d ' ')"
}

# require_env <VAR> [<VAR> ...] - fail fast with the variable names only.
require_env() {
  local missing=() name
  for name in "$@"; do
    if [[ -z "${!name:-}" ]]; then
      missing+=("$name")
    fi
  done
  if (( ${#missing[@]} > 0 )); then
    printf 'ERROR: missing environment variables: %s\n' "${missing[*]}" >&2
    printf 'They must come from code/lab/docker/.env (see .env.mysql.example).\n' >&2
    return 1
  fi
}
