# Chaos scenario JSON (schema v1)

Shared fault timeline consumed by the k6 driver
(`code/lab/k6/scenarios/ha-failover.js`) and the operation console
(`GET /lab/guide/scenarios` → `POST /lab/guide/step`, Task 23) and any other lab
tooling. A scenario is **data**, not code: the same file drives the interactive
guide and the automated k6 run.

## Files

| File | Purpose |
|---|---|
| `ha-app-failover.json` | Production demo (4 min): stop app-1 at 60s, freeze app-2 at 120s, resume at 150s. |
| `quick-failover.json` | Short live acceptance: stop **jl-app-3** at 5s with a 15s TTL. No other target. |
| `redis-sentinel-failover.json` | Redis Sentinel failover (M3-1c, compose profile `ha`): stop the node that is currently the HA master at 10s with a 45s auto-restore TTL, an observation checkpoint at 25s, an idempotent rejoin confirmation at 60s. **Targets a datastore**, so it only loads when the operator opts in - see [Datastore targets](#datastore-targets-closed-by-default). |

## Schema

```json
{
  "schemaVersion": 1,
  "id": "ha-app-failover",
  "name": "负载均衡与实例故障转移",
  "description": "Two-step application-tier drill...",
  "durationHintSeconds": 240,
  "events": [
    {"atSeconds": 60, "action": "kill", "target": "jl-app-1", "ttlSeconds": 60, "title": "停止 app-1", "text": "...",
     "explain": {"symptom": "...", "rootCause": "...", "fix": "...", "expectation": "..."}},
    {"atSeconds": 120, "action": "freeze", "target": "jl-app-2", "ttlSeconds": 30, "title": "冻结 app-2", "text": "..."},
    {"atSeconds": 150, "action": "resume", "target": "jl-app-2", "title": "恢复 app-2", "text": "..."}
  ]
}
```

### Top-level fields

| Field | Required | Type | Semantics |
|---|---|---|---|
| `schemaVersion` | yes | integer | Must be `1`. Bumped only for breaking changes. |
| `id` | yes | string | Stable machine id. Convention: lowercase `[a-z0-9-]`, matching the file name. The schema enforces non-empty only; the format is not validated. |
| `name` | yes | string | Display name for the console/README. |
| `description` | no | string | One or two sentences for the operation console. |
| `durationHintSeconds` | no | number > 0 | Expected wall-clock length of the drill. A hint for UI progress bars; the k6 driver uses its own `CHAOS_DURATION`. |
| `events` | yes | array, non-empty | Fault timeline, already sorted ascending by `atSeconds`. |

### Event fields

| Field | Required | Type | Semantics |
|---|---|---|---|
| `atSeconds` | yes | number > 0 | Offset from timeline start. The k6 driver sleeps until `start + atSeconds` and never fires early. |
| `action` | yes | enum | `kill`, `start`, `restart`, `freeze`, `resume`. |
| `target` | yes | string | Container name, whitelisted by the chaos-controller (`lab=java-learn`). Default k6 pattern: `^jl-app-[0-9]+$`. |
| `ttlSeconds` | kill/freeze only | integer > 0 | Auto-revert window. `kill` → `start`, `freeze` → `SIGCONT`. Must be absent for `start`/`restart`/`resume`. |
| `title` | no | string | Short label shown in the console timeline. |
| `text` | no | string | One-line explanation shown next to the event. |
| `explain` | no | object | 现象/根因/处方/预期曲线 block printed under the pending step in the console. Keys `symptom`, `rootCause`, `fix`, `expectation`, each an optional non-empty string; an all-empty block reads as absent. |

### Action semantics

| Action | Controller behavior | Tracked / TTL |
|---|---|---|
| `kill` | Docker stop (not `docker kill`); `restart: unless-stopped` does not auto-start it | Active until TTL / `stop-all` → start |
| `freeze` | `SIGSTOP` on the container process | Active until TTL / `stop-all` → `SIGCONT` |
| `resume` | `SIGCONT` | One-shot, no TTL (idempotent; safe after TTL already fired) |
| `start` | Docker start | One-shot |
| `restart` | Docker restart | One-shot |

## Validation rules

Enforced by `validateScenario()` in `code/lab/k6/lib/chaos-schema.js` (pure JS,
no k6 imports, so it runs under Node and inside k6):

1. `schemaVersion === 1`.
2. `id`/`name` non-empty; `description`/`title`/`text` strings when present;
   `explain` (when present) is an object whose four fields are non-empty strings.
3. `durationHintSeconds` is a positive finite number when present.
4. `events` is a non-empty array, sorted by `atSeconds` (non-decreasing).
5. `atSeconds` is a positive finite number; two events may not share the same
   `(atSeconds, target)` pair.
6. `action` is one of the five allowed values.
7. `target` matches the configured pattern (`CHAOS_TARGET_PATTERN`, default
   `^jl-app-[0-9]+$`; controller side: `chaos.guide.target-pattern`) **and**
   passes the datastore rule: a name containing
   `mysql|redis|rabbit|mongo|postgres` is refused unless
   `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` / `chaos.datastore.allowed-target-pattern`
   admits it by its **whole** name. The allow-list is empty by default, which
   refuses every datastore exactly like the old absolute prohibition; widening
   the target pattern alone never opens a datastore. Every refusal names the
   rule that produced it.
8. `ttlSeconds` is a positive integer within `CHAOS_MAX_TTL` (default 600, the
   controller's `chaos.max-ttl-seconds`); it must be absent for one-shot actions.

Validation failures abort k6 during init with `[k6/chaos] <file>: <reason>`.
The rules above are regression-tested under plain Node:

```bash
node --test code/lab/k6/test/chaos-schema.test.mjs
```

The controller still re-validates every request (token, lab whitelist, action,
TTL ceiling), so the JSON schema is a fast pre-flight, not the security
boundary.

## Safety rules

- Only lab containers (`lab=java-learn`) are reachable; the default target
  pattern restricts timelines to `jl-app-*`.
- `kill` is a stop, never a volume removal.
- Every driver run ends with `POST /chaos/stop-all` in k6 `teardown`, and each
  kill/freeze also carries a TTL as the second line of defense.

## Datastore targets (closed by default)

M3-1c replaced the absolute "no datastore may ever be a fault target" rule with
an **opt-in allow-list**, because the Sentinel drill (`redis-sentinel-failover.json`)
is not drivable from the console otherwise.

| Rule | Property (controller) | Env (k6) | Default |
|---|---|---|---|
| Which datastore-named container may be faulted | `chaos.datastore.allowed-target-pattern` | `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` | empty = refuse all |
| Which container names a timeline may use at all | `chaos.guide.target-pattern` | `CHAOS_TARGET_PATTERN` | `^jl-app-[0-9]+$` |

Both gates must pass, independently: the allow-list never widens the target
pattern and the target pattern never admits a datastore. The controller enforces
the allow-list twice - in `ChaosActionService` (the real boundary, HTTP 403
`40301`) and in `GuideScenarioLoader` (scenario pre-flight, HTTP 400) - from one
shared `DatastoreTargetPolicy`, and `chaos-schema.js` mirrors the same verdict for
k6. The regex is compiled at startup, so a broken pattern fails the context
instead of surfacing mid-drill; an empty value is the closed default, not an error.

Stay refused, in every configuration the lab documents: **`jl-mysql`,
`jl-rabbitmq`, the standalone `jl-redis`** (note the asymmetry - `^jl-redis-[0-9]+$`
admits the HA nodes and still rejects the suffix-less standalone), the
`jl-redis-*-exporter` sidecars, the observability stack and
anything without the `lab=java-learn` label. This is a **teaching-lab safety
valve, not a production affordance**: it exists so a 25-container demo topology can
be driven from the console, and it is deliberately configured per drill (`.env`),
never widened "because it is convenient". An automatic TTL revert
(`start`/`SIGCONT`) bypasses the rule by design - a fault that was allowed to be
applied must always be allowed to be undone.

### `redis-sentinel-failover.json`: the master node is a fixed name (simplification)

Sentinel promotion **moves** the master: after one failover the master is a
different container than before, and the lab's topology drifted exactly that way
during step 24b. schema v1 has no "discover the current master" action - events
are `(atSeconds, action, target)` triples executed against a container name - so
step 1 **names `jl-redis-1` as the HA master. That is a teaching simplification,
not a guarantee.**

Check first, before starting the scenario:

```bash
bash code/lab/scripts/sentinel-status.sh                      # who is master?
bash code/lab/scripts/sentinel-status.sh --watch 2 -n 15      # keep it on screen during the drill
```

Read `SENTINEL get-master-addr-by-name mymaster` / the `role=master` line in that
output. If the master is **not** `jl-redis-1`, either edit the `target` fields of
the scenario for the run, or promote first and re-check - do not run it against a
replica, because stopping a replica teaches nothing about failover. Also confirm
the app is actually in `sentinel` mode (`docker logs jl-app-1 | grep 'Redis access mode'`),
otherwise the drill faults a group the application never talks to.

Configuring the lab to run it (controller side, both gates at once):

```bash
CHAOS_GUIDE_TARGET_PATTERN='^jl-(app|redis|sentinel)-[0-9]+$' \
CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-redis-[0-9]+$'
```

With the default app-only pattern the scenario is invalid and simply **skipped**
by `GET /lab/guide/scenarios` (logged, never half-loaded) - which is the closed
default behaviour, not a bug. See `doc/process/Stage1/step-24c-sentinel-targets.md` for
the full runbook and the compose wiring this step could not ship.

## How the driver consumes it

```bash
# 4 min demo against the default scenario
LAB_TOKEN=... bash code/lab/k6/run.sh ha-failover

# Short live check (jl-app-3 only)
LAB_TOKEN=... CHAOS_SCENARIO_FILE=quick-failover.json \
  CHAOS_DURATION=30s CHAOS_VUS=6 bash code/lab/k6/run.sh ha-failover
```

`code/lab/scenarios` is mounted read-only into the k6 container at
`/chaos-scenarios` (override with `K6_SCENARIOS_DIR` /
`K6_SCENARIOS_MOUNT`). It is deliberately **not** mounted under `/scripts`:
that directory is a read-only mount that already holds the k6 JavaScript
scenarios, so a nested mount point cannot be created and `/scripts/scenarios`
must not be shadowed.

`CHAOS_SCENARIO_FILE` accepts a bare file name (resolved against
`CHAOS_SCENARIO_DIR`, default `/chaos-scenarios`) or an absolute path for a
host k6 binary.

See `code/lab/k6/README.md` for the full env table, metrics and teardown
behavior.
