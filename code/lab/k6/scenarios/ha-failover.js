/**
 * HA failover timeline (Task 22a).
 *
 * One constant-VU product-read load plus one VU that replays the shared
 * scenario JSON against the chaos-controller. The timeline is data-driven:
 * `CHAOS_SCENARIO_FILE` selects which file under `/chaos-scenarios` is used
 * (default `ha-app-failover.json`). `teardown` always calls
 * `/chaos/stop-all` so a failed or shortened run leaves no active fault.
 *
 * The default scenario is destructive for app-1/app-2. For live smoke checks
 * pass `CHAOS_SCENARIO_FILE=quick-failover.json` (jl-app-3 only).
 */
import { sleep } from 'k6';
import { config, baseOptions } from '../lib/config.js';
import { ensureLogin, product } from '../lib/flow.js';
import {
  action,
  chaosConfig,
  loadScenario,
  resolveScenarioPath,
  sortEvents,
  stopAll,
} from '../lib/chaos.js';

const scenarioPath = resolveScenarioPath(chaosConfig.scenarioFile, chaosConfig.scenarioDir);
const scenario = loadScenario(scenarioPath);
const events = sortEvents(scenario.events);

const lastEventSeconds = events.reduce((latest, event) => {
  const end = event.atSeconds + (event.ttlSeconds === null ? 0 : event.ttlSeconds);
  return end > latest ? end : latest;
}, 0);
const loadSeconds = chaosConfig.loadDurationMs / 1000;
if (lastEventSeconds > loadSeconds) {
  console.warn(
    `[chaos] ${scenario.id}: last event ends ${lastEventSeconds}s after timeline start, ` +
      `but load runs ${loadSeconds}s; the tail has no background traffic. ` +
      'Increase CHAOS_DURATION or shorten the scenario.',
  );
}

export const options = {
  ...baseOptions,
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: chaosConfig.loadVus,
      duration: chaosConfig.loadDuration,
      gracefulStop: config.gracefulStop,
      exec: 'loadProduct',
    },
    chaos: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: chaosConfig.startOffset,
      gracefulStop: config.gracefulStop,
      exec: 'chaosTimeline',
    },
  },
  thresholds: {
    flow_errors: [`rate<${config.thresholds.errorRate}`],
    server_errors: [`rate<${config.thresholds.errorRate}`],
    'http_req_duration{flow:product}': [
      `p(95)<${config.thresholds.p95Ms}`,
      `p(99)<${config.thresholds.p99Ms}`,
    ],
  },
};

/** Constant-VU baseline: product reads only, so no inventory is consumed. */
export function loadProduct() {
  ensureLogin();
  product();
  sleep(config.thinkTimeSeconds);
}

/**
 * Replays the scenario relative to this VU's start. The first failure stops
 * the remaining events; metrics keep the accepted/failed split, and teardown
 * still runs `stop-all`.
 */
export function chaosTimeline() {
  const started = Date.now();
  let applied = 0;
  console.log(
    `[chaos] timeline start id=${scenario.id} events=${events.length} file=${scenarioPath} ` +
      `offset=${chaosConfig.startOffset}`,
  );

  for (const event of events) {
    const elapsedSeconds = (Date.now() - started) / 1000;
    const waitSeconds = event.atSeconds - elapsedSeconds;
    if (waitSeconds > 0) {
      sleep(waitSeconds);
    }
    const ttl = event.ttlSeconds === null ? '-' : `${event.ttlSeconds}s`;
    console.log(
      `[chaos] t=+${(event.atSeconds).toFixed(1)}s action=${event.action} target=${event.target} ttl=${ttl}`,
    );
    try {
      const res = action(
        chaosConfig.controllerUrl,
        chaosConfig.token,
        event,
        chaosConfig.httpTimeout,
      );
      applied += 1;
      console.log(
        `[chaos] accepted action=${event.action} target=${event.target} status=${res.status}`,
      );
    } catch (error) {
      console.error(
        `[chaos] failed action=${event.action} target=${event.target}: ${error.message}`,
      );
      break;
    }
  }
  console.log(`[chaos] timeline finished id=${scenario.id} applied=${applied}/${events.length}`);
}

/** Always attempt the global rollback, even when the timeline aborted. */
export function teardown() {
  try {
    const result = stopAll(
      chaosConfig.controllerUrl,
      chaosConfig.token,
      chaosConfig.teardownTimeout,
    );
    const reverted = result === null ? '?' : result.reverted;
    const failed = result === null ? '?' : result.failed;
    console.log(`[chaos] teardown stop-all reverted=${reverted} failed=${failed}`);
  } catch (error) {
    console.error(`[chaos] teardown stop-all failed: ${error.message}`);
  }
}
