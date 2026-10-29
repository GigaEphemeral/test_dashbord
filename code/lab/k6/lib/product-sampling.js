/**
 * Stage2 B3-00: product-ID sampling for the `load` scenario (design 16.9).
 *
 * Pure functions only (no k6 imports) so the same module runs under `k6` and
 * under plain `node --test`. The defaults (min = max = 1) preserve the
 * historical behaviour exactly: every load iteration reads product 1. A wider
 * range is opt-in until the 10k seed lands in B3-02.
 *
 * Env names:
 *   PRODUCT_ID_MIN           integer >= 1, default 1
 *   PRODUCT_ID_MAX           integer >= PRODUCT_ID_MIN, default 1
 *   PRODUCT_ID_DISTRIBUTION  "uniform" | "normal", default "uniform"
 *   PRODUCT_ID_CONCENTRATION 0..100, default 0 (normal only; 0 = wide,
 *                            100 = concentrated around the range centre)
 */

const PREFIX = '[k6/product-sampling]';
export const DISTRIBUTIONS = Object.freeze(['uniform', 'normal']);

function rawValue(env, name, fallback) {
  if (env === null || env === undefined) {
    return String(fallback);
  }
  const value = env[name];
  if (value === undefined || value === null || value === '') {
    return String(fallback);
  }
  return String(value);
}

function parseInteger(name, raw) {
  const value = Number(raw);
  if (!Number.isInteger(value)) {
    throw new Error(`${PREFIX} ${name} must be an integer, got "${raw}"`);
  }
  return value;
}

function parseDistribution(raw) {
  if (DISTRIBUTIONS.indexOf(raw) === -1) {
    throw new Error(
      `${PREFIX} PRODUCT_ID_DISTRIBUTION must be one of ${DISTRIBUTIONS.join(', ')}, got "${raw}"`,
    );
  }
  return raw;
}

function parseConcentration(raw) {
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0 || value > 100) {
    throw new Error(`${PREFIX} PRODUCT_ID_CONCENTRATION must be a number in [0, 100], got "${raw}"`);
  }
  return value;
}

/**
 * Reads and validates the four sampling variables from a plain env map
 * (`__ENV` in k6). Missing or empty values fall back to the defaults; any
 * invalid value throws an Error prefixed with `[k6/product-sampling]`.
 */
export function parseSampling(env) {
  const min = parseInteger('PRODUCT_ID_MIN', rawValue(env, 'PRODUCT_ID_MIN', 1));
  if (min < 1) {
    throw new Error(`${PREFIX} PRODUCT_ID_MIN must be >= 1, got "${min}"`);
  }
  const max = parseInteger('PRODUCT_ID_MAX', rawValue(env, 'PRODUCT_ID_MAX', 1));
  if (max < min) {
    throw new Error(
      `${PREFIX} PRODUCT_ID_MAX must be >= PRODUCT_ID_MIN (${min}), got "${max}"`,
    );
  }
  const distribution = parseDistribution(rawValue(env, 'PRODUCT_ID_DISTRIBUTION', 'uniform'));
  const concentration = parseConcentration(
    rawValue(env, 'PRODUCT_ID_CONCENTRATION', 0),
  );
  return { min, max, distribution, concentration };
}

/**
 * Normal sigma: concentration 0 => span/6 (about +-3 sigma covers the range),
 * concentration 100 => span/30 (tight around the centre), linear in between.
 * Kept strictly positive for any non-empty range.
 */
function normalSigma(min, max, concentration) {
  const span = max - min;
  const ratio = Math.min(100, Math.max(0, concentration)) / 100;
  const sigma = span * (1 / 6 - (2 / 15) * ratio);
  return Math.max(sigma, 1e-9);
}

/**
 * Draws one inclusive product id in [min, max].
 *
 * `uniform` is a plain inclusive integer draw. `normal` is Box-Muller around
 * the range centre, rounded and clamped to the range, so the tails never read
 * an id outside the configured scope. `min === max` short-circuits (also when
 * distribution is normal) and never calls `rand`.
 */
export function pickProductId(sampling, rand = Math.random) {
  const { min, max } = sampling;
  if (max <= min) {
    return min;
  }
  const random = typeof rand === 'function' ? rand : Math.random;
  if (sampling.distribution === 'normal') {
    const mean = (min + max) / 2;
    const sigma = normalSigma(min, max, sampling.concentration);
    let u1 = Number(random());
    if (!(u1 > 0)) {
      // log(0) would be -Infinity; keep Box-Muller finite and clamped.
      u1 = Number.EPSILON;
    }
    if (u1 >= 1) {
      u1 = 1 - Number.EPSILON;
    }
    const u2 = Number(random());
    const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    const value = Math.round(mean + sigma * z);
    return Math.min(max, Math.max(min, value));
  }
  const value = min + Math.floor(Number(random()) * (max - min + 1));
  return Math.min(max, Math.max(min, value));
}
