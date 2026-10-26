/**
 * Smoke: one full business pass per VU, capped by SMOKE_DURATION.
 *
 * login -> product -> order -> repeat same Idempotency-Key -> seckill -> poll.
 * It creates exactly one order and one seckill attempt (per VU/iteration), so
 * it must not be used as a high-frequency loop.
 */
import { config, baseOptions } from '../lib/config.js';
import { login, order, product, seckill } from '../lib/flow.js';

export const options = {
  ...baseOptions,
  scenarios: {
    smoke: {
      executor: 'per-vu-iterations',
      vus: config.smoke.vus,
      iterations: config.smoke.iterations,
      maxDuration: config.smoke.duration,
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
  if (!login()) {
    return;
  }
  if (!product()) {
    return;
  }
  order({ replay: true });
  seckill();
}
