/**
 * Node-runnable regression tests for the chaos scenario schema v1
 * (`../lib/chaos-schema.js`). The module is pure JS (no k6 imports), so the
 * exact validation used inside k6 runs here unchanged.
 *
 * Run: node --test code/lab/k6/test/chaos-schema.test.mjs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import {
  DEFAULT_TARGET_PATTERN,
  SCHEMA_VERSION,
  sortEvents,
  validateScenario,
} from '../lib/chaos-schema.js';

const SCENARIOS_DIR = new URL('../../scenarios/', import.meta.url);

function readScenario(name) {
  return JSON.parse(fs.readFileSync(new URL(name, SCENARIOS_DIR), 'utf8'));
}

function event(overrides) {
  return {
    atSeconds: 10,
    action: 'kill',
    target: 'jl-app-1',
    ttlSeconds: 30,
    ...overrides,
  };
}

function scenario(events, overrides) {
  return {
    schemaVersion: SCHEMA_VERSION,
    id: 'test-scenario',
    name: '测试场景',
    events,
    ...overrides,
  };
}

function expectRejected(raw, pattern, options) {
  assert.throws(
    () => validateScenario(raw, options),
    (error) => {
      assert.match(error.message, pattern);
      return true;
    },
    `expected rejection matching ${pattern}`,
  );
}

// --- bundled scenarios ------------------------------------------------------

test('ha-app-failover.json validates with its documented events', () => {
  const validated = validateScenario(readScenario('ha-app-failover.json'), {
    source: 'ha-app-failover.json',
  });
  assert.equal(validated.id, 'ha-app-failover');
  assert.equal(validated.durationHintSeconds, 240);
  assert.equal(validated.events.length, 3);
  assert.deepEqual(
    validated.events.map((e) => `${e.atSeconds}:${e.action}:${e.target}:${e.ttlSeconds ?? '-'}`),
    ['60:kill:jl-app-1:60', '120:freeze:jl-app-2:30', '150:resume:jl-app-2:-'],
  );
});

test('quick-failover.json only targets jl-app-3 with a short TTL', () => {
  const validated = validateScenario(readScenario('quick-failover.json'), {
    source: 'quick-failover.json',
  });
  assert.equal(validated.id, 'quick-failover');
  assert.equal(validated.events.length, 1);
  const [only] = validated.events;
  assert.equal(only.target, 'jl-app-3');
  assert.equal(only.action, 'kill');
  assert.equal(only.ttlSeconds, 15);
});

// --- header fields ----------------------------------------------------------

test('rejects a wrong schemaVersion', () => {
  expectRejected(scenario([event()], { schemaVersion: 2 }), /schemaVersion must be 1/);
  expectRejected(scenario([event()], { schemaVersion: '1' }), /schemaVersion must be 1/);
});

test('rejects empty or missing id/name', () => {
  expectRejected(scenario([event()], { id: '' }), /id must be a non-empty string/);
  expectRejected(scenario([event()], { id: '   ' }), /id must be a non-empty string/);
  expectRejected(scenario([event()], { name: null }), /name must be a non-empty string/);
});

test('rejects a non-positive durationHintSeconds and accepts its absence', () => {
  expectRejected(scenario([event()], { durationHintSeconds: 0 }), /durationHintSeconds must be a positive number/);
  expectRejected(scenario([event()], { durationHintSeconds: -5 }), /durationHintSeconds must be a positive number/);
  const validated = validateScenario(scenario([event()]));
  assert.equal(validated.durationHintSeconds, null);
});

// --- events structure -------------------------------------------------------

test('rejects an empty events array and a non-array', () => {
  expectRejected(scenario([]), /events must be a non-empty array/);
  expectRejected(scenario({}), /events must be a non-empty array/);
});

test('rejects unsorted atSeconds (non-decreasing required)', () => {
  expectRejected(
    scenario([event({ atSeconds: 120 }), event({ atSeconds: 60, target: 'jl-app-2' })]),
    /atSeconds=60 is out of order/,
  );
});

test('rejects zero or negative offsets and non-numbers', () => {
  expectRejected(scenario([event({ atSeconds: 0 })]), /atSeconds must be a positive number/);
  expectRejected(scenario([event({ atSeconds: -3 })]), /atSeconds must be a positive number/);
  expectRejected(scenario([event({ atSeconds: '10' })]), /atSeconds must be a positive number/);
});

test('accepts equal offsets for different targets but rejects duplicate (atSeconds,target)', () => {
  const ok = validateScenario(
    scenario([event({ atSeconds: 30 }), event({ atSeconds: 30, target: 'jl-app-2' })]),
  );
  assert.equal(ok.events.length, 2);
  expectRejected(
    scenario([event({ atSeconds: 30 }), event({ atSeconds: 30 })]),
    /duplicates atSeconds=30/,
  );
});

// --- actions, targets, TTL ----------------------------------------------------

test('rejects unknown actions', () => {
  expectRejected(scenario([event({ action: 'delete' })]), /action must be one of kill\/start\/restart\/freeze\/resume/);
});

test('rejects TTL on one-shot actions and missing TTL on kill/freeze', () => {
  expectRejected(
    scenario([event({ action: 'resume', ttlSeconds: 10 })]),
    /ttlSeconds is only valid for kill\/freeze/,
  );
  expectRejected(
    scenario([event({ action: 'start', ttlSeconds: 5 })]),
    /ttlSeconds is only valid for kill\/freeze/,
  );
  expectRejected(
    scenario([event({ action: 'kill', ttlSeconds: undefined })]),
    /ttlSeconds is required for kill/,
  );
  expectRejected(
    scenario([event({ action: 'freeze', ttlSeconds: null })]),
    /ttlSeconds is required for freeze/,
  );
});

test('rejects non-integer, non-positive and over-ceiling TTLs', () => {
  expectRejected(scenario([event({ ttlSeconds: 2.5 })]), /ttlSeconds must be a positive integer/);
  expectRejected(scenario([event({ ttlSeconds: 0 })]), /ttlSeconds must be a positive integer/);
  // default ceiling is 600s (matches chaos.max-ttl-seconds)
  expectRejected(scenario([event({ ttlSeconds: 601 })]), /exceeds the 600s ceiling/);
  // explicit ceiling via CHAOS_MAX_TTL wiring
  expectRejected(
    scenario([event({ ttlSeconds: 30 })]),
    /exceeds the 20s ceiling/,
    { maxTtlSeconds: 20 },
  );
  const ok = validateScenario(scenario([event({ ttlSeconds: 20 })]), { maxTtlSeconds: 20 });
  assert.equal(ok.events[0].ttlSeconds, 20);
});

test('rejects targets outside the pattern and the datastore denylist', () => {
  expectRejected(scenario([event({ target: 'nginx' })]), /does not match/);
  expectRejected(
    scenario([event({ target: 'jl-mysql-1' })]),
    /does not match|is a datastore/,
  );
});

test('datastore targets stay rejected even when the pattern is widened', () => {
  const options = { targetPattern: '.*' };
  for (const target of ['jl-mysql-1', 'jl-redis-1', 'jl-rabbitmq-1', 'jl-mongo-1', 'jl-postgres-1']) {
    expectRejected(scenario([event({ target })]), /is a datastore; datastore actions are forbidden/, options);
  }
  // a widened pattern still allows matching app containers
  const ok = validateScenario(scenario([event({ target: 'jl-app-9' })]), options);
  assert.equal(ok.events[0].target, 'jl-app-9');
});

test('a custom pattern is honoured', () => {
  expectRejected(
    scenario([event({ target: 'jl-app-3' })]),
    /does not match/,
    { targetPattern: '^jl-app-[12]$' },
  );
  const ok = validateScenario(scenario([event({ target: 'jl-app-2' })]), {
    targetPattern: '^jl-app-[12]$',
  });
  assert.equal(ok.events[0].target, 'jl-app-2');
});

test('an invalid pattern source fails fast', () => {
  expectRejected(scenario([event()]), /not a valid RegExp/, { targetPattern: '([' });
});

// --- normalized result --------------------------------------------------------

test('returns frozen normalized events and fills optional fields', () => {
  const raw = scenario([
    event({ title: '  停止 app-1  ', ttlSeconds: undefined, action: 'start' }),
  ]);
  const validated = validateScenario(raw);
  assert.ok(Object.isFrozen(validated));
  assert.ok(Object.isFrozen(validated.events));
  assert.ok(Object.isFrozen(validated.events[0]));
  const [only] = validated.events;
  assert.equal(only.ttlSeconds, null);
  assert.equal(only.title, '停止 app-1');
  assert.equal(only.text, null);
});

test('sortEvents is a stable ascending sort', () => {
  const inputs = [
    { atSeconds: 5, action: 'kill', target: 'jl-app-1' },
    { atSeconds: 5, action: 'kill', target: 'jl-app-2' },
    { atSeconds: 1, action: 'start', target: 'jl-app-3' },
  ];
  const sorted = sortEvents(inputs);
  assert.deepEqual(
    sorted.map((e) => e.target),
    ['jl-app-3', 'jl-app-1', 'jl-app-2'],
  );
  // original order kept for ties
  assert.equal(sorted[0].atSeconds, 1);
  assert.equal(DEFAULT_TARGET_PATTERN, '^jl-app-[0-9]+$');
});
