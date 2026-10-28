const API_BASE = '/lab-api'
const TOKEN_HEADER = 'X-Lab-Token'

export class ApiError extends Error {
  constructor(message, status, code, data = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    // Failure envelopes may still carry evidence (409/40903 keeps the whole
    // pre-check report in data); callers must not have to re-request it.
    this.data = data
  }
}

async function toApiError(response) {
  let payload = null
  try {
    payload = await response.json()
  } catch {
    // Non-JSON body (proxy error page, empty response).
  }
  return new ApiError(
    payload?.message || `HTTP ${response.status}`,
    response.status,
    payload?.code,
    payload?.data ?? null
  )
}

/** One rendering of an ApiError for every page of the console. */
export function describeError(err) {
  if (err instanceof ApiError) {
    const code = err.code ? `（${err.code}）` : ''
    if (err.status === 401) return '401 未授权：X-Lab-Token 不正确，修改令牌后重试。'
    if (err.status === 403) return `403 禁止访问${code}：${err.message}`
    if (err.status === 404) return `404 未找到${code}：${err.message}`
    if (err.status === 409) return `409 状态冲突${code}：${err.message}`
    if (err.status === 502) return `502 上游异常${code}：${err.message}`
    if (err.status === 503) return `503 服务不可用${code}：${err.message}`
    return `${err.status}${code}：${err.message}`
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

async function postJson(path, token, body, extraHeaders = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: {
      ...authHeaders(token),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...extraHeaders
    },
    body: body ? JSON.stringify(body) : undefined
  })
  return readJson(response)
}

/** Same as postJson but keeps the HTTP status (202 = pending confirmation). */
async function postJsonDetailed(path, token, body, extraHeaders = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: {
      ...authHeaders(token),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...extraHeaders
    },
    body: body ? JSON.stringify(body) : undefined
  })
  const data = await readJson(response)
  return { status: response.status, data }
}

/** Omit empty filters so the controller applies its own defaults. */
function withQuery(path, params = {}) {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    query.set(key, String(value))
  }
  const text = query.toString()
  return text ? `${path}?${text}` : path
}

function idempotencyHeaders(key) {
  return key ? { 'Idempotency-Key': key } : {}
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

/**
 * Read-only bench scenario catalog: manifest version, run/bench/ui views and the
 * per-scenario fields (script, requires, writesData, thresholdsExpected, env,
 * params defaults, scriptExists). A missing/invalid manifest is HTTP 503/50303,
 * never an empty catalog.
 */
export function fetchBenchScenarios(token) {
  return getJson('/lab/bench/scenarios', token)
}

/** Tracked chaos actions ({ id, target, action, ttlSeconds, status, ... }). */
export function fetchActiveFaults(token) {
  return getJson('/chaos/actions', token)
}

/**
 * Revert every tracked fault without clearing the wizard cursor. The reply is
 * a StopAllResult (200) with per-layer outcomes, or an OperationView (202) when
 * the blocking budget ran out; keep the status so callers can poll.
 */
export function stopAllFaults(token, idempotencyKey) {
  return postJsonDetailed('/chaos/stop-all', token, undefined, idempotencyHeaders(idempotencyKey))
}

/** Network-fault layer ({ enabled, allowedProxies, proxies:[{name,present,enabled,toxics}] }). */
export function fetchToxiproxyState(token) {
  return getJson('/chaos/toxiproxy/state', token)
}

/** One tracked container action; the reply is an operation or a chaos action view. */
export function applyChaosAction(token, instance, action, ttlSeconds, idempotencyKey) {
  return postJsonDetailed(
    `/chaos/instances/${encodeURIComponent(instance)}/actions`,
    token,
    { action, ttlSeconds },
    idempotencyHeaders(idempotencyKey)
  )
}

// ---------------------------------------------------------------- run ledger
// Result-wrapped payloads: runs, operations, pre-check reports and metric
// windows. Read helpers only name catalog keys; PromQL never leaves the server.

export function fetchRuns(token, { scenario, status, limit, offset } = {}) {
  return getJson(withQuery('/lab/runs', { scenario, status, limit, offset }), token)
}

/** Creates a DRAFT run; params is a JSON object (credentials are rejected server-side). */
export function createRun(token, { scenario, params }) {
  return postJson('/lab/runs', token, { scenario, params })
}

export function fetchRun(token, runId) {
  return getJson(`/lab/runs/${encodeURIComponent(runId)}`, token)
}

/** Same report the start gate uses; a BLOCKED verdict is still HTTP 200. */
export function fetchRunPrecheck(token, runId) {
  return getJson(`/lab/runs/${encodeURIComponent(runId)}/precheck`, token)
}

/** Window defaults to [run start - grace, run end + grace] on the controller. */
export function fetchRunMetrics(token, runId, { keys, phase } = {}) {
  return getJson(
    withQuery(`/lab/runs/${encodeURIComponent(runId)}/metrics`, { keys, phase }),
    token
  )
}

/** Realtime aggregation; from/to are required ISO-8601 or epoch values. */
export function fetchWindowMetrics(token, { from, to, keys, phase } = {}) {
  return getJson(withQuery('/lab/metrics/window', { from, to, keys, phase }), token)
}

export function fetchOperations(token, { runId, status, limit, offset } = {}) {
  return getJson(withQuery('/lab/operations', { runId, status, limit, offset }), token)
}

export function fetchOperation(token, operationId) {
  return getJson(`/lab/operations/${encodeURIComponent(operationId)}`, token)
}

/**
 * B2-04a/c: close an UNKNOWN operation as RESTORED or FAILED. Repeating the
 * same verdict is 200, the other verdict is 409/40905, a non-UNKNOWN operation
 * is 409 and a missing one is 404; note is optional (<=512 chars).
 */
export function closeOperation(token, operationId, status, note) {
  const body = { status }
  if (note) body.note = note
  return postJson(`/lab/operations/${encodeURIComponent(operationId)}/close`, token, body)
}

/** 200 = settled inside the sync budget, 202 = PENDING_CONFIRMATION. */
export function startRun(token, runId, idempotencyKey) {
  return postJsonDetailed(
    `/lab/runs/${encodeURIComponent(runId)}/start`,
    token,
    undefined,
    idempotencyHeaders(idempotencyKey)
  )
}

export function stopRun(token, runId, idempotencyKey) {
  return postJsonDetailed(
    `/lab/runs/${encodeURIComponent(runId)}/stop`,
    token,
    undefined,
    idempotencyHeaders(idempotencyKey)
  )
}

// ---------------------------------------------------------------- k6 execution
// Read-only view of the k6 container a run start launched. An absent container
// is a normal 200 with status=ABSENT (the console polls before launch), so only
// the report download has meaningful 404/413 failures.

export function fetchK6State(token, runId) {
  return getJson(`/lab/runs/${encodeURIComponent(runId)}/k6`, token)
}

/** Bounded tail; the controller clamps tail/maxBytes instead of rejecting them. */
export function fetchK6Logs(token, runId, { tail, maxBytes } = {}) {
  return getJson(
    withQuery(`/lab/runs/${encodeURIComponent(runId)}/k6/logs`, { tail, maxBytes }),
    token
  )
}

/**
 * Raw summary.json as an attachment. A missing report is 404 and an oversized
 * one is 413; both keep their ApiError status/code so the card can explain why
 * the download is unavailable without re-requesting the state.
 */
export async function downloadK6Report(token, runId) {
  const response = await fetch(
    `${API_BASE}/lab/runs/${encodeURIComponent(runId)}/k6/report`,
    { headers: authHeaders(token) }
  )
  if (!response.ok) {
    throw await toApiError(response)
  }
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
  let filename = `k6-summary-${runId}.json`
  if (match) {
    try {
      filename = decodeURIComponent(match[1])
    } catch {
      filename = match[1]
    }
  }
  return { blob, filename, bytes: blob.size }
}

/** Always answerable (also while DEGRADED): the diagnosis surface for 503/50302. */
export function fetchLedgerStatus(token) {
  return getJson('/lab/ledger/status', token)
}

/** UNKNOWN runs and unconfirmed operations after a controller restart. */
export function fetchLedgerRecovery(token) {
  return getJson('/lab/ledger/recovery', token)
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
