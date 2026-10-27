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

/** Network-fault layer ({ enabled, allowedProxies, proxies:[{name,present,enabled,toxics}] }). */
export function fetchToxiproxyState(token) {
  return getJson('/chaos/toxiproxy/state', token)
}

// ---------------------------------------------------------------- prometheus
// Nginx proxies /prom/ to Prometheus (same origin, no CORS), so the animation
// views read metrics through the console's own origin instead of the app's
// internal /lab/cache/** endpoints, which are not reachable from the browser.
const PROM_BASE = '/prom/api/v1'

async function readProm(response) {
  let payload = null
  try {
    payload = await response.json()
  } catch {
    // Non-JSON body: the proxy answered with an HTML error page.
  }
  if (!response.ok || payload?.status !== 'success') {
    throw new ApiError(payload?.error || `HTTP ${response.status}`, response.status, payload?.errorType)
  }
  return payload.data?.result ?? []
}

/** Instant vector for one PromQL expression; [] means the series is absent. */
export async function queryProm(expr) {
  const query = new URLSearchParams({ query: expr })
  return readProm(await fetch(`${PROM_BASE}/query?${query.toString()}`))
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

// ---------------------------------------------------------------- cache lab
// Same narrow controller hop, second explicit allow-list: state/preheat reads,
// mode/seed/preheat writes. The app's /lab/cache/** is never reachable directly.
function cacheBase(instance) {
  return `/chaos/app/${encodeURIComponent(instance)}/lab/cache`
}

/** Defense mode, live strategy parameters, dataset and L1/L2 key counts. */
export function fetchCacheState(token, instance) {
  return getJson(`${cacheBase(instance)}/state`, token)
}

/** One of off | null-cache | bloom | mutex | logical-expire | ttl-jitter | l1-cache | db-fallback. */
export function updateCacheMode(token, instance, mode) {
  return postJson(`${cacheBase(instance)}/mode`, token, { mode })
}

/** Idempotent 1000-product seed; can take seconds and writes MySQL + Redis. */
export function seedCache(token, instance) {
  return postJson(`${cacheBase(instance)}/seed`, token)
}

/** Preheat snapshot: enabled flags, hot list, tracker size and the last run. */
export function fetchCachePreheat(token, instance) {
  return getJson(`${cacheBase(instance)}/preheat`, token)
}

/** Runs one manual preheat synchronously and returns the post-run snapshot. */
export function runCachePreheat(token, instance) {
  return postJson(`${cacheBase(instance)}/preheat`, token)
}
