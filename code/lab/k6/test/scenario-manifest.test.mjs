/**
 * Stage2 B2-01: the k6 scenario manifest is the single source of truth.
 *
 * This suite pins four contracts without a running lab:
 *   1. scenarios/ (directory) <-> manifest: every .js script has an entry, every
 *      entry points at an existing script, and the three views are self-consistent
 *      (ui subset of bench subset of run; service-only traffic in no view;
 *      stress/soak deliberately outside ui).
 *   2. manifest.params defaults equal what lib/config.js (and the scenario
 *      modules) actually default to, so the manifest cannot drift silently.
 *   3. manifest.forwarding equals the env names run.sh actually forwards (parsed
 *      from the script text), both ways; every scenario env name is forwarded
 *      and only service-only traffic consumes the compose-service group.
 *   4. script-level mock: run.sh/bench.sh refuse unknown scenarios with exit 2,
 *      accept every bench-view scenario (stress/soak included), explain the
 *      traffic exception, and render --help from the manifest bench view.
 *
 * Run: node --test "code/lab/k6/test/*.test.mjs"
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test, { after } from 'node:test';
import { register } from 'node:module';
import { fileURLToPath } from 'node:url';

register('./k6-loader.mjs', import.meta.url);

const here = path.dirname(fileURLToPath(import.meta.url));
const k6Dir = path.resolve(here, '..');
const repoRoot = path.resolve(k6Dir, '..', '..', '..');
const scenariosDir = path.join(k6Dir, 'scenarios');
const manifestFile = path.join(scenariosDir, 'manifest.json');
const runScript = path.join(k6Dir, 'run.sh');
const benchScript = path.join(repoRoot, 'code', 'lab', 'scripts', 'bench.sh');

const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));

// 1. Directory <-> manifest --------------------------------------------------
test('every scenario script in the directory has exactly one manifest entry', () => {
  const scripts = fs
    .readdirSync(scenariosDir)
    .filter((name) => name.endsWith('.js'))
    .sort();
  const declared = Object.entries(manifest.scenarios)
    .map(([name, scenario]) => {
      assert.equal(scenario.script, `scenarios/${name}.js`);
      return `${name}.js`;
    })
    .sort();
  assert.deepEqual(declared, scripts);
});

test('every manifest script path exists', () => {
  for (const [name, scenario] of Object.entries(manifest.scenarios)) {
    const file = path.join(k6Dir, scenario.script);
    assert.ok(fs.existsSync(file), `${name}: ${scenario.script} is missing`);
  }
});

test('the view membership matches the confirmed Stage2 policy', () => {
  assert.deepEqual(manifest.views.run, [
    'smoke',
    'load',
    'stress',
    'spike',
    'soak',
    'd7',
    'ha-failover',
    'db-failover',
    'cache-lab',
  ]);
  assert.deepEqual(manifest.views.bench, ['smoke', 'load', 'stress', 'spike', 'soak']);
  assert.deepEqual(manifest.views.ui, ['smoke', 'load', 'spike']);
  assert.ok(!manifest.views.ui.includes('stress'), 'stress must stay out of the UI view');
  assert.ok(!manifest.views.ui.includes('soak'), 'soak must stay out of the UI view');
  for (const view of ['run', 'bench', 'ui']) {
    assert.ok(!manifest.views[view].includes('traffic'), `traffic must stay out of views.${view}`);
  }
  for (const name of ['d7', 'ha-failover', 'db-failover', 'cache-lab']) {
    assert.ok(!manifest.views.ui.includes(name), `${name} is driven from other panels/CLI`);
  }
});

test('service-only scenarios are exactly the compose traffic service', () => {
  const serviceOnly = Object.entries(manifest.scenarios)
    .filter(([, scenario]) => scenario.serviceOnly)
    .map(([name]) => name);
  assert.deepEqual(serviceOnly, ['traffic']);
  assert.equal(manifest.scenarios.traffic.requires.profile, 'traffic');
  for (const [name, scenario] of Object.entries(manifest.scenarios)) {
    if (!scenario.serviceOnly) {
      assert.ok(manifest.views.run.includes(name), `${name} is neither service-only nor in run`);
    }
  }
});

test('stress/soak keep CLI access and no bench verdict for stress', () => {
  assert.ok(manifest.views.run.includes('stress'));
  assert.ok(manifest.views.run.includes('soak'));
  assert.ok(manifest.views.bench.includes('stress'));
  assert.ok(manifest.views.bench.includes('soak'));
  assert.equal(manifest.scenarios.stress.thresholdsExpected, false);
  for (const [name, scenario] of Object.entries(manifest.scenarios)) {
    if (name !== 'stress') {
      assert.equal(scenario.thresholdsExpected, true, `${name} should expect thresholds`);
    }
  }
});

test('the manifest validates and the reader agrees with the JSON', async () => {
  const {
    checkBench,
    checkRun,
    loadManifest,
    viewNames,
  } = await import('../lib/scenario-manifest.mjs');
  const loaded = loadManifest(manifestFile);
  assert.equal(loaded.version, manifest.version);
  assert.deepEqual(viewNames(loaded, 'bench'), manifest.views.bench);
  assert.equal(checkRun(loaded, 'traffic'), 5);
  assert.equal(checkRun(loaded, 'd7'), 0);
  assert.equal(checkRun(loaded, 'nope'), 3);
  assert.equal(checkBench(loaded, 'soak'), 0);
  assert.equal(checkBench(loaded, 'd7'), 4);
  assert.equal(checkBench(loaded, 'traffic'), 5);
});

test('the validator rejects view, service-only and env drift', async () => {
  const { validateManifest } = await import('../lib/scenario-manifest.mjs');
  const withD7InUi = structuredClone(manifest);
  withD7InUi.views.ui.push('d7');
  assert.throws(() => validateManifest(withD7InUi), /views\.ui must be a subset of views\.bench/);

  const trafficInRun = structuredClone(manifest);
  trafficInRun.views.run.push('traffic');
  assert.throws(() => validateManifest(trafficInRun), /service-only scenario "traffic"/);

  const unknownParam = structuredClone(manifest);
  unknownParam.scenarios.smoke.params.NOT_FORWARDED = 1;
  assert.throws(() => validateManifest(unknownParam), /params\.NOT_FORWARDED is not declared/);

  const droppedForward = structuredClone(manifest);
  droppedForward.scenarios.smoke.env = droppedForward.scenarios.smoke.env.filter(
    (name) => name !== 'SMOKE_VUS',
  );
  assert.throws(() => validateManifest(droppedForward), /views\.run|params\.SMOKE_VUS/);
});

// 2. Params defaults <-> lib/config.js (and the scenario modules) -------------
globalThis.__ENV = {};
globalThis.__VU = 1;
globalThis.__ITER = 0;

const { config, parseDurationMs } = await import('../lib/config.js?manifest=defaults');

// The scenario modules read env at init; LAB_TOKEN is required by the chaos and
// cache paths. Only the *defaults* are read here, so no gate is widened except
// the datastore allow-list db-failover needs to build its synthetic timeline.
globalThis.__ENV = { LAB_TOKEN: 'manifest-test-token' };
const { chaosConfig } = await import('../lib/chaos.js?manifest=defaults');
const cacheLab = await import('../scenarios/cache-lab.js?manifest=defaults');
globalThis.__ENV = {
  LAB_TOKEN: 'manifest-test-token',
  CHAOS_TARGET_PATTERN: '^jl-mysql-[0-9]+$',
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN: '^jl-mysql-[0-9]+$',
};
const dbFailover = await import('../scenarios/db-failover.js?manifest=defaults');
globalThis.__ENV = {};

const thresholds = {
  P95_MS: config.thresholds.p95Ms,
  P99_MS: config.thresholds.p99Ms,
  ERROR_RATE: config.thresholds.errorRate,
  CHECKS_RATE: config.thresholds.checksRate,
};
const loadTotalMs = parseDurationMs(config.load.duration, 'LOAD_DURATION');
const loadParams = {
  LOAD_DURATION: config.load.duration,
  LOAD_START_VUS: config.load.startVus,
  LOAD_VUS: config.load.vus,
  LOAD_RAMP_UP_FRACTION:
    parseDurationMs(config.load.stages[0].duration, 'stage') / loadTotalMs,
  LOAD_RAMP_PEAK_FRACTION:
    parseDurationMs(config.load.stages[1].duration, 'stage') / loadTotalMs,
};
const spikeStage = config.spike.stages;
const chaosParams = {
  CHAOS_CONTROLLER_URL: chaosConfig.controllerUrl,
  CHAOS_HTTP_TIMEOUT: chaosConfig.httpTimeout,
  CHAOS_TEARDOWN_TIMEOUT: chaosConfig.teardownTimeout,
  CHAOS_MAX_TTL: chaosConfig.maxTtlSeconds,
  CHAOS_TARGET_PATTERN: chaosConfig.targetPattern,
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN: chaosConfig.allowedDatastorePattern,
};
const db = dbFailover.dbFailoverConfig;

const expectedParams = {
  smoke: {
    SMOKE_VUS: config.smoke.vus,
    SMOKE_ITERATIONS: config.smoke.iterations,
    SMOKE_DURATION: config.smoke.duration,
    ...thresholds,
  },
  load: { ...loadParams, ...thresholds },
  stress: { STRESS_STAGES: config.stress.stages, ...thresholds },
  spike: {
    SPIKE_BASELINE_VUS: spikeStage[0].target,
    SPIKE_BASELINE_DURATION: spikeStage[0].duration,
    SPIKE_RAMP_UP: spikeStage[1].duration,
    SPIKE_VUS: spikeStage[1].target,
    SPIKE_DURATION: spikeStage[2].duration,
    SPIKE_RAMP_DOWN: spikeStage[3].duration,
    ...thresholds,
  },
  soak: {
    SOAK_VUS: config.soak.vus,
    SOAK_DURATION: config.soak.duration,
    ...thresholds,
  },
  d7: { ...loadParams, ...thresholds },
  'ha-failover': {
    ...chaosParams,
    CHAOS_SCENARIO_FILE: chaosConfig.scenarioFile,
    CHAOS_START_OFFSET: chaosConfig.startOffset,
    CHAOS_DURATION: chaosConfig.loadDuration,
    CHAOS_VUS: chaosConfig.loadVus,
    ...thresholds,
  },
  'db-failover': {
    ...chaosParams,
    DB_FAILOVER_TARGET: db.target,
    DB_FAILOVER_TTL_SECONDS: db.ttlSeconds,
    DB_FAILOVER_KILL_AT: db.killAt,
    DB_FAILOVER_ERROR_WINDOW: db.errorWindow,
    DB_FAILOVER_DURATION: db.loadDuration,
    DB_FAILOVER_READ_VUS: db.readVus,
    DB_FAILOVER_WRITE_VUS: db.writeVus,
    DB_FAILOVER_WRITE_INTERVAL: db.writeInterval,
    DB_FAILOVER_WRITE_EXPECTED_STATUSES: db.writeExpectedStatuses,
    DB_FAILOVER_WRITE_MIN_SUCCESS_RATE: db.writeMinSuccessRate,
    ...thresholds,
  },
  'cache-lab': {
    CACHE_LAB_APP_URLS: cacheLab.cacheLabConfig.appUrls,
    CACHE_LAB_MODES: cacheLab.cacheLabConfig.modes,
    CACHE_LAB_MODE_DURATION: cacheLab.cacheLabConfig.modeDuration,
    CACHE_LAB_VUS: cacheLab.cacheLabConfig.readVus,
    CACHE_LAB_SEED_START_ID: cacheLab.cacheLabConfig.seedStartId,
    CACHE_LAB_SEED_COUNT: cacheLab.cacheLabConfig.seedCount,
    CACHE_LAB_READ_EXPECTED_STATUSES: cacheLab.cacheLabConfig.readExpectedStatuses,
    CACHE_LAB_ABSENT_IDS: cacheLab.cacheLabConfig.absentIds,
    CACHE_LAB_PROBE_EVERY: cacheLab.cacheLabConfig.probeEvery,
    CACHE_LAB_PROBE_EXPECTED_STATUSES: cacheLab.cacheLabConfig.probeExpectedStatuses,
    CACHE_LAB_PROBE_EXPECTED_CODE: cacheLab.cacheLabConfig.probeExpectedCode,
    CACHE_LAB_HOT_KEY_EVERY: cacheLab.cacheLabConfig.hotKeyEvery,
    CACHE_LAB_HOT_KEY_ID: cacheLab.cacheLabConfig.hotKeyIdOverride,
    CACHE_LAB_RESEED_MODES: cacheLab.cacheLabConfig.reseedModes,
    CACHE_LAB_AVALANCHE_TTL_SECONDS: cacheLab.cacheLabConfig.avalancheTtlSeconds,
    ...thresholds,
  },
  traffic: {
    TRAFFIC_VUS: config.traffic.vus,
    TRAFFIC_DURATION: config.traffic.duration,
  },
};

test('manifest params equal the real defaults of config.js and the scenarios', () => {
  for (const [name, expected] of Object.entries(expectedParams)) {
    assert.deepEqual(manifest.scenarios[name].params, expected, `${name} params drifted`);
  }
});

test('every parameter is declared as an accepted env name', () => {
  for (const [name, scenario] of Object.entries(manifest.scenarios)) {
    for (const key of Object.keys(scenario.params)) {
      assert.ok(scenario.env.includes(key), `${name}.params.${key} missing from ${name}.env`);
    }
  }
});

// 3. Env forwarding <-> run.sh ------------------------------------------------
function arrayBody(source, header, closeIndent) {
  const start = source.indexOf(header);
  assert.notEqual(start, -1, `missing ${header}`);
  const end = source.indexOf(`\n${closeIndent})`, start);
  assert.notEqual(end, -1, `unterminated ${header}`);
  return source
    .slice(start + header.length, end)
    .split(/\s+/)
    .filter((name) => name.length > 0);
}

const runSource = fs.readFileSync(runScript, 'utf8');
const runCommon = arrayBody(runSource, 'CONFIG_VARS=(', '');
const runChaos = arrayBody(runSource, 'if [[ "$IS_CHAOS" == "1" ]]; then\n  CONFIG_VARS+=(', '  ');
const runCache = arrayBody(runSource, 'if [[ "$IS_CACHE" == "1" ]]; then\n  CONFIG_VARS+=(', '  ');

function uniqueSorted(names) {
  return [...new Set(names)].sort();
}

test('manifest forwarding equals the run.sh env arrays, both ways', () => {
  assert.deepEqual(uniqueSorted(runCommon), uniqueSorted(manifest.forwarding.common));
  assert.deepEqual(uniqueSorted(runChaos), uniqueSorted(manifest.forwarding.chaos));
  assert.deepEqual(uniqueSorted(runCache), uniqueSorted(manifest.forwarding.cache));
});

test('runtimeEnv is a subset of the run.sh common list', () => {
  for (const name of manifest.runtimeEnv) {
    assert.ok(runCommon.includes(name), `${name} must be forwarded by run.sh`);
  }
});

test('the scenarios consume every forwarded name and no more', () => {
  const forwarded = new Set([
    ...manifest.forwarding.common,
    ...manifest.forwarding.chaos,
    ...manifest.forwarding.cache,
    ...manifest.forwarding.service,
  ]);
  const consumed = new Set(manifest.runtimeEnv);
  for (const scenario of Object.values(manifest.scenarios)) {
    for (const name of scenario.env) {
      consumed.add(name);
    }
  }
  assert.deepEqual(uniqueSorted([...consumed]), uniqueSorted([...forwarded]));
});

test('scenario env groups match requires.chaos / requires.cache', () => {
  const common = new Set(manifest.forwarding.common);
  const chaosGroup = new Set(manifest.forwarding.chaos);
  const cacheGroup = new Set(manifest.forwarding.cache);
  // LAB_TOKEN belongs to both opt-in groups; treat it as shared, not as a
  // one-sided leak.
  const chaosOnly = manifest.forwarding.chaos.filter(
    (name) => !common.has(name) && !cacheGroup.has(name),
  );
  const cacheOnly = manifest.forwarding.cache.filter(
    (name) => !common.has(name) && !chaosGroup.has(name),
  );
  const service = new Set(manifest.forwarding.service);

  for (const [name, scenario] of Object.entries(manifest.scenarios)) {
    const env = new Set(scenario.env);
    if (scenario.requires.chaos) {
      assert.ok(env.has('CHAOS_CONTROLLER_URL'), `${name} must accept CHAOS_CONTROLLER_URL`);
      assert.ok(env.has('LAB_TOKEN'), `${name} must accept LAB_TOKEN`);
    } else {
      for (const key of chaosOnly) {
        assert.ok(!env.has(key), `${name} must not accept chaos-only ${key}`);
      }
    }
    if (scenario.requires.cache) {
      assert.ok(env.has('LAB_TOKEN'), `${name} must accept LAB_TOKEN`);
      assert.ok(env.has('CACHE_LAB_MODES'), `${name} must accept CACHE_LAB_MODES`);
    } else {
      for (const key of cacheOnly) {
        assert.ok(!env.has(key), `${name} must not accept cache-only ${key}`);
      }
    }
    if (scenario.serviceOnly) {
      assert.ok([...env].every((key) => common.has(key) || service.has(key)));
    } else {
      for (const key of service) {
        assert.ok(!env.has(key), `${name} must not consume compose-service ${key}`);
      }
    }
  }
});

test('traffic consumes exactly the compose traffic service environment', () => {
  // Evidence: code/lab/docker/compose.base.yml `traffic.environment` forwards
  // BASE_URL, PRODUCT_ID, USER_PREFIX, TRAFFIC_VUS, TRAFFIC_DURATION, THINK_TIME
  // and RUN_ID (TZ is not a k6 variable).
  assert.deepEqual(manifest.scenarios.traffic.env.slice().sort(), [
    'BASE_URL',
    'PRODUCT_ID',
    'RUN_ID',
    'THINK_TIME',
    'TRAFFIC_DURATION',
    'TRAFFIC_VUS',
    'USER_PREFIX',
  ]);
});

// 4. Script-level mock --------------------------------------------------------
const fakeBin = fs.mkdtempSync(path.join(os.tmpdir(), 'k6-manifest-'));
const fakeDocker = path.join(fakeBin, 'docker');
fs.writeFileSync(fakeDocker, '#!/usr/bin/env bash\nexit 1\n');
fs.chmodSync(fakeDocker, 0o755);
after(() => fs.rmSync(fakeBin, { recursive: true, force: true }));

function withFakeDocker() {
  return {
    ...process.env,
    PATH: `${fakeBin}${path.delimiter}${process.env.PATH === undefined ? '' : process.env.PATH}`,
  };
}

function runShell(script, args, env = process.env) {
  return spawnSync('bash', [script, ...args], { cwd: repoRoot, encoding: 'utf8', env });
}

test('run.sh rejects an unknown scenario with exit 2 and the actual name', () => {
  const result = runShell(runScript, ['no-such-scenario']);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /unknown scenario "no-such-scenario"/);
  assert.match(result.stderr, /smoke\|load\|stress\|spike\|soak/);
});

test('run.sh explains the traffic exception instead of running it', () => {
  const result = runShell(runScript, ['traffic']);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /scenario "traffic"/);
  assert.match(result.stderr, /compose traffic service/);
});

test('run.sh accepts the run view (d7 reaches the image check)', () => {
  const result = runShell(runScript, ['d7'], withFakeDocker());
  assert.equal(result.status, 2);
  assert.match(result.stderr, /k6 image "grafana\/k6:1\.3\.0" is not present locally/);
});

test('bench.sh rejects an unknown scenario with exit 2 and the actual name', () => {
  const result = runShell(benchScript, ['no-such-scenario']);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /unknown scenario "no-such-scenario"/);
  assert.match(result.stderr, /bench scenarios: smoke\|load\|stress\|spike\|soak/);
});

test('bench.sh rejects known run-view scenarios outside the bench view', () => {
  const result = runShell(benchScript, ['d7'], withFakeDocker());
  assert.equal(result.status, 2);
  assert.match(result.stderr, /scenario "d7" is not a bench scenario/);
});

test('every bench-view scenario passes validation (stress/soak included)', () => {
  for (const name of manifest.views.bench) {
    const result = runShell(benchScript, [name], withFakeDocker());
    assert.equal(result.status, 2, `${name}: expected the image check to stop the run`);
    assert.match(
      result.stderr,
      /k6 image "grafana\/k6:1\.3\.0" is not present locally/,
      `${name} did not reach the image check`,
    );
    assert.doesNotMatch(result.stderr, /not a bench scenario|unknown scenario/);
  }
});

test('bench.sh --help is generated from the manifest bench view', () => {
  const result = runShell(benchScript, ['--help']);
  assert.equal(result.status, 0);
  const firstLine = result.stdout.split('\n')[0];
  assert.equal(
    firstLine,
    `Usage: bench.sh <${manifest.views.bench.join('|')}> [options] [--] [k6 env overrides...]`,
  );
  assert.match(result.stdout, /LAB_SCENARIOS_MANIFEST=.*manifest\.json/);
});
