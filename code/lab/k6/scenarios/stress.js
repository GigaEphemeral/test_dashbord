/**
 * Stress: product reads only, staged 50 -> 500 VUs by default.
 *
 * There is deliberately no threshold here: the scenario exists to observe the
 * knee (error rate and P95/P99 by stage) rather than to produce a pass/fail
 * verdict. Set STRESS_STAGES (JSON array of {duration,target}) to reshape it.
 */
import { sleep } from 'k6';
import { config, baseOptions } from '../lib/config.js';
import { ensureLogin, product } from '../lib/flow.js';

export const options = {
  ...baseOptions,
  scenarios: {
    stress: {
      executor: 'ramping-vus',
      stages: config.stress.stages,
      gracefulRampDown: config.gracefulRampDown,
      gracefulStop: config.gracefulStop,
    },
  },
};

export default function () {
  ensureLogin();
  product();
  sleep(config.thinkTimeSeconds);
}
