/**
 * Shared k6 configuration for the HA lab.
 *
 * Every runtime value (URLs, ids, durations, VUs, think times, thresholds,
 * timeouts, status lists, path prefixes) is environment-overridable. The
 * defaults below are the only place where concrete values live; scenarios and
 * the flow library must read them from this module.
 *
 * k6 evaluates init code once per VU. `run.sh` therefore always generates and
 * exports one RUN_ID for the whole test; when k6 is invoked directly, pass
 * RUN_ID explicitly so every VU emits the same label.
 */
import { parseSampling } from './product-sampling.js';

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
    throw new Error(`[k6/config] ${name} must be an integer >= ${min}, got "${raw}"`);
  }
  return value;
}

function decimal(name, fallback, min, max) {
  const raw = text(name, String(fallback));
  const value = Number(raw);
  if (!Number.isFinite(value) || value < min || value > max) {
    throw new Error(`[k6/config] ${name} must be a number in [${min}, ${max}], got "${raw}"`);
  }
  return value;
}

function fraction(name, fallback) {
  const value = decimal(name, fallback, 0, 1);
  if (value >= 1) {
    throw new Error(`[k6/config] ${name} must be < 1, got "${value}"`);
  }
  return value;
}

function integerList(name, fallback) {
  const raw = text(name, fallback);
  const values = raw
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .map((part) => {
      const value = Number(part);
      if (!Number.isInteger(value) || value < 100 || value > 599) {
        throw new Error(`[k6/config] ${name} contains an invalid HTTP status "${part}"`);
      }
      return value;
    });
  if (values.length === 0) {
    throw new Error(`[k6/config] ${name} must contain at least one status`);
  }
  return values;
}

function csv(name, fallback) {
  const raw = text(name, fallback);
  const values = raw
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
  if (values.length === 0) {
    throw new Error(`[k6/config] ${name} must contain at least one value`);
  }
  return values;
}

const DURATION_UNITS_MS = {
  ms: 1,
  s: 1000,
  m: 60 * 1000,
  h: 60 * 60 * 1000,
  d: 24 * 60 * 60 * 1000,
};

/**
 * Parses k6-style durations ("250ms", "30s", "2m", "1m30s") into milliseconds
 * so scenarios can derive staged durations from a single total.
 */
export function parseDurationMs(raw, label) {
  const source = String(raw).trim();
  if (source.length === 0) {
    throw new Error(`[k6/config] ${label} must not be empty`);
  }
  const pattern = /(\d+(?:\.\d+)?)(ms|s|m|h|d)/g;
  let total = 0;
  let consumed = 0;
  let match = pattern.exec(source);
  while (match !== null) {
    if (match.index !== consumed) {
      throw new Error(`[k6/config] ${label} has an invalid duration "${source}"`);
    }
    total += Number(match[1]) * DURATION_UNITS_MS[match[2]];
    consumed = pattern.lastIndex;
    match = pattern.exec(source);
  }
  if (consumed !== source.length || total <= 0) {
    throw new Error(`[k6/config] ${label} has an invalid duration "${source}"`);
  }
  return total;
}

function duration(name, fallback) {
  const raw = text(name, fallback);
  parseDurationMs(raw, name);
  return raw;
}

function stageDuration(milliseconds) {
  return `${Math.max(1, Math.round(milliseconds))}ms`;
}

function loadStages(durationRaw, startVus, targetVus, rampUpFraction, rampPeakFraction) {
  const totalMs = parseDurationMs(durationRaw, 'LOAD_DURATION');
  const rampUpMs = Math.max(1, Math.round(totalMs * rampUpFraction));
  const rampPeakMs = Math.max(1, Math.round(totalMs * rampPeakFraction));
  const holdMs = totalMs - rampUpMs - rampPeakMs;
  if (holdMs < 1) {
    throw new Error(
      '[k6/config] LOAD_DURATION is too short for LOAD_RAMP_UP_FRACTION + ' +
        'LOAD_RAMP_PEAK_FRACTION; leave a positive hold phase',
    );
  }
  return [
    { duration: stageDuration(rampUpMs), target: startVus },
    { duration: stageDuration(rampPeakMs), target: targetVus },
    { duration: stageDuration(holdMs), target: targetVus },
  ];
}

function parseStages() {
  const fallback = JSON.stringify([
    { duration: '1m', target: 50 },
    { duration: '2m', target: 100 },
    { duration: '2m', target: 200 },
    { duration: '2m', target: 300 },
    { duration: '2m', target: 400 },
    { duration: '2m', target: 500 },
    { duration: '1m', target: 0 },
  ]);
  const raw = text('STRESS_STAGES', fallback);
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    throw new Error(`[k6/config] STRESS_STAGES must be a JSON array: ${error.message}`);
  }
  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new Error('[k6/config] STRESS_STAGES must be a non-empty JSON array');
  }
  return parsed.map((stage, index) => {
    if (stage === null || typeof stage !== 'object') {
      throw new Error(`[k6/config] STRESS_STAGES[${index}] must be an object`);
    }
    const target = Number(stage.target);
    if (!Number.isInteger(target) || target < 0) {
      throw new Error(`[k6/config] STRESS_STAGES[${index}].target must be an integer >= 0`);
    }
    const stageRaw = String(stage.duration);
    parseDurationMs(stageRaw, `STRESS_STAGES[${index}].duration`);
    return { duration: stageRaw, target };
  });
}

function generatedRunId() {
  const stamp = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14);
  const suffix = Math.random().toString(36).slice(2, 8);
  return `k6-${stamp}-${suffix}`;
}

function apiPath(name, fallback) {
  const value = text(name, fallback).replace(/\/+$/, '');
  if (!value.startsWith('/')) {
    throw new Error(`[k6/config] ${name} must start with "/", got "${value}"`);
  }
  return value;
}

const runId = text('RUN_ID', generatedRunId());
const baseUrl = text('BASE_URL', 'http://nginx').replace(/\/+$/, '');
if (!/^https?:\/\//.test(baseUrl)) {
  throw new Error(`[k6/config] BASE_URL must be an absolute http(s) URL, got "${baseUrl}"`);
}

const p95Ms = integer('P95_MS', 300, 0);
const p99Ms = integer('P99_MS', 800, 0);
const errorRate = decimal('ERROR_RATE', 0.01, 0, 1);
const checksRate = decimal('CHECKS_RATE', 0.99, 0, 1);

const loadDuration = duration('LOAD_DURATION', '10m');
const loadStartVus = integer('LOAD_START_VUS', 50, 1);
const loadVus = integer('LOAD_VUS', 200, 1);
if (loadVus < loadStartVus) {
  throw new Error('[k6/config] LOAD_VUS must be >= LOAD_START_VUS');
}
const loadRampUpFraction = fraction('LOAD_RAMP_UP_FRACTION', 0.2);
const loadRampPeakFraction = fraction('LOAD_RAMP_PEAK_FRACTION', 0.2);
if (loadRampUpFraction + loadRampPeakFraction >= 1) {
  throw new Error('[k6/config] LOAD ramp fractions must add up to less than 1');
}

const spikeBaselineVus = integer('SPIKE_BASELINE_VUS', 50, 1);
const spikeVus = integer('SPIKE_VUS', 800, 1);
const spikeStages = [
  { duration: duration('SPIKE_BASELINE_DURATION', '30s'), target: spikeBaselineVus },
  { duration: duration('SPIKE_RAMP_UP', '10s'), target: spikeVus },
  { duration: duration('SPIKE_DURATION', '60s'), target: spikeVus },
  { duration: duration('SPIKE_RAMP_DOWN', '10s'), target: 0 },
];

const thinkTime = duration('THINK_TIME', '1s');
const pollInterval = duration('SECKILL_POLL_INTERVAL', '1s');

/**
 * Stage2 B2-03a traffic attribution. The runner (chaos-controller planner)
 * injects both values; direct `run.sh` runs fall back to the documented
 * defaults. Mode is a closed enum because an unknown value would silently
 * mislabel the run; the instance must be a plain label, never a URL.
 */
const TRAFFIC_MODES = ['via-nginx', 'target-instance'];

function trafficMode() {
  const value = text('TRAFFIC_MODE', 'via-nginx');
  if (TRAFFIC_MODES.indexOf(value) === -1) {
    throw new Error(
      `[k6/config] TRAFFIC_MODE must be one of ${TRAFFIC_MODES.join(', ')}, got "${value}"`,
    );
  }
  return value;
}

function trafficTargetInstance() {
  const value = text('TARGET_INSTANCE', 'nginx').trim();
  if (value.length === 0) {
    throw new Error('[k6/config] TARGET_INSTANCE must not be blank');
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(value)) {
    throw new Error(`[k6/config] TARGET_INSTANCE must be a plain instance label, got "${value}"`);
  }
  return value;
}

export const config = {
  runId,
  baseUrl,
  httpTimeout: duration('HTTP_TIMEOUT', '10s'),
  thinkTimeSeconds: parseDurationMs(thinkTime, 'THINK_TIME') / 1000,
  gracefulStop: duration('GRACEFUL_STOP', '5s'),
  gracefulRampDown: duration('GRACEFUL_RAMP_DOWN', '10s'),
  summaryTrendStats: csv('SUMMARY_TREND_STATS', 'avg,min,med,p(90),p(95),p(99),max'),
  summaryTimeUnit: text('SUMMARY_TIME_UNIT', 'ms'),
  paths: {
    login: apiPath('LOGIN_PATH', '/api/login'),
    products: apiPath('PRODUCTS_PATH', '/api/products'),
    orders: apiPath('ORDERS_PATH', '/api/orders'),
    seckillOrders: apiPath('SECKILL_ORDERS_PATH', '/api/seckill/orders'),
  },
  productId: integer('PRODUCT_ID', 1, 1),
  // B3-00: load-only sampling range. Defaults (1..1) keep every load
  // iteration on PRODUCT_ID's historical value, product 1.
  productSampling: parseSampling(ENV),
  seckillProductId: integer('SECKILL_PRODUCT_ID', 20, 1),
  userPrefix: text('USER_PREFIX', 'k6'),
  order: {
    quantity: integer('ORDER_QUANTITY', 1, 1),
    keyPrefix: text('ORDER_KEY_PREFIX', 'k6-order'),
  },
  seckill: {
    quantity: integer('SECKILL_QUANTITY', 1, 1),
    keyPrefix: text('SECKILL_KEY_PREFIX', 'k6-seckill'),
    expectedStatuses: integerList('SECKILL_EXPECTED_STATUSES', '200,202,409,429'),
  },
  poll: {
    attempts: integer('SECKILL_POLL_ATTEMPTS', 30, 1),
    intervalSeconds: parseDurationMs(pollInterval, 'SECKILL_POLL_INTERVAL') / 1000,
  },
  smoke: {
    vus: integer('SMOKE_VUS', 1, 1),
    iterations: integer('SMOKE_ITERATIONS', 1, 1),
    duration: duration('SMOKE_DURATION', '30s'),
  },
  load: {
    duration: loadDuration,
    startVus: loadStartVus,
    vus: loadVus,
    stages: loadStages(
      loadDuration,
      loadStartVus,
      loadVus,
      loadRampUpFraction,
      loadRampPeakFraction,
    ),
  },
  stress: {
    stages: parseStages(),
  },
  spike: {
    stages: spikeStages,
  },
  soak: {
    vus: integer('SOAK_VUS', 100, 1),
    duration: duration('SOAK_DURATION', '30m'),
  },
  // Interactive background load (Task 23): started/stopped from the console,
  // product reads only so it never consumes seckill stock.
  traffic: {
    vus: integer('TRAFFIC_VUS', 50, 1),
    duration: duration('TRAFFIC_DURATION', '30m'),
    // Stage2 B2-03a attribution, also used by lib/http.js request tags.
    mode: trafficMode(),
    targetInstance: trafficTargetInstance(),
  },
  thresholds: {
    p95Ms,
    p99Ms,
    errorRate,
    checksRate,
  },
};

/**
 * Options shared by every scenario. `tags` is what puts run_id on *all* k6
 * metrics (including custom rates/counters and VU/iteration metrics), not only
 * on the HTTP requests that are tagged individually in lib/http.js.
 */
export const baseOptions = {
  summaryTrendStats: config.summaryTrendStats,
  summaryTimeUnit: config.summaryTimeUnit,
  tags: {
    run_id: config.runId,
  },
};
