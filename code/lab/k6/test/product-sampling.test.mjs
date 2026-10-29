/**
 * Stage2 B3-00: product-ID sampling for the load scenario (design 16.9).
 *
 * Run: node --test "code/lab/k6/test/*.test.mjs"
 *
 * The module is pure (no k6 imports), so these tests pin the parsing gates and
 * the draw semantics directly with deterministic rand values:
 *   - defaults keep the historical product 1;
 *   - uniform is inclusive on [min, max];
 *   - normal is centred on the range middle, concentration tightens it, and
 *     every draw is clamped into the configured range;
 *   - min === max short-circuits without drawing.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const { parseSampling, pickProductId } = await import('../lib/product-sampling.js');

function sequence(values) {
  let index = 0;
  return () => values[Math.min(index++, values.length - 1)];
}

test('missing env keeps the historical product-1 defaults', () => {
  assert.deepEqual(parseSampling({}), {
    min: 1,
    max: 1,
    distribution: 'uniform',
    concentration: 0,
  });
  // Empty strings are treated like a missing value (run.sh forwards only
  // non-empty variables, but direct k6 invocations may export "").
  assert.deepEqual(
    parseSampling({
      PRODUCT_ID_MIN: '',
      PRODUCT_ID_MAX: '',
      PRODUCT_ID_DISTRIBUTION: '',
      PRODUCT_ID_CONCENTRATION: '',
    }),
    { min: 1, max: 1, distribution: 'uniform', concentration: 0 },
  );
});

test('parseSampling accepts a full sampling configuration', () => {
  assert.deepEqual(
    parseSampling({
      PRODUCT_ID_MIN: '5000',
      PRODUCT_ID_MAX: '20000',
      PRODUCT_ID_DISTRIBUTION: 'normal',
      PRODUCT_ID_CONCENTRATION: '55',
    }),
    { min: 5000, max: 20000, distribution: 'normal', concentration: 55 },
  );
});

test('uniform draws stay inside the inclusive range', () => {
  const sampling = { min: 5, max: 10, distribution: 'uniform', concentration: 0 };
  assert.equal(pickProductId(sampling, () => 0), 5);
  assert.equal(pickProductId(sampling, () => 0.5), 8);
  assert.equal(pickProductId(sampling, () => 0.9999999999), 10);
  // A hostile rand is clamped instead of escaping the range.
  assert.equal(pickProductId(sampling, () => 1), 10);
  assert.equal(pickProductId(sampling, () => -1), 5);
});

test('normal is centred on the range middle', () => {
  const sampling = { min: 1, max: 101, distribution: 'normal', concentration: 0 };
  // u1 = 0.5, u2 = 0.25 -> cos(pi/2) ~ 0 -> z ~ 0 -> the mean.
  assert.equal(pickProductId(sampling, sequence([0.5, 0.25])), 51);
});

test('normal clamps rounded tails into [min, max]', () => {
  const sampling = { min: 100, max: 200, distribution: 'normal', concentration: 0 };
  // Tiny u1 explodes the Box-Muller z; u2 picks the positive/negative tail.
  assert.equal(pickProductId(sampling, sequence([1e-9, 1e-9])), 200);
  assert.equal(pickProductId(sampling, sequence([1e-9, 0.5])), 100);
});

test('higher concentration tightens the normal spread', () => {
  const wide = { min: 1, max: 101, distribution: 'normal', concentration: 0 };
  const tight = { min: 1, max: 101, distribution: 'normal', concentration: 100 };
  const wideDraw = pickProductId(wide, sequence([0.9, 0.3]));
  const tightDraw = pickProductId(tight, sequence([0.9, 0.3]));
  assert.ok(Math.abs(wideDraw - 51) > Math.abs(tightDraw - 51));
  assert.ok(tightDraw >= 1 && tightDraw <= 101);
});

test('min === max returns the single id without drawing (both distributions)', () => {
  const explode = () => {
    throw new Error('rand must not be called for a single-id range');
  };
  assert.equal(
    pickProductId({ min: 7, max: 7, distribution: 'uniform', concentration: 0 }, explode),
    7,
  );
  assert.equal(
    pickProductId({ min: 7, max: 7, distribution: 'normal', concentration: 50 }, explode),
    7,
  );
});

test('parse errors are prefixed and describe the broken field', () => {
  const prefix = /\[k6\/product-sampling\]/;
  assert.throws(
    () => parseSampling({ PRODUCT_ID_MIN: '5', PRODUCT_ID_MAX: '4' }),
    (error) => prefix.test(error.message) && /PRODUCT_ID_MAX must be >= PRODUCT_ID_MIN/.test(error.message),
  );
  assert.throws(
    () => parseSampling({ PRODUCT_ID_DISTRIBUTION: 'zipf' }),
    (error) => prefix.test(error.message) && /must be one of uniform, normal/.test(error.message),
  );
  assert.throws(
    () => parseSampling({ PRODUCT_ID_CONCENTRATION: '-1' }),
    (error) => prefix.test(error.message) && /\[0, 100\]/.test(error.message),
  );
  assert.throws(
    () => parseSampling({ PRODUCT_ID_CONCENTRATION: '101' }),
    (error) => prefix.test(error.message) && /\[0, 100\]/.test(error.message),
  );
  assert.throws(
    () => parseSampling({ PRODUCT_ID_MIN: '1.5' }),
    (error) => prefix.test(error.message) && /must be an integer/.test(error.message),
  );
  assert.throws(
    () => parseSampling({ PRODUCT_ID_MAX: 'abc' }),
    (error) => prefix.test(error.message) && /must be an integer/.test(error.message),
  );
  assert.throws(
    () => parseSampling({ PRODUCT_ID_MIN: '0' }),
    (error) => prefix.test(error.message) && /must be >= 1/.test(error.message),
  );
});
