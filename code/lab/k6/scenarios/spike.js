/**
 * Spike: baseline -> 800 VU -> back down, targeting the seckill product.
 *
 * Expected business rejections do not fail the run:
 * - 409 (sold out / lock timeout) is counted in seckill_expected_409;
 * - 429 (rate limited) is counted in seckill_expected_429.
 * Only 5xx/transport failures and unexpected statuses feed the error-rate
 * thresholds. The spike may consume the remaining stock of SECKILL_PRODUCT_ID.
 */
import { sleep } from 'k6';
import { config, baseOptions } from '../lib/config.js';
import { ensureLogin, seckill } from '../lib/flow.js';

export const options = {
  ...baseOptions,
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      stages: config.spike.stages,
      gracefulRampDown: config.gracefulRampDown,
      gracefulStop: config.gracefulStop,
    },
  },
  thresholds: {
    flow_errors: [`rate<${config.thresholds.errorRate}`],
    server_errors: [`rate<${config.thresholds.errorRate}`],
  },
};

export default function () {
  ensureLogin();
  seckill();
  sleep(config.thinkTimeSeconds);
}
