/**
 * ESM resolve hook that maps k6's bare module specifiers to the stubs in
 * ./stubs so lib/flow.js and lib/http.js can be unit-tested with plain node.
 */
const stubs = {
  k6: new URL('./stubs/k6.mjs', import.meta.url).href,
  'k6/http': new URL('./stubs/k6-http.mjs', import.meta.url).href,
  'k6/metrics': new URL('./stubs/k6-metrics.mjs', import.meta.url).href,
};

export async function resolve(specifier, context, next) {
  if (Object.prototype.hasOwnProperty.call(stubs, specifier)) {
    return { url: stubs[specifier], format: 'module', shortCircuit: true };
  }
  return next(specifier, context);
}
