/**
 * Reader/validator for the k6 scenario manifest (`scenarios/manifest.json`).
 *
 * The manifest is the single source of truth for:
 *   - which scripts exist, which of them are runnable by `run.sh` (view `run`),
 *     which are archived by `bench.sh` (view `bench`) and which are eligible
 *     for the Stage2 UI executor (view `ui`);
 *   - whether a scenario needs the chaos-controller or the cache experiment
 *     wiring (so `run.sh` no longer keeps a hardcoded case list);
 *   - the env names forwarded per scenario family and the documented defaults.
 *
 * Both shell entry points call this module instead of keeping their own
 * whitelists. Nothing here imports k6 or Docker, so it runs under plain node.
 *
 * Manifest path: `LAB_SCENARIOS_MANIFEST` if set, otherwise
 * `code/lab/k6/scenarios/manifest.json` next to this module's parent directory.
 *
 * CLI (see `main` below):
 *   node lib/scenario-manifest.mjs validate
 *   node lib/scenario-manifest.mjs list run|bench|ui
 *   node lib/scenario-manifest.mjs check-run <name>
 *   node lib/scenario-manifest.mjs check-bench <name>
 *
 * Exit codes: 0 ok, 2 usage/manifest error, 3 unknown scenario, 4 known but not
 * in the requested view, 5 service-only scenario (never a shell scenario).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const MODULE_DIR = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_MANIFEST = path.resolve(MODULE_DIR, '..', 'scenarios', 'manifest.json');

export const VIEW_NAMES = Object.freeze(['run', 'bench', 'ui']);
const PROFILE_VALUES = Object.freeze(['dev', 'ha', 'db-ha', 'traffic']);
const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
const SCRIPT_PATH = /^scenarios\/[A-Za-z0-9._-]+\.js$/;

export class ManifestError extends Error {}

export function manifestPath() {
  const override = process.env.LAB_SCENARIOS_MANIFEST;
  if (override !== undefined && String(override).trim() !== '') {
    return path.resolve(String(override).trim());
  }
  return DEFAULT_MANIFEST;
}

function fail(message) {
  throw new ManifestError(message);
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function readNameList(value, label) {
  if (!Array.isArray(value)) {
    fail(`${label} must be an array of scenario names`);
  }
  const seen = new Set();
  for (const name of value) {
    if (typeof name !== 'string' || name.trim() === '') {
      fail(`${label} contains a non-string or empty entry`);
    }
    if (seen.has(name)) {
      fail(`${label} lists "${name}" twice`);
    }
    seen.add(name);
  }
  return value;
}

function readEnvNames(value, label) {
  if (!Array.isArray(value)) {
    fail(`${label} must be an array of environment variable names`);
  }
  const seen = new Set();
  for (const name of value) {
    if (typeof name !== 'string' || !ENV_NAME.test(name)) {
      fail(`${label} contains an invalid environment variable name: ${JSON.stringify(name)}`);
    }
    if (seen.has(name)) {
      fail(`${label} lists "${name}" twice`);
    }
    seen.add(name);
  }
  return value;
}

/**
 * Parses and validates a manifest object. Throws ManifestError with a readable
 * message on any structural or cross-view inconsistency.
 */
export function validateManifest(manifest, source = '<memory>') {
  if (!isPlainObject(manifest)) {
    fail(`${source}: manifest must be a JSON object`);
  }
  if (!Number.isInteger(manifest.version) || manifest.version < 1) {
    fail(`${source}: "version" must be a positive integer`);
  }
  if (!isPlainObject(manifest.views)) {
    fail(`${source}: "views" must be an object with run/bench/ui arrays`);
  }
  const scenarios = manifest.scenarios;
  if (!isPlainObject(scenarios) || Object.keys(scenarios).length === 0) {
    fail(`${source}: "scenarios" must be a non-empty object`);
  }

  const views = {};
  for (const view of VIEW_NAMES) {
    views[view] = readNameList(manifest.views[view], `${source}: views.${view}`);
  }
  const inView = (view, name) => views[view].includes(name);
  for (const view of VIEW_NAMES) {
    for (const name of views[view]) {
      if (!Object.prototype.hasOwnProperty.call(scenarios, name)) {
        fail(`${source}: views.${view} references unknown scenario "${name}"`);
      }
    }
  }
  for (const name of views.bench) {
    if (!inView('run', name)) {
      fail(`${source}: views.bench must be a subset of views.run ("${name}" is not in run)`);
    }
  }
  for (const name of views.ui) {
    if (!inView('bench', name)) {
      fail(`${source}: views.ui must be a subset of views.bench ("${name}" is not in bench)`);
    }
  }

  if (!isPlainObject(manifest.forwarding)) {
    fail(`${source}: "forwarding" must be an object with common/chaos/cache/service arrays`);
  }
  for (const group of ['common', 'chaos', 'cache', 'service']) {
    readEnvNames(manifest.forwarding[group], `${source}: forwarding.${group}`);
  }
  if (!Array.isArray(manifest.runtimeEnv)) {
    fail(`${source}: "runtimeEnv" must be an array`);
  }
  readEnvNames(manifest.runtimeEnv, `${source}: runtimeEnv`);

  const forwarded = new Set([
    ...manifest.forwarding.common,
    ...manifest.forwarding.chaos,
    ...manifest.forwarding.cache,
    ...manifest.forwarding.service,
    ...manifest.runtimeEnv,
  ]);

  for (const [name, scenario] of Object.entries(scenarios)) {
    const label = `${source}: scenarios.${name}`;
    if (!isPlainObject(scenario)) {
      fail(`${label} must be an object`);
    }
    if (typeof scenario.script !== 'string' || !SCRIPT_PATH.test(scenario.script)) {
      fail(`${label}.script must look like "scenarios/<name>.js"`);
    }
    if (typeof scenario.serviceOnly !== 'boolean') {
      fail(`${label}.serviceOnly must be a boolean`);
    }
    if (!isPlainObject(scenario.requires)) {
      fail(`${label}.requires must be an object`);
    }
    if (typeof scenario.requires.chaos !== 'boolean' || typeof scenario.requires.cache !== 'boolean') {
      fail(`${label}.requires.chaos/cache must be booleans`);
    }
    const profile = scenario.requires.profile;
    if (profile !== null && !PROFILE_VALUES.includes(profile)) {
      fail(
        `${label}.requires.profile must be null or one of ${PROFILE_VALUES.join(', ')}; got ` +
          `${JSON.stringify(profile)}`,
      );
    }
    if (typeof scenario.writesData !== 'boolean') {
      fail(`${label}.writesData must be a boolean`);
    }
    if (typeof scenario.thresholdsExpected !== 'boolean') {
      fail(`${label}.thresholdsExpected must be a boolean`);
    }
    const env = readEnvNames(scenario.env, `${label}.env`);
    if (!isPlainObject(scenario.params)) {
      fail(`${label}.params must be an object`);
    }
    for (const key of Object.keys(scenario.params)) {
      if (!env.includes(key)) {
        fail(`${label}.params.${key} is not declared in ${label}.env`);
      }
    }
    for (const key of env) {
      if (!forwarded.has(key)) {
        fail(`${label}.env lists "${key}" but neither forwarding nor runtimeEnv forwards it`);
      }
      if (!scenario.serviceOnly && manifest.forwarding.service.includes(key)) {
        fail(
          `${label}.env lists compose-service variable "${key}"; only a service-only ` +
            'scenario may consume forwarding.service',
        );
      }
    }

    if (scenario.serviceOnly) {
      for (const view of VIEW_NAMES) {
        if (inView(view, name)) {
          fail(`${source}: service-only scenario "${name}" must not be in views.${view}`);
        }
      }
    } else if (!inView('run', name)) {
      fail(`${source}: scenario "${name}" is neither service-only nor in views.run`);
    }
  }

  // Forwarded names must be consumed somewhere: either by a scenario family or
  // as a k6 runtime variable. This catches a manifest drift where an env name is
  // forwarded but no scenario declares it.
  const consumed = new Set(manifest.runtimeEnv);
  for (const scenario of Object.values(scenarios)) {
    for (const key of scenario.env) {
      consumed.add(key);
    }
  }
  for (const key of forwarded) {
    if (!consumed.has(key)) {
      fail(`${source}: "${key}" is forwarded but no scenario or runtimeEnv declares it`);
    }
  }

  return manifest;
}

export function loadManifest(file = manifestPath()) {
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (error) {
    fail(`cannot read scenario manifest ${file}: ${error.message}`);
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    fail(`scenario manifest ${file} is not valid JSON: ${error.message}`);
  }
  return validateManifest(parsed, file);
}

export function viewNames(manifest, view) {
  if (!VIEW_NAMES.includes(view)) {
    fail(`unknown view "${view}" (expected ${VIEW_NAMES.join('|')})`);
  }
  return [...manifest.views[view]];
}

export function scenarioConfig(manifest, name) {
  if (!Object.prototype.hasOwnProperty.call(manifest.scenarios, name)) {
    return null;
  }
  return manifest.scenarios[name];
}

/** 0 = runnable, 3 = unknown, 4 = not in run, 5 = service-only. */
export function checkRun(manifest, name) {
  const scenario = scenarioConfig(manifest, name);
  if (scenario === null) {
    return 3;
  }
  if (scenario.serviceOnly) {
    return 5;
  }
  return viewNames(manifest, 'run').includes(name) ? 0 : 4;
}

/** 0 = benchable, 3 = unknown, 4 = not in bench, 5 = service-only. */
export function checkBench(manifest, name) {
  const scenario = scenarioConfig(manifest, name);
  if (scenario === null) {
    return 3;
  }
  if (scenario.serviceOnly) {
    return 5;
  }
  return viewNames(manifest, 'bench').includes(name) ? 0 : 4;
}

function inlineView(manifest, view) {
  return viewNames(manifest, view).join('|');
}

function writeError(message) {
  process.stderr.write(`ERROR: ${message}\n`);
}

function main(argv) {
  const [command, argument] = argv;
  let manifest;
  try {
    manifest = loadManifest();
  } catch (error) {
    writeError(error instanceof ManifestError ? error.message : String(error));
    return 2;
  }

  switch (command) {
    case 'validate': {
      const counts = VIEW_NAMES.map((view) => `${view}=${manifest.views[view].length}`).join(' ');
      process.stdout.write(
        `manifest ok: ${Object.keys(manifest.scenarios).length} scenarios (${counts})\n`,
      );
      return 0;
    }
    case 'list': {
      try {
        for (const name of viewNames(manifest, argument)) {
          process.stdout.write(`${name}\n`);
        }
      } catch (error) {
        writeError(error instanceof ManifestError ? error.message : String(error));
        return 2;
      }
      return 0;
    }
    case 'check-run': {
      const status = checkRun(manifest, argument);
      if (status === 3) {
        writeError(`unknown scenario "${argument}" (run.sh scenarios: ${inlineView(manifest, 'run')})`);
      } else if (status === 4) {
        writeError(
          `scenario "${argument}" is not a run.sh scenario (run.sh scenarios: ` +
            `${inlineView(manifest, 'run')})`,
        );
      } else if (status === 5) {
        writeError(
          `scenario "${argument}" is a service-only background load: it runs as the compose ` +
            `traffic service (profile "${manifest.scenarios[argument].requires.profile}"), ` +
            'not as a run.sh scenario',
        );
      } else {
        const scenario = scenarioConfig(manifest, argument);
        process.stdout.write(
          `${scenario.requires.chaos ? 1 : 0} ${scenario.requires.cache ? 1 : 0}\n`,
        );
      }
      return status;
    }
    case 'check-bench': {
      const status = checkBench(manifest, argument);
      if (status === 3) {
        writeError(
          `unknown scenario "${argument}" (bench scenarios: ${inlineView(manifest, 'bench')})`,
        );
      } else if (status === 4) {
        writeError(
          `scenario "${argument}" is not a bench scenario (bench scenarios: ` +
            `${inlineView(manifest, 'bench')})`,
        );
      } else if (status === 5) {
        writeError(
          `scenario "${argument}" is a service-only background load; bench.sh cannot archive it ` +
            `(bench scenarios: ${inlineView(manifest, 'bench')})`,
        );
      } else {
        process.stdout.write(
          `thresholds-expected=${scenarioConfig(manifest, argument).thresholdsExpected}\n`,
        );
      }
      return status;
    }
    default:
      writeError(
        'usage: scenario-manifest.mjs validate|list <run|bench|ui>|check-run <name>|check-bench <name>',
      );
      return 2;
  }
}

const invokedPath = process.argv[1] === undefined ? '' : path.resolve(process.argv[1]);
if (invokedPath !== '' && import.meta.url === pathToFileURL(invokedPath).href) {
  process.exitCode = main(process.argv.slice(2));
}
