#!/usr/bin/env bash
# Wrapper for check-doc-claims.mjs.
#
# Parameters are externalized:
#   REPO_ROOT             repository root (default: three levels above this script)
#   DOC_CLAIMS_REGISTRY   registry path, absolute or relative to REPO_ROOT
#   NODE_BIN              node executable (default: node)
#
# All arguments are forwarded to the .mjs (--json, --only <glob>, --help).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd "$SCRIPT_DIR/../../.." && pwd)}"
DOC_CLAIMS_REGISTRY="${DOC_CLAIMS_REGISTRY:-code/lab/docclaims/claims.yml}"
NODE_BIN="${NODE_BIN:-node}"

exec "$NODE_BIN" "$SCRIPT_DIR/check-doc-claims.mjs" \
  --root "$REPO_ROOT" \
  --registry "$DOC_CLAIMS_REGISTRY" \
  "$@"
