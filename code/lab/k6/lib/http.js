/**
 * HTTP wrapper shared by every scenario.
 *
 * Responsibilities:
 * - build absolute URLs from config (never from scenario literals);
 * - attach the per-run `run_id` tag, a `flow` tag and the Stage2 B2-03a
 *   attribution tags (`instance`, `traffic_mode`) to every request;
 * - mark expected business statuses (200/202/409/429 for seckill) through
 *   http.expectedStatuses so they do not count as failed requests;
 * - record custom error/server-error rates and seckill rejection counters;
 * - run a single check per request so `checks: rate>...` reflects assertions.
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { config } from './config.js';

export const flowErrors = new Rate('flow_errors');
export const serverErrors = new Rate('server_errors');
export const seckillExpected409 = new Counter('seckill_expected_409');
export const seckillExpected429 = new Counter('seckill_expected_429');
export const seckillTerminalFailed = new Counter('seckill_terminal_failed');
export const seckillPollTimeouts = new Counter('seckill_poll_timeouts');

function absoluteUrl(path) {
  return `${config.baseUrl}${path}`;
}

/**
 * Issues one request and applies the shared contract.
 *
 * options:
 *   flow            - tag value describing the business step;
 *   token           - bearer token, optional;
 *   body            - JS object serialized as JSON, optional;
 *   headers         - extra request headers, optional;
 *   acceptStatuses  - exact HTTP statuses considered successful (default [200]);
 *   requireCode     - require Result.code === "0" on accepted responses (default true);
 *   validate        - extra assertion (payload, response) => boolean;
 *   checkName       - static check name; omit for polling calls.
 */
export function call(method, path, options) {
  const opts = options || {};
  const acceptStatuses = opts.acceptStatuses || [200];
  const headers = { Accept: 'application/json' };
  if (opts.body !== undefined && opts.body !== null) {
    headers['Content-Type'] = 'application/json';
  }
  if (opts.token) {
    headers.Authorization = `Bearer ${opts.token}`;
  }
  if (opts.headers) {
    Object.assign(headers, opts.headers);
  }

  const params = {
    headers,
    timeout: config.httpTimeout,
    tags: {
      run_id: config.runId,
      flow: opts.flow || 'unknown',
      // Stage2 B2-03a: every request is attributable to the traffic mode and
      // the instance the runner pinned (nginx when going through the upstream).
      instance: config.traffic.targetInstance,
      traffic_mode: config.traffic.mode,
    },
    responseCallback: http.expectedStatuses(...acceptStatuses),
  };
  if (opts.tags) {
    Object.assign(params.tags, opts.tags);
    // Attribution is owned by the runner-injected config: a scenario may add
    // tags through opts.tags, but it can never forge which instance/mode
    // produced the request.
    params.tags.instance = config.traffic.targetInstance;
    params.tags.traffic_mode = config.traffic.mode;
  }

  const body = opts.body === undefined || opts.body === null ? null : JSON.stringify(opts.body);
  const res = http.request(method, absoluteUrl(path), body, params);

  let payload = null;
  try {
    payload = res.json();
  } catch (error) {
    payload = null;
  }

  const statusOk = acceptStatuses.indexOf(res.status) !== -1;
  const codeOk = opts.requireCode === false ? true : payload !== null && payload.code === '0';
  const extraOk = typeof opts.validate === 'function' ? opts.validate(payload, res) === true : true;
  const ok = statusOk && codeOk && extraOk;

  flowErrors.add(!ok);
  serverErrors.add(res.status >= 500 || res.status === 0);

  if (opts.checkName) {
    check(res, {
      [opts.checkName]: () => ok,
    });
  }

  return {
    res,
    payload,
    data: payload === null ? null : payload.data,
    status: res.status,
    ok,
  };
}

/** Records an assertion failure that is only detectable after a request. */
export function recordError(checkName) {
  flowErrors.add(true);
  check(false, {
    [checkName]: () => false,
  });
}
