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

function authHeaders(token) {
  return { [TOKEN_HEADER]: token }
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
