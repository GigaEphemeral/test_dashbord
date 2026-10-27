const API_BASE = '/lab-api'
const TOKEN_HEADER = 'X-Lab-Token'

export class ApiError extends Error {
  constructor(message, status, code) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function toApiError(response) {
  let payload = null
  try {
    payload = await response.json()
  } catch {
    // Non-JSON body (proxy error page, empty response).
  }
  return new ApiError(payload?.message || `HTTP ${response.status}`, response.status, payload?.code)
}

/** One rendering of an ApiError for every page of the console. */
export function describeError(err) {
  if (err instanceof ApiError) {
    if (err.status === 401) return '401 未授权：X-Lab-Token 不正确，修改令牌后重试。'
    if (err.status === 403) return `403 禁止访问：${err.message}`
    if (err.status === 404) return `404 未找到：${err.message}`
    if (err.status === 409) return `409 状态冲突：${err.message}`
    if (err.status === 502) return `502 上游异常：${err.message}`
    if (err.status === 503) return `503 服务不可用：${err.message}`
    return `${err.status}：${err.message}`
  }
  return err?.message || '请求失败，请检查网络或控制器状态。'
}

function authHeaders(token) {
  return { [TOKEN_HEADER]: token }
}

async function readJson(response) {
  if (!response.ok) {
    throw await toApiError(response)
  }
  const payload = await response.json()
  return payload.data ?? null
}

async function getJson(path, token) {
  return readJson(await fetch(`${API_BASE}${path}`, { headers: authHeaders(token) }))
}

async function postJson(path, token, body) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { ...authHeaders(token), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined
  })
  return readJson(response)
}

export async function fetchContainers(token) {
  const response = await fetch(`${API_BASE}/containers`, { headers: authHeaders(token) })
  if (!response.ok) {
    throw await toApiError(response)
  }
  const payload = await response.json()
  return payload.data ?? []
}

export async function fetchLogs(token, container, tail) {
  const query = new URLSearchParams({ tail: String(tail) })
  const response = await fetch(
    `${API_BASE}/logs/${encodeURIComponent(container)}?${query.toString()}`,
    { headers: authHeaders(token) }
  )
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.text()
}

export async function fetchHealth() {
  const response = await fetch(`${API_BASE}/health`)
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Scenario list for the wizard ({ id, name, description, stepCount, durationHintSeconds }). */
export function fetchGuideScenarios(token) {
  return getJson('/lab/guide/scenarios', token)
}

export function fetchGuideState(token) {
  return getJson('/lab/guide/state', token)
}

/** Loads a scenario without executing anything; the wizard then drives step. */
export function startGuide(token, scenarioId) {
  return postJson('/lab/guide/start', token, { scenarioId })
}

/**
 * Guide transitions without a body. `skip` moves past the current event without
 * injecting the fault; `reset` is the escape hatch that works in every status.
 */
export function runGuideAction(token, action) {
  return postJson(`/lab/guide/${action}`, token)
}

export function fetchTrafficState(token) {
  return getJson('/lab/traffic/state', token)
}
/** Start/stop the profile-gated jl-traffic k6 container (background load). */
export function runTrafficAction(token, action) {
  return postJson(`/lab/traffic/${action}`, token)
}

/** Tracked chaos actions ({ id, target, action, ttlSeconds, status, ... }). */
export function fetchActiveFaults(token) {
  return getJson('/chaos/actions', token)
}

/** Revert every tracked fault without clearing the wizard cursor. */
export function stopAllFaults(token) {
  return postJson('/chaos/stop-all', token)
}

// ---------------------------------------------------------------- thread lab
// Nginx never proxies the app's /lab/** paths; the controller's narrow proxy
// (/chaos/app/{instance}/lab/thread/**) is the only route, and only the paths
// below are on its allow-list.
function threadBase(instance) {
  return `/chaos/app/${encodeURIComponent(instance)}/lab/thread`
}

/** Active thread faults of one instance ({ mode, hold, state, remainingTtlMillis, ... }). */
export function fetchThreadState(token, instance) {
  return getJson(`${threadBase(instance)}/state`, token)
}

/** Inject one of the seven fault modes; manual hold never auto-reverts. */
export function startThreadFault(token, instance, payload) {
  return postJson(`${threadBase(instance)}/fault`, token, payload)
}

/** action is stop | pause | resume; pause freezes the remaining TTL. */
export function runThreadFaultAction(token, instance, mode, action) {
  return postJson(`${threadBase(instance)}/fault/${encodeURIComponent(mode)}/${action}`, token)
}

export function stopAllThreadFaults(token, instance) {
  return postJson(`${threadBase(instance)}/stop-all`, token)
}

/** Real JVM six-state view of the single demo thread. */
export function fetchThreadLifecycle(token, instance) {
  return getJson(`${threadBase(instance)}/lifecycle`, token)
}

/** action is start | step | freeze | resume | reset. */
export function runThreadLifecycleAction(token, instance, action) {
  return postJson(`${threadBase(instance)}/lifecycle/${action}`, token)
}

/** On-demand jcmd snapshot bundle; a failed capture still answers 200 with captured=false. */
export function captureThreadSnapshot(token, instance) {
  return postJson(`${threadBase(instance)}/snapshot`, token)
}
