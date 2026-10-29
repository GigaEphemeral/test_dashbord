<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import MetricCards from './MetricCards.vue'
import {
  archiveRun,
  closeOperation,
  createRun,
  deleteRun,
  describeError,
  downloadK6Report,
  fetchBenchScenarios,
  fetchK6Logs,
  fetchK6State,
  fetchLedgerStatus,
  fetchOperation,
  fetchOperations,
  fetchRun,
  fetchRunMetrics,
  fetchRunPrecheck,
  fetchRuns,
  startRun,
  stopRun,
  unarchiveRun
} from '../api'
import { formatBytes, formatMillis, formatPercent, formatRate } from '../labModel'

/**
 * Run ledger: list / create / detail (steps, env, params, checks), pre-check,
 * environment operations and metric windows. The selected run is remembered in
 * localStorage so a browser refresh returns to the same audit trail.
 *
 * The controller is the source of truth for operation state: 202 means
 * PENDING_CONFIRMATION and this page polls GET /lab/operations/{id}; a 409 is
 * rendered with its evidence (40903 carries the whole pre-check report, 40902
 * names the holder run in the message).
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 }
})

const STATUSES = [
  'DRAFT', 'VALIDATING', 'READY', 'RUNNING', 'STOPPING', 'VERIFYING',
  'COMPLETED', 'FAILED', 'INVALID', 'ABORTED', 'UNKNOWN'
]
const RUN_STORAGE_KEY = 'ha-lab-selected-run'
const PAGE_SIZE = Number(import.meta.env.VITE_LAB_LEDGER_PAGE_SIZE || 20)
const OPERATIONS_PAGE_SIZE = Number(import.meta.env.VITE_LAB_OPERATIONS_PAGE_SIZE || 50)
const OPERATION_POLL_INTERVAL_MS = Number(import.meta.env.VITE_LAB_OPERATION_POLL_INTERVAL_MS || 1000)
const OPERATION_POLL_TIMEOUT_MS = Number(import.meta.env.VITE_LAB_OPERATION_POLL_TIMEOUT_MS || 30000)
const DEFAULT_RUN_METRIC_KEYS = [
  'http.rps',
  'http.latency.p95',
  'http.failure.ratio',
  'jvm.heap.used',
  'jvm.gc.pause.max',
  'jvm.threads.live',
  'hikari.active',
  'cache.l1.hit.ratio',
  'cache.l2.keys',
  'business.orders.created',
  'business.stock.oversold',
  'mysql.cluster.probe.up',
  // Never wired by Micrometer 1.15: the ledger must show NO_DATA/COLLECTING,
  // never a synthetic 0. Kept in the default set as a quality-contract probe.
  'executor.rejected'
]
const RUN_METRIC_KEYS = (import.meta.env.VITE_LAB_RUN_METRIC_KEYS || DEFAULT_RUN_METRIC_KEYS.join(','))
  .split(',')
  .map((key) => key.trim())
  .filter(Boolean)
// B3-00e: a hard delete is only offered once the run can no longer write.
const DELETE_LOCKED_STATUSES = ['RUNNING', 'STOPPING', 'VERIFYING', 'VALIDATING', 'READY']

const filters = reactive({ scenario: '', status: '' })
const nameQuery = ref('')
const showArchived = ref(false)
const runs = ref([])
const total = ref(0)
const offset = ref(0)
const loadingList = ref(false)
const listError = ref('')

const ledgerStatus = ref(null)
const ledgerError = ref('')
const statusError = ref('')
const degraded = ref(false)

const selectedRunId = ref(localStorage.getItem(RUN_STORAGE_KEY) || '')
const detail = ref(null)
const operations = ref([])
const precheck = ref(null)
const runMetrics = ref(null)
const loadingDetail = ref(false)
const detailError = ref('')
const operationsError = ref('')
const precheckError = ref('')
const metricsError = ref('')
const metricsLoading = ref(false)

const actionBusy = ref('')
const operation = ref(null)
const actionError = ref('')
const conflict = ref(null)
const pendingNote = ref('')

const createForm = reactive({ scenario: '', paramsText: '{}', name: '' })
const createBusy = ref(false)
const createError = ref('')
const createResult = ref(null)

// B3-00e: archive/delete are row-level actions; their failures reuse the list
// alert surface, never a modal-local error. Delete is guarded by a confirmation
// modal because it cascades through every child table and the k6 report dir.
const archiveBusy = ref('')
const deleteTarget = ref(null)
const deleteBusy = ref(false)

// Report compare: at most two runs, each fetched with the same fixed key set as
// the single-run metric card. Missing values stay 无数据 (never a synthetic 0).
const compareIds = ref([])
const compareDataA = ref(null)
const compareDataB = ref(null)
const compareLoading = ref(false)
const compareError = ref('')
let compareSeq = 0

// B2-04c: UNKNOWN operations can only be closed by an operator verdict. The
// optional note is shared by every row (one input, no new dependency); errors
// stay inside the operations card instead of the global error banner.
const closeNote = ref('')
const closeBusy = ref('')
const closeError = ref('')
const closeInfo = ref('')

// k6 execution card. Every failure stays local to this card: a 503 here must
// never blank the run detail, pre-check, operations or metric windows.
const k6 = ref(null)
const k6Error = ref('')
const k6Unavailable = ref(false)
const k6Logs = ref(null)
const k6LogError = ref('')
const k6LogLoading = ref(false)
const k6ReportError = ref('')
const k6ReportBusy = ref(false)
const benchCatalog = ref(null)
const benchCatalogError = ref('')
let benchCatalogLoaded = false

let listSeq = 0
let detailSeq = 0
let pollSeq = 0
let k6Seq = 0
let initialised = false

const selectedRun = computed(() => detail.value?.run || null)
const runStatus = computed(() => selectedRun.value?.status || '')
const currentPage = computed(() => {
  // A degraded ledger must not look like an empty table: no 0-count anywhere.
  if (degraded.value) return 'DEGRADED'
  const from = total.value === 0 ? 0 : offset.value + 1
  const to = Math.min(offset.value + runs.value.length, total.value)
  return `${from}–${to} / ${total.value}`
})
// Quick filter is client-side (name / runId of the current page). Archived rows
// stay hidden unless the showArchived toggle asked the backend for them.
const visibleRuns = computed(() => {
  const query = nameQuery.value.trim().toLowerCase()
  return runs.value.filter((run) => {
    if (run.archived && !showArchived.value) return false
    if (!query) return true
    const name = String(run.name || '').toLowerCase()
    return name.includes(query) || String(run.runId || '').toLowerCase().includes(query)
  })
})
const compareA = computed(() => compareIds.value[0] || '')
const compareB = computed(() => compareIds.value[1] || '')
const compareRows = computed(() => RUN_METRIC_KEYS.map((key) => buildCompareRow(key)))
const detailNodes = computed(() => {
  const nodes = selectedRun.value?.nodes
  if (Array.isArray(nodes)) return nodes.length ? nodes.join(' / ') : '全部'
  return nodes || '全部'
})
const detailTraffic = computed(() => {
  const run = selectedRun.value
  const mode = k6.value?.trafficMode || run?.trafficMode || ''
  const target = k6.value?.targetInstance || run?.targetInstance || ''
  if (!mode && !target) return '—'
  return `${mode || '—'} / ${target || '—'}`
})
const detailLock = computed(() => {
  const run = selectedRun.value
  if (run?.lockHolder || run?.lock) return run.lockHolder || run.lock
  return conflict.value?.holder ? `held by run ${conflict.value.holder}` : '—'
})
const startEnabled = computed(() => (
  runStatus.value === 'DRAFT'
  && !actionBusy.value
  // A blocking pre-check cannot be force-skipped; keep the button disabled
  // until the environment is fixed and the report is re-read.
  && precheck.value?.verdict !== 'BLOCKED'
))
const stopEnabled = computed(() => ['RUNNING', 'UNKNOWN'].includes(runStatus.value) && !actionBusy.value)
const metricsOverall = computed(() => runMetrics.value?.quality || '')
const metricsReason = computed(() => runMetrics.value?.reason || '')

// k6 status vocabulary is the backend's: ABSENT/CREATED/RUNNING/EXITED.
// ABSENT is a normal 200 (never started or already cleaned up), not an error.
const k6Status = computed(() => k6.value?.status || '')
const k6StatusLabel = computed(() => {
  if (!k6Status.value) return '—'
  if (k6Status.value === 'ABSENT') return '未启动/已清理'
  return k6Status.value
})
const k6Pollable = computed(() => (
  ['RUNNING', 'STOPPING', 'VERIFYING', 'UNKNOWN'].includes(runStatus.value)
  || ['RUNNING', 'CREATED'].includes(k6Status.value)
))
const k6LogText = computed(() => {
  if (k6Logs.value) return k6Logs.value.text || '（k6 暂无输出）'
  return k6LogError.value || (k6LogLoading.value ? '读取中…' : '')
})
const k6LogTruncated = computed(() => Boolean(k6Logs.value?.truncated))
const uiScenarioNames = computed(() => benchCatalog.value?.views?.ui || [])

function k6Tone(status, exitCode) {
  if (status === 'RUNNING') return 'run-running'
  if (status === 'CREATED') return 'run-pending'
  if (status === 'EXITED') return exitCode === 0 ? 'run-completed' : 'run-failed'
  return 'muted'
}

function byteLabel(bytes) {
  if (!Number.isFinite(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function statusTone(status) {
  const value = String(status || '').toLowerCase()
  if (['running', 'ready'].includes(value)) return 'run-running'
  if (['validating', 'stopping', 'verifying'].includes(value)) return 'run-pending'
  if (value === 'completed') return 'run-completed'
  if (value === 'failed') return 'run-failed'
  if (value === 'invalid') return 'run-invalid'
  if (value === 'aborted') return 'run-aborted'
  if (value === 'unknown') return 'run-unknown'
  if (value === 'draft') return 'run-draft'
  return 'muted'
}

function precheckTone(verdict) {
  if (verdict === 'PASS') return 'ok'
  if (verdict === 'WARN') return 'warn'
  if (verdict === 'BLOCKED') return 'down'
  return 'muted'
}

function timeLabel(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false })
}

function pretty(value) {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function isLedgerUnavailable(err) {
  return err?.status === 503 || err?.code === '50302'
}

function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `lab-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function extractHolder(message) {
  const match = /held by run ([^\s,;]+)/i.exec(message || '')
  return match ? match[1] : ''
}

// ------------------------------------------------------------------ ledger
async function loadStatus() {
  try {
    ledgerStatus.value = await fetchLedgerStatus(props.token)
    statusError.value = ''
  } catch (err) {
    ledgerStatus.value = null
    statusError.value = describeError(err)
  }
}

async function loadRuns() {
  if (!props.token) return
  const seq = ++listSeq
  loadingList.value = true
  listError.value = ''
  try {
    const page = await fetchRuns(props.token, {
      scenario: filters.scenario.trim(),
      status: filters.status,
      includeArchived: showArchived.value,
      limit: PAGE_SIZE,
      offset: offset.value
    })
    if (seq !== listSeq) return
    runs.value = page?.items || []
    total.value = page?.total || 0
    degraded.value = false
    ledgerError.value = ''
  } catch (err) {
    if (seq !== listSeq) return
    runs.value = []
    total.value = 0
    if (isLedgerUnavailable(err)) {
      degraded.value = true
      ledgerError.value = describeError(err)
      await loadStatus()
    } else {
      listError.value = describeError(err)
    }
  } finally {
    if (seq === listSeq) loadingList.value = false
  }
}

async function loadDetail(runId) {
  const seq = ++detailSeq
  loadingDetail.value = true
  detailError.value = ''
  try {
    const value = await fetchRun(props.token, runId)
    if (seq !== detailSeq) return
    detail.value = value
  } catch (err) {
    if (seq !== detailSeq) return
    detail.value = null
    if (err?.status === 404) {
      selectedRunId.value = ''
      localStorage.removeItem(RUN_STORAGE_KEY)
      detailError.value = `run ${runId} 不存在（可能已被保留策略清理）。`
    } else if (isLedgerUnavailable(err)) {
      degraded.value = true
      ledgerError.value = describeError(err)
      await loadStatus()
    } else {
      detailError.value = describeError(err)
    }
  } finally {
    if (seq === detailSeq) loadingDetail.value = false
  }
}

async function loadOperations(runId) {
  operationsError.value = ''
  try {
    const page = await fetchOperations(props.token, { runId, limit: OPERATIONS_PAGE_SIZE })
    operations.value = page?.items || []
  } catch (err) {
    operations.value = []
    operationsError.value = describeError(err)
    if (isLedgerUnavailable(err)) {
      degraded.value = true
      ledgerError.value = describeError(err)
    }
  }
}

/**
 * Close an UNKNOWN operation with an operator verdict. Success refreshes both
 * the operation list and the run detail (the fault timeline may gain rows);
 * 400/404/409 stay in this card with their code from describeError.
 */
async function closeUnknown(item, status) {
  if (!item?.operationId) return
  closeBusy.value = `${item.operationId}:${status}`
  closeError.value = ''
  closeInfo.value = ''
  try {
    const note = closeNote.value.trim()
    const updated = await closeOperation(props.token, item.operationId, status, note || undefined)
    closeInfo.value = `operation ${item.operationId} 已关闭为 ${updated?.status || status}。`
    const runId = selectedRunId.value
    if (runId) {
      await Promise.all([loadDetail(runId), loadOperations(runId)])
    }
  } catch (err) {
    closeError.value = describeError(err)
  } finally {
    closeBusy.value = ''
  }
}

async function loadPrecheck(runId) {
  precheckError.value = ''
  try {
    precheck.value = await fetchRunPrecheck(props.token, runId)
  } catch (err) {
    precheck.value = null
    precheckError.value = describeError(err)
    if (isLedgerUnavailable(err)) {
      degraded.value = true
      ledgerError.value = describeError(err)
    }
  }
}

async function loadRunMetrics(runId) {
  metricsLoading.value = true
  metricsError.value = ''
  try {
    runMetrics.value = await fetchRunMetrics(props.token, runId, {
      keys: RUN_METRIC_KEYS.join(','),
      phase: 'run'
    })
  } catch (err) {
    runMetrics.value = null
    metricsError.value = describeError(err)
    if (isLedgerUnavailable(err)) {
      degraded.value = true
      ledgerError.value = describeError(err)
    }
  } finally {
    metricsLoading.value = false
  }
}

function isK6Unavailable(err) {
  return err?.status === 503 || err?.code === '50303'
}

function resetK6() {
  k6Seq += 1
  k6.value = null
  k6Error.value = ''
  k6Unavailable.value = false
  k6Logs.value = null
  k6LogError.value = ''
  k6LogLoading.value = false
  k6ReportError.value = ''
}

/**
 * State poll for the selected run. Terminal runs (COMPLETED/FAILED/...) are
 * read once and then skipped: the container cannot change after the watchdog
 * settled the run, so the 5s sync must not keep requesting it.
 */
async function loadK6(runId, { force = false } = {}) {
  if (!runId) return
  const terminal = ['COMPLETED', 'FAILED', 'ABORTED', 'INVALID'].includes(runStatus.value)
  const settled = ['EXITED', 'ABSENT'].includes(k6Status.value)
  // Terminal run: one more read is still needed when the last state was
  // RUNNING (the watchdog settles the run just before the next 5s poll), then
  // stop polling. A degraded k6 layer is not retried on every tick either.
  if (!force && terminal && (settled || k6Unavailable.value)) return
  // Non-terminal run with a state that cannot change (DRAFT + ABSENT).
  if (!force && !terminal && k6.value && !k6Pollable.value) return
  const seq = ++k6Seq
  try {
    const value = await fetchK6State(props.token, runId)
    if (seq !== k6Seq || runId !== selectedRunId.value) return
    k6.value = value
    k6Error.value = ''
    k6Unavailable.value = false
  } catch (err) {
    if (seq !== k6Seq || runId !== selectedRunId.value) return
    k6.value = null
    k6Unavailable.value = isK6Unavailable(err)
    k6Error.value = k6Unavailable.value
      ? `k6 执行层不可用：${describeError(err)}`
      : describeError(err)
  }
}

async function loadK6Logs() {
  const runId = selectedRunId.value
  if (!runId) return
  k6LogLoading.value = true
  k6LogError.value = ''
  try {
    const value = await fetchK6Logs(props.token, runId, { tail: 100 })
    if (runId !== selectedRunId.value) return
    k6Logs.value = value
  } catch (err) {
    if (runId !== selectedRunId.value) return
    k6Logs.value = null
    if (err?.status === 404) {
      k6LogError.value = 'k6 容器不存在（尚未启动或已清理），暂无日志。'
    } else if (isK6Unavailable(err)) {
      k6LogError.value = `k6 执行层不可用：${describeError(err)}`
    } else {
      k6LogError.value = describeError(err)
    }
  } finally {
    k6LogLoading.value = false
  }
}

async function downloadReport() {
  const runId = selectedRunId.value
  if (!runId || !k6.value?.reportAvailable) return
  k6ReportBusy.value = true
  k6ReportError.value = ''
  try {
    const { blob, filename } = await downloadK6Report(props.token, runId)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (err) {
    if (err?.status === 413) {
      k6ReportError.value = `报告超过下载上限（413）：${err.message}`
    } else if (err?.status === 404) {
      k6ReportError.value = '报告尚未生成：k6 退出时才会写出 summary.json。'
    } else if (isK6Unavailable(err)) {
      k6ReportError.value = `k6 执行层不可用：${describeError(err)}`
    } else {
      k6ReportError.value = describeError(err)
    }
  } finally {
    k6ReportBusy.value = false
  }
}

/**
 * The create form's scenario hint comes from the same manifest the start gate
 * uses. A catalog failure only degrades the hint (plain text input still works):
 * creation writes a DRAFT and never needs the catalog.
 */
async function loadBenchScenarios() {
  if (benchCatalogLoaded) return
  try {
    benchCatalog.value = await fetchBenchScenarios(props.token)
    benchCatalogLoaded = true
    benchCatalogError.value = ''
  } catch (err) {
    benchCatalog.value = null
    benchCatalogError.value = isK6Unavailable(err)
      ? `场景目录不可用（503/50303），仍可手动输入 scenario：${describeError(err)}`
      : `场景目录读取失败，仍可手动输入 scenario：${describeError(err)}`
  }
}

async function selectRun(runId) {
  if (!runId) return
  selectedRunId.value = runId
  localStorage.setItem(RUN_STORAGE_KEY, runId)
  conflict.value = null
  pendingNote.value = ''
  actionError.value = ''
  closeError.value = ''
  closeInfo.value = ''
  operation.value = null
  resetK6()
  await Promise.all([
    loadDetail(runId),
    loadOperations(runId),
    loadK6(runId),
    loadBenchScenarios()
  ])
  await Promise.all([loadPrecheck(runId), loadRunMetrics(runId)])
}


async function restoreSelection() {
  await loadStatus()
  await loadRuns()
  await loadBenchScenarios()
  if (selectedRunId.value) {
    await selectRun(selectedRunId.value)
  } else if (runs.value.length) {
    await selectRun(runs.value[0].runId)
  }
}

async function refreshSelected() {
  const runId = selectedRunId.value
  if (!runId) return
  await Promise.all([loadDetail(runId), loadOperations(runId)])
  await loadK6(runId)
}

function applyFilters() {
  offset.value = 0
  loadRuns()
}

function resetFilters() {
  filters.scenario = ''
  filters.status = ''
  nameQuery.value = ''
  showArchived.value = false
  offset.value = 0
  loadRuns()
}

function prevPage() {
  if (offset.value <= 0) return
  offset.value = Math.max(0, offset.value - PAGE_SIZE)
  loadRuns()
}

function nextPage() {
  if (offset.value + PAGE_SIZE >= total.value) return
  offset.value += PAGE_SIZE
  loadRuns()
}

function toggleArchivedList(event) {
  showArchived.value = event.target.checked
  offset.value = 0
  loadRuns()
}

// ------------------------------------------------------- archive / delete
async function toggleArchive(run) {
  if (!run?.runId || archiveBusy.value) return
  archiveBusy.value = run.runId
  listError.value = ''
  try {
    if (run.archived) {
      await unarchiveRun(props.token, run.runId)
    } else {
      await archiveRun(props.token, run.runId)
    }
    await loadRuns()
    // Keep the detail card in sync when the affected run is the selected one.
    if (selectedRunId.value) await refreshSelected()
  } catch (err) {
    // 409 keeps the backend message (e.g. "run ... is RUNNING; stop it ...").
    listError.value = describeError(err)
  } finally {
    archiveBusy.value = ''
  }
}

function canDelete(run) {
  return !DELETE_LOCKED_STATUSES.includes(run?.status)
}

function deleteHint(run) {
  return canDelete(run) ? '删除 run（需确认）' : '先停止/关闭'
}

function askDelete(run) {
  if (!run || !canDelete(run)) return
  listError.value = ''
  deleteTarget.value = run
}

function cancelDelete() {
  if (deleteBusy.value) return
  deleteTarget.value = null
}

function clearSelection() {
  detailSeq += 1
  selectedRunId.value = ''
  localStorage.removeItem(RUN_STORAGE_KEY)
  detail.value = null
  operations.value = []
  precheck.value = null
  runMetrics.value = null
  operation.value = null
  conflict.value = null
  pendingNote.value = ''
  actionError.value = ''
  resetK6()
}

async function confirmDelete() {
  const target = deleteTarget.value
  if (!target || deleteBusy.value) return
  deleteBusy.value = true
  listError.value = ''
  try {
    await deleteRun(props.token, target.runId)
    deleteTarget.value = null
    compareIds.value = compareIds.value.filter((id) => id !== target.runId)
    if (selectedRunId.value === target.runId) clearSelection()
    await loadRuns()
    // The deleted row was the only item on the last page: step back one page.
    if (!runs.value.length && total.value > 0 && offset.value > 0) {
      offset.value = Math.max(0, offset.value - PAGE_SIZE)
      await loadRuns()
    }
  } catch (err) {
    // Close the modal so the shared list alert (with 409's backend message) shows.
    deleteTarget.value = null
    listError.value = describeError(err)
  } finally {
    deleteBusy.value = false
  }
}

// --------------------------------------------------------------- report compare
function isCompareSelected(runId) {
  return compareIds.value.includes(runId)
}

function toggleCompare(runId) {
  if (!runId) return
  if (isCompareSelected(runId)) {
    compareIds.value = compareIds.value.filter((id) => id !== runId)
    return
  }
  // Two slots: selecting a third drops the oldest selection.
  compareIds.value = [...compareIds.value, runId].slice(-2)
}

function clearCompare() {
  compareIds.value = []
}

function compareRunLabel(runId) {
  const run = runs.value.find((item) => item.runId === runId)
    || (selectedRun.value?.runId === runId ? selectedRun.value : null)
  if (!run) return runId
  return run.name ? `${run.name} · ${runId}` : runId
}

function metricForKey(data, key) {
  return (data?.metrics || []).find((metric) => metric.key === key) || null
}

function metricLast(metric) {
  // NO_DATA / COLLECTING / DEGRADED must stay 无数据, never a synthetic 0.
  if (!metric || metric.quality !== 'OK') return null
  const value = metric.series?.[0]?.stats?.last
  return Number.isFinite(value) ? value : null
}

function formatMetricValue(unit, value) {
  if (!Number.isFinite(value)) return '无数据'
  switch (unit) {
    case 'ratio':
      return formatPercent(value)
    case 'bytes':
      return formatBytes(value)
    case 's':
      return formatMillis(value)
    case 'req/s':
      return formatRate(value)
    case 'bool':
      return value >= 1 ? 'UP' : 'DOWN'
    case 'threads':
    case 'connections':
    case 'tasks':
    case 'entries':
    case 'keys':
    case 'orders':
    case 'events':
      return Math.round(value).toLocaleString('zh-CN')
    default:
      return Number.isInteger(value) ? value.toLocaleString('zh-CN') : value.toFixed(2)
  }
}

function formatMetricDelta(unit, delta) {
  if (!Number.isFinite(delta)) return '无数据'
  const sign = delta > 0 ? '+' : ''
  switch (unit) {
    case 'ratio':
      return `${sign}${(delta * 100).toFixed(2)} pp`
    case 'bytes':
      return `${sign}${(delta / (1024 * 1024)).toFixed(2)} MB`
    case 's': {
      const millis = delta * 1000
      return `${sign}${Math.abs(millis) >= 1000 ? `${(millis / 1000).toFixed(2)} s` : `${millis.toFixed(1)} ms`}`
    }
    case 'req/s':
      return `${sign}${delta.toFixed(2)} /s`
    case 'bool':
      return delta === 0 ? '0' : `${sign}${delta > 0 ? 1 : -1}`
    case 'threads':
    case 'connections':
    case 'tasks':
    case 'entries':
    case 'keys':
    case 'orders':
    case 'events':
      return `${sign}${Math.round(delta).toLocaleString('zh-CN')}`
    default:
      return `${sign}${Number.isInteger(delta) ? delta.toLocaleString('zh-CN') : delta.toFixed(2)}`
  }
}

function buildCompareRow(key) {
  const metricA = metricForKey(compareDataA.value, key)
  const metricB = metricForKey(compareDataB.value, key)
  const valueA = metricLast(metricA)
  const valueB = metricLast(metricB)
  const unit = metricA?.unit || metricB?.unit || ''
  return {
    key,
    label: metricA?.label || metricB?.label || key,
    unit,
    aText: formatMetricValue(unit, valueA),
    bText: formatMetricValue(unit, valueB),
    deltaText: valueA === null || valueB === null
      ? '无数据'
      : formatMetricDelta(unit, valueB - valueA)
  }
}

async function loadComparison() {
  const first = compareA.value
  const second = compareB.value
  const seq = ++compareSeq
  if (!first || !second) {
    compareDataA.value = null
    compareDataB.value = null
    compareError.value = ''
    compareLoading.value = false
    return
  }
  compareLoading.value = true
  compareError.value = ''
  try {
    const [dataA, dataB] = await Promise.all([
      fetchRunMetrics(props.token, first, { keys: RUN_METRIC_KEYS.join(','), phase: 'run' }),
      fetchRunMetrics(props.token, second, { keys: RUN_METRIC_KEYS.join(','), phase: 'run' })
    ])
    if (seq !== compareSeq) return
    compareDataA.value = dataA
    compareDataB.value = dataB
  } catch (err) {
    if (seq !== compareSeq) return
    compareDataA.value = null
    compareDataB.value = null
    compareError.value = describeError(err)
  } finally {
    if (seq === compareSeq) compareLoading.value = false
  }
}

watch([compareA, compareB], () => {
  loadComparison()
})

// ------------------------------------------------------------------ create
async function submitCreate() {
  createError.value = ''
  createResult.value = null
  const scenario = createForm.scenario.trim()
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(scenario)) {
    createError.value = 'scenario 必须匹配 [A-Za-z0-9][A-Za-z0-9_.-]{0,127}。'
    return
  }
  let params = {}
  const text = createForm.paramsText.trim()
  if (text) {
    try {
      params = JSON.parse(text)
    } catch (err) {
      createError.value = `params 不是合法 JSON：${err.message}`
      return
    }
    if (params === null || Array.isArray(params) || typeof params !== 'object') {
      createError.value = 'params 必须是 JSON 对象（例如 {"note":"smoke"}）。'
      return
    }
  }
  createBusy.value = true
  try {
    const name = createForm.name.trim()
    const created = await createRun(props.token, { scenario, params, name: name || undefined })
    createResult.value = created
    selectedRunId.value = created.runId
    localStorage.setItem(RUN_STORAGE_KEY, created.runId)
    filters.scenario = ''
    filters.status = ''
    nameQuery.value = ''
    offset.value = 0
    await loadRuns()
    await selectRun(created.runId)
  } catch (err) {
    createError.value = describeError(err)
    if (isLedgerUnavailable(err)) {
      degraded.value = true
      ledgerError.value = describeError(err)
      await loadStatus()
    }
  } finally {
    createBusy.value = false
  }
}

// ----------------------------------------------------------------- actions
async function pollOperation(operationId, seq) {
  if (!operationId) return null
  const deadline = Date.now() + OPERATION_POLL_TIMEOUT_MS
  let latest = null
  while (Date.now() < deadline) {
    try {
      latest = await fetchOperation(props.token, operationId)
    } catch (err) {
      if (seq === pollSeq) actionError.value = describeError(err)
      return null
    }
    if (seq !== pollSeq) return latest
    operation.value = latest
    if (['APPLIED', 'RESTORED', 'FAILED', 'UNKNOWN'].includes(latest?.status)) {
      return latest
    }
    await sleep(OPERATION_POLL_INTERVAL_MS)
  }
  if (seq === pollSeq) {
    pendingNote.value = `operation ${operationId} 仍在 PENDING_CONFIRMATION；刷新页面后可继续观察，不要重复点击。`
  }
  return latest
}

async function refreshAfterAction() {
  await loadRuns()
  const runId = selectedRunId.value
  if (!runId) return
  // start/stop changes the container state even when the detail read lags, so
  // this refresh bypasses the terminal-run short-circuit of the 5s poller.
  await Promise.all([loadDetail(runId), loadOperations(runId), loadK6(runId, { force: true })])
  await Promise.all([loadPrecheck(runId), loadRunMetrics(runId)])
}

function recordConflict(err) {
  if (err?.code === '40903') {
    conflict.value = {
      code: err.code,
      message: err.message,
      holder: extractHolder(err.message),
      precheck: err.data
    }
    if (err.data) precheck.value = err.data
  } else if (err?.status === 409) {
    conflict.value = {
      code: err.code || '409',
      message: err.message,
      holder: extractHolder(err.message),
      precheck: err.data?.verdict ? err.data : null
    }
  } else if (isLedgerUnavailable(err)) {
    degraded.value = true
    ledgerError.value = describeError(err)
    loadStatus()
  } else {
    actionError.value = describeError(err)
  }
}

async function runAction(kind) {
  const runId = selectedRunId.value
  if (!runId) return
  actionBusy.value = kind
  actionError.value = ''
  conflict.value = null
  pendingNote.value = ''
  operation.value = null
  const seq = ++pollSeq
  try {
    const key = newIdempotencyKey()
    const result = kind === 'start'
      ? await startRun(props.token, runId, key)
      : await stopRun(props.token, runId, key)
    operation.value = result.data
    if (result.status === 202 || result.data?.status === 'PENDING_CONFIRMATION') {
      await pollOperation(result.data?.operationId, seq)
    }
    await refreshAfterAction()
  } catch (err) {
    recordConflict(err)
    await refreshSelected()
  } finally {
    actionBusy.value = ''
  }
}

watch(
  () => [props.active, props.token],
  ([active, token], previous) => {
    if (!active || !token) return
    const tokenChanged = previous && previous[1] !== token
    if (!initialised || tokenChanged) {
      initialised = true
      if (tokenChanged) benchCatalogLoaded = false
      restoreSelection()
    } else {
      loadStatus()
      loadRuns()
      refreshSelected()
    }
  },
  { immediate: true }
)

watch(
  () => props.syncTick,
  () => {
    if (!props.active || !initialised) return
    loadStatus()
    loadRuns()
    refreshSelected()
  }
)

onBeforeUnmount(() => {
  pollSeq += 1
  compareSeq += 1
})
</script>

<template>
  <div class="ledger-panel" data-testid="run-ledger-page">
    <section class="card ledger-list-card">
      <div class="card-head">
        <h2>实验记录 <span class="count">{{ degraded ? '—' : total }}</span></h2>
        <div class="bench-actions">
          <span class="hint" data-testid="run-page-info">{{ currentPage }}</span>
          <button type="button" class="btn ghost" data-testid="run-refresh" :disabled="loadingList" @click="loadRuns">
            {{ loadingList ? '刷新中…' : '刷新列表' }}
          </button>
        </div>
      </div>

      <div class="ledger-filters">
        <label class="form-label">
          scenario
          <input v-model="filters.scenario" class="input" data-testid="run-filter-scenario" placeholder="精确匹配" @keyup.enter="applyFilters" />
        </label>
        <label class="form-label">
          status
          <select v-model="filters.status" class="input select" data-testid="run-filter-status">
            <option value="">全部</option>
            <option v-for="status in STATUSES" :key="status" :value="status">{{ status }}</option>
          </select>
        </label>
        <label class="form-label">
          名称 / runId（本页过滤）
          <input v-model="nameQuery" class="input" data-testid="run-list-name-filter" placeholder="输入名称或 runId 片段" />
        </label>
        <label class="archived-toggle">
          <input
            type="checkbox"
            :checked="showArchived"
            data-testid="run-list-archived-toggle"
            @change="toggleArchivedList"
          />
          显示已归档
        </label>
        <button type="button" class="btn" data-testid="run-filter-apply" :disabled="loadingList" @click="applyFilters">查询</button>
        <button type="button" class="btn ghost" data-testid="run-filter-reset" :disabled="loadingList" @click="resetFilters">重置</button>
      </div>

      <div v-if="degraded" class="ledger-degraded" data-testid="ledger-degraded">
        <h3>台账不可用 · DEGRADED</h3>
        <p>不展示“0 条记录”：先修复台账，再读列表。</p>
        <p data-testid="ledger-reason">{{ ledgerError || statusError || '台账接口返回 503。' }}</p>
        <dl class="ledger-facts">
          <dt>state</dt>
          <dd data-testid="ledger-state">{{ ledgerStatus?.state || 'NO_DATA' }}</dd>
          <dt>mode</dt>
          <dd>{{ ledgerStatus?.mode || '—' }}</dd>
          <dt>schema</dt>
          <dd>{{ ledgerStatus?.schemaVersion || '—' }}</dd>
          <dt>pendingStash</dt>
          <dd>{{ ledgerStatus?.pendingStash ?? '—' }}</dd>
          <dt>lastWriteAt</dt>
          <dd>{{ ledgerStatus?.lastWriteAt || '—' }}</dd>
          <dt>lastError</dt>
          <dd>{{ ledgerStatus?.lastError || '—' }}</dd>
        </dl>
        <p v-if="statusError" class="hint" data-testid="ledger-status-error">状态接口也不可用：{{ statusError }}</p>
      </div>

      <div v-if="listError" class="error-banner" role="alert" data-testid="run-list-error">{{ listError }}</div>

      <template v-if="!degraded">
        <div class="run-list" data-testid="run-list">
          <div class="run-row run-row-head">
            <span>对比</span>
            <span>测试名称</span>
            <span>runId</span>
            <span>scenario</span>
            <span>status</span>
            <span>归档</span>
            <span>operator</span>
            <span>sourceCommit</span>
            <span>createdAt</span>
            <span>updatedAt</span>
            <span>操作</span>
          </div>
          <div
            v-for="run in visibleRuns"
            :key="run.runId"
            class="run-row run-row-item"
            :class="{ selected: run.runId === selectedRunId }"
            :data-testid="`run-row-${run.runId}`"
            role="button"
            tabindex="0"
            @click="selectRun(run.runId)"
            @keydown.enter.prevent="selectRun(run.runId)"
            @keydown.space.prevent="selectRun(run.runId)"
          >
            <input
              type="checkbox"
              class="run-compare-check"
              :checked="isCompareSelected(run.runId)"
              :data-testid="`run-compare-toggle-${run.runId}`"
              :aria-label="`对比 run ${run.runId}`"
              @click.stop
              @keydown.stop
              @change="toggleCompare(run.runId)"
            />
            <span class="run-name-cell" :title="run.name || '—'">{{ run.name || '—' }}</span>
            <code>{{ run.runId }}</code>
            <span>{{ run.scenario }}</span>
            <span class="badge" :class="statusTone(run.status)" :data-testid="`run-status-${run.runId}`">{{ run.status }}</span>
            <span class="chip" :class="{ on: run.archived }" :data-testid="`run-archived-chip-${run.runId}`">{{ run.archived ? '已归档' : '未归档' }}</span>
            <span>{{ run.operator || '—' }}</span>
            <code class="run-commit">{{ (run.sourceCommit || '—').slice(0, 12) }}</code>
            <span>{{ timeLabel(run.createdAt) }}</span>
            <span>{{ timeLabel(run.updatedAt) }}</span>
            <span class="run-row-actions" @click.stop @keydown.stop>
              <button
                type="button"
                class="btn ghost"
                :data-testid="`run-archive-${run.runId}`"
                :disabled="Boolean(archiveBusy)"
                @click="toggleArchive(run)"
              >{{ archiveBusy === run.runId ? (run.archived ? '取消归档中…' : '归档中…') : (run.archived ? '取消归档' : '归档') }}</button>
              <button
                type="button"
                class="btn ghost danger"
                :data-testid="`run-delete-${run.runId}`"
                :disabled="!canDelete(run) || deleteBusy"
                :title="deleteHint(run)"
                @click="askDelete(run)"
              >删除</button>
              <span v-if="!canDelete(run)" class="hint run-delete-hint">先停止/关闭</span>
            </span>
          </div>
          <p v-if="!visibleRuns.length && !loadingList" class="empty" data-testid="run-list-empty">
            <template v-if="nameQuery.trim()">{{ '当前页没有匹配 name/runId 的 run。' }}</template>
            <template v-else>{{ '台账里还没有 run；用下方表单创建一个 DRAFT。' }}</template>
          </p>
        </div>
        <div class="ledger-pager">
          <button type="button" class="btn ghost" data-testid="run-page-prev" :disabled="offset === 0 || loadingList" @click="prevPage">上一页</button>
          <span class="hint">{{ currentPage }}</span>
          <button type="button" class="btn ghost" data-testid="run-page-next" :disabled="offset + PAGE_SIZE >= total || loadingList" @click="nextPage">下一页</button>
        </div>
      </template>
    </section>

    <section v-if="compareIds.length === 2" class="card ledger-compare-card" data-testid="run-compare">
      <div class="card-head">
        <h2>报告对比 <span class="count">2</span></h2>
        <div class="bench-actions">
          <span class="hint" data-testid="run-compare-status">{{ compareLoading ? '读取中…' : 'phase=run' }}</span>
          <button type="button" class="btn ghost" data-testid="run-compare-clear" @click="clearCompare">清除对比</button>
        </div>
      </div>
      <p class="hint">
        A：{{ compareRunLabel(compareA) }} · B：{{ compareRunLabel(compareB) }}
      </p>
      <p class="notice info" data-testid="run-compare-note">单次结果为演示性数据，未重复验证</p>
      <p v-if="compareError" class="error-banner" role="alert" data-testid="run-compare-error">{{ compareError }}</p>
      <table class="data-table" data-testid="run-compare-table">
        <thead>
          <tr>
            <th>指标</th>
            <th>A</th>
            <th>B</th>
            <th>差值（B − A）</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in compareRows" :key="row.key">
            <td>
              <span>{{ row.label }}</span>
              <br />
              <code class="hint">{{ row.key }}</code>
            </td>
            <td>{{ row.aText }}</td>
            <td>{{ row.bText }}</td>
            <td>{{ row.deltaText }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card ledger-create-card">
      <div class="card-head">
        <h2>新建 run</h2>
        <span class="hint">POST /lab-api/lab/runs → DRAFT</span>
      </div>
      <form class="ledger-create" @submit.prevent="submitCreate">
        <label class="form-label ledger-name-field">
          测试名称（可选）
          <input v-model="createForm.name" class="input" maxlength="128" data-testid="run-create-name" placeholder="例如 双十一压测" />
        </label>
        <label class="form-label ledger-scenario-field">
          scenario
          <input v-model="createForm.scenario" class="input" data-testid="run-create-scenario" list="run-create-scenario-options" placeholder="例如 smoke / load / spike" />
        </label>
        <datalist id="run-create-scenario-options" data-testid="run-create-scenario-options">
          <option v-for="name in uiScenarioNames" :key="name" :value="name">{{ name }}</option>
        </datalist>
        <label class="form-label ledger-params-field">
          params (JSON)
          <textarea v-model="createForm.paramsText" class="input ledger-params-input" rows="3" data-testid="run-create-params" spellcheck="false"></textarea>
        </label>
        <button type="submit" class="btn" data-testid="run-create-submit" :disabled="createBusy || !token">
          {{ createBusy ? '创建中…' : '创建 DRAFT' }}
        </button>
      </form>
      <div v-if="createError" class="error-banner" role="alert" data-testid="run-create-error">{{ createError }}</div>
      <p class="hint" data-testid="run-create-scenario-hint">
        可执行场景来自后端 manifest 的 <code>views.ui</code>
        <template v-if="uiScenarioNames.length">：{{ uiScenarioNames.join(' / ') }}</template>
        ；其它场景 start 会被 400/40002 拒绝。params 只接受该场景 manifest 声明的键。
      </p>
      <p v-if="benchCatalogError" class="hint" data-testid="run-create-catalog-error">{{ benchCatalogError }}</p>
      <p v-if="createResult" class="notice" data-testid="run-create-result">
        已创建 <code data-testid="run-created-id">{{ createResult.runId }}</code>
        · 状态 <strong>{{ createResult.status }}</strong>
        · {{ timeLabel(createResult.createdAt) }}
      </p>
    </section>

    <template v-if="selectedRunId">
      <section class="card ledger-detail-card" data-testid="run-detail">
        <div class="card-head">
          <h2>
            run 详情
            <code data-testid="run-detail-run-id">{{ selectedRunId }}</code>
          </h2>
          <div class="bench-actions">
            <span v-if="runStatus" class="badge" :class="statusTone(runStatus)" data-testid="run-status-badge">{{ runStatus }}</span>
            <button type="button" class="btn" data-testid="run-start" :disabled="!startEnabled" @click="runAction('start')">
              {{ actionBusy === 'start' ? '启动中…' : '启动 run' }}
            </button>
            <button type="button" class="btn ghost danger" data-testid="run-stop" :disabled="!stopEnabled" @click="runAction('stop')">
              {{ actionBusy === 'stop' ? '停止中…' : '停止 run' }}
            </button>
          </div>
        </div>

        <div v-if="detailError" class="error-banner" role="alert" data-testid="run-detail-error">{{ detailError }}</div>
        <div v-if="actionError" class="error-banner" role="alert" data-testid="run-action-error">{{ actionError }}</div>

        <section v-if="conflict" class="ledger-conflict" data-testid="run-conflict">
          <p>
            状态冲突 <code data-testid="run-conflict-code">{{ conflict.code }}</code>：{{ conflict.message }}
          </p>
          <p v-if="conflict.holder" data-testid="run-conflict-holder">
            lock holder runId：<code>{{ conflict.holder }}</code>（先对该 run 执行 stop，或等待 TTL）
          </p>
          <div v-if="conflict.precheck?.blocking?.length" data-testid="run-conflict-blocking">
            <h3>阻断明细</h3>
            <ul>
              <li v-for="item in conflict.precheck.blocking" :key="item.key">
                <code>{{ item.key }}</code> · {{ item.message }}
              </li>
            </ul>
          </div>
        </section>

        <div v-if="operation" class="chaos-result" data-testid="run-operation-result">
          <span>operation <code data-testid="run-operation-id">{{ operation.operationId }}</code></span>
          <span class="badge" :class="operation.status === 'FAILED' ? 'down' : 'ok'" data-testid="run-operation-status">{{ operation.status }}</span>
          <span class="hint">{{ timeLabel(operation.requestedAt) }}</span>
          <span v-if="operation.lastError" class="hint">错误：{{ operation.lastError }}</span>
        </div>
        <p v-if="pendingNote" class="notice" role="status" data-testid="run-operation-pending">{{ pendingNote }}</p>

        <h3>档案字段</h3>
        <dl v-if="selectedRun" class="kv-grid ledger-run-kv" data-testid="run-detail-kv">
          <div class="kv-row">
            <dt>测试名称</dt>
            <dd data-testid="run-name">{{ selectedRun.name || '—' }}</dd>
          </div>
          <div class="kv-row">
            <dt>场景</dt>
            <dd>{{ selectedRun.scenario || '—' }}</dd>
          </div>
          <div class="kv-row">
            <dt>节点</dt>
            <dd data-testid="run-nodes">{{ detailNodes }}</dd>
          </div>
          <div class="kv-row">
            <dt>流量模式 / 目标实例</dt>
            <dd>{{ detailTraffic }}</dd>
          </div>
          <div class="kv-row">
            <dt>写锁</dt>
            <dd>{{ detailLock }}</dd>
          </div>
          <div class="kv-row">
            <dt>归档状态</dt>
            <dd>
              <span class="chip" :class="{ on: selectedRun.archived }" data-testid="run-archived">{{ selectedRun.archived ? '已归档' : '未归档' }}</span>
            </dd>
          </div>
        </dl>

        <dl v-if="selectedRun" class="ledger-facts">
          <dt>scenario</dt>
          <dd>{{ selectedRun.scenario }}</dd>
          <dt>operator</dt>
          <dd>{{ selectedRun.operator || '—' }}</dd>
          <dt>sourceCommit</dt>
          <dd><code>{{ selectedRun.sourceCommit || '—' }}</code></dd>
          <dt>createdAt</dt>
          <dd>{{ timeLabel(selectedRun.createdAt) }}</dd>
          <dt>updatedAt</dt>
          <dd>{{ timeLabel(selectedRun.updatedAt) }}</dd>
          <dt>startedAt</dt>
          <dd>{{ timeLabel(selectedRun.startedAt) }}</dd>
          <dt>endedAt</dt>
          <dd>{{ timeLabel(selectedRun.endedAt) }}</dd>
        </dl>

        <h3>状态时间线</h3>
        <ol v-if="detail?.steps?.length" class="run-steps" data-testid="run-steps">
          <li v-for="(step, index) in detail.steps" :key="`${step.stepKey}-${index}`" :data-testid="`run-step-${index}`">
            <span class="step-marker">{{ index + 1 }}</span>
            <div>
              <p class="step-title">
                <code>{{ step.fromStatus }}</code> → <code>{{ step.toStatus }}</code>
                <span class="hint">{{ step.stepKey }}</span>
              </p>
              <p class="hint">
                {{ timeLabel(step.occurredAt) }}
                <template v-if="step.dwellMs !== null && step.dwellMs !== undefined"> · dwell {{ step.dwellMs }} ms</template>
              </p>
              <pre v-if="step.detail" class="ledger-json">{{ pretty(step.detail) }}</pre>
            </div>
          </li>
        </ol>
        <p v-else class="empty" data-testid="run-steps-empty">
          暂无状态转移（DRAFT 创建不写 steps）；启动 run 后会记录 from→to / occurredAt / dwell / detail。
        </p>

        <h3>故障事件</h3>
        <ul v-if="detail?.faults?.length" class="fault-tags" data-testid="run-faults">
          <li v-for="(fault, index) in detail.faults" :key="`${fault.operationId}-${index}`" :data-testid="`run-fault-${index}`">
            <span class="hint">{{ timeLabel(fault.occurredAt) }}</span>
            <span class="badge muted" data-testid="run-fault-source">{{ fault.source }}</span>
            <code>{{ fault.target }}</code>
            <span>{{ fault.event }}</span>
            <code class="run-commit">{{ fault.operationId || '—' }}</code>
          </li>
        </ul>
        <p v-else class="empty" data-testid="run-faults-empty">暂无故障事件</p>

        <h3>环境快照</h3>
        <div v-if="detail?.env?.length" class="ledger-table" data-testid="run-env">
          <div class="ledger-tr ledger-th">
            <span>container</span><span>imageTag</span><span>imageId</span><span>java</span><span>jvmArgs</span><span>gc</span><span>capturedAt</span>
          </div>
          <div v-for="env in detail.env" :key="env.containerName" class="ledger-tr">
            <code>{{ env.containerName }}</code>
            <span>{{ env.imageTag || '—' }}</span>
            <code class="run-commit">{{ (env.imageId || '—').slice(0, 12) }}</code>
            <span>{{ env.javaVersion || '—' }}</span>
            <code class="ledger-cell-json">{{ pretty(env.jvmArgs) }}</code>
            <code class="ledger-cell-json">{{ pretty(env.gc) }}</code>
            <span>{{ timeLabel(env.capturedAt) }}</span>
          </div>
        </div>
        <p v-else class="hint">该 run 还没有环境快照。</p>

        <h3>参数快照</h3>
        <div v-if="detail?.params?.length" class="ledger-params" data-testid="run-params">
          <article v-for="param in detail.params" :key="`${param.group}-${param.savedAt}`">
            <h4>{{ param.group }} <span class="hint">{{ timeLabel(param.savedAt) }}</span></h4>
            <pre class="ledger-json">{{ pretty(param.payload) }}</pre>
          </article>
        </div>
        <p v-else class="hint">该 run 还没有参数快照。</p>

        <h3>检查项</h3>
        <div v-if="detail?.checks?.length" class="ledger-table" data-testid="run-checks">
          <div class="ledger-tr ledger-th">
            <span>checkKey</span><span>expected</span><span>actual</span><span>verdict</span><span>checkedAt</span>
          </div>
          <div v-for="check in detail.checks" :key="check.checkKey" class="ledger-tr">
            <code>{{ check.checkKey }}</code>
            <span>{{ check.expected || '—' }}</span>
            <span>{{ check.actual || '—' }}</span>
            <span class="badge" :class="check.verdict === 'PASS' ? 'ok' : check.verdict === 'FAIL' ? 'down' : 'muted'">{{ check.verdict }}</span>
            <span>{{ timeLabel(check.checkedAt) }}</span>
          </div>
        </div>
        <p v-else class="hint">该 run 还没有检查项。</p>
      </section>

      <section class="card" data-testid="k6-run-card">
        <div class="card-head">
          <h2>
            k6 执行
            <span
              class="badge"
              :class="k6Tone(k6Status, k6?.exitCode)"
              data-testid="k6-status"
            >{{ k6StatusLabel }}</span>
          </h2>
          <div class="bench-actions">
            <button type="button" class="btn ghost" data-testid="k6-refresh" :disabled="!selectedRunId" @click="loadK6(selectedRunId, { force: true })">
              刷新状态
            </button>
            <button type="button" class="btn ghost" data-testid="k6-log-refresh" :disabled="k6LogLoading || !selectedRunId" @click="loadK6Logs">
              {{ k6LogLoading ? '读取中…' : '刷新日志' }}
            </button>
            <button type="button" class="btn ghost" data-testid="k6-report-download" :disabled="!k6?.reportAvailable || k6ReportBusy" @click="downloadReport">
              {{ k6ReportBusy ? '下载中…' : '下载报告' }}
            </button>
          </div>
        </div>
        <p class="hint">
          GET /lab-api/lab/runs/{id}/k6 · ABSENT 表示未启动/已清理（200，不是 404）；日志 tail=100；
          报告由 k6 退出时写出的 summary.json 提供。
        </p>
        <div v-if="k6Unavailable" class="notice" data-testid="k6-unavailable">
          k6 执行层不可用：{{ k6Error }}
        </div>
        <p v-else-if="k6Error" class="hint" data-testid="k6-error">{{ k6Error }}</p>
        <dl class="ledger-facts" data-testid="k6-facts">
          <dt>container</dt>
          <dd><code data-testid="k6-container">{{ k6?.containerName || '—' }}</code></dd>
          <dt>scenario</dt>
          <dd data-testid="k6-scenario">{{ k6?.scenario || '—' }}</dd>
          <dt>traffic</dt>
          <dd>
            <span data-testid="k6-traffic-mode">{{ k6?.trafficMode || '—' }}</span>
            · <span data-testid="k6-target-instance">{{ k6?.targetInstance || '—' }}</span>
          </dd>
          <dt>bypass / mixed</dt>
          <dd data-testid="k6-traffic-routing">
            {{ k6?.trafficMode === 'target-instance' ? '绕过 Nginx' : '经 Nginx upstream' }}
            · traffic_mixed=false（受控 run 预检查停背景流量）
          </dd>
          <dt>exitCode</dt>
          <dd data-testid="k6-exit-code">{{ k6?.exitCode ?? '—' }}</dd>
          <dt>startedAt</dt>
          <dd data-testid="k6-started">{{ timeLabel(k6?.startedAt) }}</dd>
          <dt>finishedAt</dt>
          <dd data-testid="k6-finished">{{ timeLabel(k6?.finishedAt) }}</dd>
          <dt>report</dt>
          <dd>
            <span data-testid="k6-report-available">{{ k6?.reportAvailable ? '可用' : '未生成' }}</span>
            · <span data-testid="k6-report-bytes">{{ byteLabel(k6?.reportBytes) }}</span>
          </dd>
          <dt>note</dt>
          <dd data-testid="k6-note">{{ k6?.note || '—' }}</dd>
        </dl>
        <div class="k6-log-head">
          <span class="hint">
            日志 tail=100
            <template v-if="k6Logs"> · {{ byteLabel(k6Logs.bytes) }}</template>
          </span>
          <span v-if="k6LogTruncated" class="badge warn" data-testid="k6-log-truncated">已截断</span>
        </div>
        <pre class="ledger-json k6-log-view" data-testid="k6-log-view">{{ k6LogText }}</pre>
        <p v-if="k6ReportError" class="hint" data-testid="k6-report-error">{{ k6ReportError }}</p>
      </section>

      <section class="card" data-testid="run-precheck">
        <div class="card-head">
          <h2>预检查</h2>
          <div class="bench-actions">
            <span v-if="precheck" class="badge" :class="precheckTone(precheck.verdict)" data-testid="run-precheck-verdict">{{ precheck.verdict }}</span>
            <button type="button" class="btn ghost" :disabled="!selectedRunId" @click="loadPrecheck(selectedRunId)">重新检查</button>
          </div>
        </div>
        <p class="hint">GET /lab-api/lab/runs/{id}/precheck；阻断项没有 force/skip，修复环境后重试。</p>
        <div v-if="precheckError" class="error-banner" role="alert" data-testid="run-precheck-error">{{ precheckError }}</div>
        <template v-if="precheck">
          <p class="hint">检查时间 {{ timeLabel(precheck.checkedAt) }} · 共 {{ precheck.items?.length || 0 }} 项</p>
          <h3>阻断项</h3>
          <ul class="precheck-list" data-testid="run-precheck-blocking">
            <li v-for="item in precheck.blocking || []" :key="item.key">
              <code>{{ item.key }}</code>
              <span class="badge down">{{ item.status }}</span>
              <span>{{ item.message }}</span>
              <pre v-if="item.detail && Object.keys(item.detail).length" class="ledger-json">{{ pretty(item.detail) }}</pre>
            </li>
            <li v-if="!precheck.blocking?.length" class="hint">无阻断项。</li>
          </ul>
          <h3>警告项</h3>
          <ul class="precheck-list" data-testid="run-precheck-warnings">
            <li v-for="item in precheck.warnings || []" :key="item.key">
              <code>{{ item.key }}</code>
              <span class="badge warn">{{ item.status }}</span>
              <span>{{ item.message }}</span>
            </li>
            <li v-if="!precheck.warnings?.length" class="hint">无警告项。</li>
          </ul>
          <details class="precheck-all">
            <summary>全部检查项（{{ precheck.items?.length || 0 }}）</summary>
            <ul class="precheck-list">
              <li v-for="item in precheck.items || []" :key="item.key">
                <code>{{ item.key }}</code>
                <span class="badge" :class="item.status === 'PASS' ? 'ok' : item.status === 'WARN' ? 'warn' : 'down'">{{ item.status }}</span>
                <span>{{ item.message }}</span>
              </li>
            </ul>
          </details>
        </template>
      </section>

      <section class="card" data-testid="run-operations-card">
        <div class="card-head">
          <h2>操作记录 <span class="count">{{ operations.length }}</span></h2>
          <button type="button" class="btn ghost" :disabled="!selectedRunId" @click="loadOperations(selectedRunId)">刷新操作</button>
        </div>
        <div v-if="operationsError" class="error-banner" role="alert" data-testid="run-operations-error">{{ operationsError }}</div>
        <div class="operation-close-bar">
          <label class="form-label">
            关闭 note（可选，≤512）
            <input
              v-model="closeNote"
              class="input operation-close-note"
              maxlength="512"
              data-testid="operation-close-note"
              placeholder="人工核验说明，写入 lastError"
            />
          </label>
          <span class="hint">仅 UNKNOWN 可关闭：RESTORED 或 FAILED，同终态幂等，异终态 409。</span>
        </div>
        <div v-if="closeError" class="error-banner" role="alert" data-testid="operation-close-error">{{ closeError }}</div>
        <p v-if="closeInfo" class="notice" role="status" data-testid="operation-close-result">{{ closeInfo }}</p>
        <div v-if="operations.length" class="ledger-table" data-testid="run-operations">
          <div class="ledger-tr ledger-th">
            <span>operationId</span><span>action</span><span>status</span><span>requestedAt</span><span>applied/restored</span><span>lastError</span><span>关闭 UNKNOWN</span>
          </div>
          <div v-for="item in operations" :key="item.operationId" class="ledger-tr" :data-testid="`run-operation-row-${item.operationId}`">
            <code>{{ item.operationId }}</code>
            <span>{{ item.action }}</span>
            <span class="badge" :class="item.status === 'FAILED' ? 'down' : item.status === 'UNKNOWN' ? 'run-unknown' : item.status === 'PENDING_CONFIRMATION' ? 'warn' : 'ok'">{{ item.status }}</span>
            <span>{{ timeLabel(item.requestedAt) }}</span>
            <span>{{ timeLabel(item.appliedAt || item.restoredAt) }}</span>
            <span>{{ item.lastError || '—' }}</span>
            <span class="operation-close-actions">
              <template v-if="item.status === 'UNKNOWN'">
                <button
                  type="button"
                  class="btn ghost"
                  :data-testid="`operation-close-restored-${item.operationId}`"
                  :disabled="Boolean(closeBusy)"
                  @click="closeUnknown(item, 'RESTORED')"
                >{{ closeBusy === `${item.operationId}:RESTORED` ? '提交中…' : '关闭→RESTORED' }}</button>
                <button
                  type="button"
                  class="btn ghost danger"
                  :data-testid="`operation-close-failed-${item.operationId}`"
                  :disabled="Boolean(closeBusy)"
                  @click="closeUnknown(item, 'FAILED')"
                >{{ closeBusy === `${item.operationId}:FAILED` ? '提交中…' : '关闭→FAILED' }}</button>
              </template>
              <span v-else class="hint">—</span>
            </span>
          </div>
        </div>
        <p v-else class="hint" data-testid="run-operations-empty">该 run 还没有操作记录。</p>
      </section>

      <div v-if="metricsError" class="error-banner" role="alert" data-testid="run-metrics-error">{{ metricsError }}</div>
      <MetricCards
        :metrics="runMetrics?.metrics || []"
        :overall="metricsOverall"
        :reason="metricsReason"
        :generated-at="runMetrics?.generatedAt || ''"
        :window="runMetrics?.window || null"
        title="Run 指标"
      />
      <p class="hint">
        <button type="button" class="btn ghost" data-testid="run-metrics-load" :disabled="metricsLoading || !selectedRunId" @click="loadRunMetrics(selectedRunId)">
          {{ metricsLoading ? '查询中…' : '刷新 run 指标' }}
        </button>
        窗口由控制器按 run 起止时间加抓取/排空宽限计算。
      </p>
    </template>

    <div v-if="deleteTarget" class="modal-backdrop" data-testid="run-delete-modal" @click.self="cancelDelete">
      <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="run-delete-title">
        <h3 id="run-delete-title">删除 run</h3>
        <dl class="kv-grid">
          <div class="kv-row">
            <dt>测试名称</dt>
            <dd>{{ deleteTarget.name || '—' }}</dd>
          </div>
          <div class="kv-row">
            <dt>runId</dt>
            <dd><code>{{ deleteTarget.runId }}</code></dd>
          </div>
          <div class="kv-row">
            <dt>status</dt>
            <dd><span class="badge" :class="statusTone(deleteTarget.status)">{{ deleteTarget.status }}</span></dd>
          </div>
        </dl>
        <p class="notice danger">
          将级联删除该 run 的 steps、operations、fault events、metric windows、checks，
          并清理 k6 报告目录；不可恢复。
        </p>
        <div class="rowline modal-actions">
          <button type="button" class="btn ghost" data-testid="run-delete-cancel" :disabled="deleteBusy" @click="cancelDelete">取消</button>
          <button type="button" class="btn danger" data-testid="run-delete-confirm" :disabled="deleteBusy" @click="confirmDelete">
            {{ deleteBusy ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.run-list .run-row {
  grid-template-columns: 44px 150px 170px minmax(120px, 1.2fr) 100px 88px 80px 100px 150px 150px minmax(190px, 1fr);
  min-width: 1390px;
}

.run-compare-check {
  margin: 0;
  accent-color: var(--accent);
  cursor: pointer;
}

.run-name-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.run-row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.run-row-actions .btn {
  padding: 3px 8px;
  font-size: 12px;
  border-radius: 7px;
}

.run-delete-hint {
  font-size: 11px;
}

.run-row-item:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

.archived-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding-bottom: 10px;
  font-size: 13px;
  color: var(--text-muted);
  cursor: pointer;
}

.archived-toggle input {
  accent-color: var(--accent);
}

.ledger-run-kv {
  margin: 8px 0 14px;
}

.ledger-compare-card {
  margin-top: 14px;
}

.modal-actions {
  justify-content: flex-end;
  margin-top: 12px;
}

.ledger-create .ledger-name-field {
  flex: 0 1 200px;
}

.ledger-create .ledger-scenario-field {
  flex: 1 1 240px;
}

.ledger-create .ledger-params-field {
  flex: 2 1 360px;
}
</style>
