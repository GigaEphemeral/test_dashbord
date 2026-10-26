/**
 * Zero-dependency node harness for lib/flow.js + lib/http.js + lib/config.js.
 *
 * Run:  node --test "code/lab/k6/test/*.test.mjs"
 *
 * It replaces the k6 runtime modules with stubs (see k6-loader.mjs) and
 * scripts canned HTTP responses, so the seckill admission branches can be
 * asserted without a live stack:
 *   - 200 CREATED+orderId  -> terminal, never polled;
 *   - 202 + requestId      -> polled until CREATED/FAILED;
 *   - degraded 202 (48d45fb: requestId null, CREATED+orderId) -> success,
 *     no poll, no flow error;
 *   - 409/429              -> expected business rejections, counted, no error;
 *   - 5xx / transport 0    -> flow + server errors.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { register } from 'node:module';

register('./k6-loader.mjs', import.meta.url);
globalThis.__ENV = globalThis.__ENV || {};
// k6 injects these per iteration inside a VU; a single fake VU is enough here.
globalThis.__VU = 1;
globalThis.__ITER = 0;

const flow = await import('../lib/flow.js');
const httpLib = await import('../lib/http.js');
const { parseDurationMs, config } = await import('../lib/config.js');
const k6Stub = await import('./stubs/k6.mjs');
const k6HttpStub = await import('./stubs/k6-http.mjs');

const metrics = [
  httpLib.flowErrors,
  httpLib.serverErrors,
  httpLib.seckillExpected409,
  httpLib.seckillExpected429,
  httpLib.seckillTerminalFailed,
  httpLib.seckillPollTimeouts,
];

function resetAll() {
  k6HttpStub.reset();
  k6Stub.reset();
  for (const metric of metrics) {
    metric.reset();
  }
}

function envelope(data) {
  return { code: '0', message: 'ok', data };
}

function truthyAdds(metric) {
  return metric.added.filter((value) => value === true).length;
}

test('seckill 202 carries the expected business statuses only (no 5xx)', () => {
  resetAll();
  k6HttpStub.script([{ status: 409, body: { code: '40901', message: 'sold out', data: null } }]);
  flow.seckill();
  assert.deepEqual(k6HttpStub.state.expectedStatusesCalls[0], [200, 202, 409, 429]);
});

test('terminal 200 CREATED+orderId does not poll', () => {
  resetAll();
  k6HttpStub.script([{ status: 200, body: envelope({ requestId: null, status: 'CREATED', orderId: 424 }) }]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'created');
  assert.equal(result.orderId, 424);
  assert.equal(k6HttpStub.state.requests.length, 1);
  assert.equal(truthyAdds(httpLib.flowErrors), 0);
  assert.equal(truthyAdds(httpLib.serverErrors), 0);
});

test('degraded 202 (requestId null, terminal CREATED+orderId) is a created order', () => {
  resetAll();
  // Shape produced by 48d45fb: circuit open -> synchronous DB path, mq still on.
  k6HttpStub.script([{ status: 202, body: envelope({ requestId: null, status: 'CREATED', orderId: 555 }) }]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'created');
  assert.equal(result.orderId, 555);
  assert.equal(k6HttpStub.state.requests.length, 1, 'must not poll a null requestId');
  assert.equal(truthyAdds(httpLib.flowErrors), 0, 'degraded admission must not feed flow_errors');
  assert.equal(truthyAdds(httpLib.serverErrors), 0);
  const handled = k6Stub.state.checks.find((entry) => entry.name === 'seckill handled');
  assert.ok(handled, 'seckill handled check must run');
  assert.equal(handled.ok, true);
});

test('202 with requestId is polled until CREATED', () => {
  resetAll();
  k6HttpStub.script([
    { status: 202, body: envelope({ requestId: 'req-1', status: 'ACCEPTED', orderId: null }) },
    { status: 200, body: envelope({ requestId: 'req-1', status: 'ACCEPTED' }) },
    { status: 200, body: envelope({ requestId: 'req-1', status: 'CREATED', orderId: 777 }) },
  ]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'created');
  assert.equal(result.orderId, 777);
  assert.equal(k6HttpStub.state.requests.length, 3);
  assert.match(k6HttpStub.state.requests[1].url, /\/api\/seckill\/orders\/req-1$/);
  assert.equal(truthyAdds(httpLib.flowErrors), 0);
  // Polls keep the default expected status list ([200]).
  assert.deepEqual(k6HttpStub.state.expectedStatusesCalls[1], [200]);
});

test('202 polled to terminal FAILED counts the failure, not an error', () => {
  resetAll();
  k6HttpStub.script([
    { status: 202, body: envelope({ requestId: 'req-2', status: 'ACCEPTED', orderId: null }) },
    { status: 200, body: envelope({ requestId: 'req-2', status: 'FAILED', message: 'db down' }) },
  ]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'failed');
  assert.equal(httpLib.seckillTerminalFailed.added.length, 1);
  assert.equal(truthyAdds(httpLib.flowErrors), 0);
});

test('202 without requestId and without terminal outcome is an error, never polls /null', () => {
  resetAll();
  k6HttpStub.script([{ status: 202, body: envelope({ requestId: null, status: 'ACCEPTED', orderId: null }) }]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'error');
  for (const request of k6HttpStub.state.requests) {
    assert.doesNotMatch(request.url, /\/(null|undefined)$/);
  }
  assert.equal(k6HttpStub.state.requests.length, 1);
});

test('409 sold out is an expected rejection, not a flow error', () => {
  resetAll();
  k6HttpStub.script([{ status: 409, body: { code: '40901', message: 'sold out', data: null } }]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'conflict');
  assert.equal(httpLib.seckillExpected409.added.length, 1);
  assert.equal(truthyAdds(httpLib.flowErrors), 0);
  assert.equal(truthyAdds(httpLib.serverErrors), 0);
});

test('429 rate limited is an expected rejection counted separately from 409', () => {
  resetAll();
  k6HttpStub.script([{ status: 429, body: { code: '42901', message: 'too many', data: null } }]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'rate_limited');
  assert.equal(httpLib.seckillExpected429.added.length, 1);
  assert.equal(httpLib.seckillExpected409.added.length, 0);
  assert.equal(truthyAdds(httpLib.flowErrors), 0);
});

test('5xx is a flow error and a server error', () => {
  resetAll();
  k6HttpStub.script([{ status: 503, body: { code: '50301', message: 'backend down', data: null } }]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'error');
  assert.equal(truthyAdds(httpLib.flowErrors), 1);
  assert.equal(truthyAdds(httpLib.serverErrors), 1);
});

test('transport error (status 0) is a flow error and a server error', () => {
  resetAll();
  k6HttpStub.script([{ status: 0, error: 'dial tcp: connection refused' }]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'error');
  assert.equal(truthyAdds(httpLib.flowErrors), 1);
  assert.equal(truthyAdds(httpLib.serverErrors), 1);
});

test('poll timeout records one flow error and one timeout counter', () => {
  resetAll();
  const accepted = { status: 202, body: envelope({ requestId: 'req-3', status: 'ACCEPTED', orderId: null }) };
  const pending = { status: 200, body: envelope({ requestId: 'req-3', status: 'ACCEPTED' }) };
  k6HttpStub.script([accepted, ...Array.from({ length: config.poll.attempts }, () => pending)]);
  const result = flow.seckill();
  assert.equal(result.outcome, 'timeout');
  assert.equal(httpLib.seckillPollTimeouts.added.length, 1);
  assert.equal(truthyAdds(httpLib.flowErrors), 1, 'only the timeout itself feeds flow_errors');
});

test('parseDurationMs handles compound and simple durations, rejects garbage', () => {
  assert.equal(parseDurationMs('250ms', 'T'), 250);
  assert.equal(parseDurationMs('30s', 'T'), 30000);
  assert.equal(parseDurationMs('2m', 'T'), 120000);
  assert.equal(parseDurationMs('1m30s', 'T'), 90000);
  assert.throws(() => parseDurationMs('3x', 'T'), /invalid duration/);
  assert.throws(() => parseDurationMs('0s', 'T'), /invalid duration/);
  assert.throws(() => parseDurationMs('', 'T'), /must not be empty/);
});

test('load stages derive from LOAD_DURATION with 20/20/60 split and 50->200 targets', () => {
  assert.deepEqual(
    config.load.stages.map((stage) => stage.target),
    [50, 200, 200],
  );
  const totalMs = config.load.stages.reduce((sum, stage) => sum + parseDurationMs(stage.duration, 'stage'), 0);
  assert.equal(totalMs, parseDurationMs('10m', 'LOAD_DURATION'));
});
