/**
 * Load: product reads only (inventory-safe), ramping 50 -> 200 VUs over
 * LOAD_DURATION. The acceptance run may override LOAD_DURATION=2m; stage
 * durations are derived from that single total so they stay consistent.
 */
import { sleep } from 'k6';
import { config, baseOptions } from '../lib/config.js';
import { ensureLogin, product } from '../lib/flow.js';
import { pickProductId } from '../lib/product-sampling.js';

export const options = {
  ...baseOptions,
  scenarios: {
    load: {
      executor: 'ramping-vus',
      stages: config.load.stages,
      gracefulRampDown: config.gracefulRampDown,
      gracefulStop: config.gracefulStop,
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

export default function () {
  ensureLogin();
  product(pickProductId(config.productSampling));
  sleep(config.thinkTimeSeconds);
}
