/**
 * Node regression for the cache experiment driver (`../scenarios/cache-lab.js`).
 *
 * Pins the documented contract without a running lab:
 *   - read/mode scenario shape and the schedule-derived duration;
 *   - the canonical mode order (and rejection of duplicates/reordering);
 *   - seeded-id coverage (90001+) and the per-elapsed-time mode tag;
 *   - the unconditional `off` teardown and the Prometheus text parser used for
 *     per-mode DB-fallback gauges.
 *
 * Run: node --test "code/lab/k6/test/*.test.mjs"
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { register } from 'node:module';

register('./k6-loader.mjs', import.meta.url);
globalThis.__ENV = {
  LAB_TOKEN: 'test-token',
  RUN_ID: 'test-run',
  CACHE_LAB_APP_URLS: 'http://app-1:8080',
  CACHE_LAB_SEED_START_ID: '90001',
  CACHE_LAB_SEED_COUNT: '10',
  CACHE_LAB_MODE_DURATION: '5s',
  CACHE_LAB_VUS: '3',
};
globalThis.__VU = 1;
globalThis.__ITER = 0;

const scenario = await import('../scenarios/cache-lab.js');
const k6HttpStub = await import('./stubs/k6-http.mjs');

function resetAll() {
  k6HttpStub.reset();
}

test('cache-lab reads seeded ids for the whole mode schedule', () => {
  const scenarios = scenario.options.scenarios;
  assert.deepEqual(Object.keys(scenarios), ['read', 'modes']);
  assert.equal(scenarios.read.executor, 'constant-vus');
  assert.equal(scenarios.read.vus, 3);
  // 8 modes * 5s dwell
  assert.equal(scenarios.read.duration, '40000ms');
  assert.equal(scenarios.read.exec, 'readSeededProduct');
  assert.equal(scenarios.modes.executor, 'per-vu-iterations');
  assert.equal(scenarios.modes.vus, 1);
  assert.equal(scenarios.modes.iterations, 1);
  assert.equal(scenarios.modes.startTime, '0s');
  assert.equal(scenarios.modes.exec, 'modeTimeline');
  assert.equal(typeof scenario.teardown, 'function');
});

test('the default mode list is the canonical off..db-fallback order', () => {
  assert.deepEqual(scenario.cacheLabConfig.modes, [
    'off',
    'null-cache',
    'bloom',
    'mutex',
    'logical-expire',
    'ttl-jitter',
    'l1-cache',
    'db-fallback',
  ]);
  assert.deepEqual(scenario.cacheLabConfig.modes, scenario.CACHE_MODE_ORDER);
});

test('mode lists reject duplicates, unknown keys and reordering', () => {
  assert.deepEqual(scenario.parseModes('off,null-cache,db-fallback'), [
    'off',
    'null-cache',
    'db-fallback',
  ]);
  assert.throws(() => scenario.parseModes('off,off'), /duplicate mode "off"/);
  assert.throws(() => scenario.parseModes('off,nope'), /unknown mode "nope"/);
  assert.throws(
    () => scenario.parseModes('off,bloom,null-cache'),
    /must follow the canonical order/,
  );
  assert.throws(() => scenario.parseModes(''), /at least one mode/);
});

test('reads cover the seeded 90001+ range deterministically', () => {
  const { seedStartId, seedEndId } = scenario.cacheLabConfig;
  assert.equal(seedStartId, 90001);
  assert.equal(seedEndId, 90010);
  const ids = [];
  for (let vu = 1; vu <= 3; vu += 1) {
    for (let iter = 0; iter < 5; iter += 1) {
      ids.push(scenario.seededProductId(vu, iter));
    }
  }
  for (const id of ids) {
    assert.ok(id >= 90001 && id <= 90010, `id ${id} outside the seeded range`);
  }
  assert.deepEqual(ids.slice(0, 3), [90002, 90003, 90004]);
  assert.equal(scenario.seededProductId(1, 0), scenario.seededProductId(1, 0));
});

test('the read mode tag follows the dwell schedule', () => {
  assert.equal(scenario.modeForElapsed(0), 'off');
  assert.equal(scenario.modeForElapsed(4_999), 'off');
  assert.equal(scenario.modeForElapsed(5_000), 'null-cache');
  assert.equal(scenario.modeForElapsed(35_000), 'db-fallback');
  assert.equal(scenario.modeForElapsed(999_999), 'db-fallback');
});

test('setup seeds once and resets every app URL to off before traffic', () => {
  resetAll();
  k6HttpStub.script([
    { status: 200, body: { code: '0', message: 'OK', data: { mode: 'off' } } },
    {
      status: 200,
      body: {
        code: '0',
        message: 'OK',
        data: {
          startId: 90000,
          endId: 90999,
          requestedProducts: 1000,
          inserted: 1000,
          rowsInRange: 1000,
          mode: 'off',
        },
      },
    },
  ]);
  const data = scenario.setup();
  assert.equal(typeof data.startedAt, 'number');
  assert.equal(data.seedStartId, 90000);
  assert.equal(data.seedEndId, 90999);
  assert.equal(k6HttpStub.state.requests.length, 2);
  assert.match(k6HttpStub.state.requests[0].url, /\/lab\/cache\/mode$/);
  assert.deepEqual(k6HttpStub.state.requests[0].body, { mode: 'off' });
  assert.match(k6HttpStub.state.requests[1].url, /\/lab\/cache\/seed$/);
  assert.equal(k6HttpStub.state.requests[1].body, null);
});

test('a seeded read carries the schedule mode and counts 429 as the limiter', () => {
  resetAll();
  k6HttpStub.script([
    { status: 200, body: { code: '0', message: 'OK', data: { id: 90002 } } },
    { status: 429, body: { code: '42901', message: 'limited', data: null } },
  ]);
  scenario.readSeededProduct({ startedAt: Date.now() });
  scenario.readSeededProduct({ startedAt: Date.now() });
  assert.equal(k6HttpStub.state.requests.length, 2);
  assert.match(k6HttpStub.state.requests[0].url, /\/api\/products\/90002$/);
  assert.equal(k6HttpStub.state.requests[0].params.tags.mode, 'off');
  assert.deepEqual(k6HttpStub.state.requests[0].params.tags.flow, 'cache_read');
  assert.equal(scenario.cacheLabReads.added.length, 2);
  assert.equal(scenario.cacheLabDbFallbackRejections.added.length, 1);
});

test('teardown always resets the app to mode=off', () => {
  resetAll();
  k6HttpStub.script([
    { status: 200, body: { code: '0', message: 'OK', data: { mode: 'off' } } },
  ]);
  scenario.teardown();
  assert.equal(k6HttpStub.state.requests.length, 1);
  const request = k6HttpStub.state.requests[0];
  assert.equal(request.method, 'POST');
  assert.equal(request.url, 'http://app-1:8080/lab/cache/mode');
  assert.deepEqual(request.body, { mode: 'off' });
  assert.equal(request.params.headers['X-Lab-Token'], 'test-token');
});

test('run.sh whitelists both opt-in scenarios and their env branches', () => {
  const script = fs.readFileSync(new URL('../run.sh', import.meta.url), 'utf8');
  assert.match(script, /ha-failover\|db-failover\) IS_CHAOS=1/);
  assert.match(script, /cache-lab\) IS_CACHE=1/);
  assert.match(script, /expected smoke\|load\|stress\|spike\|soak\|d7\|ha-failover\|db-failover\|cache-lab/);
  assert.match(script, /CACHE_LAB_APP_URLS/);
  assert.match(script, /DB_FAILOVER_TARGET/);
});

test('sumPrometheusMetric sums labelled samples and honours a label filter', () => {
  const body = [
    '# HELP cache_db_fallbacks_total fallbacks',
    '# TYPE cache_db_fallbacks_total counter',
    'cache_db_fallbacks_total{reason="miss",} 3.0',
    'cache_db_fallbacks_total{reason="penetration",} 2.5',
    'cache_l1_requests_total{result="hit",} 5.0',
    'cache_l1_requests_total{result="miss",} 7.0',
    'cache_l1_requests_total 1.0',
    'unrelated_metric 100',
  ].join('\n');
  assert.equal(scenario.sumPrometheusMetric(body, 'cache_db_fallbacks_total'), 5.5);
  assert.equal(
    scenario.sumPrometheusMetric(body, 'cache_l1_requests_total', 'result="hit"'),
    5,
  );
  assert.equal(scenario.sumPrometheusMetric(body, 'cache_l1_requests_total'), 13);
  assert.equal(
    scenario.sumPrometheusMetric(body, 'cache_bloom_requests_total', 'result="absent"'),
    0,
  );
  assert.equal(scenario.sumPrometheusMetric(null, 'cache_db_fallbacks_total'), 0);
});
