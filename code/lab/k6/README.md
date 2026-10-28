# k6 base scenarios (M2-1), bench/compare/D9 (M2-2), chaos timeline (M2-4a) and background traffic (M2-5)

This directory is the load-testing foundation for the HA lab: one shared
config/HTTP/flow library, five base scenarios, a dockerized single-scenario
runner and Prometheus remote write. Task 20a adds the lab reset, the
`bench.sh` orchestrator and per-run archives (`reports/<run_id>/`). Task 20b
adds run comparison (`compare.sh`) and the D9 bench dashboard. Task 22a adds
the data-driven chaos timeline (`ha-failover.js` + `lib/chaos*.js`) that
replays `code/lab/scenarios/*.json` against the chaos-controller. Task 23 adds
the console-driven background load (`traffic.js` + the profile-gated
`jl-traffic` compose service). Step 48 adds the two remaining HA/cache drills:
`db-failover.js` (reads + idempotent writes while a MySQL node is killed) and
`cache-lab.js` (seed once, then walk every `/lab/cache/mode` defense while
manufacturing the penetration, breakdown and avalanche traffic those defenses
exist to catch). Both are **opt-in**: they change cluster or cache state and are
not part of the bench baseline.

Stage2 B2-01 adds `scenarios/manifest.json` as the **single source of truth for
the scenario whitelist**: `run.sh` and `bench.sh` no longer keep their own case
lists, and the manifest also records the required profile, env forwarding,
whether a scenario writes business data and the documented parameter defaults.

## Layout

```
code/lab/
├── k6/
│   ├── lib/config.js          # env defaults, derived stages, thresholds, run_id
│   ├── lib/http.js            # HTTP wrapper, expected statuses, custom metrics
│   ├── lib/flow.js            # login, product, order(+replay), seckill(+poll)
│   ├── lib/chaos-schema.js    # pure scenario schema v1 validation (Node-runnable)
│   ├── lib/chaos.js           # controller client, chaos_events counter, loader
│   ├── lib/scenario-manifest.mjs  # manifest reader/validator (B2-01, plain node)
│   ├── scenarios/manifest.json    # single source of truth: views/requires/env/params
│   ├── scenarios/{smoke,load,stress,spike,soak}.js
│   ├── scenarios/ha-failover.js  # product load + chaos timeline + teardown
│   ├── scenarios/db-failover.js  # reads + idempotent writes + MySQL kill
│   ├── scenarios/cache-lab.js    # seed + mode schedule + per-mode metrics
│   ├── scenarios/traffic.js      # console-driven background read load (M2-5)
│   ├── test/chaos-schema.test.mjs  # node --test schema regression suite
│   ├── test/db-failover-scenario.test.mjs
│   ├── test/cache-lab-scenario.test.mjs
│   ├── test/scenario-manifest.test.mjs  # views/env/params + script-level mock
│   ├── run.sh                 # dockerized scenario runner (+ summary export)
│   ├── reports/<run_id>/      # generated archives (gitignored)
│   └── README.md
├── scenarios/                 # shared chaos timeline JSON (schema v1)
│   ├── README.md
│   ├── ha-app-failover.json
│   └── quick-failover.json
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

## Chaos timeline (M2-4a)

`scenarios/ha-failover.js` runs two k6 scenarios in parallel:

- `load` — constant VUs reading `GET /api/products/{PRODUCT_ID}` for
  `CHAOS_DURATION` (default `4m`, `CHAOS_VUS=20`).
- `chaos` — 1 VU, 1 iteration, `exec: 'chaosTimeline'`, starting at
  `CHAOS_START_OFFSET` (default `0s`). It loads `CHAOS_SCENARIO_FILE`
  (default `ha-app-failover.json`) from `/chaos-scenarios`, validates it, then
  sleeps until each `atSeconds` offset and calls
  `POST /chaos/instances/{target}/actions` with `X-Lab-Token`.

Every applied action increments `chaos_events{action,target,result}` with
`result=accepted`; the first failed call increments `result=failed`, logs the
error and stops the remaining timeline events. k6's `teardown` always posts
`/chaos/stop-all` (best effort) and logs `reverted=` / `failed=`, so an
aborted timeline never leaves a container stopped or frozen beyond its TTL.

```bash
# Production demo (dangerous: stops app-1 and freezes app-2) — 4 min
TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
LAB_TOKEN="$TOKEN" bash code/lab/k6/run.sh ha-failover

# Short live check: jl-app-3 only, kill at 5s, TTL 15s, 30s load
LAB_TOKEN="$TOKEN" CHAOS_SCENARIO_FILE=quick-failover.json \
  CHAOS_DURATION=30s CHAOS_VUS=6 \
  bash code/lab/k6/run.sh ha-failover
```

For **ha-failover runs only**, `run.sh` additionally mounts `code/lab/scenarios`
read-only at `/chaos-scenarios` (not under `/scripts`: that mount is already
read-only and holds the k6 JS scenarios) and forwards `LAB_TOKEN` plus the
`CHAOS_*` variables. The other scenarios keep their original mounts, printed
lines and environment — in particular the token never reaches their containers.
The token is passed as an environment variable only; it is never printed,
tagged or written to an archive.

The schema rules and both bundled JSON files are covered by a Node-runnable
regression suite:

```bash
node --test code/lab/k6/test/chaos-schema.test.mjs
```

Expected actions for the bundled files:

| File | Events | Notes |
|---|---|---|
| `ha-app-failover.json` | 60s kill app-1 (TTL 60), 120s freeze app-2 (TTL 30), 150s resume app-2 | 240s hint; Nginx serves from the remaining instances |
| `quick-failover.json` | 5s kill app-3 (TTL 15) | acceptance only; never targets datastores |
| `redis-sentinel-failover.json` | 10s kill jl-redis-1 (TTL 45), 25s resume jl-redis-2, 60s start jl-redis-1 | Sentinel drill; needs the HA target pattern **and** the datastore allow-list, and step 1 assumes jl-redis-1 is the current master (verify with `sentinel-status.sh` first) |

Safety rules: only containers labelled `lab=java-learn` are reachable; the
schema pattern default `^jl-app-[0-9]+$` keeps app instances in scope, and a
datastore-shaped name (mysql/redis/rabbit/mongo/postgres) is refused on top of
that unless `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` admits it by its **whole**
name. That allow-list is empty by default - closed by default - so MySQL,
standalone Redis and RabbitMQ stay out of every timeline until an operator
opts in for a specific drill (M3-1c; see `doc/process/Stage1/step-24c-sentinel-targets.md`).
`kill` is a Docker stop, never a volume removal.

Schema, field semantics and validation rules:
`code/lab/scenarios/README.md`.

### Chaos environment variables

| Variable | Default | Meaning |
|---|---|---|
| `LAB_TOKEN` | unset (required for `ha-failover`) | `X-Lab-Token` value; source it from the lab `.env`, never commit it. |
| `CHAOS_CONTROLLER_URL` | `http://chaos-controller:8081` | Controller base URL on `jl-net` (host runs: `http://127.0.0.1:8080/lab-api`). |
| `CHAOS_SCENARIO_FILE` | `ha-app-failover.json` | Bare name under `CHAOS_SCENARIO_DIR`, or an absolute path. |
| `CHAOS_SCENARIO_DIR` | `/chaos-scenarios` | Container mount point of `code/lab/scenarios` (set by `run.sh`). |
| `CHAOS_START_OFFSET` | `0s` | Delay before the timeline VU starts (k6 `startTime`); any zero form (`0`, `0.0`, `00`) is normalized to `0s`. |
| `CHAOS_HTTP_TIMEOUT` | `20s` | Per-action HTTP timeout. Must stay above the controller's synchronous Docker budget: `POST /chaos/instances/{target}/actions` answers only after the stop returns, and `chaos.stop-grace-seconds` defaults to 10s (a MySQL kill measured 11s). A too-small value reports `HTTP 0` for a fault that **was** applied and aborts the remaining timeline events — see `doc/avoid_common_error/15-k6-action-timeout-vs-docker-stop.md`. |
| `CHAOS_TEARDOWN_TIMEOUT` | `10s` | Timeout for the `stop-all` teardown call. |
| `CHAOS_MAX_TTL` | `600` | Schema ceiling for `ttlSeconds` (matches `chaos.max-ttl-seconds`). |
| `CHAOS_TARGET_PATTERN` | `^jl-app-[0-9]+$` | Allowed target regex; a datastore-shaped name needs the allow-list too, even if this is widened. |
| `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` | unset (empty = refuse all) | Whole-name allow-list of datastore containers a timeline may fault, e.g. `^jl-redis-[0-9]+$` for the Sentinel drill. Mirrors the controller's `chaos.datastore.allowed-target-pattern`; the controller still refuses independently. |
| `CHAOS_DURATION` | `4m` (`DURATION` is accepted as an alias) | Product-load duration. |
| `CHAOS_VUS` | `20` | Constant load VUs. |
| `K6_SCENARIOS_DIR` | `code/lab/scenarios` | Host directory mounted at `K6_SCENARIOS_MOUNT` (checked/mounted only for `ha-failover`). |
| `K6_SCENARIOS_MOUNT` | `/chaos-scenarios` | Container path for the scenario JSON (`ha-failover` only). |

Read `chaos_events` in the k6 summary or in Prometheus:

```promql
sum by (action, target, result) (increase(k6_chaos_events_total{run_id="..."}[5m]))
```

The controller also exposes `chaos_actions_total{action,target,result}` and
`chaos_active_actions`; Grafana gets one annotation per action (Task 21).
`http_req_duration{flow:chaos}` covers controller latency and does not affect
the product thresholds.

## MySQL primary failover (`db-failover.js`, step 48)

This is the k6 counterpart of the step-31 Router drill. Three k6 scenarios run
in parallel for `DB_FAILOVER_DURATION`:

- `read` — constant VUs reading `PRODUCT_ID` through Nginx. Reads must never
  fail, including during the election.
- `write` — constant VUs creating idempotent orders with a fresh
  `Idempotency-Key` every `DB_FAILOVER_WRITE_INTERVAL`.
- `chaos` — one timeline VU that kills `DB_FAILOVER_TARGET` with
  `DB_FAILOVER_TTL_SECONDS` through the chaos-controller, then `teardown`
  always posts `/chaos/stop-all`.

The kill event is built from env and validated with the same schema v1 rules as
the JSON files (target pattern, datastore allow-list, TTL ceiling), so there is
no new JSON to edit. Datastore targets remain **closed by default**: a run needs
both gates widened by the operator.

### Prerequisites (this drill only)

1. **The app tier must run through the Router.** In the default
   `MYSQL_MODE=standalone` both pools point at the dev `jl-mysql`, so killing a
   cluster node changes nothing on the request path. Set in
   `code/lab/docker/.env` (gitignored): `MYSQL_MODE=router`,
   `DB_WRITE_URL=jdbc:mysql://jl-mysql-router:6446/<db>`,
   `DB_READ_URL=jdbc:mysql://jl-mysql-router:6447/<db>`, then recreate the tier
   and reload Nginx (Nginx caches the upstream IPs):

   ```bash
   cd code/lab/docker && docker compose -f compose.base.yml -f compose.dev.yml \
     up -d --no-deps --wait app-1 app-2 app-3
   docker exec jl-nginx nginx -s reload
   docker logs jl-app-1 2>&1 | grep -o 'MySQL access mode: [a-z]*'   # -> router
   ```

2. **`DB_FAILOVER_TARGET` must be the current PRIMARY.** Confirm first, or the
   drill stops a replica and teaches nothing:

   ```bash
   bash code/lab/scripts/db-cluster-status.sh    # RESULT: ok + the PRIMARY row
   ```

3. **Both datastore gates must be widened for the run** — `CHAOS_TARGET_PATTERN`
   (k6-side schema check) and `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` (mirrors
   the controller's `chaos.datastore.allowed-target-pattern`, which refuses
   independently). The cluster probe container
   (`CHAOS_MYSQL_CLUSTER_CONTAINER`, default `jl-mysql-3`) must not be the target,
   or D11 loses its `mysql_cluster_*` series during the drill.

4. **Writes consume real stock.** Each run creates roughly `duration /
   DB_FAILOVER_WRITE_INTERVAL` orders for `PRODUCT_ID` (a 3m run ≈ 85). Check the
   remaining `inventory_stock.available` or run
   `bash code/lab/scripts/reset-lab.sh --yes` first; a sold-out product turns
   writes into business rejections and fails the success-rate threshold for the
   wrong reason. `reset-lab.sh` targets the **standalone** `jl-mysql` by default
   — in router mode point it at the cluster (`RESET_MYSQL_CONTAINER`) or pick a
   `PRODUCT_ID` that still has stock.

```bash
TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
LAB_TOKEN="$TOKEN" \
  CHAOS_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  DB_FAILOVER_TARGET=jl-mysql-2 DB_FAILOVER_TTL_SECONDS=45 \
  DB_FAILOVER_KILL_AT=20s DB_FAILOVER_ERROR_WINDOW=30s \
  DB_FAILOVER_DURATION=3m \
  bash code/lab/k6/run.sh db-failover
```

Restore `MYSQL_MODE=standalone` afterwards and recreate the tier the same way,
otherwise `verify.sh`'s MySQL content checks run against the cluster and its
Redis/MySQL baselines no longer match the documented defaults.

**Threshold rationale.** `http_req_failed{flow:product}` must be `rate==0`:
the whole point of the Router is that reads stay available while the write port
moves. Writes are different — the election window is allowed to lose writes, so
the verdict is `db_failover_write_success: rate>DB_FAILOVER_WRITE_MIN_SUCCESS_RATE`
(default `0.85`). The budget is arithmetic, not guesswork: with one write VU
sleeping `DB_FAILOVER_WRITE_INTERVAL` (2s), a window of `W` seconds loses ≈ `W/2`
of the ≈ `duration/2` writes, so the success rate is ≈ `1 - W/duration`. The 0.85
floor therefore tolerates `W ≤ 0.15 × duration` — **18s at the 2m default, 27s at
3m**. `DB_FAILOVER_ERROR_WINDOW=30s` is the *classification* window (which 5xx
count as expected), not the budget: at 2m a genuinely 30s-long election would
cross the threshold, so use `DB_FAILOVER_DURATION=3m` unless the promotion is
known to be fast. Measured on this host: promotion ≤ 17s from the kill request,
one lost write, 98.8% success.

### Live run (2026-10-27, `jl-mysql-1` PRIMARY, `PRODUCT_ID=2`)

`run_id=k6-20261027-161756-dbfo2`, archive
`code/lab/k6/reports/20261027-161756-db-failover/` (gitignored), k6 exit **0**,
all five thresholds passed:

| Evidence | Value |
|---|---|
| `chaos_events{action=kill,target=jl-mysql-1,result=accepted}` | 1 (`status=202`, 11s after the POST) |
| `http_req_failed{flow=product}` | **0** over 3580 reads |
| product read p95 / p99 / max | 8.30ms / 17.08ms / 504.8ms |
| `db_failover_writes` | 85 → 84 `created`, 1 `expected-election-error`, **0 `failed`** |
| `db_failover_expected_write_errors{status=500}` | 1 at `08:18:25`, i.e. 7s after the kill and inside the window |
| `db_failover_write_success` | 0.9882 (> 0.85) |
| `mysql_cluster_member_count` | 3 → 2 at `08:18:35` → 3 at `08:19:35` |
| `mysql_cluster_primary{member}` | `mysql-1` → `mysql-2` at `08:18:35`; Router 6446 followed |
| old primary | TTL-restarted, `ONLINE SECONDARY` again; teardown `stop-all reverted=0 failed=0` |
| post-run | `GET /chaos/actions` → `[]`, Nginx `/api/products/2` → 200 |

The first attempt (`20261027-160742-db-failover`, target `jl-mysql-2`) applied the
kill but reported `HTTP 0` because `CHAOS_HTTP_TIMEOUT` was still 5s — shorter
than the controller's synchronous Docker stop. That is
`doc/avoid_common_error/15-k6-action-timeout-vs-docker-stop.md`.

The expected 5xx list (`DB_FAILOVER_WRITE_EXPECTED_STATUSES`, default
`500,502,503,504`) is passed to `http.expectedStatuses` **only** between
`DB_FAILOVER_KILL_AT` and `DB_FAILOVER_KILL_AT + DB_FAILOVER_ERROR_WINDOW`.
Outside that window the same status is a real flow/server error, so a
regression cannot hide behind the election budget. Expected and real failures
are counted separately:

| Metric | Meaning |
|---|---|
| `db_failover_reads{result}` | read outcomes (`ok` / `error`). |
| `db_failover_writes{result}` | write outcomes (`created` / `expected-election-error` / `failed`). |
| `db_failover_write_success` | rate over every write; the threshold metric. |
| `db_failover_expected_write_errors{status}` | 5xx accepted inside the window. |
| `db_failover_write_failures{status}` | every other write failure, inside or outside the window. |

Orders consume stock for `PRODUCT_ID` (see prerequisite 4 above: `reset-lab.sh`
resets the **standalone** `jl-mysql` unless you repoint it at the cluster).
`DB_FAILOVER_TTL_SECONDS` auto-starts the old primary, but if the TTL ends after
the load window the teardown `stop-all` restores it first.

| Variable | Default | Meaning |
|---|---|---|
| `LAB_TOKEN` | unset (required) | `X-Lab-Token` for the chaos-controller. |
| `DB_FAILOVER_TARGET` | `jl-mysql-2` | MySQL container to kill; must match both gates above **and** be the current PRIMARY (`db-cluster-status.sh`). |
| `DB_FAILOVER_TTL_SECONDS` | `45` | Auto-`start` TTL, bounded by `CHAOS_MAX_TTL`. |
| `DB_FAILOVER_KILL_AT` | `20s` | Offset from load start to the kill. |
| `DB_FAILOVER_ERROR_WINDOW` | `30s` | Window in which configured write 5xx are *classified* as expected; the threshold budget is `0.15 × duration`. |
| `DB_FAILOVER_DURATION` | `2m` (or `CHAOS_DURATION`) | Total load window; kill+error window must fit. Use `3m` so a slow election still clears the 0.85 write floor. |
| `DB_FAILOVER_READ_VUS` | `20` | Constant read VUs. |
| `DB_FAILOVER_WRITE_VUS` | `1` | Constant write VUs. |
| `DB_FAILOVER_WRITE_INTERVAL` | `2s` | Sleep between order creates per VU. |
| `DB_FAILOVER_READ_THINK_TIME` | `1s` | Sleep between reads per VU. |
| `DB_FAILOVER_WRITE_EXPECTED_STATUSES` | `500,502,503,504` | Statuses accepted only inside the window. |
| `DB_FAILOVER_WRITE_MIN_SUCCESS_RATE` | `0.85` | Write-success threshold (fraction in `(0,1)`). |

## Cache experiment (`cache-lab.js`, step 48)

This scenario drives the D10 cache experiment end to end:

1. `setup()` calls `POST /lab/cache/seed` once (idempotent) on the internal lab
   API and resets every app instance to `mode=off`.
2. Constant VUs read the seeded ids `CACHE_LAB_SEED_START_ID..+COUNT-1`
   (default `90001+`) through `BASE_URL` (Nginx).
3. One timeline VU walks `CACHE_LAB_MODES` (default
   `off → null-cache → bloom → mutex → logical-expire → ttl-jitter → l1-cache →
   db-fallback`) on `CACHE_LAB_MODE_DURATION` dwells, calling
   `POST /lab/cache/mode` on every URL in `CACHE_LAB_APP_URLS`.
4. At the end of each dwell it samples `GET /lab/cache/state` and
   `/actuator/prometheus`, then finally resets to `off`; `teardown()` resets
   `off` again even if the timeline aborted.
5. Inside those dwells it **manufactures the three defects the defenses exist to
   catch** (step 56). Without this the scenario only ever read ids that exist
   and are still warm, so every defense counter stayed at zero by construction:

   | Defect | How the run creates it | What should absorb it |
   |---|---|---|
   | 穿透 (penetration) | every `CACHE_LAB_PROBE_EVERY`-th read targets an id from `CACHE_LAB_ABSENT_IDS` (`cache_probe` flow, expects `404` + code `40401`) | null-value cache, bloom filter |
   | 击穿 (breakdown) | every `CACHE_LAB_HOT_KEY_EVERY`-th read targets the seed's hot key (`hotKeyId` from the seed response; `CACHE_LAB_HOT_KEY_ID` overrides) | mutex rebuild, logical expire |
   | 雪崩 (avalanche) | dwells listed in `CACHE_LAB_RESEED_MODES` re-call `POST /lab/cache/seed` at dwell start, so `CACHE_LAB_SEED_COUNT` keys with one identical TTL expire **inside that same dwell** | ttl-jitter, L1 cache, DB-fallback limiter |

   Probing an id that also appears in the read range would silently turn the
   penetration probe into a normal hit, so `CACHE_LAB_ABSENT_IDS` overlapping
   `[CACHE_LAB_SEED_START_ID, +CACHE_LAB_SEED_COUNT)` is rejected at init.

```bash
TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
LAB_TOKEN="$TOKEN" \
  CACHE_LAB_APP_URLS='http://app-1:8080,http://app-2:8080,http://app-3:8080' \
  CACHE_LAB_MODE_DURATION=20s \
  bash code/lab/k6/run.sh cache-lab
```

That quick-start walks the modes but **arms nothing** (20s dwell is below the
avalanche TTL). For the full before/after comparison, re-seed inside every dwell:

```bash
LAB_TOKEN="$TOKEN" \
  CACHE_LAB_APP_URLS='http://app-1:8080,http://app-2:8080,http://app-3:8080' \
  CACHE_LAB_MODE_DURATION=90s \
  CACHE_LAB_RESEED_MODES='off,null-cache,bloom,mutex,logical-expire,ttl-jitter,l1-cache,db-fallback' \
  bash code/lab/k6/run.sh cache-lab
```

`run.sh` prints the effective arming parameters on every run
(`cache reseed:`, `cache probe:`, `cache dwell:`) — check those lines first
when a defense counter comes back empty. A 90s dwell over the canonical 8-mode
list is ~12 minutes, outside the 5-minute teaching window; narrow it with
`CACHE_LAB_MODES='off,bloom'` (the subset must keep the canonical order) rather
than shortening the dwell, because the arm guard below rejects short dwells.

**Guards that fail at init, not mid-run**

- `CACHE_LAB_RESEED_MODES` non-empty requires
  `CACHE_LAB_MODE_DURATION ≥ CACHE_LAB_AVALANCHE_TTL_SECONDS×1000 + CACHE_LAB_ARM_MARGIN`
  (default ≥ 75s). Below that the re-seeded keys expire in the *next* dwell and
  pollute that dwell's comparison instead of arming this one.
- `CACHE_LAB_ARM_MARGIN` must exceed `CACHE_LAB_STORM_SAMPLE_DELAY`, or the
  storm sample is taken before the keys have expired.
- The `modes` scenario uses `per-vu-iterations`, whose `maxDuration` defaults to
  **10m0s**. The loop runs `modes × dwell`, so the budget is derived from it
  (`+CACHE_LAB_TIMELINE_SLACK`, default 30s) — otherwise k6 interrupts the
  timeline mid-loop, the last mode never arms and the final `mode=off` reset is
  skipped, all while exiting 0 with every check passing. See
  `doc/avoid_common_error/17-k6-per-vu-iterations-max-duration.md`.

This is **opt-in**: it writes 1000 experiment rows (`catalog_product`
90000..90999 by default), warms Redis keys and flips the instance-level defense
mode. Do not archive it as a bench baseline, and do not run it concurrently
with another cache experiment.

Reads are tagged with the schedule-derived `mode`, so a read that lands just
after a switch can briefly carry the previous tag; the mode timeline logs the
authoritative switch time. `429` is accepted as the db-fallback limiter's
fast-fail (`42901`) and counted in `cache_lab_db_fallback_rejections`.

Per-mode metrics (all also carry `run_id` from `baseOptions.tags`):

| Metric | Type | Meaning |
|---|---|---|
| `cache_lab_reads{mode,result}` | Counter | read outcomes (`ok` / `limited` / `error`). |
| `cache_lab_read_errors{mode,status}` | Counter | unexpected read statuses. |
| `cache_lab_mode_switches{mode,result}` | Counter | accepted/failed switch per app URL set. |
| `cache_lab_db_fallback_rejections{mode}` | Counter | `42901` fast fails in db-fallback. |
| `cache_lab_probes{mode,result}` | Counter | penetration probes issued (`404` is the expected outcome). |
| `cache_lab_hot_key_reads{mode,result}` | Counter | reads routed to the seed's hot key. |
| `cache_lab_reseeds{mode,result}` | Counter | avalanche re-arms, one per armed dwell. |
| `cache_lab_db_fallbacks{mode}` | Gauge | Δ `cache_db_fallbacks_total` over the dwell (actuator). |
| `cache_lab_l1_hits{mode}` / `cache_lab_l1_requests{mode}` | Gauge | Δ L1 hits / L1 requests. |
| `cache_lab_bloom_absents{mode}` | Gauge | Δ bloom `absent` rejections. |
| `cache_lab_null_cache_hits{mode}` | Gauge | Δ null-value cache hits. |
| `cache_lab_mutex_rebuilds{mode}` | Gauge | Δ mutex `rebuild` outcomes. |
| `cache_lab_mutex_double_check_hits{mode}` | Gauge | Δ rebuilds skipped because a concurrent writer already filled the slot. |
| `cache_lab_logical_stale_serves{mode}` | Gauge | Δ logical-expire serves of a logically stale value. |
| `cache_lab_logical_cold_rebuilds{mode}` | Gauge | Δ logical-expire synchronous cold rebuilds. |
| `cache_lab_refresh_submitted{mode}` | Gauge | Δ asynchronous refreshes submitted. |
| `cache_lab_limiter_rejections{mode}` | Gauge | Δ avalanche limiter `rejected`. |
| `cache_lab_state_l2_keys{mode}` | Gauge | live L2 experiment keys at dwell end. |
| `cache_lab_state_l1_keys{mode}` | Gauge | live L1 experiment keys at dwell end. |
| `cache_lab_state_live_markers{mode}` | Gauge | null-cache markers at dwell end. |
| `cache_lab_state_bloom_size{mode}` | Gauge | bloom filter size at dwell end. |
| `cache_lab_state_redis_reachable{mode}` | Gauge | number of app URLs with Redis reachable. |

The actuator gauges are **deltas over each dwell**, so D10 can compare
`off` with each defense on the same `run_id`. If `/actuator/prometheus` is not
reachable the driver logs a warning and skips those gauges; state gauges still
come from `/lab/cache/state`.

Reading those gauges back has three traps (all cost a debugging session once):

- `summary.json` keeps only the **last** dwell's value per `mode`; `max` is the
  cross-dwell peak. Use Prometheus for the per-mode comparison.
- An app-side counter only appears on `/actuator/prometheus` after its first
  non-zero increment, so "series missing" and "series at 0" mean the same thing
  in the before-state and neither proves the wiring is broken.
- Instant PromQL queries look back only 5m. Querying an ended run hours later
  returns **no series**, not `0`. Use `max_over_time(<metric>[6h])`, or the
  series may have left the retention window entirely.

| Variable | Default | Meaning |
|---|---|---|
| `LAB_TOKEN` | unset (required) | `X-Lab-Token` for `/lab/cache/**`. |
| `CACHE_LAB_APP_URLS` | `http://app-1:8080` | Comma list of internal app base URLs to switch/sample. |
| `CACHE_LAB_SEED_START_ID` | `90001` | First id read (must be inside the seed range). |
| `CACHE_LAB_SEED_COUNT` | `200` | Number of consecutive ids read. |
| `CACHE_LAB_MODES` | canonical 8-mode list | Ordered subset; duplicates/reordering are rejected. |
| `CACHE_LAB_MODE_DURATION` | `20s` | Dwell per mode; read duration = modes × dwell. |
| `CACHE_LAB_TIMELINE_SLACK` | `30s` | Extra headroom on the mode timeline's `maxDuration` (see the guard above). |
| `CACHE_LAB_ABSENT_IDS` | `99990..99999` | Penetration probe id set; must not overlap the read range. |
| `CACHE_LAB_PROBE_EVERY` | `10` | One probe per N reads; `0` disables penetration traffic. |
| `CACHE_LAB_PROBE_EXPECTED_STATUSES` | `404,429` | Accepted probe statuses (`404` is the point of the probe). |
| `CACHE_LAB_PROBE_EXPECTED_CODE` | `40401` | Accepted business code on a probe miss. |
| `CACHE_LAB_HOT_KEY_EVERY` | `25` | One hot-key read per N iterations; `0` disables breakdown traffic. |
| `CACHE_LAB_HOT_KEY_ID` | `0` | `0` = take `hotKeyId` from the seed response; non-zero forces it. |
| `CACHE_LAB_RESEED_MODES` | unset (no re-arm) | Dwells that re-call `POST /lab/cache/seed` to arm the avalanche. |
| `CACHE_LAB_AVALANCHE_TTL_SECONDS` | `60` | Must match the app's `LAB_CACHE_SEED_AVALANCHE_TTL_SECONDS`; mismatched it logs the app value as authoritative. |
| `CACHE_LAB_ARM_MARGIN` | `15s` | Slack above the avalanche TTL that a dwell must have to be armable. |
| `CACHE_LAB_STORM_SAMPLE_DELAY` | `2s` | Delay after the TTL at which the mid-dwell `sample=storm` snapshot is taken. |
| `CACHE_LAB_VUS` | `10` | Constant read VUs. |
| `CACHE_LAB_THINK_TIME` | `200ms` | Sleep between reads per VU. |
| `CACHE_LAB_READ_EXPECTED_STATUSES` | `200,429` | Accepted read statuses. |
| `CACHE_LAB_SAMPLE_ACTUATOR` | `1` | `0` disables the `/actuator/prometheus` deltas. |
| `CACHE_LAB_SEED_PATH` / `_MODE_PATH` / `_STATE_PATH` / `_ACTUATOR_PATH` | `/lab/cache/seed`, `/lab/cache/mode`, `/lab/cache/state`, `/actuator/prometheus` | Endpoint overrides. |

## Interactive background traffic (M2-5)

`scenarios/traffic.js` is the load the experiment console keeps running while
you walk through a scenario: constant VUs doing `login → product read` with a
think time, so the dashboards show real traffic while you pause, read logs and
compare before/after a fault. It only reads the catalog, so it never consumes
seckill stock and does not need a `reset-lab.sh` between wizard steps.

The container is defined in compose (`code/lab/docker/compose.base.yml`,
service `traffic`, container `jl-traffic`) under the `traffic` profile. Profile
gating keeps the default `docker compose config --services` count at 16, so
`verify.sh`, `bench.sh` and `up.sh` are unaffected; `up.sh` creates the
container stopped, and `down.sh` removes it.

Start and stop it from the console, or with curl:

```bash
# created but stopped after up.sh
curl -X POST -H "X-Lab-Token: $LAB_TOKEN" http://localhost:8080/lab-api/lab/traffic/start
curl -H "X-Lab-Token: $LAB_TOKEN" http://localhost:8080/lab-api/lab/traffic/state
curl -X POST -H "X-Lab-Token: $LAB_TOKEN" http://localhost:8080/lab-api/lab/traffic/stop
# or without the controller: docker start jl-traffic / docker stop jl-traffic
```

| Variable | Default | Meaning |
|---|---|---|
| `TRAFFIC_VUS` | `50` | Constant VUs (`< 1` is rejected at init). |
| `TRAFFIC_DURATION` | `30m` | How long the run keeps generating load. |
| `TRAFFIC_BASE_URL` | `http://nginx` | Entry point inside `jl-net`. |
| `TRAFFIC_PRODUCT_ID` | `1` | Product read each iteration. |
| `TRAFFIC_USER_PREFIX` | `traffic` | Login user prefix, keeps traffic users out of the way of `k6-*` bench users. |
| `TRAFFIC_THINK_TIME` | `1s` | Sleep per iteration. |
| `TRAFFIC_RUN_ID` | `console` | `run_id` tag on the emitted metrics. |
| `CHAOS_TRAFFIC_CONTAINER` | `jl-traffic` | Name the controller starts/stops. |
| `CHAOS_TRAFFIC_STOP_GRACE_SECONDS` | `5` | Graceful stop window handed to the Docker API. |

Traffic is deliberately **not** a chaos action: it has no TTL to revert, and
pausing the guide neither freezes the load nor keeps generating it behind your
back. `TRAFFIC_DURATION` is a ceiling, not a hard stop — if the wizard ends
first, stop the traffic from the console (or `docker stop jl-traffic`).
`bench.sh` refuses to archive a run while the traffic service is generating
load, so baselines stay comparable; `BENCH_ALLOW_TRAFFIC=1` is the escape hatch
for a deliberately loaded bench.

## Bench runner and lab reset (M2-2a)

`bench.sh` is the repeatable entry point: it checks the stack (16 compose
services running/healthy, Prometheus ready, k6 image and scenario present),
resets the lab data, runs one k6 scenario, and archives the result. It never
starts the stack.

```bash
# Full archive + result doc under doc/process/Stage1/benchmark/
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

and `doc/process/Stage1/benchmark/<date>-<scenario>-<run_id>.md` with key metrics,
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
compose service). Overrides passed after `--` must be k6 scenario variables:
bench.sh internal names (`RUN_ID`, `SCENARIO`, `PATH`, `K6_*` runner/archive
names, `BENCH_*`, `RESET_*`) are rejected so they cannot corrupt the archive,
the mount or the reset baseline; set those in the environment before invoking
bench.sh. Credentials for the reset come from the lab `.env`; they are never
passed on a command line, written to an archive or committed (MySQL via a
0600 `--env-file`, RabbitMQ via curl stdin config).

## Compare runs and D9 (M2-2b)

`compare.sh` compares two archived runs from their `manifest.json` (keyMetrics +
run window) and queries Prometheus for JVM/resource peaks over each run's own
window (`startEpoch..endEpoch` plus a one-scrape grace). It prints a Markdown
table (also a `Δ (B-A)` and `Δ% (B vs A)` 涨跌幅 column) and can write it with
`--out`. k6 metrics come from the manifest; only heap/threads/GC/Hikari peaks
use Prometheus, so a Prometheus outage shows `n/a` for those rows but still
exits 0.

```bash
# Run id resolves under BENCH_REPORTS_DIR; a directory containing manifest.json
# also works. Both runs must exist for comparison to start.
bash code/lab/scripts/compare.sh 20261026-145555-smoke neg-threshold
bash code/lab/scripts/compare.sh <runA> <runB> --out doc/process/Stage1/benchmark/compare.md

# Point at another reports dir / Prometheus, or tune the query/peak window:
BENCH_REPORTS_DIR=/path/to/reports PROM_URL=http://127.0.0.1:8080/prom \
  bash code/lab/scripts/compare.sh <runA> <runB>
```

Compare env: `BENCH_REPORTS_DIR` (default `<repo>/code/lab/k6/reports`),
`PROM_URL` (default `http://127.0.0.1:8080/prom`), `BENCH_COMPARE_TIMEOUT_MS`
(default 5000, per Prometheus query), `BENCH_COMPARE_WINDOW_GRACE_SECONDS`
(default 15, positive integer seconds rounded up; peak-window margin over the
run — must cover at least one 5s scrape), `BENCH_COMPARE_OUT` (optional default
output file), `BENCH_COMPARE_SCRIPT` (override the node script path). Exit
codes: `0` on a rendered comparison (including Prometheus `n/a` rows and
unusable run timing, which is noted in the output), `2` on usage errors (wrong
arg count, missing `--out` value), unreadable/absent `manifest.json`,
unsupported `schemaVersion`, or write failure.

409/429 are expected business rejections; the manifest records their counts and
the table derives a `Expected 429 rate` (expected429 / total requests). They do
not enter k6 failed/flow/server error rates.

The **D9 压测** dashboard (`code/lab/docker/grafana/dashboards/ha-d9.json`,
provisioned at UID `ha-d9`) visualizes the same k6 families live. Its `run_id`
template variable (multi + All, sourced from `k6_http_reqs_total`) filters every
k6 panel; each panel groups by `run_id` (latency also by `flow`) so multiple or
"All" selections stay separate. k6 latency is stored in seconds by remote write
and is multiplied by 1000 to render in ms. App RPS/P95 (`job="seckill-app"`) are
overlay references and carry no `run_id` label. Fault annotations and a dynamic
run manifest are deferred to Task 21/22 (chaos).

## Scenarios

The table below is descriptive. The machine-checked whitelist — who may run a
script and with which environment — is `scenarios/manifest.json`
(see [Scenario whitelist](#scenario-whitelist-single-source-of-truth-stage2-b2-01)).

| Script | Default profile | Traffic | Verdict |
|---|---|---|---|
| `smoke.js` | 1 VU, 1 iteration, capped by `SMOKE_DURATION=30s` | login → product → order → repeat key → seckill → poll | errors < 1%, checks > 99% |
| `load.js` | 50 → 200 VU over `LOAD_DURATION=10m` (acceptance: 2m) | product reads only | errors < 1%, product P95 < 300ms, P99 < 800ms |
| `stress.js` | staged 50 → 500 VU (`STRESS_STAGES`) | product reads only | no threshold; inspect the knee (errors/latency per stage) |
| `spike.js` | 50 baseline → 800 VU for 60s, then down | seckill product `SECKILL_PRODUCT_ID` | 5xx/error < 1%; 409/429 are expected and counted |
| `soak.js` | 100 VU for `SOAK_DURATION=30m` (acceptance: 2m) | product reads only | errors < 1%, checks > 99% |
| `ha-failover.js` | 20 VU constant (`CHAOS_VUS`) for `CHAOS_DURATION=4m`, plus one timeline VU | product reads + scenario JSON actions | errors < 1%, product P95/P99 from Task 19 |
| `db-failover.js` | 20 read VU + 1 write VU for `DB_FAILOVER_DURATION=2m` (3m recommended), plus one timeline VU | product reads + idempotent orders + MySQL kill | read failures exactly 0; write success > 85% |
| `cache-lab.js` | 10 read VU for modes × dwell (default 8 × 20s), plus one mode VU capped at that duration + slack | seeded 90001+ reads, absent-id probes, hot-key reads, per-dwell re-seed + `/lab/cache/mode` schedule | flow errors < 1%; per-mode `cache_lab_*` markers |
| `traffic.js` | `TRAFFIC_VUS=50` constant for `TRAFFIC_DURATION=30m` | login → product read only (no stock consumed) | errors < 1%, product P95/P99 from Task 19; started/stopped from the console |
| `d7.js` | identical to `load.js` (same stages/thresholds) | product reads only, **no login** | errors < 1%, product P95 < 300ms, P99 < 800ms |

Smoke uses `per-vu-iterations` with `SMOKE_ITERATIONS=1` so one run creates
exactly one order and one seckill attempt. It does not loop for the full 30s;
`SMOKE_DURATION` is the upper bound (`maxDuration`).

Load stages are derived from the single `LOAD_DURATION` value:

- first `LOAD_RAMP_UP_FRACTION` (20%) ramps 0 → `LOAD_START_VUS` (50);
- next `LOAD_RAMP_PEAK_FRACTION` (20%) ramps 50 → `LOAD_VUS` (200);
- the remaining 60% holds `LOAD_VUS`.

The acceptance run `LOAD_DURATION=2m` therefore uses 24s / 24s / 72s stages.

### D7 dual-JDK scenario

`d7.js` exists because the JDK 8 shell has no `POST /api/login`: every other
base scenario calls `ensureLogin()` first, which would record one failed login
per VU per iteration and flood `flow_errors` on the JDK 8 target. `d7.js` keeps
the load-test VU and threshold shape but calls only `product()`.

```bash
# JDK 21 tier through Nginx
LOAD_DURATION=2m bash code/lab/k6/run.sh d7

# JDK 8 shell directly on jl-net (compose profile dual-jdk must be up)
BASE_URL=http://seckill-app-jdk8:8080 LOAD_DURATION=2m \
  bash code/lab/k6/run.sh d7
```

`load`, `stress` and `soak` never create orders, so they do not drain stock.
`smoke` creates one order and one seckill attempt. `spike` may consume the
remaining stock of product 20; after the acceptance spike product 20 is sold
out and a smoke seckill legitimately returns 409.

### Scenario whitelist (single source of truth, Stage2 B2-01)

`scenarios/manifest.json` is the only place that decides which script may run
where. `run.sh` and `bench.sh` read it through `lib/scenario-manifest.mjs`
(plain node, no jq and no npm dependency); neither script keeps a case list.

| View | Members | Consumer |
|---|---|---|
| `run` | 9 scripts: `smoke`, `load`, `stress`, `spike`, `soak`, `d7`, `ha-failover`, `db-failover`, `cache-lab` | `run.sh`; `requires.chaos` / `requires.cache` select the extra scenario mount and env group |
| `bench` | 5 scripts: `smoke`, `load`, `stress`, `spike`, `soak` | `bench.sh` (preflight -> reset -> k6 -> verdict -> archive); `thresholdsExpected=false` for `stress`, which has no k6 threshold by design |
| `ui` | 3 scripts: `smoke`, `load`, `spike` | Stage2 B2-02 executor / console whitelist |

- `traffic` is `serviceOnly: true`: it is started by the compose `traffic`
  service (profile `traffic`), never by `run.sh` or `bench.sh`, and is in no
  view. `bash code/lab/k6/run.sh traffic` refuses with an explicit message.
- `stress` and `soak` stay in `run` + `bench` (the CLI keeps them) but are
  deliberately **not** in `ui`: neither has been run for its full design
  duration (14m / 30m), and the confirmed decision moves them to the Future
  list (design doc §7.1/§15). `ui` therefore means "verified and
  UI-launchable", not "every CLI scenario".
- `d7`, `ha-failover`, `db-failover` and `cache-lab` are not in `ui` either:
  they are triggered from the instance/topology panels or from the CLI.
- Every entry also records `requires.profile` (`dev` = the default 16-container
  stack, plus `db-ha` / `traffic`), `writesData` (orders/stock or seeded rows),
  `env` (accepted variable names) and `params` (documented defaults). The
  defaults are pinned to `lib/config.js` and the scenario modules by
  `test/scenario-manifest.test.mjs`, so a default cannot drift silently.
- Override the manifest location with
  `LAB_SCENARIOS_MANIFEST=/path/to/manifest.json`; both scripts default to
  `code/lab/k6/scenarios/manifest.json`.

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
| `LAB_SCENARIOS_MANIFEST` | `code/lab/k6/scenarios/manifest.json` | Scenario whitelist/defaults read by `run.sh` and `bench.sh`. |
| `K6_MANIFEST_TOOL` | `code/lab/k6/lib/scenario-manifest.mjs` | Manifest reader used by `run.sh`. |
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

`run.sh` needs node: the accepted scenario names come from the manifest reader
(view `run`), not from a case list. `bench.sh` accepts the manifest view
`bench` and generates its usage line from the same file.

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

# D7 dual-JDK: read-only, no login; run once per target
LOAD_DURATION=1m bash code/lab/k6/run.sh d7
BASE_URL=http://seckill-app-jdk8:8080 LOAD_DURATION=1m bash code/lab/k6/run.sh d7

# Chaos timeline: short live check on jl-app-3 only
LAB_TOKEN="$TOKEN" CHAOS_SCENARIO_FILE=quick-failover.json \
  CHAOS_DURATION=30s CHAOS_VUS=6 bash code/lab/k6/run.sh ha-failover

# MySQL primary failover (opt-in; widens the datastore gates)
# Confirm the PRIMARY first, and run the app tier in MYSQL_MODE=router.
LAB_TOKEN="$TOKEN" \
  CHAOS_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  DB_FAILOVER_TARGET=jl-mysql-2 DB_FAILOVER_DURATION=3m \
  bash code/lab/k6/run.sh db-failover

# Cache experiment (opt-in; seeds data and flips the defense mode)
LAB_TOKEN="$TOKEN" CACHE_LAB_MODE_DURATION=20s \
  bash code/lab/k6/run.sh cache-lab
```

Extra arguments before the script are passed to `k6 run`, for example
`bash code/lab/k6/run.sh smoke --quiet`. The runner always adds
`-o experimental-prometheus-rw`, so do not add a second output.

### Stack-free checks (no running lab required)

The seckill status/poll branches and config validation are covered by a
zero-dependency node harness (k6 modules stubbed under `test/`):

```bash
node --test "code/lab/k6/test/*.test.mjs"

# static script/option validation inside the pinned image, no network:
docker run --rm --network none -v "$PWD/code/lab/k6:/scripts:ro" \
  grafana/k6:1.3.0 inspect /scripts/scenarios/smoke.js
```

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

When the `seckillBackend` circuit breaker is open (Task 17 degradation) the app
still answers **202** because the `mq` toggle is on, but the body comes from the
synchronous DB path: `requestId: null` with a terminal `status=CREATED` and
`orderId`. `lib/flow.js` accepts that shape as a success and does not poll it.

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
- k6 1.3 evaluates thresholds at the end of the run and exits **99** when one
  is crossed. A negative test with `ERROR_RATE=0` exits 99 and prints
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
`doc/process/Stage1/step-19-k6-base.md` for the exact results.

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

- Design: `doc/design/Stage1/2026-10-26-ha-concurrency-lab-design.md` §11.1
- Existing HTTP smoke: `code/lab/scripts/smoke.sh`
- Task 19 handover: `doc/process/Stage1/step-19-k6-base.md`
- Task 20a handover (reset/bench/archive): `doc/process/Stage1/step-20a-bench-reset.md`
- Task 20b compare/D9: `code/lab/scripts/compare.sh`,
  `code/lab/docker/grafana/dashboards/ha-d9.json` and the handover
  `doc/process/Stage1/step-20b-compare-d9.md`; sample:
  `doc/process/Stage1/benchmark/compare-smoke-vs-neg.md`.
- Task 21 handover (chaos actions/TTL): `doc/process/Stage1/step-21-chaos-actions.md`
- Task 22a handover (scenario JSON + timeline): `doc/process/Stage1/step-22a-scenario-timeline.md`
- Scenario schema: `code/lab/scenarios/README.md`
