/**
 * Node regression for the interactive background-traffic scenario (Task 23):
 * its shape must come from TRAFFIC_* env with sane lab defaults, and it must
 * stay read-only so it never consumes seckill stock while the console demos run.
 *
 * Run: node --test "code/lab/k6/test/*.test.mjs"
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import { register } from 'node:module';

register('./k6-loader.mjs', import.meta.url);

globalThis.__ENV = {};
globalThis.__VU = 1;
globalThis.__ITER = 0;

const scenarioModule = await import('../scenarios/traffic.js');
const { config } = await import('../lib/config.js');

test('traffic runs constant-vus reads with the documented defaults', () => {
  const traffic = scenarioModule.options.scenarios.traffic;
  assert.equal(traffic.executor, 'constant-vus');
  assert.equal(traffic.vus, 50);
  assert.equal(traffic.duration, '30m');
  assert.deepEqual(Object.keys(scenarioModule.options.scenarios), ['traffic']);
});

test('traffic thresholds reuse the shared error-rate budget', () => {
  assert.deepEqual(scenarioModule.options.thresholds, {
    flow_errors: [`rate<${config.thresholds.errorRate}`],
    server_errors: [`rate<${config.thresholds.errorRate}`],
    checks: [`rate>${config.thresholds.checksRate}`],
  });
});

test('the traffic shape is environment-overridable', async () => {
  globalThis.__ENV = { TRAFFIC_VUS: '7', TRAFFIC_DURATION: '45s' };
  // lib/config.js reads __ENV while the module initializes, so a fresh
  // instance is the only way to observe an override.
  const overridden = await import('../lib/config.js?env=traffic-override');
  assert.equal(overridden.config.traffic.vus, 7);
  assert.equal(overridden.config.traffic.duration, '45s');
  globalThis.__ENV = {};
});

test('an empty traffic VU count fails fast at init', async () => {
  globalThis.__ENV = { TRAFFIC_VUS: '0' };
  await assert.rejects(
    () => import('../lib/config.js?env=traffic-zero'),
    /TRAFFIC_VUS/,
  );
  globalThis.__ENV = {};
});
