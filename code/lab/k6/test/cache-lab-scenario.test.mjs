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

// ---------------------------------------------------------------------------
// Step-49 additions: the scenario must MANUFACTURE the defects (穿透 probes,
// 击穿 hot key, re-armable 雪崩) instead of only observing idle defenses.
// ---------------------------------------------------------------------------

const httpLib = await import('../lib/http.js');

/** Imports a fresh scenario instance under overridden env, then restores it. */
async function importWithEnv(tag, overrides) {
  const saved = {};
  for (const [key, value] of Object.entries(overrides)) {
    saved[key] = globalThis.__ENV[key];
    globalThis.__ENV[key] = value;
  }
  try {
    return await import(`../scenarios/cache-lab.js?env=${tag}`);
  } finally {
    for (const key of Object.keys(overrides)) {
      if (saved[key] === undefined) {
        delete globalThis.__ENV[key];
      } else {
        globalThis.__ENV[key] = saved[key];
      }
    }
  }
}

const modeOk = (mode) => ({
  status: 200,
  body: { code: '0', message: 'OK', data: { mode } },
});
const stateOk = {
  status: 200,
  body: {
    code: '0',
    message: 'OK',
    data: {
      keys: { redisReachable: true, l2ExperimentKeys: 600, l1ExperimentKeys: 590 },
      nullCache: { liveMarkerKeys: 0 },
      bloom: { size: 1020 },
    },
  },
};
const seedOk = {
  status: 200,
  body: {
    code: '0',
    message: 'OK',
    data: {
      startId: 90000,
      endId: 90999,
      requestedProducts: 1000,
      inserted: 0,
      rowsInRange: 1000,
      avalancheKeysWritten: 200,
      avalancheTtlSeconds: 60,
      hotKeyProductId: 90999,
      hotKeyWritten: true,
    },
  },
};

test('defaults keep the old behaviour and expose the documented new knobs', () => {
  const cfg = scenario.cacheLabConfig;
  assert.deepEqual(cfg.reseedModes, []);
  assert.deepEqual(cfg.absentIds, [
    99990, 99991, 99992, 99993, 99994, 99995, 99996, 99997, 99998, 99999,
  ]);
  assert.equal(cfg.probeEvery, 10);
  assert.deepEqual(cfg.probeExpectedStatuses, [404, 429]);
  assert.equal(cfg.probeExpectedCode, '40401');
  assert.equal(cfg.hotKeyEvery, 25);
  assert.equal(cfg.hotKeyIdOverride, 0);
  assert.equal(cfg.avalancheTtlSeconds, 60);
  assert.equal(cfg.armMarginMs, 15_000);
  assert.equal(cfg.stormSampleDelayMs, 2_000);
  // The two step-48 thresholds are unchanged; probes live on their own flow.
  assert.deepEqual(Object.keys(scenario.options.thresholds), [
    'flow_errors',
    'http_req_failed{flow:cache_read}',
  ]);
  assert.equal(scenario.iterationKind(0, true), 'seeded');
});

test('parseAbsentIds rejects invalid, duplicate and in-range ids', () => {
  assert.deepEqual(scenario.parseAbsentIds('99999, 99998', 90001, 90200), [99999, 99998]);
  assert.throws(() => scenario.parseAbsentIds('99999,99999', 90001, 90200), /duplicate id 99999/);
  assert.throws(() => scenario.parseAbsentIds('abc', 90001, 90200), /invalid id "abc"/);
  assert.throws(() => scenario.parseAbsentIds('90005', 90001, 90200), /overlaps the read range/);
  assert.throws(() => scenario.parseAbsentIds('', 90001, 90200), /at least one id/);
});

test('parseReseedModes accepts a subset of the run modes and rejects the rest', () => {
  const modes = ['off', 'bloom', 'mutex'];
  assert.deepEqual(scenario.parseReseedModes('', modes), []);
  assert.deepEqual(scenario.parseReseedModes('bloom,mutex', modes), ['bloom', 'mutex']);
  assert.throws(() => scenario.parseReseedModes('nope', modes), /unknown mode "nope"/);
  assert.throws(
    () => scenario.parseReseedModes('null-cache', modes),
    /not part of CACHE_LAB_MODES/,
  );
  assert.throws(() => scenario.parseReseedModes('bloom,bloom', modes), /duplicate mode "bloom"/);
});

test('the iteration mix rotates seeded reads, probes and the hot key', () => {
  assert.equal(scenario.iterationKind(7, false), 'seeded');
  assert.equal(scenario.iterationKind(10, false), 'absent-probe');
  assert.equal(scenario.iterationKind(20, true), 'absent-probe');
  // Without a known hot key (no seed data, no override) the slot stays seeded.
  assert.equal(scenario.iterationKind(25, false), 'seeded');
  assert.equal(scenario.iterationKind(25, true), 'hot-key');
  // lcm(25,10)=50: the hot key wins the shared slot.
  assert.equal(scenario.iterationKind(50, true), 'hot-key');
  assert.equal(scenario.absentProbeId(1, 9), 99990);
  assert.equal(scenario.absentProbeId(1, 10), 99991);
  assert.equal(scenario.resolveHotKeyId(null), null);
  assert.equal(scenario.resolveHotKeyId({ startedAt: 1 }), null);
  assert.equal(scenario.resolveHotKeyId({ hotKeyId: 90999 }), 90999);
  assert.equal(scenario.resolveHotKeyId({ hotKeyId: '90999' }), 90999);
});

test('arming fails fast when the dwell cannot contain the avalanche', async () => {
  await assert.rejects(
    importWithEnv('guard-short', {
      CACHE_LAB_RESEED_MODES: 'bloom',
      CACHE_LAB_MODE_DURATION: '20s',
    }),
    (error) => {
      assert.match(error.message, /CACHE_LAB_MODE_DURATION=20s is too short to re-arm/);
      assert.match(error.message, /CACHE_LAB_AVALANCHE_TTL_SECONDS=60/);
      assert.match(error.message, /LAB_CACHE_SEED_AVALANCHE_TTL_SECONDS/);
      return true;
    },
  );
  await assert.rejects(
    importWithEnv('guard-margin', {
      CACHE_LAB_MODES: 'off,bloom',
      CACHE_LAB_RESEED_MODES: 'bloom',
      CACHE_LAB_MODE_DURATION: '90s',
      CACHE_LAB_ARM_MARGIN: '2s',
      CACHE_LAB_STORM_SAMPLE_DELAY: '2s',
    }),
    /CACHE_LAB_ARM_MARGIN=2s must exceed CACHE_LAB_STORM_SAMPLE_DELAY=2s/,
  );
  const armed = await importWithEnv('guard-ok', {
    CACHE_LAB_MODES: 'off,bloom',
    CACHE_LAB_RESEED_MODES: 'bloom',
    CACHE_LAB_MODE_DURATION: '90s',
  });
  assert.deepEqual(armed.cacheLabConfig.reseedModes, ['bloom']);
  assert.equal(armed.cacheLabConfig.readDuration, '180000ms');
});

test('an absent probe is a cache_probe 404, never a cache_read failure', () => {
  resetAll();
  k6HttpStub.script([
    { status: 404, body: { code: '40401', message: 'Product not found: 99991', data: null } },
  ]);
  const readsBefore = scenario.cacheLabReads.added.length;
  const errorsBefore = scenario.cacheLabReadErrors.added.length;
  globalThis.__ITER = 10;
  try {
    scenario.readSeededProduct({ startedAt: Date.now() });
  } finally {
    globalThis.__ITER = 0;
  }
  assert.equal(k6HttpStub.state.requests.length, 1);
  const request = k6HttpStub.state.requests[0];
  assert.match(request.url, /\/api\/products\/99991$/);
  assert.equal(request.params.tags.flow, 'cache_probe');
  assert.equal(request.params.tags.mode, 'off');
  assert.deepEqual(
    k6HttpStub.state.expectedStatusesCalls[
      k6HttpStub.state.expectedStatusesCalls.length - 1
    ],
    [404, 429],
  );
  const probe = scenario.cacheLabProbes.samples[scenario.cacheLabProbes.samples.length - 1];
  assert.deepEqual(probe.tags, { mode: 'off', result: 'absent' });
  assert.equal(scenario.cacheLabReads.added.length, readsBefore);
  assert.equal(scenario.cacheLabReadErrors.added.length, errorsBefore);
  assert.equal(httpLib.flowErrors.added[httpLib.flowErrors.added.length - 1], false);
});

test('a probe that answers 200 is an unexpected hit and a flow error', () => {
  resetAll();
  k6HttpStub.script([{ status: 200, body: { code: '0', message: 'OK', data: { id: 99991 } } }]);
  globalThis.__ITER = 10;
  try {
    scenario.readSeededProduct({ startedAt: Date.now() });
  } finally {
    globalThis.__ITER = 0;
  }
  const probe = scenario.cacheLabProbes.samples[scenario.cacheLabProbes.samples.length - 1];
  assert.deepEqual(probe.tags, { mode: 'off', result: 'unexpected-hit' });
  assert.equal(httpLib.flowErrors.added[httpLib.flowErrors.added.length - 1], true);
});

test('a probe limited by db-fallback counts 429 as the limiter, not an error', () => {
  resetAll();
  k6HttpStub.script([
    { status: 429, body: { code: '42901', message: 'limited', data: null } },
  ]);
  globalThis.__ITER = 10;
  try {
    scenario.readSeededProduct({ startedAt: Date.now() });
  } finally {
    globalThis.__ITER = 0;
  }
  const probe = scenario.cacheLabProbes.samples[scenario.cacheLabProbes.samples.length - 1];
  assert.deepEqual(probe.tags, { mode: 'off', result: 'limited' });
  assert.equal(httpLib.flowErrors.added[httpLib.flowErrors.added.length - 1], false);
});

test('the hot key joins the cache_read mix with a target tag', () => {
  resetAll();
  k6HttpStub.script([
    { status: 200, body: { code: '0', message: 'OK', data: { id: 90999 } } },
  ]);
  globalThis.__ITER = 25;
  try {
    scenario.readSeededProduct({ startedAt: Date.now(), hotKeyId: 90999 });
  } finally {
    globalThis.__ITER = 0;
  }
  const request = k6HttpStub.state.requests[0];
  assert.match(request.url, /\/api\/products\/90999$/);
  assert.equal(request.params.tags.flow, 'cache_read');
  assert.equal(request.params.tags.target, 'hot');
  const read = scenario.cacheLabReads.samples[scenario.cacheLabReads.samples.length - 1];
  assert.deepEqual(read.tags, { mode: 'off', result: 'ok', target: 'hot' });
  const hot = scenario.cacheLabHotKeyReads.samples[
    scenario.cacheLabHotKeyReads.samples.length - 1
  ];
  assert.deepEqual(hot.tags, { mode: 'off', result: 'ok' });
});

test('CACHE_LAB_HOT_KEY_ID overrides the seed-derived hot key', async () => {
  const mod = await importWithEnv('hot-override', { CACHE_LAB_HOT_KEY_ID: '90555' });
  assert.equal(mod.resolveHotKeyId(null), 90555);
  assert.equal(mod.resolveHotKeyId({ hotKeyId: 90999 }), 90555);
  assert.equal(mod.cacheLabConfig.hotKeyIdOverride, 90555);
});

test('re-seed arms only the configured dwells, after the mode switch', async () => {
  const mod = await importWithEnv('reseed-order', {
    CACHE_LAB_MODES: 'off,bloom',
    CACHE_LAB_RESEED_MODES: 'bloom',
    CACHE_LAB_MODE_DURATION: '90s',
    CACHE_LAB_SAMPLE_ACTUATOR: '0',
  });
  k6HttpStub.reset();
  k6HttpStub.script([
    modeOk('off'),
    stateOk,
    modeOk('bloom'),
    seedOk,
    stateOk,
    stateOk,
    modeOk('off'),
  ]);
  mod.modeTimeline({ startedAt: Date.now() });
  const requests = k6HttpStub.state.requests;
  // off dwell: switch + end sample. bloom dwell: switch, arm, storm sample,
  // end sample — the arm must come AFTER the switch so the whole storm runs
  // under the armed mode. Plus the final unconditional off reset.
  assert.equal(requests.length, 7);
  assert.match(requests[0].url, /\/lab\/cache\/mode$/);
  assert.deepEqual(requests[0].body, { mode: 'off' });
  assert.match(requests[1].url, /\/lab\/cache\/state$/);
  assert.match(requests[2].url, /\/lab\/cache\/mode$/);
  assert.deepEqual(requests[2].body, { mode: 'bloom' });
  assert.equal(requests[3].method, 'POST');
  assert.match(requests[3].url, /\/lab\/cache\/seed$/);
  assert.match(requests[4].url, /\/lab\/cache\/state$/);
  assert.match(requests[5].url, /\/lab\/cache\/state$/);
  assert.deepEqual(requests[6].body, { mode: 'off' });
  assert.equal(mod.cacheLabReseeds.samples.length, 1);
  assert.deepEqual(mod.cacheLabReseeds.samples[0].tags, { mode: 'bloom', result: 'armed' });
  const l2Samples = mod.cacheLabStateL2Keys.samples;
  assert.equal(l2Samples.length, 3);
  assert.deepEqual(l2Samples[0].tags, { mode: 'off' });
  assert.deepEqual(l2Samples[1].tags, { mode: 'bloom', sample: 'storm' });
  assert.deepEqual(l2Samples[2].tags, { mode: 'bloom' });
});

test('a failed arm is counted, skips the storm sample and never aborts', async () => {
  const mod = await importWithEnv('reseed-fail', {
    CACHE_LAB_MODES: 'off,bloom',
    CACHE_LAB_RESEED_MODES: 'bloom',
    CACHE_LAB_MODE_DURATION: '90s',
    CACHE_LAB_SAMPLE_ACTUATOR: '0',
  });
  k6HttpStub.reset();
  k6HttpStub.script([
    modeOk('off'),
    stateOk,
    modeOk('bloom'),
    { status: 500, body: { code: '50000', message: 'boom', data: null } },
    stateOk,
    modeOk('off'),
  ]);
  mod.modeTimeline({ startedAt: Date.now() });
  assert.equal(mod.cacheLabReseeds.samples.length, 1);
  assert.deepEqual(mod.cacheLabReseeds.samples[0].tags, { mode: 'bloom', result: 'failed' });
  const stormSamples = mod.cacheLabStateL2Keys.samples.filter(
    (sample) => sample.tags.sample === 'storm',
  );
  assert.equal(stormSamples.length, 0);
  // The timeline still reached the final reset.
  const last = k6HttpStub.state.requests[k6HttpStub.state.requests.length - 1];
  assert.deepEqual(last.body, { mode: 'off' });
});

test('setup derives the hot key and cross-checks the avalanche TTL mirror', () => {
  resetAll();
  k6HttpStub.script([modeOk('off'), seedOk]);
  const data = scenario.setup();
  assert.equal(data.hotKeyId, 90999);
  assert.equal(data.avalancheTtlSeconds, 60);
});

test('setup rejects an absent id that the real seed range contains', async () => {
  const mod = await importWithEnv('absent-in-seed', { CACHE_LAB_ABSENT_IDS: '90500' });
  k6HttpStub.reset();
  k6HttpStub.script([modeOk('off'), seedOk]);
  assert.throws(() => mod.setup(), /id 90500 exists inside the seed range/);
});

test('setup rejects a hot key override outside the seed range', async () => {
  const mod = await importWithEnv('hot-outside', { CACHE_LAB_HOT_KEY_ID: '12345' });
  k6HttpStub.reset();
  k6HttpStub.script([modeOk('off'), seedOk]);
  assert.throws(() => mod.setup(), /hot key id 12345 is outside the seed range/);
});

test('setup fails fast when the TTL mirror contradicts the app while arming', async () => {
  const mod = await importWithEnv('ttl-mismatch', {
    CACHE_LAB_MODES: 'off,bloom',
    CACHE_LAB_RESEED_MODES: 'bloom',
    CACHE_LAB_MODE_DURATION: '90s',
    CACHE_LAB_AVALANCHE_TTL_SECONDS: '30',
  });
  k6HttpStub.reset();
  k6HttpStub.script([modeOk('off'), seedOk]);
  assert.throws(
    () => mod.setup(),
    /CACHE_LAB_AVALANCHE_TTL_SECONDS=30 does not match the app's avalanche TTL \(60s/,
  );
});

test('run.sh forwards every new cache-lab variable', () => {
  const script = fs.readFileSync(new URL('../run.sh', import.meta.url), 'utf8');
  for (const name of [
    'CACHE_LAB_ABSENT_IDS',
    'CACHE_LAB_PROBE_EVERY',
    'CACHE_LAB_PROBE_EXPECTED_STATUSES',
    'CACHE_LAB_PROBE_EXPECTED_CODE',
    'CACHE_LAB_HOT_KEY_ID',
    'CACHE_LAB_HOT_KEY_EVERY',
    'CACHE_LAB_RESEED_MODES',
    'CACHE_LAB_AVALANCHE_TTL_SECONDS',
    'CACHE_LAB_ARM_MARGIN',
    'CACHE_LAB_STORM_SAMPLE_DELAY',
  ]) {
    assert.ok(script.includes(name), `run.sh does not forward ${name}`);
  }
});

test('the parser covers the new defense counters', () => {
  const body = [
    'cache_null_cache_requests_total{cache="product",result="hit",} 12.0',
    'cache_null_cache_requests_total{cache="product",result="miss",} 3.0',
    'cache_breakdown_logical_total{cache="product",result="stale-serve",} 7.0',
    'cache_breakdown_refresh_total{cache="product",result="submitted",} 5.0',
    'cache_breakdown_mutex_total{cache="product",result="double-check-hit",} 9.0',
  ].join('\n');
  assert.equal(
    scenario.sumPrometheusMetric(body, 'cache_null_cache_requests_total', 'result="hit"'),
    12,
  );
  assert.equal(
    scenario.sumPrometheusMetric(body, 'cache_breakdown_logical_total', 'result="stale-serve"'),
    7,
  );
  assert.equal(
    scenario.sumPrometheusMetric(body, 'cache_breakdown_refresh_total', 'result="submitted"'),
    5,
  );
  assert.equal(
    scenario.sumPrometheusMetric(
      body,
      'cache_breakdown_mutex_total',
      'result="double-check-hit"',
    ),
    9,
  );
});
