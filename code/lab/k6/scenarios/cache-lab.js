/**
 * Cache experiment driver (D10).
 *
 * `setup()` seeds the reserved experiment dataset once (`POST /lab/cache/seed`
 * on the internal lab API, `X-Lab-Token`), then one timeline VU walks the
 * canonical mode order:
 *
 *   off -> null-cache -> bloom -> mutex -> logical-expire ->
 *   ttl-jitter -> l1-cache -> db-fallback -> off (reset)
 *
 * At the same time constant VUs read seeded products through Nginx. Reads are
 * tagged with the schedule-derived mode, and at the end of every dwell the
 * driver samples `GET /lab/cache/state` and `/actuator/prometheus` from the app
 * instance(s) and emits `cache_lab_*` gauges tagged with that mode. D10 can
 * therefore align k6 activity, app-side DB fallbacks and the cache counters by
 * `run_id` + `mode`.
 *
 * This scenario is opt-in: it writes 1000 experiment rows and flips the
 * instance-level defense mode, so it must not share a run with a bench baseline.
 * It always ends with `POST /lab/cache/mode {"mode":"off"}` in the timeline and
 * again in `teardown()`, so an aborted run does not leave a defense enabled.
 *
 * Lab endpoint base URL(s) default to `http://app-1:8080`; set
 * `CACHE_LAB_APP_URLS=http://app-1:8080,http://app-2:8080,http://app-3:8080`
 * to switch the whole tier. Business reads always go through `BASE_URL`
 * (`http://nginx`).
 */
import { sleep } from 'k6';
import http from 'k6/http';
import { Counter, Gauge } from 'k6/metrics';
import { config, baseOptions, parseDurationMs } from '../lib/config.js';
import { call } from '../lib/http.js';

const ENV = __ENV;

/** Canonical order: penetration -> breakdown -> avalanche. */
export const CACHE_MODE_ORDER = Object.freeze([
  'off',
  'null-cache',
  'bloom',
  'mutex',
  'logical-expire',
  'ttl-jitter',
  'l1-cache',
  'db-fallback',
]);

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
    throw new Error(`[k6/cache-lab] ${name} must be an integer >= ${min}, got "${raw}"`);
  }
  return value;
}

function httpUrl(name, value) {
  const url = String(value).replace(/\/+$/, '');
  if (!/^https?:\/\//.test(url)) {
    throw new Error(`[k6/cache-lab] ${name} must be an absolute http(s) URL, got "${value}"`);
  }
  return url;
}

function urlList(name, fallback) {
  const values = text(name, fallback)
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .map((part) => httpUrl(name, part));
  if (values.length === 0) {
    throw new Error(`[k6/cache-lab] ${name} must contain at least one URL`);
  }
  return values;
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
        throw new Error(`[k6/cache-lab] ${name} contains an invalid HTTP status "${part}"`);
      }
      return value;
    });
  if (values.length === 0) {
    throw new Error(`[k6/cache-lab] ${name} must contain at least one status`);
  }
  return values;
}

/**
 * Parses `CACHE_LAB_MODES` as an ordered subset of the canonical list. Unknown
 * keys, duplicates and a reordering are rejected: the drill compares successive
 * defenses, so silently running them in another order would change the lesson.
 */
export function parseModes(raw) {
  const modes = String(raw)
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
  if (modes.length === 0) {
    throw new Error('[k6/cache-lab] CACHE_LAB_MODES must contain at least one mode');
  }
  const seen = {};
  let previousIndex = -1;
  for (const mode of modes) {
    if (seen[mode] === true) {
      throw new Error(`[k6/cache-lab] CACHE_LAB_MODES contains duplicate mode "${mode}"`);
    }
    seen[mode] = true;
    const index = CACHE_MODE_ORDER.indexOf(mode);
    if (index === -1) {
      throw new Error(
        `[k6/cache-lab] CACHE_LAB_MODES has unknown mode "${mode}" ` +
          `(allowed: ${CACHE_MODE_ORDER.join(',')})`,
      );
    }
    if (index <= previousIndex) {
      throw new Error(
        `[k6/cache-lab] CACHE_LAB_MODES must follow the canonical order ` +
          `(${CACHE_MODE_ORDER.join(' -> ')}), got "${modes.join(',')}"`,
      );
    }
    previousIndex = index;
  }
  return modes;
}

/**
 * Sums every sample of `metricName` in Prometheus text format, optionally only
 * lines whose label set contains `labelFilter` (e.g. `result="hit"`). Pure JS so
 * the parser is unit-tested under Node without a k6 runtime.
 */
export function sumPrometheusMetric(body, metricName, labelFilter) {
  if (typeof body !== 'string' || body.length === 0) {
    return 0;
  }
  let total = 0;
  for (const rawLine of body.split('\n')) {
    const line = rawLine.trim();
    if (line.length === 0 || line.startsWith('#')) {
      continue;
    }
    const brace = line.indexOf('{');
    const space = line.indexOf(' ');
    const hasLabels = brace !== -1 && (space === -1 || brace < space);
    const name = hasLabels
      ? line.slice(0, brace)
      : line.slice(0, space === -1 ? line.length : space);
    if (name !== metricName) {
      continue;
    }
    let labels = '';
    let valueStart;
    if (hasLabels) {
      const close = line.indexOf('}', brace);
      if (close === -1) {
        continue;
      }
      labels = line.slice(brace + 1, close);
      valueStart = close + 1;
    } else {
      valueStart = space === -1 ? line.length : space;
    }
    if (labelFilter && labels.indexOf(labelFilter) === -1) {
      continue;
    }
    const value = Number(line.slice(valueStart).trim().split(/\s+/)[0]);
    if (Number.isFinite(value)) {
      total += value;
    }
  }
  return total;
}

const token = text('LAB_TOKEN', '');
if (token.length === 0) {
  throw new Error('[k6/cache-lab] LAB_TOKEN is required to call the lab cache API');
}

const appUrls = urlList('CACHE_LAB_APP_URLS', 'http://app-1:8080');
const modes = parseModes(text('CACHE_LAB_MODES', CACHE_MODE_ORDER.join(',')));
const modeDuration = duration('CACHE_LAB_MODE_DURATION', '20s');
const readVus = integer('CACHE_LAB_VUS', 10, 1);
const thinkTime = duration('CACHE_LAB_THINK_TIME', '200ms');
const seedStartId = integer('CACHE_LAB_SEED_START_ID', 90001, 1);
const seedCount = integer('CACHE_LAB_SEED_COUNT', 200, 1);
const seedEndId = seedStartId + seedCount - 1;
const readExpectedStatuses = statusList('CACHE_LAB_READ_EXPECTED_STATUSES', '200,429');
const sampleActuator = text('CACHE_LAB_SAMPLE_ACTUATOR', '1') !== '0';

const paths = {
  seed: text('CACHE_LAB_SEED_PATH', '/lab/cache/seed'),
  mode: text('CACHE_LAB_MODE_PATH', '/lab/cache/mode'),
  state: text('CACHE_LAB_STATE_PATH', '/lab/cache/state'),
  actuator: text('CACHE_LAB_ACTUATOR_PATH', '/actuator/prometheus'),
};

const readDurationMs = modes.length * modeDuration.ms;
const readDuration = { raw: `${readDurationMs}ms`, ms: readDurationMs };
const runStartedAt = Date.now();

export const cacheLabConfig = Object.freeze({
  appUrls,
  modes,
  modeDuration: modeDuration.raw,
  modeDurationMs: modeDuration.ms,
  readDuration: readDuration.raw,
  readDurationMs: readDuration.ms,
  readVus,
  thinkTimeSeconds: thinkTime.ms / 1000,
  seedStartId,
  seedCount,
  seedEndId,
  readExpectedStatuses,
  sampleActuator,
  paths,
});

// Per-mode k6-side metrics (run_id is added by baseOptions.tags).
export const cacheLabReads = new Counter('cache_lab_reads');
export const cacheLabReadErrors = new Counter('cache_lab_read_errors');
export const cacheLabModeSwitches = new Counter('cache_lab_mode_switches');
export const cacheLabDbFallbackRejections = new Counter(
  'cache_lab_db_fallback_rejections',
);
export const cacheLabDbFallbacks = new Gauge('cache_lab_db_fallbacks');
export const cacheLabL1Hits = new Gauge('cache_lab_l1_hits');
export const cacheLabL1Requests = new Gauge('cache_lab_l1_requests');
export const cacheLabBloomAbsents = new Gauge('cache_lab_bloom_absents');
export const cacheLabMutexRebuilds = new Gauge('cache_lab_mutex_rebuilds');
export const cacheLabLimiterRejections = new Gauge('cache_lab_limiter_rejections');
export const cacheLabStateL2Keys = new Gauge('cache_lab_state_l2_keys');
export const cacheLabStateL1Keys = new Gauge('cache_lab_state_l1_keys');
export const cacheLabStateLiveMarkers = new Gauge('cache_lab_state_live_markers');
export const cacheLabStateBloomSize = new Gauge('cache_lab_state_bloom_size');
export const cacheLabStateRedisReachable = new Gauge(
  'cache_lab_state_redis_reachable',
);

export const options = {
  ...baseOptions,
  scenarios: {
    read: {
      executor: 'constant-vus',
      vus: readVus,
      duration: readDuration.raw,
      gracefulStop: config.gracefulStop,
      exec: 'readSeededProduct',
    },
    modes: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: '0s',
      gracefulStop: config.gracefulStop,
      exec: 'modeTimeline',
    },
  },
  thresholds: {
    flow_errors: [`rate<${config.thresholds.errorRate}`],
    // 200 is a served product and 429 is the db-fallback limiter's fast fail;
    // anything else is a real read failure.
    'http_req_failed{flow:cache_read}': [`rate<${config.thresholds.errorRate}`],
  },
};

/** Deterministic coverage of the seeded id range across VUs and iterations. */
export function seededProductId(vu, iter) {
  const offset = (((vu * 131 + iter) % seedCount) + seedCount) % seedCount;
  return seedStartId + offset;
}

/** Schedule-derived mode for a read (the app is switched by another VU). */
export function modeForElapsed(elapsedMs) {
  const index = Math.floor(Math.max(0, elapsedMs) / modeDuration.ms);
  return modes[Math.min(index, modes.length - 1)];
}

function scheduleStart(data) {
  return data !== null && data !== undefined && typeof data.startedAt === 'number'
    ? data.startedAt
    : runStartedAt;
}

function labRequest(method, baseUrl, path, body, actionTag) {
  const headers = { Accept: 'application/json', 'X-Lab-Token': token };
  let payload = null;
  if (body !== undefined && body !== null) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  return http.request(method, `${baseUrl}${path}`, payload, {
    headers,
    timeout: config.httpTimeout,
    tags: {
      run_id: config.runId,
      flow: 'cache_lab',
      cache_action: actionTag,
    },
    responseCallback: http.expectedStatuses(200),
  });
}

function envelope(res, label) {
  let payload = null;
  try {
    payload = res.json();
  } catch (error) {
    payload = null;
  }
  if (res.status !== 200 || payload === null || payload.code !== '0') {
    throw new Error(`[k6/cache-lab] ${label} returned HTTP ${res.status}`);
  }
  return payload.data;
}

/**
 * Seeds once (idempotent on the app side) and resets the mode to `off` before
 * traffic starts. A seed failure aborts the run: reading missing ids would make
 * the per-mode comparison meaningless.
 */
export function setup() {
  // Reset first: if seeding fails, setup aborts before k6 can run teardown and
  // the tier must already be in the unprotected baseline state.
  for (const url of appUrls) {
    envelope(
      labRequest('POST', url, paths.mode, { mode: 'off' }, 'mode'),
      `POST ${paths.mode} off on ${url}`,
    );
  }
  const seed = envelope(
    labRequest('POST', appUrls[0], paths.seed, null, 'seed'),
    `POST ${paths.seed} on ${appUrls[0]}`,
  );
  if (seed === null || seed.startId === undefined || seed.endId === undefined) {
    throw new Error('[k6/cache-lab] seed response has no id range');
  }
  if (seed.startId > seedStartId || seed.endId < seedEndId) {
    throw new Error(
      `[k6/cache-lab] seed range [${seed.startId},${seed.endId}] does not cover ` +
        `the read range [${seedStartId},${seedEndId}]`,
    );
  }
  if (typeof seed.rowsInRange === 'number' && seed.rowsInRange < seed.requestedProducts) {
    throw new Error(
      `[k6/cache-lab] seed is incomplete: rowsInRange=${seed.rowsInRange} ` +
        `requestedProducts=${seed.requestedProducts}`,
    );
  }
  console.log(
    `[cache-lab] seeded ids [${seed.startId},${seed.endId}] inserted=${seed.inserted} ` +
      `rows=${seed.rowsInRange}; reads [${seedStartId},${seedEndId}] ` +
      `modes=${modes.join(' -> ')} dwell=${modeDuration.raw} apps=${appUrls.join(',')}`,
  );
  return {
    startedAt: Date.now(),
    seedStartId: seed.startId,
    seedEndId: seed.endId,
    modes,
  };
}

function applyMode(mode) {
  let accepted = 0;
  for (const url of appUrls) {
    try {
      const data = envelope(
        labRequest('POST', url, paths.mode, { mode }, 'mode'),
        `POST ${paths.mode}=${mode} on ${url}`,
      );
      if (data === null || data.mode !== mode) {
        console.warn(
          `[cache-lab] ${url} answered mode=${data === null ? '?' : data.mode} ` +
            `after switching to ${mode}`,
        );
      } else {
        accepted += 1;
      }
    } catch (error) {
      console.error(`[cache-lab] mode switch ${mode} failed: ${error.message}`);
    }
  }
  return { accepted, failed: appUrls.length - accepted };
}

function readCounters(url) {
  const res = http.request('GET', `${url}${paths.actuator}`, null, {
    headers: { Accept: 'text/plain' },
    timeout: config.httpTimeout,
    tags: {
      run_id: config.runId,
      flow: 'cache_lab',
      cache_action: 'actuator',
    },
    responseCallback: http.expectedStatuses(200),
  });
  if (res.status !== 200) {
    return null;
  }
  const body = String(res.body === undefined || res.body === null ? '' : res.body);
  return {
    dbFallbacks: sumPrometheusMetric(body, 'cache_db_fallbacks_total'),
    l1Hits: sumPrometheusMetric(body, 'cache_l1_requests_total', 'result="hit"'),
    l1Requests: sumPrometheusMetric(body, 'cache_l1_requests_total'),
    bloomAbsents: sumPrometheusMetric(
      body,
      'cache_bloom_requests_total',
      'result="absent"',
    ),
    mutexRebuilds: sumPrometheusMetric(
      body,
      'cache_breakdown_mutex_total',
      'result="rebuild"',
    ),
    limiterRejections: sumPrometheusMetric(
      body,
      'cache_avalanche_limiter_total',
      'result="rejected"',
    ),
  };
}

function primeCounters(previous) {
  if (!sampleActuator) {
    return;
  }
  for (const url of appUrls) {
    const counters = readCounters(url);
    if (counters !== null) {
      previous[url] = counters;
    }
  }
}

function sampleActuatorCounters(mode, previous) {
  const deltas = {
    dbFallbacks: 0,
    l1Hits: 0,
    l1Requests: 0,
    bloomAbsents: 0,
    mutexRebuilds: 0,
    limiterRejections: 0,
  };
  let available = 0;
  for (const url of appUrls) {
    const counters = readCounters(url);
    if (counters === null) {
      continue;
    }
    available += 1;
    const before = previous[url];
    if (before !== undefined) {
      for (const key of Object.keys(deltas)) {
        deltas[key] += Math.max(0, counters[key] - before[key]);
      }
    }
    previous[url] = counters;
  }
  if (available === 0) {
    console.warn(`[cache-lab] no app actuator answered; skipping counters for ${mode}`);
    return;
  }
  cacheLabDbFallbacks.add(deltas.dbFallbacks, { mode });
  cacheLabL1Hits.add(deltas.l1Hits, { mode });
  cacheLabL1Requests.add(deltas.l1Requests, { mode });
  cacheLabBloomAbsents.add(deltas.bloomAbsents, { mode });
  cacheLabMutexRebuilds.add(deltas.mutexRebuilds, { mode });
  cacheLabLimiterRejections.add(deltas.limiterRejections, { mode });
}

function sampleState(mode) {
  let l2Keys = 0;
  let l1Keys = 0;
  let liveMarkers = 0;
  let bloomSize = 0;
  let redisReachable = 0;
  let answered = 0;
  for (const url of appUrls) {
    let data = null;
    try {
      data = envelope(
        labRequest('GET', url, paths.state, null, 'state'),
        `GET ${paths.state} on ${url}`,
      );
    } catch (error) {
      console.warn(`[cache-lab] state sample failed: ${error.message}`);
      continue;
    }
    if (data === null || data.keys === null || data.keys === undefined) {
      continue;
    }
    answered += 1;
    l2Keys += Number(data.keys.l2ExperimentKeys) || 0;
    l1Keys += Number(data.keys.l1ExperimentKeys) || 0;
    liveMarkers += Number(data.nullCache === null || data.nullCache === undefined
      ? 0
      : data.nullCache.liveMarkerKeys) || 0;
    bloomSize += Number(data.bloom === null || data.bloom === undefined
      ? 0
      : data.bloom.size) || 0;
    if (data.keys.redisReachable === true) {
      redisReachable += 1;
    }
  }
  if (answered === 0) {
    console.warn(`[cache-lab] no app /lab/cache/state answered for ${mode}`);
    return;
  }
  cacheLabStateL2Keys.add(l2Keys, { mode });
  cacheLabStateL1Keys.add(l1Keys, { mode });
  cacheLabStateLiveMarkers.add(liveMarkers, { mode });
  cacheLabStateBloomSize.add(bloomSize, { mode });
  cacheLabStateRedisReachable.add(redisReachable, { mode });
}

/**
 * Walks the mode list on the shared schedule and samples each dwell. The final
 * switch back to `off` is unconditional; `teardown()` repeats it after the run.
 */
export function modeTimeline(data) {
  const startedAt = scheduleStart(data);
  const previous = {};
  primeCounters(previous);

  for (let index = 0; index < modes.length; index += 1) {
    const waitSeconds = (startedAt + index * modeDuration.ms - Date.now()) / 1000;
    if (waitSeconds > 0) {
      sleep(waitSeconds);
    }
    const mode = modes[index];
    const switched = applyMode(mode);
    cacheLabModeSwitches.add(1, {
      mode,
      result: switched.failed === 0 ? 'accepted' : 'failed',
    });
    console.log(
      `[cache-lab] t=+${(index * modeDuration.ms) / 1000}s mode=${mode} ` +
        `apps=${switched.accepted}/${appUrls.length}`,
    );
    sleep(modeDuration.ms / 1000);
    sampleState(mode);
    sampleActuatorCounters(mode, previous);
  }

  const reset = applyMode('off');
  cacheLabModeSwitches.add(1, {
    mode: 'off',
    result: reset.failed === 0 ? 'accepted' : 'failed',
  });
  console.log(
    `[cache-lab] final reset mode=off apps=${reset.accepted}/${appUrls.length}`,
  );
}

/** Constant-VU reads over the seeded range, through the business entry point. */
export function readSeededProduct(data) {
  const startedAt = scheduleStart(data);
  const mode = modeForElapsed(Date.now() - startedAt);
  const id = seededProductId(__VU, __ITER);
  const result = call('GET', `${config.paths.products}/${id}`, {
    flow: 'cache_read',
    tags: { mode },
    acceptStatuses: readExpectedStatuses,
    requireCode: false,
    checkName: 'cache read ok',
    validate: (payload, res) => {
      if (res.status === 429) {
        return true;
      }
      return (
        payload !== null &&
        payload.code === '0' &&
        payload.data !== null &&
        Number(payload.data.id) === id
      );
    },
  });

  if (result.status === 429) {
    // db-fallback's bounded DB budget fast-fails with 42901 instead of
    // stampeding MySQL; that is the expected signal, not a read error.
    cacheLabReads.add(1, { mode, result: 'limited' });
    cacheLabDbFallbackRejections.add(1, { mode });
  } else if (result.ok) {
    cacheLabReads.add(1, { mode, result: 'ok' });
  } else {
    cacheLabReads.add(1, { mode, result: 'error' });
    cacheLabReadErrors.add(1, { mode, status: String(result.status) });
  }
  sleep(thinkTime.ms / 1000);
}

/** Always reset every instance to `off`, even when the timeline aborted. */
export function teardown() {
  for (const url of appUrls) {
    try {
      const data = envelope(
        labRequest('POST', url, paths.mode, { mode: 'off' }, 'mode'),
        `POST ${paths.mode}=off on ${url}`,
      );
      console.log(`[cache-lab] teardown mode=${data === null ? '?' : data.mode} app=${url}`);
    } catch (error) {
      console.error(`[cache-lab] teardown reset failed for ${url}: ${error.message}`);
    }
  }
}
