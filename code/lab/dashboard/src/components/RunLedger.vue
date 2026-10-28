<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import MetricCards from './MetricCards.vue'
import {
  createRun,
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
  stopRun
} from '../api'

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

const filters = reactive({ scenario: '', status: '' })
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

const createForm = reactive({ scenario: '', paramsText: '{}' })
const createBusy = ref(false)
const createError = ref('')
const createResult = ref(null)

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
  // A DRAFT/terminal run has nothing to poll once the state was read once.
  if (!force && k6.value && (terminal || !k6Pollable.value)) return
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
    const created = await createRun(props.token, { scenario, params })
    createResult.value = created
    selectedRunId.value = created.runId
    localStorage.setItem(RUN_STORAGE_KEY, created.runId)
    filters.scenario = ''
    filters.status = ''
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
            <span>runId</span>
            <span>scenario</span>
            <span>status</span>
            <span>operator</span>
            <span>sourceCommit</span>
            <span>createdAt</span>
            <span>updatedAt</span>
          </div>
          <button
            v-for="run in runs"
            :key="run.runId"
            type="button"
            class="run-row run-row-item"
            :class="{ selected: run.runId === selectedRunId }"
            :data-testid="`run-row-${run.runId}`"
            @click="selectRun(run.runId)"
          >
            <code>{{ run.runId }}</code>
            <span>{{ run.scenario }}</span>
            <span class="badge" :class="statusTone(run.status)" :data-testid="`run-status-${run.runId}`">{{ run.status }}</span>
            <span>{{ run.operator || '—' }}</span>
            <code class="run-commit">{{ (run.sourceCommit || '—').slice(0, 12) }}</code>
            <span>{{ timeLabel(run.createdAt) }}</span>
            <span>{{ timeLabel(run.updatedAt) }}</span>
          </button>
          <p v-if="!runs.length && !loadingList" class="empty" data-testid="run-list-empty">
            台账里还没有 run；用下方表单创建一个 DRAFT。
          </p>
        </div>
        <div class="ledger-pager">
          <button type="button" class="btn ghost" data-testid="run-page-prev" :disabled="offset === 0 || loadingList" @click="prevPage">上一页</button>
          <span class="hint">{{ currentPage }}</span>
          <button type="button" class="btn ghost" data-testid="run-page-next" :disabled="offset + PAGE_SIZE >= total || loadingList" @click="nextPage">下一页</button>
        </div>
      </template>
    </section>

    <section class="card ledger-create-card">
      <div class="card-head">
        <h2>新建 run</h2>
        <span class="hint">POST /lab-api/lab/runs → DRAFT</span>
      </div>
      <form class="ledger-create" @submit.prevent="submitCreate">
        <label class="form-label">
          scenario
          <input v-model="createForm.scenario" class="input" data-testid="run-create-scenario" list="run-create-scenario-options" placeholder="例如 smoke / load / spike" />
        </label>
        <datalist id="run-create-scenario-options" data-testid="run-create-scenario-options">
          <option v-for="name in uiScenarioNames" :key="name" :value="name">{{ name }}</option>
        </datalist>
        <label class="form-label">
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
        <div v-if="operations.length" class="ledger-table" data-testid="run-operations">
          <div class="ledger-tr ledger-th">
            <span>operationId</span><span>action</span><span>status</span><span>requestedAt</span><span>applied/restored</span><span>lastError</span>
          </div>
          <div v-for="item in operations" :key="item.operationId" class="ledger-tr" :data-testid="`run-operation-row-${item.operationId}`">
            <code>{{ item.operationId }}</code>
            <span>{{ item.action }}</span>
            <span class="badge" :class="item.status === 'FAILED' ? 'down' : item.status === 'PENDING_CONFIRMATION' ? 'warn' : 'ok'">{{ item.status }}</span>
            <span>{{ timeLabel(item.requestedAt) }}</span>
            <span>{{ timeLabel(item.appliedAt || item.restoredAt) }}</span>
            <span>{{ item.lastError || '—' }}</span>
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
  </div>
</template>
