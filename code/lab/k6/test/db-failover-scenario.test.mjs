/**
 * Node regression for the MySQL failover timeline (`../scenarios/db-failover.js`).
 *
 * The k6 modules are stubbed (see k6-loader.mjs) so the scenario can be
 * imported with plain node. The tests pin:
 *   - the read/write/chaos scenario shape and the time-bounded write window;
 *   - the validated kill event (target/TTL/ordering) and the always-on
 *     `stop-all` teardown;
 *   - the write thresholds and unique idempotency keys.
 *
 * Run: node --test "code/lab/k6/test/*.test.mjs"
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import { register } from 'node:module';

register('./k6-loader.mjs', import.meta.url);
globalThis.__ENV = {
  LAB_TOKEN: 'test-token',
  RUN_ID: 'test-run',
  CHAOS_TARGET_PATTERN: '^jl-mysql-[0-9]+$',
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN: '^jl-mysql-[0-9]+$',
  DB_FAILOVER_TARGET: 'jl-mysql-2',
  DB_FAILOVER_TTL_SECONDS: '30',
  DB_FAILOVER_KILL_AT: '10s',
  DB_FAILOVER_ERROR_WINDOW: '20s',
  DB_FAILOVER_DURATION: '60s',
  DB_FAILOVER_READ_VUS: '7',
  DB_FAILOVER_WRITE_VUS: '2',
  DB_FAILOVER_WRITE_INTERVAL: '1s',
  DB_FAILOVER_WRITE_MIN_SUCCESS_RATE: '0.8',
};
globalThis.__VU = 1;
globalThis.__ITER = 0;

const scenario = await import('../scenarios/db-failover.js');
const flow = await import('../lib/flow.js');
const k6HttpStub = await import('./stubs/k6-http.mjs');
const k6Stub = await import('./stubs/k6.mjs');

function resetAll() {
  k6HttpStub.reset();
  k6Stub.reset();
}

test('db-failover runs reads, writes and one timeline VU for the whole window', () => {
  const scenarios = scenario.options.scenarios;
  assert.deepEqual(Object.keys(scenarios), ['read', 'write', 'chaos']);
  assert.equal(scenarios.read.executor, 'constant-vus');
  assert.equal(scenarios.read.vus, 7);
  assert.equal(scenarios.read.duration, '60s');
  assert.equal(scenarios.read.exec, 'readProduct');
  assert.equal(scenarios.write.executor, 'constant-vus');
  assert.equal(scenarios.write.vus, 2);
  assert.equal(scenarios.write.duration, '60s');
  assert.equal(scenarios.write.exec, 'writeOrder');
  assert.equal(scenarios.chaos.executor, 'per-vu-iterations');
  assert.equal(scenarios.chaos.vus, 1);
  assert.equal(scenarios.chaos.iterations, 1);
  assert.equal(scenarios.chaos.startTime, '0s');
  assert.equal(scenarios.chaos.exec, 'chaosTimeline');
  assert.equal(typeof scenario.teardown, 'function');
});

test('the synthetic timeline validates one ordered kill with the configured TTL', () => {
  assert.equal(scenario.timeline.id, 'db-failover');
  assert.equal(scenario.timeline.events.length, 1);
  const [event] = scenario.timeline.events;
  assert.equal(event.atSeconds, 10);
  assert.equal(event.action, 'kill');
  assert.equal(event.target, 'jl-mysql-2');
  assert.equal(event.ttlSeconds, 30);
  assert.ok(Object.isFrozen(scenario.timeline.events[0]));
  assert.equal(scenario.dbFailoverConfig.target, 'jl-mysql-2');
  assert.equal(scenario.dbFailoverConfig.ttlSeconds, 30);
  assert.ok(
    scenario.dbFailoverConfig.killAtMs + scenario.dbFailoverConfig.errorWindowMs <=
      scenario.dbFailoverConfig.loadDurationMs,
  );
});

test('expected write errors are bounded to kill..kill+window, not the whole run', () => {
  assert.equal(scenario.withinExpectedWindow(9_999), false);
  assert.equal(scenario.withinExpectedWindow(10_000), true);
  assert.equal(scenario.withinExpectedWindow(30_000), true);
  assert.equal(scenario.withinExpectedWindow(30_001), false);
  assert.deepEqual(scenario.dbFailoverConfig.writeExpectedStatuses, [500, 502, 503, 504]);
});

test('read and write thresholds separate availability from the election budget', () => {
  const thresholds = scenario.options.thresholds;
  assert.deepEqual(thresholds['http_req_failed{flow:product}'], ['rate==0']);
  assert.deepEqual(thresholds['db_failover_write_success'], ['rate>0.8']);
  assert.deepEqual(thresholds.flow_errors, ['rate<0.01']);
  assert.deepEqual(thresholds['http_req_duration{flow:product}'], [
    'p(95)<300',
    'p(99)<800',
  ]);
});

test('teardown always calls the global stop-all with the lab token', () => {
  resetAll();
  k6HttpStub.script([
    { status: 200, body: { code: '0', message: 'OK', data: { reverted: 1, failed: 0 } } },
  ]);
  scenario.teardown();
  assert.equal(k6HttpStub.state.requests.length, 1);
  const request = k6HttpStub.state.requests[0];
  assert.equal(request.method, 'POST');
  assert.match(request.url, /\/chaos\/stop-all$/);
  assert.equal(request.params.headers['X-Lab-Token'], 'test-token');
});

test('order writes use a fresh idempotency key on every attempt', () => {
  resetAll();
  k6HttpStub.script([
    { status: 200, body: { code: '0', message: 'OK', data: { id: 101 } } },
    { status: 200, body: { code: '0', message: 'OK', data: { id: 102 } } },
  ]);
  const first = flow.order();
  const second = flow.order();
  assert.equal(first.created, true);
  assert.equal(second.created, true);
  assert.equal(k6HttpStub.state.requests.length, 2);
  const keys = k6HttpStub.state.requests.map(
    (request) => request.params.headers['Idempotency-Key'],
  );
  assert.notEqual(keys[0], keys[1]);
  assert.match(keys[0], /^k6-order-test-run-1-0-/);
  assert.match(keys[1], /^k6-order-test-run-1-0-/);
});

test('an election-window 5xx is expected, while the same status outside is a failure', () => {
  resetAll();
  k6HttpStub.script([
    { status: 503, body: { code: '50301', message: 'election', data: null } },
  ]);
  const inside = flow.order({
    acceptStatuses: [200, 500, 502, 503, 504],
    requireCode: false,
  });
  assert.equal(inside.ok, true);
  assert.equal(inside.created, false);
  assert.equal(inside.expectedFailure, true);
  assert.equal(inside.status, 503);

  k6HttpStub.script([
    { status: 503, body: { code: '50301', message: 'still down', data: null } },
  ]);
  const outside = flow.order();
  assert.equal(outside.ok, false);
  assert.equal(outside.created, false);
  assert.equal(outside.expectedFailure, false);
  assert.equal(outside.status, 503);
});

test('a kill outside the measured window fails fast at init', async () => {
  globalThis.__ENV = {
    ...globalThis.__ENV,
    DB_FAILOVER_DURATION: '60s',
    DB_FAILOVER_KILL_AT: '70s',
  };
  await assert.rejects(
    () => import('../scenarios/db-failover.js?bad-window'),
    /DB_FAILOVER_KILL_AT=70s must be shorter than DB_FAILOVER_DURATION=60s/,
  );
  globalThis.__ENV = {
    ...globalThis.__ENV,
    DB_FAILOVER_DURATION: '60s',
    DB_FAILOVER_KILL_AT: '10s',
  };
});
