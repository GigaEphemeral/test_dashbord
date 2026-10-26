/**
 * D7 dual-JDK comparison: read-only product calls with NO login.
 *
 * Why a dedicated scenario instead of reusing load.js: every existing base
 * scenario calls ensureLogin() -> POST /api/login, and the JDK 8 shell has no
 * login endpoint by design (its HTTP surface is products + hello + actuator).
 * Reusing load.js against the JDK 8 target would count one failed login per VU
 * per iteration and flood flow_errors, making the comparison meaningless.
 *
 * The VU/threshold shape is identical to load.js (ramping-vus over
 * config.load.stages, same error-rate and P95/P99 thresholds); only the login
 * step is dropped. Point it at either runtime:
 *
 *   bash code/lab/k6/run.sh d7                                        # JDK 21 via Nginx
 *   BASE_URL=http://seckill-app-jdk8:8080 bash code/lab/k6/run.sh d7  # JDK 8 direct
 *
 * Reads never consume seckill stock, so the same script is safe to run against
 * both targets in sequence without resetting lab data.
 */
import { sleep } from 'k6';
import { config, baseOptions } from '../lib/config.js';
import { product } from '../lib/flow.js';

export const options = {
  ...baseOptions,
  scenarios: {
    d7: {
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
  product();
  sleep(config.thinkTimeSeconds);
}
