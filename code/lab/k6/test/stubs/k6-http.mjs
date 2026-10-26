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

export default {
  expectedStatuses(...statuses) {
    state.expectedStatusesCalls.push([...statuses]);
    return () => true;
  },

  request(method, url, body, params) {
    if (state.responses.length === 0) {
      throw new Error(`[k6-http stub] unexpected extra request: ${method} ${url}`);
    }
    const scripted = state.responses.shift();
    state.requests.push({
      method,
      url,
      body: body === null || body === undefined ? null : JSON.parse(body),
      params,
    });
    const status = scripted.status === undefined ? 0 : scripted.status;
    return {
      status,
      error: scripted.error === undefined ? (status === 0 ? 'dial error' : '') : scripted.error,
      json() {
        if (scripted.body === undefined) {
          throw new Error('no body');
        }
        return scripted.body;
      },
    };
  },
};
