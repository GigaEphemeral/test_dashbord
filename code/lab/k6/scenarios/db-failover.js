/**
 * MySQL primary failover drill.
 *
 * Three k6 scenarios run in parallel:
 *   - `read`  - constant VUs reading `GET /api/products/{PRODUCT_ID}` through
 *               Nginx for the whole run. Reads must never see a server error:
 *               the Router moves 6446 to the surviving primary during the
 *               election.
 *   - `write` - constant VUs creating idempotent orders with a unique
 *               `Idempotency-Key` every `DB_FAILOVER_WRITE_INTERVAL`. Writes are
 *               allowed to fail only inside the documented election window.
 *   - `chaos` - 1 VU that kills `DB_FAILOVER_TARGET` with
 *               `DB_FAILOVER_TTL_SECONDS` through the chaos-controller
 *               (`POST /chaos/instances/{target}/actions`, `X-Lab-Token`).
 *
 * Why the write expectation is time-bounded: `http.expectedStatuses` hides a
 * status from k6's `http_req_failed`, so marking 5xx "expected" for the whole
 * run would also hide a real regression. The write VU therefore accepts the
 * configured 5xx list only between `DB_FAILOVER_KILL_AT` and
 * `DB_FAILOVER_KILL_AT + DB_FAILOVER_ERROR_WINDOW`; outside that window the
 * same status is a hard flow/server error. Expected election errors and real
 * failures are counted separately (`db_failover_expected_write_errors` vs
 * `db_failover_write_failures`).
 *
 * Datastore targets stay closed by default. A run needs the operator to widen
 * BOTH gates explicitly, for example:
 *
 *   LAB_TOKEN=... \
 *   CHAOS_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
 *   CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
 *   DB_FAILOVER_TARGET=jl-mysql-2 DB_FAILOVER_TTL_SECONDS=45 \
 *   bash code/lab/k6/run.sh db-failover
 *
 * `teardown` always calls `/chaos/stop-all`, so a failed or shortened run does
 * not leave the primary stopped (the TTL is the second line of defense).
 */
import { sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { config, baseOptions, parseDurationMs } from '../lib/config.js';
import { ensureLogin, order, product } from '../lib/flow.js';
import {
  action,
  chaosConfig,
  stopAll,
  validateScenario,
} from '../lib/chaos.js';

const ENV = __ENV;

function text(name, fallback) {
  const value = ENV[name];
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  return String(value);
}

function integer(name, fallback, min) {
  const raw = text(name, String(fallback));
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min) {
    throw new Error(`[k6/db-failover] ${name} must be an integer >= ${min}, got "${raw}"`);
  }
  return value;
}

function fraction(name, fallback) {
  const raw = text(name, String(fallback));
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0 || value >= 1) {
    throw new Error(`[k6/db-failover] ${name} must be a number in (0, 1), got "${raw}"`);
  }
  return value;
}

function duration(name, fallback) {
  const raw = text(name, fallback);
  return { raw, ms: parseDurationMs(raw, name) };
}

function statusList(name, fallback) {
  const raw = text(name, fallback);
  const values = raw
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .map((part) => {
      const value = Number(part);
      if (!Number.isInteger(value) || value < 100 || value > 599) {
        throw new Error(`[k6/db-failover] ${name} contains an invalid HTTP status "${part}"`);
      }
      return value;
    });
  if (values.length === 0) {
    throw new Error(`[k6/db-failover] ${name} must contain at least one status`);
  }
  return values;
}

const target = text('DB_FAILOVER_TARGET', 'jl-mysql-2').trim();
if (target.length === 0) {
  throw new Error('[k6/db-failover] DB_FAILOVER_TARGET must not be empty');
}
const ttlSeconds = integer('DB_FAILOVER_TTL_SECONDS', 45, 1);
if (ttlSeconds > chaosConfig.maxTtlSeconds) {
  throw new Error(
    `[k6/db-failover] DB_FAILOVER_TTL_SECONDS=${ttlSeconds} exceeds the ` +
      `${chaosConfig.maxTtlSeconds}s CHAOS_MAX_TTL ceiling`,
  );
}

const killAt = duration('DB_FAILOVER_KILL_AT', '20s');
const errorWindow = duration('DB_FAILOVER_ERROR_WINDOW', '30s');
const loadDuration = duration(
  'DB_FAILOVER_DURATION',
  text('CHAOS_DURATION', '2m'),
);
if (killAt.ms >= loadDuration.ms) {
  throw new Error(
    `[k6/db-failover] DB_FAILOVER_KILL_AT=${killAt.raw} must be shorter than ` +
      `DB_FAILOVER_DURATION=${loadDuration.raw}`,
  );
}
if (killAt.ms + errorWindow.ms > loadDuration.ms) {
  throw new Error(
    `[k6/db-failover] kill at ${killAt.raw} + error window ${errorWindow.raw} ` +
      `exceeds DB_FAILOVER_DURATION=${loadDuration.raw}; expected write errors ` +
      'would fall outside the measured run',
  );
}

const readVus = integer('DB_FAILOVER_READ_VUS', 20, 1);
const writeVus = integer('DB_FAILOVER_WRITE_VUS', 1, 1);
const writeInterval = duration('DB_FAILOVER_WRITE_INTERVAL', '2s');
const readThinkTime = duration('DB_FAILOVER_READ_THINK_TIME', '1s');
const writeExpectedStatuses = statusList(
  'DB_FAILOVER_WRITE_EXPECTED_STATUSES',
  '500,502,503,504',
);
const writeMinSuccessRate = fraction('DB_FAILOVER_WRITE_MIN_SUCCESS_RATE', 0.85);

// The synthetic timeline goes through the same schema validation as the shared
// JSON files, so the datastore allow-list and the TTL ceiling still apply.
const timeline = validateScenario(
  {
    schemaVersion: 1,
    id: 'db-failover',
    name: 'MySQL 主库故障转移',
    description:
      'Kill the configured MySQL node with a TTL while product reads and ' +
      'idempotent order writes run through Nginx.',
    events: [
      {
        atSeconds: killAt.ms / 1000,
        action: 'kill',
        target,
        ttlSeconds,
        title: `停止 ${target}`,
        text: `${target} 停止 ${ttlSeconds}s，由 Router 把写端口切到新主；TTL 到点自动 start。`,
        explain: {
          symptom: '写请求在选举窗口内出现 5xx，读请求经 Router/缓存继续 200。',
          rootCause: 'Router 跟随 InnoDB Cluster 的 PRIMARY，旧主停止后需要一次选举。',
          fix: 'TTL 自动 start 旧主并以 SECONDARY 回组；teardown 的 stop-all 是第二道保险。',
          expectation:
            '读错误率为 0；写在选举窗口内的失败占比有界，恢复后继续成功。',
        },
      },
    ],
  },
  {
    source: 'db-failover (env)',
    targetPattern: chaosConfig.targetPattern,
    allowedDatastorePattern: chaosConfig.allowedDatastorePattern,
    maxTtlSeconds: chaosConfig.maxTtlSeconds,
  },
);

const runStartedAt = Date.now();

/** True while a write 5xx is an expected election outcome, not a regression. */
export function withinExpectedWindow(elapsedMs) {
  return elapsedMs >= killAt.ms && elapsedMs <= killAt.ms + errorWindow.ms;
}

export const dbFailoverConfig = Object.freeze({
  target,
  ttlSeconds,
  killAt: killAt.raw,
  killAtMs: killAt.ms,
  killAtSeconds: killAt.ms / 1000,
  errorWindow: errorWindow.raw,
  errorWindowMs: errorWindow.ms,
  errorWindowSeconds: errorWindow.ms / 1000,
  loadDuration: loadDuration.raw,
  loadDurationMs: loadDuration.ms,
  readVus,
  writeVus,
  writeInterval: writeInterval.raw,
  writeIntervalSeconds: writeInterval.ms / 1000,
  readThinkTimeSeconds: readThinkTime.ms / 1000,
  writeExpectedStatuses,
  writeMinSuccessRate,
});

export { timeline };

/** One rate over all writes; election errors count as missed writes. */
export const dbFailoverWriteSuccess = new Rate('db_failover_write_success');
export const dbFailoverWrites = new Counter('db_failover_writes');
export const dbFailoverExpectedWriteErrors = new Counter(
  'db_failover_expected_write_errors',
);
export const dbFailoverWriteFailures = new Counter('db_failover_write_failures');
export const dbFailoverReads = new Counter('db_failover_reads');

export const options = {
  ...baseOptions,
  scenarios: {
    read: {
      executor: 'constant-vus',
      vus: readVus,
      duration: loadDuration.raw,
      gracefulStop: config.gracefulStop,
      exec: 'readProduct',
    },
    write: {
      executor: 'constant-vus',
      vus: writeVus,
      duration: loadDuration.raw,
      gracefulStop: config.gracefulStop,
      exec: 'writeOrder',
    },
    chaos: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: '0s',
      gracefulStop: config.gracefulStop,
      exec: 'chaosTimeline',
    },
  },
  thresholds: {
    // A read must never fail, including 5xx and transport errors. This is
    // stricter than the shared 1% error budget on purpose: the drill proves the
    // Router keeps reads available across the election.
    'http_req_failed{flow:product}': ['rate==0'],
    'http_req_duration{flow:product}': [
      `p(95)<${config.thresholds.p95Ms}`,
      `p(99)<${config.thresholds.p99Ms}`,
    ],
    flow_errors: [`rate<${config.thresholds.errorRate}`],
    // Bounded write failure budget: with 1 write every 2s over 2m (about 60
    // writes), a 30s election window can lose at most ~15 writes (25%). The
    // 85% floor leaves room for the documented election window while still
    // failing a run where writes stay broken after the primary is back.
    db_failover_write_success: [`rate>${writeMinSuccessRate}`],
  },
};

/** Constant-VU product reads; no inventory is consumed. */
export function readProduct() {
  ensureLogin();
  const ok = product();
  dbFailoverReads.add(1, { result: ok ? 'ok' : 'error' });
  sleep(readThinkTime.ms / 1000);
}

/**
 * Periodic idempotent order writes. Each iteration gets a fresh Idempotency-Key
 * from lib/flow.js (`k6-order-<run_id>-<vu>-<iter>-<random>`), so retries after
 * a transport error do not accidentally replay an older order.
 */
export function writeOrder() {
  ensureLogin();
  const elapsedMs = Date.now() - runStartedAt;
  const inWindow = withinExpectedWindow(elapsedMs);
  const acceptStatuses = inWindow ? [200].concat(writeExpectedStatuses) : [200];
  const result = order({
    acceptStatuses,
    requireCode: !inWindow,
    tags: { db_failover_phase: inWindow ? 'failover' : 'steady' },
  });

  if (result.created) {
    dbFailoverWriteSuccess.add(true);
    dbFailoverWrites.add(1, { result: 'created' });
  } else if (result.expectedFailure && inWindow) {
    // Accepted through http.expectedStatuses: an expected election error.
    dbFailoverWriteSuccess.add(false);
    dbFailoverExpectedWriteErrors.add(1, { status: String(result.status) });
    dbFailoverWrites.add(1, { result: 'expected-election-error' });
  } else {
    dbFailoverWriteSuccess.add(false);
    dbFailoverWriteFailures.add(1, { status: String(result.status) });
    dbFailoverWrites.add(1, { result: 'failed' });
  }

  sleep(writeInterval.ms / 1000);
}

/**
 * Kills the configured primary once. The event is already validated; a failed
 * controller call stops the timeline and teardown still runs `stop-all`.
 */
export function chaosTimeline() {
  if (killAt.ms + ttlSeconds * 1000 > loadDuration.ms) {
    console.warn(
      `[db-failover] TTL ${ttlSeconds}s ends after DB_FAILOVER_DURATION=` +
        `${loadDuration.raw}; the primary is only restored by teardown stop-all, ` +
        'not by the TTL inside this run.',
    );
  }
  console.log(
    `[db-failover] target=${target} killAt=${killAt.raw} ttl=${ttlSeconds}s ` +
      `expectedWindow=${errorWindow.raw} statuses=${writeExpectedStatuses.join(',')} ` +
      `duration=${loadDuration.raw}`,
  );
  const started = Date.now();
  for (const event of timeline.events) {
    const elapsedSeconds = (Date.now() - started) / 1000;
    const waitSeconds = event.atSeconds - elapsedSeconds;
    if (waitSeconds > 0) {
      sleep(waitSeconds);
    }
    console.log(
      `[db-failover] t=+${event.atSeconds.toFixed(1)}s action=${event.action} ` +
        `target=${event.target} ttl=${event.ttlSeconds}s`,
    );
    try {
      const res = action(
        chaosConfig.controllerUrl,
        chaosConfig.token,
        event,
        chaosConfig.httpTimeout,
      );
      console.log(
        `[db-failover] accepted action=${event.action} target=${event.target} ` +
          `status=${res.status}`,
      );
    } catch (error) {
      console.error(
        `[db-failover] failed action=${event.action} target=${event.target}: ` +
          error.message,
      );
      break;
    }
  }
}

/** Always attempt the global rollback, even when the timeline aborted. */
export function teardown() {
  try {
    const result = stopAll(
      chaosConfig.controllerUrl,
      chaosConfig.token,
      chaosConfig.teardownTimeout,
    );
    const reverted = result === null ? '?' : result.reverted;
    const failed = result === null ? '?' : result.failed;
    console.log(`[db-failover] teardown stop-all reverted=${reverted} failed=${failed}`);
  } catch (error) {
    console.error(`[db-failover] teardown stop-all failed: ${error.message}`);
  }
}
