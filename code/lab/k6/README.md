# k6 base scenarios (M2-1) and bench runner (M2-2a)

This directory is the load-testing foundation for the HA lab: one shared
config/HTTP/flow library, five base scenarios, a dockerized single-scenario
runner and Prometheus remote write. Task 20a adds the lab reset, the
`bench.sh` orchestrator and per-run archives (`reports/<run_id>/`). Compare/D9
remains Task 20b; chaos timelines remain Task 22.

## Layout

```
code/lab/
├── k6/
│   ├── lib/config.js          # env defaults, derived stages, thresholds, run_id
│   ├── lib/http.js            # HTTP wrapper, expected statuses, custom metrics
│   ├── lib/flow.js            # login, product, order(+replay), seckill(+poll)
│   ├── scenarios/{smoke,load,stress,spike,soak}.js
│   ├── run.sh                 # dockerized scenario runner (+ summary export)
│   ├── reports/<run_id>/      # generated archives (gitignored)
│   └── README.md
└── scripts/
    ├── reset-lab.sh           # deterministic lab data reset
    ├── bench.sh               # preflight -> reset -> k6 -> verdict -> archive
    └── bench-report.js        # manifest.json + report.html + result doc
```

## Prerequisites

- The lab stack is running (16 `lab=java-learn` containers) and the `jl-net`
  Docker network exists.
- Prometheus has the remote-write receiver enabled. The lab compose file
  already passes `--web.enable-remote-write-receiver`; no extra flag is needed.
- A pinned k6 image is present locally:

```bash
docker pull grafana/k6:1.3.0
# digest: sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d
docker run --rm grafana/k6:1.3.0 version   # k6 v1.3.0
```

`run.sh` fails with a pull hint if `K6_IMAGE` is not available; it never
silently falls back to `latest`.

By default k6 runs **inside** the compose network:

- `K6_NETWORK=jl-net`
- `BASE_URL=http://nginx` (the Nginx entry; Nginx round-robins to app-1/2/3)
- remote write to `http://prometheus:9090/api/v1/write`

## Quick start

```bash
bash code/lab/k6/run.sh smoke
```

The runner prints the generated `run_id`, the target, the remote-write URL,
a PromQL query and a Prometheus graph URL, then prints k6's normal summary.
The shell exit code is k6's exit code.

## Bench runner and lab reset (M2-2a)

`bench.sh` is the repeatable entry point: it checks the stack (16 compose
services running/healthy, Prometheus ready, k6 image and scenario present),
resets the lab data, runs one k6 scenario, and archives the result. It never
starts the stack.

```bash
# Full archive + result doc under doc/process/benchmark/
bash code/lab/scripts/bench.sh smoke

# Short load: k6 env overrides after "--"
bash code/lab/scripts/bench.sh load -- LOAD_DURATION=1m

# Skip the reset (baseline is recorded as not reset)
bash code/lab/scripts/bench.sh smoke --no-reset

# Negative test: impossible threshold -> exit 99, archive still written
bash code/lab/scripts/bench.sh smoke --run-id neg-1 -- ERROR_RATE=0
```

Each run creates `code/lab/k6/reports/<run_id>/` (gitignored) with:

| File | Content |
|---|---|
| `summary.json` | k6 `--summary-export` output |
| `k6.log` | full k6 stdout/stderr |
| `manifest.json` | scenario, run id, start/end, duration, k6 image/exit, git commit/branch, reset baseline, thresholds, verdict |
| `report.html` | self-contained report (key metrics + threshold verdicts) |
| `reset.log` | reset-lab.sh output when the reset ran |

and `doc/process/benchmark/<date>-<scenario>-<run_id>.md` with key metrics,
threshold verdicts, Grafana time window, manifest summary and reset info.

`bench.sh` exits with k6's code when it is non-zero (a crossed threshold is
99) after writing the archive; otherwise it exits non-zero only for an
internal/report failure.

`reset-lab.sh` restores a repeatable data baseline without removing volumes:

```bash
bash code/lab/scripts/reset-lab.sh --dry-run   # print the plan and current state
bash code/lab/scripts/reset-lab.sh --yes       # truncate orders, stock=100, FLUSHDB, purge queue
```

It stops app-1/2/3 first (no consumer can write during the reset), truncates
`order_order`, sets every `inventory_stock` row to `RESET_BASELINE_STOCK`
(default 100, `reserved=0`), flushes the lab-only Redis DB, purges
`RABBITMQ_QUEUE` (default `seckill.order.queue`) through the management API,
starts the app tier again and waits for health. The startup seeder then
repopulates `seckill:stock:<id>` from MySQL. Flyway history, catalog seeds and
all named volumes are untouched. The compose project is pinned to
`java-learn-lab` and every container is checked against that project label.

Reset-specific overrides: `RESET_BASELINE_STOCK`, `RESET_APP_SERVICES`,
`RESET_MYSQL_SERVICE`/`RESET_MYSQL_CONTAINER`,
`RESET_REDIS_SERVICE`/`RESET_REDIS_CONTAINER`,
`RESET_RABBITMQ_SERVICE`/`RESET_RABBITMQ_CONTAINER`, `RABBITMQ_QUEUE`,
`RABBITMQ_MANAGEMENT_URL`, `RESET_APP_HEALTH_TIMEOUT_SECONDS`,
`RESET_APP_HEALTH_INTERVAL_SECONDS`, `RESET_VERIFY_RETRIES`,
`RESET_ENV_FILE`, `RESET_COMPOSE_PROJECT`.

Bench-specific overrides: `BENCH_REPORTS_DIR`, `BENCH_DOC_DIR`,
`BENCH_GRAFANA_URL`, `BENCH_GRAFANA_DASHBOARD_UID`, `BENCH_READY_TIMEOUT_SECONDS`,
`BENCH_READY_INTERVAL_SECONDS`, `BENCH_REQUIRED_SERVICES` (default: every
compose service). Credentials for the reset come from the lab `.env`; they are
never passed on a command line, written to an archive or committed.

## Scenarios

| Script | Default profile | Traffic | Verdict |
|---|---|---|---|
| `smoke.js` | 1 VU, 1 iteration, capped by `SMOKE_DURATION=30s` | login → product → order → repeat key → seckill → poll | errors < 1%, checks > 99% |
| `load.js` | 50 → 200 VU over `LOAD_DURATION=10m` (acceptance: 2m) | product reads only | errors < 1%, product P95 < 300ms, P99 < 800ms |
| `stress.js` | staged 50 → 500 VU (`STRESS_STAGES`) | product reads only | no threshold; inspect the knee (errors/latency per stage) |
| `spike.js` | 50 baseline → 800 VU for 60s, then down | seckill product `SECKILL_PRODUCT_ID` | 5xx/error < 1%; 409/429 are expected and counted |
| `soak.js` | 100 VU for `SOAK_DURATION=30m` (acceptance: 2m) | product reads only | errors < 1%, checks > 99% |

Smoke uses `per-vu-iterations` with `SMOKE_ITERATIONS=1` so one run creates
exactly one order and one seckill attempt. It does not loop for the full 30s;
`SMOKE_DURATION` is the upper bound (`maxDuration`).

Load stages are derived from the single `LOAD_DURATION` value:

- first `LOAD_RAMP_UP_FRACTION` (20%) ramps 0 → `LOAD_START_VUS` (50);
- next `LOAD_RAMP_PEAK_FRACTION` (20%) ramps 50 → `LOAD_VUS` (200);
- the remaining 60% holds `LOAD_VUS`.

The acceptance run `LOAD_DURATION=2m` therefore uses 24s / 24s / 72s stages.

`load`, `stress` and `soak` never create orders, so they do not drain stock.
`smoke` creates one order and one seckill attempt. `spike` may consume the
remaining stock of product 20; after the acceptance spike product 20 is sold
out and a smoke seckill legitimately returns 409.

## Environment variables

Every runtime value is environment-overridable. Defaults below live in
`lib/config.js`; the runner only passes variables that are set.

### Target and identity

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://nginx` | Absolute HTTP(S) base URL. |
| `RUN_ID` | generated by `run.sh` | Label on every k6 metric. `run.sh` shares one id across all VUs. |
| `PRODUCT_ID` | `1` | Product used by product reads and orders. |
| `SECKILL_PRODUCT_ID` | `20` | Product used by the seckill flow. |
| `ORDER_QUANTITY` | `1` | Quantity in `POST /api/orders`. |
| `SECKILL_QUANTITY` | `1` | Quantity in `POST /api/seckill/orders`. |
| `USER_PREFIX` | `k6` | Login/order user prefix. |
| `ORDER_KEY_PREFIX` | `k6-order` | Idempotency-Key prefix for orders. |
| `SECKILL_KEY_PREFIX` | `k6-seckill` | Idempotency-Key prefix for seckill. |

### Paths (URI prefixes)

| Variable | Default |
|---|---|
| `LOGIN_PATH` | `/api/login` |
| `PRODUCTS_PATH` | `/api/products` |
| `ORDERS_PATH` | `/api/orders` |
| `SECKILL_ORDERS_PATH` | `/api/seckill/orders` |

### HTTP and flow timing

| Variable | Default | Meaning |
|---|---|---|
| `HTTP_TIMEOUT` | `10s` | Per-request timeout passed to k6. |
| `THINK_TIME` | `1s` | Sleep after each load/stress/soak/spike iteration. |
| `GRACEFUL_STOP` | `5s` | k6 graceful stop window. |
| `GRACEFUL_RAMP_DOWN` | `10s` | Ramping-VU graceful ramp-down. |
| `SECKILL_EXPECTED_STATUSES` | `200,202,409,429` | Statuses accepted by the seckill POST and passed to `http.expectedStatuses`. |
| `SECKILL_POLL_ATTEMPTS` | `30` | Maximum poll attempts after a 202. |
| `SECKILL_POLL_INTERVAL` | `1s` | Sleep between poll attempts. |

### Scenario profiles

| Variable | Default | Meaning |
|---|---|---|
| `SMOKE_VUS` | `1` | Smoke VUs. |
| `SMOKE_ITERATIONS` | `1` | Iterations per smoke VU. |
| `SMOKE_DURATION` | `30s` | Smoke `maxDuration`. |
| `LOAD_START_VUS` | `50` | First load stage target. |
| `LOAD_VUS` | `200` | Peak load VUs. |
| `LOAD_DURATION` | `10m` | Total load duration (stages are derived). |
| `LOAD_RAMP_UP_FRACTION` | `0.2` | Fraction of total duration for 0 → start VUs. |
| `LOAD_RAMP_PEAK_FRACTION` | `0.2` | Fraction for start → peak VUs. |
| `STRESS_STAGES` | 1m/50, 2m/100, 2m/200, 2m/300, 2m/400, 2m/500, 1m/0 | JSON array of `{duration,target}`. |
| `SPIKE_BASELINE_VUS` | `50` | Baseline VUs before the spike. |
| `SPIKE_BASELINE_DURATION` | `30s` | Baseline phase duration. |
| `SPIKE_RAMP_UP` | `10s` | Ramp to spike peak. |
| `SPIKE_VUS` | `800` | Spike peak VUs. |
| `SPIKE_DURATION` | `60s` | Hold at the peak. |
| `SPIKE_RAMP_DOWN` | `10s` | Ramp back to 0. |
| `SOAK_VUS` | `100` | Soak VUs. |
| `SOAK_DURATION` | `30m` | Soak duration. |

### Thresholds, summary and runner

| Variable | Default | Meaning |
|---|---|---|
| `P95_MS` | `300` | Load product-read P95 limit (milliseconds). |
| `P99_MS` | `800` | Load product-read P99 limit (milliseconds). |
| `ERROR_RATE` | `0.01` | Maximum flow/server error **fraction**. |
| `CHECKS_RATE` | `0.99` | Minimum check pass **fraction**. |
| `SUMMARY_TREND_STATS` | `avg,min,med,p(90),p(95),p(99),max` | k6 summary trend statistics. |
| `SUMMARY_TIME_UNIT` | `ms` | k6 summary time unit. |
| `K6_IMAGE` | `grafana/k6:1.3.0` | Pinned runner image. |
| `K6_NETWORK` | `jl-net` | Docker network; `host` switches the default base URL to `http://127.0.0.1:8080`. |
| `K6_DIR` | `code/lab/k6` | Directory mounted at `/scripts`; must contain `scenarios/`. |
| `SCENARIO` | `smoke` | Scenario selected when no positional argument is given. |
| `PROM_URL` | `http://127.0.0.1:8080/prom` | Prometheus UI/API prefix used in the printed link. |
| `K6_PROMETHEUS_RW_SERVER_URL` | `http://prometheus:9090/api/v1/write` | Remote-write endpoint. |
| `K6_PROMETHEUS_RW_TREND_STATS` | `p(95),p(99),max` | Trend statistics exported by the remote-write output. |
| `K6_PROMETHEUS_RW_PUSH_INTERVAL` | `5s` | Remote-write push interval. |
| `K6_REPORT_DIR` | unset | Host directory mounted at `K6_REPORT_MOUNT` (default `/reports`). |
| `K6_SUMMARY_EXPORT` | unset | Container path for `--summary-export`; requires `K6_REPORT_DIR`. |
| `K6_FORWARD_ENV` | unset | Comma-separated extra variable names forwarded to the k6 container (bench uses it for `--` overrides). |

Validation is fail-fast: a malformed duration, non-integer VU/id, fraction
outside `[0,1)`, or invalid status list stops k6 during init with a
`[k6/config] ...` message.

## Commands

```bash
# Smoke (login -> product -> order/replay -> seckill/poll)
bash code/lab/k6/run.sh smoke

# Load: acceptance uses 2m, production-ish default is 10m
LOAD_DURATION=2m bash code/lab/k6/run.sh load

# Stress: reshape the stages for a short local run
STRESS_STAGES='[{"duration":"5s","target":5},{"duration":"5s","target":10},{"duration":"5s","target":0}]' \
  bash code/lab/k6/run.sh stress

# Spike: short local variant of the 50 -> 800 -> 60s profile
SPIKE_BASELINE_DURATION=5s SPIKE_RAMP_UP=5s SPIKE_DURATION=20s SPIKE_RAMP_DOWN=5s \
  bash code/lab/k6/run.sh spike

# Soak: short acceptance variant
SOAK_DURATION=2m SOAK_VUS=100 bash code/lab/k6/run.sh soak
```

Extra arguments before the script are passed to `k6 run`, for example
`bash code/lab/k6/run.sh smoke --quiet`. The runner always adds
`-o experimental-prometheus-rw`, so do not add a second output.

### Local / host overrides

- k6 binary on the host (stack published at loopback):
  `BASE_URL=http://127.0.0.1:8080 k6 run code/lab/k6/scenarios/smoke.js`
  (also set `RUN_ID` explicitly if you run with more than one VU).
- Dockerized against the host: `BASE_URL=http://host.docker.internal:8080
  bash code/lab/k6/run.sh smoke`.
- Where Docker host networking is available:
  `K6_NETWORK=host bash code/lab/k6/run.sh smoke` (the runner then defaults
  `BASE_URL` to `http://127.0.0.1:8080`).

A container on `jl-net` cannot reach the Windows/macOS host via `127.0.0.1`;
use `host.docker.internal` or host networking.

## Expected statuses and seckill semantics

`lib/http.js` passes `http.expectedStatuses(...SECKILL_EXPECTED_STATUSES)` as
the k6 response callback. The default list is:

- `200` — the `mq` toggle is off, so admission created the order synchronously;
- `202` — async admission accepted; a `requestId` is polled;
- `409` — sold out or seckill lock timeout;
- `429` — Resilience4j rate limit rejection.

For `200`/`202` the response body must be `code="0"` with a `CREATED` order
or a `requestId`. `409`/`429` are accepted business rejections: they do not
count as failed requests and are recorded in:

- `seckill_expected_409` → `k6_seckill_expected_409_total`
- `seckill_expected_429` → `k6_seckill_expected_429_total`
- `seckill_terminal_failed` → `k6_seckill_terminal_failed_total`
- `seckill_poll_timeouts` → `k6_seckill_poll_timeouts_total`

A 202 is polled with `GET /api/seckill/orders/{requestId}` up to
`SECKILL_POLL_ATTEMPTS` times every `SECKILL_POLL_INTERVAL`. `CREATED` must
carry an `orderId`; `FAILED` is terminal and increments
`seckill_terminal_failed`; exhausting the attempts increments
`seckill_poll_timeouts` and records a failed check.

HTTP 5xx and transport errors go into both `flow_errors` and `server_errors`.
Unexpected 4xx statuses (anything outside the expected list for that request)
also go into `flow_errors`.

## Thresholds and exit codes

- `smoke`, `load`, `soak` and `spike` have thresholds; `stress` deliberately
  has none (record only).
- k6 1.3 aborts on a crossed threshold and exits non-zero. A negative test
  with `ERROR_RATE=0` exits **99** and prints
  `thresholds on metrics 'flow_errors, server_errors' have been crossed`.
- `run.sh` propagates k6's exit code unchanged (Docker errors return 125).

Negative threshold test (intentionally impossible `rate<0`):

```bash
set +e
ERROR_RATE=0 bash code/lab/k6/run.sh smoke
status=$?
set -e
echo "exit=$status"   # 99 on the current k6 image
```

The smoke run still executes (one order/seckill attempt) before the verdict;
do not use it repeatedly against a finite-stock product.

## Remote write and Prometheus checks

`run.sh` sets these container environment variables:

```
K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write
K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),max
K6_PROMETHEUS_RW_PUSH_INTERVAL=5s
```

and runs `k6 run -o experimental-prometheus-rw`. With trend stats enabled the
trend series are exported as separate names, e.g.:

- `k6_http_req_duration_p95`
- `k6_http_req_duration_p99`
- `k6_http_req_duration_max`
- `k6_http_req_duration_avg`, `_min`, `_med`

`run_id` comes from `options.tags` in `lib/config.js`, so it is present on
HTTP metrics, custom rates/counters, checks, iterations and VU metrics.

Query the Prometheus API from the host (the UI path is proxied at `/prom/`):

```bash
RUN_ID="k6-..."   # printed by run.sh

curl -sS --get http://127.0.0.1:8080/prom/api/v1/query \
  --data-urlencode "query=k6_http_reqs_total{run_id=\"$RUN_ID\"}"

curl -sS --get http://127.0.0.1:8080/prom/api/v1/query \
  --data-urlencode "query=k6_http_req_duration_p95{run_id=\"$RUN_ID\",flow=\"product\"}"

curl -sS --get http://127.0.0.1:8080/prom/api/v1/query \
  --data-urlencode "query=count by (__name__) ({run_id=\"$RUN_ID\"})"
```

Useful PromQL:

```promql
# total requests in the run
sum(k6_http_reqs_total{run_id="..."})
# product-read percentiles
k6_http_req_duration_p95{run_id="...", flow="product"}
k6_http_req_duration_p99{run_id="...", flow="product"}
# flow health
k6_flow_errors_rate{run_id="..."}
k6_server_errors_rate{run_id="..."}
k6_checks_rate{run_id="..."}
# expected seckill rejections
k6_seckill_expected_409_total{run_id="..."}
k6_seckill_expected_429_total{run_id="..."}
# every family emitted for this run
count by (__name__) ({run_id="..."})
```

Prometheus instant queries only look back five minutes. To inspect an older
run, add `--data-urlencode "time=<unix-seconds>"` or use a range query:

```bash
curl -sS --get http://127.0.0.1:8080/prom/api/v1/query_range \
  --data-urlencode "query=sum(k6_http_reqs_total{run_id=\"$RUN_ID\"})" \
  --data-urlencode "start=<unix-start>" --data-urlencode "end=<unix-end>" \
  --data-urlencode "step=30s"
```

The acceptance run used three ids (smoke, load, spike); see
`doc/process/step-19-k6-base.md` for the exact results.

## Known WSL2 / Docker notes

- Git Bash rewrites container-side absolute paths such as `/scripts` into
  Windows paths. `run.sh` uses `cygpath -m` for the mount source and
  `MSYS_NO_PATHCONV=1` for the Docker call. If you call `docker run` yourself
  from Git Bash, add the same environment variable.
- Docker Desktop's host networking is optional. Prefer the default `jl-net`
  with `BASE_URL=http://nginx`; use `host.docker.internal` when a
  containerized k6 must reach the host-published port.
- The k6 image is pinned; a mirror can be used for the pull, but do not replace
  the tag with `latest` in a recorded run.
- Prometheus stores k6 series in the same 7-day TSDB retention as lab metrics.
  The default remote-write output carries `name`/`url` labels; for very large
  runs, lower the URL cardinality by using a `tags.name` value or filter at
  query time.
- If remote write returns 404, the receiver flag is missing. If k6 prints a
  remote-write connection error, verify `K6_PROMETHEUS_RW_SERVER_URL` and that
  k6 is on `jl-net`.
- Smoke and spike consume finite product stock. Run
  `bash code/lab/scripts/reset-lab.sh --yes` (or let `bench.sh` do it) before a
  repeat that expects fresh seckill stock; product 20 is restored to 100.

## Related

- Design: `doc/design/2026-10-26-ha-concurrency-lab-design.md` §11.1
- Existing HTTP smoke: `code/lab/scripts/smoke.sh`
- Task 19 handover: `doc/process/step-19-k6-base.md`
- Task 20a handover (reset/bench/archive): `doc/process/step-20a-bench-reset.md`
- Task 20b will add bench compare and D9; this directory intentionally has no
  compare output yet.
