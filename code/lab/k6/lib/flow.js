/**
 * Business flows shared by the five base scenarios.
 *
 * The flows mirror code/lab/scripts/smoke.sh exactly (login -> product ->
 * idempotent order -> optional replay -> async seckill accept/poll). Load,
 * stress and soak intentionally use product reads only so they never drain
 * inventory; smoke creates one order; spike targets the seckill product.
 */
import { sleep } from 'k6';
import { config } from './config.js';
import {
  call,
  recordError,
  seckillExpected409,
  seckillExpected429,
  seckillPollTimeouts,
  seckillTerminalFailed,
} from './http.js';

let token = '';

function uniqueSuffix() {
  return `${__VU}-${__ITER}-${Math.random().toString(36).slice(2, 10)}`;
}

function orderUser() {
  return `${config.userPrefix}-${config.runId}-vu${__VU}`;
}

/** Mock login; the token is kept in VU memory and reused for later calls. */
export function login() {
  const userId = `${config.userPrefix}-${config.runId}-${uniqueSuffix()}`;
  const result = call('POST', config.paths.login, {
    flow: 'login',
    body: { userId },
    checkName: 'login ok',
    validate: (payload) =>
      payload !== null &&
      payload.data !== null &&
      typeof payload.data.token === 'string' &&
      payload.data.token.length > 0 &&
      Number(payload.data.expiresInSeconds) > 0,
  });
  if (result.ok) {
    token = result.data.token;
    return true;
  }
  return false;
}

export function ensureLogin() {
  if (token.length === 0) {
    login();
  }
  return token.length > 0;
}

/** Product read used by load/stress/soak (no inventory is consumed). */
export function product(productId) {
  const id = productId === undefined ? config.productId : productId;
  const result = call('GET', `${config.paths.products}/${id}`, {
    token,
    flow: 'product',
    checkName: 'product ok',
    validate: (payload) => payload !== null && payload.data !== null && Number(payload.data.id) === id,
  });
  return result.ok;
}

/**
 * Creates one idempotent order; with `replay: true` it repeats the same
 * Idempotency-Key and asserts the same order id is returned (smoke only).
 */
export function order(options) {
  const opts = options || {};
  const key = `${config.order.keyPrefix}-${config.runId}-${uniqueSuffix()}`;
  const payload = {
    userId: orderUser(),
    productId: config.productId,
    quantity: config.order.quantity,
  };

  const created = call('POST', config.paths.orders, {
    token,
    flow: 'order',
    body: payload,
    headers: { 'Idempotency-Key': key },
    checkName: 'order ok',
    validate: (body) => body !== null && body.data !== null && body.data.id !== undefined,
  });
  if (!created.ok) {
    return { ok: false, orderId: null, key };
  }
  const orderId = created.data.id;
  if (!opts.replay) {
    return { ok: true, orderId, key };
  }

  const replayed = call('POST', config.paths.orders, {
    token,
    flow: 'order',
    body: payload,
    headers: { 'Idempotency-Key': key },
    checkName: 'order replay ok',
    validate: (body) =>
      body !== null &&
      body.data !== null &&
      body.data.id !== undefined &&
      String(body.data.id) === String(orderId),
  });
  return {
    ok: replayed.ok && String(replayed.data.id) === String(orderId),
    orderId,
    key,
  };
}

function pollSeckill(requestId) {
  for (let attempt = 0; attempt < config.poll.attempts; attempt += 1) {
    const result = call('GET', `${config.paths.seckillOrders}/${requestId}`, {
      token,
      flow: 'seckill_poll',
      validate: (payload) => {
        if (payload === null || payload.data === null) {
          return false;
        }
        const status = payload.data.status;
        if (typeof status !== 'string' || status.length === 0) {
          return false;
        }
        if (status === 'CREATED') {
          return payload.data.orderId !== undefined && payload.data.orderId !== null;
        }
        return true;
      },
    });
    if (!result.ok) {
      return { outcome: 'error', requestId };
    }
    const status = result.data.status;
    if (status === 'CREATED') {
      return { outcome: 'created', requestId, orderId: result.data.orderId };
    }
    if (status === 'FAILED') {
      seckillTerminalFailed.add(1);
      return { outcome: 'failed', requestId, message: result.data.message };
    }
    if (attempt < config.poll.attempts - 1) {
      sleep(config.poll.intervalSeconds);
    }
  }
  seckillPollTimeouts.add(1);
  recordError('seckill poll timeout');
  return { outcome: 'timeout', requestId };
}

/**
 * True when the response already carries the durable terminal outcome
 * (synchronous path, or the circuit-open degradation added in 48d45fb).
 */
function isTerminalCreated(data) {
  return (
    data !== null &&
    data.status === 'CREATED' &&
    data.orderId !== undefined &&
    data.orderId !== null
  );
}

/**
 * Seckill admission. 200/202/409/429 are expected statuses: 409 (sold out or
 * lock timeout) and 429 (rate limited) are counted instead of being treated as
 * transport failures. A 202/requestId is polled until CREATED/FAILED with a
 * bounded attempt count. When the seckillBackend circuit is open the app
 * degrades to the synchronous DB path but still answers 202 (the mq toggle is
 * on), with `requestId: null` and a terminal `CREATED` + `orderId`: that
 * response is a success, not an error, and must not be polled.
 */
export function seckill() {
  const key = `${config.seckill.keyPrefix}-${config.runId}-${uniqueSuffix()}`;
  const payload = {
    userId: orderUser(),
    productId: config.seckillProductId,
    quantity: config.seckill.quantity,
  };

  const accepted = call('POST', config.paths.seckillOrders, {
    token,
    flow: 'seckill',
    body: payload,
    headers: { 'Idempotency-Key': key },
    acceptStatuses: config.seckill.expectedStatuses,
    requireCode: false,
    checkName: 'seckill handled',
    validate: (body, res) => {
      if (res.status === 409 || res.status === 429) {
        return true;
      }
      if (body === null || body.code !== '0' || body.data === null) {
        return false;
      }
      if (res.status === 202) {
        const hasRequestId = typeof body.data.requestId === 'string' && body.data.requestId.length > 0;
        return hasRequestId || isTerminalCreated(body.data);
      }
      if (res.status === 200) {
        return isTerminalCreated(body.data);
      }
      return false;
    },
  });

  if (accepted.status === 409) {
    seckillExpected409.add(1);
    return { outcome: 'conflict', key };
  }
  if (accepted.status === 429) {
    seckillExpected429.add(1);
    return { outcome: 'rate_limited', key };
  }
  if (!accepted.ok) {
    return { outcome: 'error', key };
  }
  if (accepted.status === 200) {
    return { outcome: 'created', key, orderId: accepted.data.orderId };
  }
  if (isTerminalCreated(accepted.data)) {
    // Degraded synchronous path answering 202: already durable, no polling.
    return { outcome: 'created', key, orderId: accepted.data.orderId };
  }
  return pollSeckill(accepted.data.requestId);
}
