<script setup>
import { computed, ref } from 'vue'
import {
  createRun,
  describeError,
  fetchRun,
  fetchRunMetrics,
  fetchRunPrecheck,
  runCachePreheat,
  setCacheL1,
  startRun,
  stopRun
} from '../api'

/**
 * Stage2 B3-03: a fixed A/B template.
 *
 * A = L1 on + preheat, B = L1 off (entries cleared) with the same dataset,
 * traffic shape, instance set and JVM configuration. The panel creates and
 * runs the two experiments back to back, then renders the per-metric diff from
 * the B1 aggregation layer. Performance thresholds are not a verdict here;
 * missing data stays "无数据" and never becomes 0.
 */
const props = defineProps({
  token: { type: String, default: '' },
  instances: { type: Array, default: () => ['app-1', 'app-2', 'app-3'] },
  active: { type: Boolean, default: true }
})

const METRICS = [
  { key: 'http.rps', label: 'RPS', unit: '' },
  { key: 'http.latency.p95', label: 'P95', unit: 'ms' },
  { key: 'http.latency.p99', label: 'P99', unit: 'ms' },
  { key: 'http.failure.ratio', label: '错误率', unit: '' },
  { key: 'jvm.gc.pause.max', label: 'GC 最长停顿', unit: 'ms' },
  { key: 'jvm.heap.used', label: '堆使用', unit: 'bytes' },
  { key: 'cache.l1.hit.ratio', label: 'L1 命中率', unit: '' },
  { key: 'cache.l2.keys', label: 'L2 键数', unit: '' },
  { key: 'business.orders.created', label: '订单新增', unit: '' }
]

const durationSeconds = ref(30)
const vus = ref(10)
const startVus = ref(1)
const productMin = ref(1)
const productMax = ref(10000)
const running = ref(false)
const stage = ref('未运行')
const error = ref('')
const logLines = ref([])
const runA = ref('')
const runB = ref('')
const rows = ref([])
const compareNote = ref('')

const canRun = computed(() => Boolean(props.token) && !running.value)

function log(text) {
  const stamp = new Date().toLocaleTimeString()
  logLines.value = [...logLines.value, `${stamp} ${text}`]
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function waitForRun(runId, predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const detail = await fetchRun(props.token, runId)
    const status = detail?.run?.status
    if (predicate(status)) {
      return status
    }
    if (['FAILED', 'INVALID', 'ABORTED'].includes(status)) {
      throw new Error(`${label}: run ${runId} 进入 ${status}`)
    }
    await sleep(1000)
  }
  throw new Error(`${label}: run ${runId} 超时未进入期望状态`)
}

async function runOne(label) {
  const name = `A/B ${label} ${new Date().toISOString().slice(11, 19)}`
  const created = await createRun(props.token, {
    scenario: 'load',
    name,
    nodes: props.instances,
    params: {
      LOAD_DURATION: `${durationSeconds.value}s`,
      LOAD_VUS: vus.value,
      LOAD_START_VUS: startVus.value,
      PRODUCT_ID_MIN: productMin.value,
      PRODUCT_ID_MAX: productMax.value,
      PRODUCT_ID_DISTRIBUTION: 'uniform',
      PRODUCT_ID_CONCENTRATION: 0
    }
  })
  const runId = created.runId
  log(`${label}: 草稿 ${runId} 已创建`)
  const precheck = await fetchRunPrecheck(props.token, runId)
  if (precheck?.verdict === 'BLOCKED') {
    const first = precheck.blocking?.[0]
    throw new Error(`${label}: 预检查 BLOCKED（${first?.key || 'unknown'}：${first?.message || ''}）`)
  }
  log(`${label}: 预检查 ${precheck?.verdict || 'NO_DATA'}`)
  const started = await startRun(props.token, runId, `ab-${runId}-start`)
  if (started?.status === 202) {
    log(`${label}: 启动待确认，等待 RUNNING`)
  }
  await waitForRun(runId, (status) => status === 'RUNNING', 30000, label)
  log(`${label}: RUNNING，等待 ${durationSeconds.value + 8}s 采集窗口`)
  await sleep((durationSeconds.value + 8) * 1000)
  // The k6 watchdog may already have closed the run when the script exits;
  // stopping a COMPLETED run is a 409, so only stop a live one.
  const current = await fetchRun(props.token, runId)
  const status = current?.run?.status
  if (['RUNNING', 'STOPPING', 'UNKNOWN'].includes(status)) {
    await stopRun(props.token, runId, `ab-${runId}-stop`)
  }
  await waitForRun(runId, (state) => ['COMPLETED', 'FAILED', 'INVALID', 'ABORTED'].includes(state),
    60000, label)
  log(`${label}: 已收盘 ${runId}`)
  return runId
}

function metricAvg(metrics, key) {
  const metric = metrics?.metrics?.find((item) => item.key === key)
  if (!metric || metric.quality !== 'OK' || !metric.series?.length) {
    return null
  }
  const values = metric.series
    .map((series) => series?.stats?.avg)
    .filter((value) => typeof value === 'number' && Number.isFinite(value))
  if (!values.length) {
    return null
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length
}

function formatValue(value, unit) {
  if (value === null) return '无数据'
  if (unit === 'bytes') return `${(value / 1024 / 1024).toFixed(1)} MB`
  if (unit === 'ms') return `${value.toFixed(1)} ms`
  if (unit === '') return Number(value).toFixed(3)
  return String(value)
}

async function compare(a, b) {
  const keys = METRICS.map((item) => item.key).join(',')
  const [metricsA, metricsB] = await Promise.all([
    fetchRunMetrics(props.token, a, { keys, phase: 'run' }),
    fetchRunMetrics(props.token, b, { keys, phase: 'run' })
  ])
  rows.value = METRICS.map((item) => {
    const valueA = metricAvg(metricsA, item.key)
    const valueB = metricAvg(metricsB, item.key)
    const delta = valueA === null || valueB === null ? null : valueB - valueA
    return { ...item, a: valueA, b: valueB, delta }
  })
  const missing = rows.value.filter((row) => row.a === null || row.b === null).length
  compareNote.value = missing
    ? `有 ${missing} 项在任一窗口无数据（显示“无数据”，未按 0 计算）。`
    : '全部指标在 A/B 两个 run 窗口都有真实数据。'
}

async function setL1All(enabled) {
  const results = []
  for (const instance of props.instances) {
    const state = await setCacheL1(props.token, instance, enabled)
    results.push({ instance, cleared: state?.clearedEntries ?? 0 })
  }
  return results
}

async function runPair() {
  if (!canRun.value) return
  running.value = true
  error.value = ''
  rows.value = []
  runA.value = ''
  runB.value = ''
  compareNote.value = ''
  logLines.value = []
  try {
    stage.value = 'A · 打开 L1 并预热'
    const enabled = await setL1All(true)
    log(`A: L1 已打开（${enabled.map((item) => item.instance).join(', ')}）`)
    for (const instance of props.instances) {
      const preheat = await runCachePreheat(props.token, instance)
      log(`A: ${instance} 预热 requested=${preheat?.requested ?? '?'} loaded=${preheat?.loaded ?? '?'}`)
    }
    runA.value = await runOne('A-L1-on')

    stage.value = 'B · 关闭 L1 并清空'
    const disabled = await setL1All(false)
    log(`B: L1 已关闭，本地条目清空 ${disabled.map((item) => `${item.instance}=${item.cleared}`).join(', ')}`)
    runB.value = await runOne('B-L1-off')

    stage.value = '对比 A/B 指标'
    await compare(runA.value, runB.value)
    stage.value = '完成'
  } catch (err) {
    error.value = describeError(err)
    stage.value = '失败'
  } finally {
    try {
      await setL1All(true)
    } catch {
      // The panel still reports the A/B failure; restoring is best effort.
    }
    running.value = false
  }
}
</script>

<template>
  <section class="card" data-testid="cache-ab-panel">
    <div class="card-head">
      <div>
        <h2>缓存 A/B 公平对照</h2>
        <p class="hint">
          A：L1 开 + 预热；B：L1 关并确认清空。同一数据集、流量形状、实例集合与 JVM 参数；
          差值来自 B1 聚合层，无数据显示“无数据”。
        </p>
      </div>
      <span class="badge" :class="running ? 'run-running' : 'run-draft'" data-testid="ab-stage">
        {{ stage }}
      </span>
    </div>

    <div class="field-row">
      <label class="field">
        <span>时长（秒）<span class="key">LOAD_DURATION</span></span>
        <input v-model.number="durationSeconds" class="input" type="number" min="10" max="300"
               data-testid="ab-duration" />
      </label>
      <label class="field">
        <span>VU<span class="key">LOAD_VUS</span></span>
        <input v-model.number="vus" class="input" type="number" min="1" max="200"
               data-testid="ab-vus" />
      </label>
      <label class="field">
        <span>起始 VU<span class="key">LOAD_START_VUS</span></span>
        <input v-model.number="startVus" class="input" type="number" min="1" max="200"
               data-testid="ab-start-vus" />
      </label>
      <label class="field">
        <span>商品起始<span class="key">PRODUCT_ID_MIN</span></span>
        <input v-model.number="productMin" class="input" type="number" min="1"
               data-testid="ab-min" />
      </label>
      <label class="field">
        <span>商品结束<span class="key">PRODUCT_ID_MAX</span></span>
        <input v-model.number="productMax" class="input" type="number" min="1"
               data-testid="ab-max" />
      </label>
    </div>

    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>

    <div class="rowline" style="margin-top: 10px">
      <button type="button" class="btn" :disabled="!canRun" data-testid="ab-run" @click="runPair">
        {{ running ? '对照进行中…' : '运行 A/B 对照' }}
      </button>
      <span class="hint">一次对照约 {{ (durationSeconds + 8) * 2 + 20 }}s；结束后会自动恢复 L1 开启。</span>
    </div>

    <div class="kv-grid" style="margin-top: 10px">
      <div class="kv-row"><dt>Run A</dt><dd data-testid="ab-run-a">{{ runA || '—' }}</dd></div>
      <div class="kv-row"><dt>Run B</dt><dd data-testid="ab-run-b">{{ runB || '—' }}</dd></div>
    </div>

    <ol v-if="logLines.length" class="ab-log" data-testid="ab-log">
      <li v-for="(line, index) in logLines" :key="index">{{ line }}</li>
    </ol>

    <div v-if="rows.length" data-testid="ab-diff">
      <table class="data-table">
        <thead>
          <tr><th>指标</th><th>A · L1 开</th><th>B · L1 关</th><th>差值 B−A</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.key" :data-testid="`ab-row-${row.key}`">
            <td>{{ row.label }}</td>
            <td>{{ formatValue(row.a, row.unit) }}</td>
            <td>{{ formatValue(row.b, row.unit) }}</td>
            <td>{{ formatValue(row.delta, row.unit) }}</td>
          </tr>
        </tbody>
      </table>
      <p class="hint">{{ compareNote }}</p>
    </div>
  </section>
</template>

<style scoped>
.ab-log {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--text-muted);
  font-size: 12px;
  max-height: 160px;
  overflow: auto;
}
</style>
