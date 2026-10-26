/**
 * Background traffic for interactive demos.
 *
 * Constant-vus product reads only - no orders, so it never consumes seckill
 * stock and can run while the operator pauses inside a guide step. The lab
 * console starts/stops it (compose service `traffic`, profile `traffic`);
 * formal runs with verdicts and archives still go through bench.sh.
 */
import { sleep } from 'k6';
import { config, baseOptions } from '../lib/config.js';
import { ensureLogin, product } from '../lib/flow.js';

export const options = {
  ...baseOptions,
  scenarios: {
    traffic: {
      executor: 'constant-vus',
      vus: config.traffic.vus,
      duration: config.traffic.duration,
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
