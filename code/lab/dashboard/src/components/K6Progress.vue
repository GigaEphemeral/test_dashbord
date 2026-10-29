<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { describeError, fetchRun, fetchRuns, queryPromRange } from '../api'
import { formatRate } from '../labModel'

/**
 * Live k6 progress for the newest RUNNING run (or the newest COMPLETED one).
 *
 * The run window comes from the ledger (startedAt → endedAt/now); the k6
 * Prometheus series are filtered by the run_id tag that k6/lib/http.js puts on
 * every metric. Counters are sampled over the same window and turned into a
 * rate here, so a finished run still shows its final numbers. A series that is
 * absent (k6 never pushed, or the window is shorter than one scrape) renders
 * 无数据 — never 0.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 }
})

const RUN_LIMIT = 5
const STEP_SECONDS = 5
// Manifest defaults, used only when the run has no params snapshot (draft
// started before B3-00a). Load's LOAD_DURATION is already the total; spike is
// the sum of its four segments.
const SCENARIO_DEFAULT_SECONDS = { smoke: 30, load: 600, spike: 110 }
const DURATION_KEY = /(_DURATION|_RAMP_UP|_RAMP_DOWN)$/

const run = ref(null)
const plannedSeconds = ref(null)
const vus = ref(null)
const iterationsRate = ref(null)
const httpReqsRate = ref(null)
const error = ref('')
const detailNote = ref('')
const loading = ref(false)
const lastSync = ref(null)
const nowMs = ref(Date.now())

let inFlight = false
let queued = false
let disposed = false

const statusLabel = computed(() => run.value?.status || '')
const startedMs = computed(() => {
  const value = run.value?.startedAt
  const ms = value ? Date.parse(value) : NaN
  return Number.isFinite(ms) ? ms : null
})
const endedMs = computed(() => {
  const value = run.value?.endedAt
  const ms = value ? Date.parse(value) : NaN
  return Number.isFinite(ms) ? ms : null
})
const elapsedSeconds = computed(() => {
  if (startedMs.value === null) return null
  const end = endedMs.value === null ? nowMs.value : endedMs.value
  return Math.max(0, (end - startedMs.value) / 1000)
})
const percent = computed(() => {
  if (elapsedSeconds.value === null) return null
  if (run.value?.status === 'COMPLETED') return 100
  if (!Number.isFinite(plannedSeconds.value) || plannedSeconds.value <= 0) return null
  return Math.min(100, Math.max(0, (elapsedSeconds.value / plannedSeconds.value) * 100))
})
const plannedLabel = computed(() => (
  Number.isFinite(plannedSeconds.value) && plannedSeconds.value > 0
    ? clock(plannedSeconds.value)
    : '计划时长未知'
))
const elapsedLabel = computed(() => (
  elapsedSeconds.value === null ? '未开始' : clock(elapsedSeconds.value)
))
const progressNote = computed(() => {
  if (run.value?.status === 'COMPLETED') return '已完成'
  if (percent.value === null) return '进度未知'
  return `${percent.value.toFixed(0)}%`
})

function clock(seconds) {
  const total = Math.max(0, Math.round(seconds))
  const minutes = Math.floor(total / 60)
  const rest = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
}

function timeLabel(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString('zh-CN', { hour12: false })
}

/** k6 duration ("1m30s", "250ms") or a plain number of seconds. */
function parseDuration(value) {
  if (typeof value === 'number') {
    return Number.isFinite(value) && value >= 0 ? value : null
  }
  const text = String(value || '').trim()
  if (!text) return null
  if (/^\d+(?:\.\d+)?$/.test(text)) return Number(text)
  const pattern = /(\d+(?:\.\d+)?)(ms|s|m|h)/g
  const unitSeconds = { ms: 0.001, s: 1, m: 60, h: 3600 }
  let total = 0
  let matched = false
  let match = pattern.exec(text)
  while (match) {
    total += Number(match[1]) * unitSeconds[match[2]]
    matched = true
    match = pattern.exec(text)
  }
  return matched ? total : null
}

/**
 * Sums every duration-like key across the run's params snapshots (request /
 * fault timeline / …). Any finite total wins; otherwise the manifest default
 * for the scenario is used.
 */
function plannedFromDetail(detail) {
  let total = 0
  let counted = 0
  for (const param of detail?.params || []) {
    const payload = param?.payload
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) continue
    for (const [key, value] of Object.entries(payload)) {
      if (!DURATION_KEY.test(key)) continue
      const seconds = parseDuration(value)
      if (seconds === null) continue
      total += seconds
      counted += 1
    }
  }
  if (counted > 0 && total > 0) return total
  return SCENARIO_DEFAULT_SECONDS[detail?.run?.scenario || run.value?.scenario] ?? null
}

function promString(value) {
  return String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

/** Sum every series by timestamp; the expressions below already aggregate. */
function pointsOf(result) {
  const byTime = new Map()
  for (const series of result || []) {
    for (const [stamp, raw] of series.values || []) {
      const value = Number(raw)
      if (!Number.isFinite(value)) continue
      byTime.set(Number(stamp), (byTime.get(Number(stamp)) || 0) + value)
    }
  }
  return [...byTime.entries()].sort((a, b) => a[0] - b[0])
}

function lastValue(points) {
  return points.length ? points[points.length - 1][1] : null
}

/** Counter slope over the sampled window; a reset or a single sample is no data. */
function slope(points) {
  if (points.length < 2) return null
  const [firstAt, firstValue] = points[0]
  const [lastAt, lastValueValue] = points[points.length - 1]
  const dt = lastAt - firstAt
  const dv = lastValueValue - firstValue
  if (!(dt > 0) || !(dv >= 0)) return null
  return dv / dt
}

function resetSeries() {
  vus.value = null
  iterationsRate.value = null
  httpReqsRate.value = null
}

async function loadRun(selected, token) {
  const detail = await fetchRun(token, selected.runId)
  if (disposed) return
  detailNote.value = ''
  plannedSeconds.value = plannedFromDetail(detail)
}

async function refresh() {
  if (!props.active || !props.token || disposed) return
  if (inFlight) {
    queued = true
    return
  }
  inFlight = true
  loading.value = true
  error.value = ''
  try {
    const page = await fetchRuns(props.token, { limit: RUN_LIMIT })
    const items = [...(page?.items || [])].sort(
      (a, b) => Date.parse(b.createdAt || 0) - Date.parse(a.createdAt || 0)
    )
    const selected = items.find((item) => item.status === 'RUNNING')
      || items.find((item) => item.status === 'COMPLETED')
      || null
    if (!selected) {
      run.value = null
      plannedSeconds.value = null
      resetSeries()
      detailNote.value = ''
      return
    }
    run.value = selected
    nowMs.value = Date.now()
    lastSync.value = new Date()
    try {
      await loadRun(selected, props.token)
    } catch (err) {
      detailNote.value = `run 详情读取失败，计划时长按场景默认值：${describeError(err)}`
      plannedSeconds.value = SCENARIO_DEFAULT_SECONDS[selected.scenario] ?? null
    }
    if (startedMs.value === null) {
      resetSeries()
      return
    }
    const endSeconds = Math.floor((endedMs.value ?? Date.now()) / 1000)
    const startSeconds = Math.floor(startedMs.value / 1000)
    if (endSeconds <= startSeconds) {
      resetSeries()
      return
    }
    const runId = promString(selected.runId)
    const step = `${STEP_SECONDS}s`
    const queries = [
      [`sum(k6_vus{run_id="${runId}"})`, (points) => { vus.value = lastValue(points) }],
      [`sum(k6_iterations_total{run_id="${runId}"})`, (points) => { iterationsRate.value = slope(points) }],
      [`sum(k6_http_reqs_total{run_id="${runId}"})`, (points) => { httpReqsRate.value = slope(points) }]
    ]
    const results = await Promise.allSettled(
      queries.map(([expr]) => queryPromRange(expr, startSeconds, endSeconds, step))
    )
    if (disposed) return
    const failures = []
    results.forEach((result, index) => {
      const [expr, apply] = queries[index]
      if (result.status === 'fulfilled') {
        apply(pointsOf(result.value))
      } else {
        failures.push(`${expr}：${describeError(result.reason)}`)
      }
    })
    if (failures.length) {
      error.value = `部分 k6 查询失败：${failures.join('；')}`
    }
  } catch (err) {
    error.value = `k6 进度查询失败：${describeError(err)}`
    resetSeries()
  } finally {
    inFlight = false
    loading.value = false
    if (queued) {
      queued = false
      refresh()
    }
  }
}

watch(
  () => [props.active, props.syncTick, props.token],
  ([active]) => {
    if (active) refresh()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  disposed = true
  inFlight = false
  queued = false
})
</script>

<template>
  <section class="card k6-progress" data-testid="k6-progress">
    <div class="card-head">
      <h2>k6 进度</h2>
      <div class="bench-actions">
        <span v-if="statusLabel" class="badge" :class="`run-${statusLabel.toLowerCase()}`">{{ statusLabel }}</span>
        <span class="hint" data-testid="k6-last-sync">上次同步 {{ timeLabel(lastSync) }}</span>
      </div>
    </div>

    <p v-if="!run" class="empty" data-testid="k6-empty">
      无数据 · 尚未开始：最近 {{ RUN_LIMIT }} 条 run 里没有 RUNNING / COMPLETED 记录。启动 run 后这里显示 VU、迭代速率与请求速率。
    </p>

    <template v-else>
      <p class="k6-run-line">
        <code data-testid="k6-run-id">{{ run.runId }}</code>
        <span class="hint">{{ run.scenario }} · 开始 {{ timeLabel(run.startedAt) }}<template v-if="run.endedAt"> · 结束 {{ timeLabel(run.endedAt) }}</template><template v-else> · 进行中</template></span>
      </p>

      <div class="k6-progress-track">
        <div class="progress-track" data-testid="k6-progress-bar">
          <span :style="{ width: `${percent ?? 0}%` }"></span>
        </div>
        <p class="hint k6-progress-note" data-testid="k6-progress-time">
          {{ elapsedLabel }} / {{ plannedLabel }} · {{ progressNote }}
        </p>
      </div>

      <div class="k6-stats">
        <div class="k6-stat">
          <span class="hint">VU</span>
          <strong data-testid="k6-vus">{{ vus === null ? '无数据' : vus.toFixed(0) }}</strong>
        </div>
        <div class="k6-stat">
          <span class="hint">iterations/s</span>
          <strong data-testid="k6-iterations-rate">{{ formatRate(iterationsRate) }}</strong>
        </div>
        <div class="k6-stat">
          <span class="hint">http_reqs/s</span>
          <strong data-testid="k6-http-reqs-rate">{{ formatRate(httpReqsRate) }}</strong>
        </div>
      </div>

      <p class="hint">
        来源 <code>k6_vus</code> / <code>k6_iterations_total</code> / <code>k6_http_reqs_total</code>，
        以 <code>run_id="{{ run.runId }}"</code> 过滤；速率由 run 窗口内计数器斜率计算，序列缺失显示无数据。
      </p>
    </template>

    <div v-if="detailNote" class="notice warn" role="status" data-testid="k6-detail-note">{{ detailNote }}</div>
    <div v-if="error" class="notice danger" role="alert" data-testid="k6-error">{{ error }}</div>
  </section>
</template>

<style scoped>
.k6-run-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  margin: 0 0 8px;
}

.k6-progress-track {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.k6-progress-track .progress-track {
  flex: 1;
}

.k6-progress-note {
  margin: 0;
  min-width: 180px;
}

.k6-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 8px;
  margin-bottom: 8px;
}

.k6-stat {
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  background: var(--bg-soft);
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.k6-stat strong {
  font-size: 18px;
}
</style>
