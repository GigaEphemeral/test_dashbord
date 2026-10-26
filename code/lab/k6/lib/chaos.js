/**
 * Chaos timeline driver library (Task 22a).
 *
 * Responsibilities:
 * - load and validate a shared scenario JSON (`code/lab/scenarios/*.json`);
 * - call the Task 21 chaos-controller (`POST /chaos/instances/{target}/actions`,
 *   `POST /chaos/stop-all`) with the `X-Lab-Token` header;
 * - record `chaos_events{action,target,result}` (accepted/failed); `run_id` is
 *   added by the global `options.tags` in lib/config.js.
 *
 * Every value (controller URL, token, scenario file/dir, timeouts, start
 * offset, target pattern, TTL ceiling, load duration/VUs) is
 * environment-overridable. The token is never printed or tagged.
 */
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { config, parseDurationMs } from './config.js';
import { validateScenario } from './chaos-schema.js';

export {
  ALLOWED_ACTIONS,
  DEFAULT_TARGET_PATTERN,
  SCHEMA_VERSION,
  TTL_ACTIONS,
  sortEvents,
  validateScenario,
} from './chaos-schema.js';

const ENV = __ENV;

/** Counts accepted/failed controller calls; labels add action/target/result. */
export const chaosEvents = new Counter('chaos_events');

function text(name, fallback) {
  const value = ENV[name];
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  return String(value);
}

function httpUrl(name, fallback) {
  const value = text(name, fallback).replace(/\/+$/, '');
  if (!/^https?:\/\//.test(value)) {
    throw new Error(`[k6/chaos] ${name} must be an absolute http(s) URL, got "${value}"`);
  }
  return value;
}

function positiveInteger(name, fallback) {
  const raw = text(name, String(fallback));
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(`[k6/chaos] ${name} must be an integer >= 1, got "${raw}"`);
  }
  return value;
}

/** k6 duration for scenario `startTime`; "0" and "0s" are valid (no delay). */
function startOffset(name, fallback) {
  const raw = text(name, fallback).trim();
  if (!/^\d+(?:\.\d+)?(?:ms|s|m|h)?$/.test(raw)) {
    throw new Error(`[k6/chaos] ${name} must be a k6 duration ("0s", "5s", "1m"), got "${raw}"`);
  }
  const milliseconds = Number.parseFloat(raw) === 0 ? 0 : parseDurationMs(raw, name);
  return { raw, milliseconds };
}

function targetPattern() {
  const value = text('CHAOS_TARGET_PATTERN', '^jl-app-[0-9]+$');
  try {
    new RegExp(value);
  } catch (error) {
    throw new Error(`[k6/chaos] CHAOS_TARGET_PATTERN is not a valid RegExp: ${error.message}`);
  }
  return value;
}

const controllerUrl = httpUrl('CHAOS_CONTROLLER_URL', 'http://chaos-controller:8081');
const token = text('LAB_TOKEN', '');
if (token.length === 0) {
  throw new Error('[k6/chaos] LAB_TOKEN is required to call the chaos-controller');
}

const scenarioDir = text('CHAOS_SCENARIO_DIR', '/chaos-scenarios').replace(/\/+$/, '');
const scenarioFile = text('CHAOS_SCENARIO_FILE', 'ha-app-failover.json');
const httpTimeout = (() => {
  const raw = text('CHAOS_HTTP_TIMEOUT', '5s');
  parseDurationMs(raw, 'CHAOS_HTTP_TIMEOUT');
  return raw;
})();
const teardownTimeout = (() => {
  const raw = text('CHAOS_TEARDOWN_TIMEOUT', '10s');
  parseDurationMs(raw, 'CHAOS_TEARDOWN_TIMEOUT');
  return raw;
})();
const offset = startOffset('CHAOS_START_OFFSET', '0s');
const loadDuration = text('CHAOS_DURATION', text('DURATION', '4m'));
const loadDurationMs = parseDurationMs(loadDuration, 'CHAOS_DURATION');

export const chaosConfig = {
  controllerUrl,
  token,
  scenarioDir,
  scenarioFile,
  httpTimeout,
  teardownTimeout,
  startOffset: offset.raw,
  startOffsetMs: offset.milliseconds,
  targetPattern: targetPattern(),
  maxTtlSeconds: positiveInteger('CHAOS_MAX_TTL', 600),
  loadDuration,
  loadDurationMs,
  loadVus: positiveInteger('CHAOS_VUS', 20),
};

/** A bare file name is resolved against CHAOS_SCENARIO_DIR; absolute wins. */
export function resolveScenarioPath(file, directory) {
  const name = String(file === undefined ? chaosConfig.scenarioFile : file).trim();
  if (name.length === 0) {
    throw new Error('[k6/chaos] scenario file name must not be empty');
  }
  if (name.startsWith('/')) {
    return name;
  }
  const base = String(directory === undefined ? chaosConfig.scenarioDir : directory).replace(/\/+$/, '');
  return `${base}/${name}`;
}

/**
 * Reads and validates a scenario JSON at init time (k6 `open()` only works in
 * the init context). Validation errors abort the run before traffic starts.
 */
export function loadScenario(path) {
  let raw;
  try {
    raw = open(path);
  } catch (error) {
    throw new Error(`[k6/chaos] cannot read scenario ${path}: ${error.message}`);
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    throw new Error(`[k6/chaos] ${path}: invalid JSON: ${error.message}`);
  }
  return validateScenario(parsed, {
    source: path,
    targetPattern: chaosConfig.targetPattern,
    maxTtlSeconds: chaosConfig.maxTtlSeconds,
  });
}

function bodySnippet(res) {
  const body = res.body === undefined || res.body === null ? '' : String(res.body);
  return body.length > 300 ? `${body.slice(0, 300)}...` : body;
}

function postJson(path, body, timeout, tags, labToken) {
  const headers = { Accept: 'application/json', 'X-Lab-Token': labToken };
  let payload = null;
  if (body !== null && body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = http.post(`${controllerUrl}${path}`, payload, {
    headers,
    timeout,
    tags: {
      run_id: config.runId,
      flow: 'chaos',
      ...(tags || {}),
    },
    responseCallback: http.expectedStatuses(200, 202),
  });
  if (res.status < 200 || res.status > 299) {
    throw new Error(`POST ${path} returned HTTP ${res.status}: ${bodySnippet(res)}`);
  }
  return res;
}

/**
 * Applies one scenario event. Throws on any non-2xx response; the timeline
 * treats a throw as a hard stop for the remaining events.
 */
export function action(baseUrl, labToken, event, timeout) {
  if (event === null || typeof event !== 'object') {
    throw new Error('[k6/chaos] event must be an object');
  }
  const target = String(event.target);
  const actionName = String(event.action);
  const url = String(baseUrl).replace(/\/+$/, '');
  const path = `/chaos/instances/${encodeURIComponent(target)}/actions`;
  const body = { action: actionName };
  if (event.ttlSeconds !== undefined && event.ttlSeconds !== null) {
    body.ttlSeconds = event.ttlSeconds;
  }
  const tags = { chaos_action: actionName, chaos_target: target };
  try {
    const res = postJson(path, body, timeout, tags, labToken);
    chaosEvents.add(1, { action: actionName, target, result: 'accepted' });
    return res;
  } catch (error) {
    chaosEvents.add(1, { action: actionName, target, result: 'failed' });
    throw error;
  }
}

/** Best-effort rollback: start every stopped container / SIGCONT every freeze. */
export function stopAll(baseUrl, labToken, timeout) {
  const url = String(baseUrl).replace(/\/+$/, '');
  const res = http.post(`${url}/chaos/stop-all`, null, {
    headers: { Accept: 'application/json', 'X-Lab-Token': labToken },
    timeout,
    tags: {
      run_id: config.runId,
      flow: 'chaos',
      chaos_action: 'stop-all',
    },
    responseCallback: http.expectedStatuses(200),
  });
  if (res.status < 200 || res.status > 299) {
    throw new Error(`POST /chaos/stop-all returned HTTP ${res.status}: ${bodySnippet(res)}`);
  }
  let payload = null;
  try {
    payload = res.json();
  } catch (error) {
    payload = null;
  }
  return payload === null ? null : payload.data;
}
