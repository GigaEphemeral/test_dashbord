/**
 * Stub of "k6/http": scripts canned responses and records every request
 * plus the status lists passed to http.expectedStatuses(...).
 */
export const state = { requests: [], responses: [], expectedStatusesCalls: [] };

export function script(responses) {
  state.responses.push(...responses);
}

export function reset() {
  state.requests.length = 0;
  state.responses.length = 0;
  state.expectedStatusesCalls.length = 0;
}

function handle(method, url, body, params) {
  if (state.responses.length === 0) {
    throw new Error(`[k6-http stub] unexpected extra request: ${method} ${url}`);
  }
  const scripted = state.responses.shift();
  let parsedBody = null;
  if (body !== null && body !== undefined) {
    parsedBody = typeof body === 'string' ? JSON.parse(body) : body;
  }
  state.requests.push({
    method,
    url,
    body: parsedBody,
    params,
  });
  const status = scripted.status === undefined ? 0 : scripted.status;
  return {
    status,
    error: scripted.error === undefined ? (status === 0 ? 'dial error' : '') : scripted.error,
    body: scripted.text === undefined ? undefined : scripted.text,
    json() {
      if (scripted.body === undefined) {
        throw new Error('no body');
      }
      return scripted.body;
    },
  };
}

export default {
  expectedStatuses(...statuses) {
    state.expectedStatusesCalls.push([...statuses]);
    return () => true;
  },

  request(method, url, body, params) {
    return handle(method, url, body, params);
  },

  get(url, params) {
    return handle('GET', url, null, params);
  },

  post(url, body, params) {
    return handle('POST', url, body, params);
  },
};
