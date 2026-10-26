/**
 * Stub of the bare "k6" module for the zero-dependency node test harness.
 * Records every check() assertion so tests can assert check outcomes.
 */
export const state = { checks: [] };

export function sleep() {
  // No-op so poll loops finish instantly.
}

export function check(res, assertions) {
  let passed = true;
  for (const [name, fn] of Object.entries(assertions)) {
    const ok = Boolean(fn(res));
    state.checks.push({ name, ok });
    if (!ok) {
      passed = false;
    }
  }
  return passed;
}

export function reset() {
  state.checks.length = 0;
}
