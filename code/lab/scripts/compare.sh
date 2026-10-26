#!/usr/bin/env bash
# Compare two Task 20a bench runs (manifest + Prometheus peaks).
#
#   bash code/lab/scripts/compare.sh <runA> <runB>
#   bash code/lab/scripts/compare.sh 20261026-145555-smoke neg-threshold --out doc/process/benchmark/compare.md
#
# Environment:
#   BENCH_REPORTS_DIR                  default <repo>/code/lab/k6/reports
#   PROM_URL                           default http://127.0.0.1:8080/prom
#   BENCH_COMPARE_TIMEOUT_MS           default 5000 (per Prometheus query)
#   BENCH_COMPARE_WINDOW_GRACE_SECONDS default 15 (peak-window margin over the run)
#   BENCH_COMPARE_OUT                  optional default output file
#   BENCH_COMPARE_SCRIPT               override the node script path
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPARE_SCRIPT="${BENCH_COMPARE_SCRIPT:-$SCRIPT_DIR/bench-compare.js}"

if [[ ! -f "$COMPARE_SCRIPT" ]]; then
  printf 'ERROR: compare script not found: %s\n' "$COMPARE_SCRIPT" >&2
  exit 2
fi

exec node "$COMPARE_SCRIPT" "$@"
