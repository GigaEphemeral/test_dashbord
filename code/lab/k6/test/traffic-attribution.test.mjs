/**
 * Stage2 B2-03a: k6-side traffic attribution.
 *
 * The runner injects TRAFFIC_MODE / TARGET_INSTANCE; config validates them at
 * init and lib/http.js stamps every request with both tags so the per-instance
 * view can tell a via-nginx run from a directed target-instance run.
 *
 * Run: node --test "code/lab/k6/test/*.test.mjs"
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { register } from 'node:module';

register('./k6-loader.mjs', import.meta.url);

globalThis.__ENV = {};
globalThis.__VU = 1;
globalThis.__ITER = 0;

const { config } = await import('../lib/config.js');
const httpLib = await import('../lib/http.js');
const k6HttpStub = await import('./stubs/k6-http.mjs');

test('traffic attribution defaults to via-nginx behind the nginx tag', () => {
  assert.equal(config.traffic.mode, 'via-nginx');
  assert.equal(config.traffic.targetInstance, 'nginx');
  // The background-traffic knobs are untouched by the new fields.
  assert.equal(config.traffic.vus, 50);
  assert.equal(config.traffic.duration, '30m');
});

test('TRAFFIC_MODE and TARGET_INSTANCE are environment-overridable', async () => {
  globalThis.__ENV = { TRAFFIC_MODE: 'target-instance', TARGET_INSTANCE: 'app-2' };
  const overridden = await import('../lib/config.js?env=traffic-attribution');
  assert.equal(overridden.config.traffic.mode, 'target-instance');
  assert.equal(overridden.config.traffic.targetInstance, 'app-2');
  globalThis.__ENV = {};
});

test('an unknown traffic mode fails fast at init', async () => {
  globalThis.__ENV = { TRAFFIC_MODE: 'direct' };
  await assert.rejects(
    () => import('../lib/config.js?env=traffic-mode-unknown'),
    /TRAFFIC_MODE must be one of via-nginx, target-instance/,
  );
  globalThis.__ENV = {};
});

test('a blank or URL-like target instance fails fast at init', async () => {
  globalThis.__ENV = { TARGET_INSTANCE: '   ' };
  await assert.rejects(
    () => import('../lib/config.js?env=traffic-target-blank'),
    /TARGET_INSTANCE must not be blank/,
  );

  globalThis.__ENV = { TARGET_INSTANCE: 'http://app-2:8080' };
  await assert.rejects(
    () => import('../lib/config.js?env=traffic-target-url'),
    /plain instance label/,
  );
  globalThis.__ENV = {};
});

test('every request carries instance and traffic_mode and cannot forge them', () => {
  k6HttpStub.reset();
  k6HttpStub.script([{ status: 200, body: { code: '0', message: 'ok', data: [] } }]);

  httpLib.call('GET', config.paths.products, {
    flow: 'product',
    tags: { instance: 'app-9', traffic_mode: 'fabricated', extra: 'kept' },
    checkName: 'product ok',
  });

  assert.equal(k6HttpStub.state.requests.length, 1);
  const tags = k6HttpStub.state.requests[0].params.tags;
  assert.equal(tags.run_id, config.runId);
  assert.equal(tags.flow, 'product');
  assert.equal(tags.instance, 'nginx');
  assert.equal(tags.traffic_mode, 'via-nginx');
  assert.equal(tags.extra, 'kept');
});
