# Chaos scenario JSON (schema v1)

Shared fault timeline consumed by the k6 driver
(`code/lab/k6/scenarios/ha-failover.js`), the future operation console
(Task 22b/23) and any other lab tooling. A scenario is **data**, not code: the
same file drives the interactive guide and the automated k6 run.

## Files

| File | Purpose |
|---|---|
| `ha-app-failover.json` | Production demo (4 min): stop app-1 at 60s, freeze app-2 at 120s, resume at 150s. |
| `quick-failover.json` | Short live acceptance: stop **jl-app-3** at 5s with a 15s TTL. No other target. |

## Schema

```json
{
  "schemaVersion": 1,
  "id": "ha-app-failover",
  "name": "负载均衡与实例故障转移",
  "description": "Two-step application-tier drill...",
  "durationHintSeconds": 240,
  "events": [
    {"atSeconds": 60, "action": "kill", "target": "jl-app-1", "ttlSeconds": 60, "title": "停止 app-1", "text": "..."},
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
2. `id`/`name` non-empty; `description`/`title`/`text` strings when present.
3. `durationHintSeconds` is a positive finite number when present.
4. `events` is a non-empty array, sorted by `atSeconds` (non-decreasing).
5. `atSeconds` is a positive finite number; two events may not share the same
   `(atSeconds, target)` pair.
6. `action` is one of the five allowed values.
7. `target` matches the configured pattern (`CHAOS_TARGET_PATTERN`, default
   `^jl-app-[0-9]+$`) and never matches `mysql|redis|rabbit|mongo|postgres`.
   Datastores are rejected regardless of the pattern override.
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
- Never target `jl-mysql`, `jl-redis`, `jl-rabbitmq` or any non-lab container.
  These actions are intentionally out of scope for the shared timeline.
- `kill` is a stop, never a volume removal.
- Every driver run ends with `POST /chaos/stop-all` in k6 `teardown`, and each
  kill/freeze also carries a TTL as the second line of defense.

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
