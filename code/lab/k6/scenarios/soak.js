/**
 * Soak: steady product reads for SOAK_DURATION (default 30m, acceptance runs
 * may override to 2m). Heap/thread trends are evaluated by the Task 20
 * post-run Prometheus checks, not by k6.
 */
import { sleep } from 'k6';
import { config, baseOptions } from '../lib/config.js';
import { ensureLogin, product } from '../lib/flow.js';

export const options = {
  ...baseOptions,
  scenarios: {
    soak: {
      executor: 'constant-vus',
      vus: config.soak.vus,
      duration: config.soak.duration,
      gracefulStop: config.gracefulStop,
    },
  },
  thresholds: {
    flow_errors: [`rate<${config.thresholds.errorRate}`],
    server_errors: [`rate<${config.thresholds.errorRate}`],
    checks: [`rate>${config.thresholds.checksRate}`],
  },
};

export default function () {
  ensureLogin();
  product();
  sleep(config.thinkTimeSeconds);
}
