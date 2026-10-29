<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { describeError, queryProm } from '../api'
import { formatBytes, formatCpu, formatMillis, formatRate } from '../labModel'

/**
 * Per-instance metric table (B3-00d).
 *
 * Rows are the jl-app-N containers the controller already reports; every cell
 * is one PromQL aggregate keyed by `instance="app-N:8080"`. A node outside the
 * newest run's enabled set is greyed (未启用) and all of its metric cells read
 * 无数据. Any missing/failed series also reads 无数据 — the table never invents
 * a 0.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  containers: { type: Array, default: () => [] },
  enabledNodes: { type: Array, default: () => [] }
})

const APP_CONTAINER = /^jl-app-(\d+)$/
const APP_JOB = import.meta.env.VITE_LAB_PROM_JOB || 'seckill-app'
const JOB = `job="${APP_JOB}"`

const METRICS = [
  {
    key: 'cpu',
    label: 'CPU',
    expr: `sum by (instance) (process_cpu_usage{${JOB}})`,
    format: formatCpu
  },
  {
    key: 'heap',
    label: '堆',
    expr: `sum by (instance) (jvm_memory_used_bytes{${JOB},area="heap"})`,
    format: formatBytes
  },
  {
    key: 'rps',
    label: '请求速率',
    expr: `sum by (instance) (rate(http_server_requests_seconds_count{${JOB}}[1m]))`,
    format: formatRate
  },
  {
    key: 'p95',
    label: 'P95',
    expr: `histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket{${JOB}}[1m])))`,
    format: formatMillis
  },
  {
    key: 'threads',
    label: '线程',
    expr: `max by (instance) (jvm_threads_live_threads{${JOB}})`,
    format: integer
  },
  {
    key: 'poolActive',
    label: '池活跃',
    // Spring actuator exports executor pool metrics as executor_active_threads.
    expr: `sum by (instance) (executor_active_threads{${JOB}})`,
    format: integer
  },
  {
    key: 'poolQueued',
    label: '池队列',
    // executor_queue_size in the design doc is executor_queued_tasks in the registry.
    expr: `sum by (instance) (executor_queued_tasks{${JOB}})`,
    format: integer
  },
  {
    key: 'rejected',
    label: '拒绝',
    expr: `sum by (instance) (increase(executor_rejected_total{${JOB}}[5m]))`,
    format: integer
  }
]

const metrics = reactive({})
const errors = ref([])
const loading = ref(false)
const lastSync = ref(null)

let inFlight = false
let queued = false
let disposed = false

const enabledSet = computed(() => new Set(
  (props.enabledNodes || [])
    .map((value) => String(value || '').trim().replace(/^jl-/, ''))
    .filter(Boolean)
))
const restrictToEnabled = computed(() => enabledSet.value.size > 0)

const rows = computed(() => (props.containers || [])
  .map((container) => {
    const match = APP_CONTAINER.exec(container.name || '')
    if (!match) return null
    const index = Number(match[1])
    const name = `app-${index}`
    return {
      index,
      name,
      container: container.name,
      state: container.state || 'unknown',
      health: container.health || 'none',
      enabled: !restrictToEnabled.value || enabledSet.value.has(name)
    }
  })
  .filter(Boolean)
  .sort((a, b) => a.index - b.index))

const dataTimeLabel = computed(() => (
  lastSync.value ? lastSync.value.toLocaleTimeString('zh-CN', { hour12: false }) : '—'
))

// Executor metrics exist in the registry's historical name list, but the app
// does not currently export them; say so instead of leaving three silent 无数据.
const executorMissing = computed(() => Boolean(lastSync.value) && ['poolActive', 'poolQueued', 'rejected']
  .every((key) => !Object.keys(metrics[key] || {}).length))

function integer(value) {
  return String(Math.round(value))
}

/** Map "app-1:8080" -> "app-1" so a row can look up any aggregate by name. */
function byInstance(result) {
  const map = {}
  for (const item of result || []) {
    const instance = item?.metric?.instance
    const value = Number(item?.value?.[1])
    if (!instance || !Number.isFinite(value)) continue
    map[String(instance).replace(/:8080$/, '')] = value
  }
  return map
}

function valueOf(metricKey, name) {
  const value = metrics[metricKey]?.[name]
  return Number.isFinite(value) ? value : null
}

function cellText(metric, row) {
  if (!row.enabled) return '无数据'
  const value = valueOf(metric.key, row.name)
  return value === null ? '无数据' : metric.format(value)
}

function stateClass(state) {
  return state === 'running' ? 'ok' : 'muted'
}

async function refresh() {
  if (!props.active || disposed) return
  if (inFlight) {
    queued = true
    return
  }
  inFlight = true
  loading.value = true
  const jobs = METRICS.map(async (metric) => {
    try {
      metrics[metric.key] = byInstance(await queryProm(metric.expr))
      return null
    } catch (err) {
      metrics[metric.key] = {}
      return { metric, err }
    }
  })
  const results = await Promise.all(jobs)
  if (!disposed) {
    const failures = results.filter(Boolean)
    errors.value = failures.length
      ? failures.map(({ metric, err }) => `${metric.label}：${describeError(err)}`)
      : []
    lastSync.value = new Date()
    loading.value = false
  }
  inFlight = false
  if (queued) {
    queued = false
    refresh()
  }
}

watch(
  () => [props.active, props.syncTick],
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
  <section class="card instance-metrics-card" data-testid="instance-metrics-table">
    <div class="card-head">
      <h2>实例指标</h2>
      <div class="bench-actions">
        <span class="hint" data-testid="instance-metrics-data-time">数据时间 {{ dataTimeLabel }}</span>
        <span v-if="loading" class="hint">刷新中…</span>
      </div>
    </div>

    <p class="hint">
      行来自 <code>GET /lab-api/containers</code> 的 <code>jl-app-N</code>；每列一条按
      <code>instance="app-N:8080"</code> 聚合的 PromQL。未启用的节点（不在当前 run 的启用节点内）
      或缺失的序列显示无数据，不显示 0。
    </p>

    <div v-if="errors.length" class="notice warn" role="alert" data-testid="instance-metrics-error">
      部分查询失败：{{ errors.join('；') }}
    </div>

    <p v-if="!rows.length" class="empty" data-testid="instance-metrics-empty">
      无数据：容器列表里没有 jl-app-N。
    </p>

    <table v-else class="data-table">
      <thead>
        <tr>
          <th>实例</th>
          <th>状态</th>
          <th>CPU</th>
          <th>堆</th>
          <th>请求速率</th>
          <th>P95</th>
          <th>线程</th>
          <th>池活跃</th>
          <th>池队列</th>
          <th>拒绝</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in rows"
          :key="row.name"
          :class="{ disabled: !row.enabled }"
          :data-testid="`instance-row-${row.name}`"
        >
          <td>
            <code>{{ row.name }}</code>
            <span class="hint instance-container">{{ row.container }}</span>
          </td>
          <td>
            <span class="badge" :class="stateClass(row.state)">{{ row.state }}</span>
            <span
              class="badge"
              :class="row.enabled ? 'ok' : 'muted'"
              :data-testid="`instance-enabled-${row.index}`"
            >{{ row.enabled ? '已启用' : '未启用' }}</span>
          </td>
          <td v-for="metric in METRICS" :key="metric.key">{{ cellText(metric, row) }}</td>
        </tr>
      </tbody>
    </table>

    <p v-if="restrictToEnabled && rows.some((row) => !row.enabled)" class="hint">
      灰显行：{{ rows.filter((row) => !row.enabled).map((row) => row.name).join(' / ') }} 不在启用节点内。
    </p>
    <p v-if="executorMissing" class="notice warn" data-testid="instance-metrics-executor-missing">
      Prometheus 当前没有 <code>executor_*</code> 序列（应用未导出线程池指标），因此池活跃 / 池队列 / 拒绝显示无数据；连接池 pending 在时间轴里可见。
    </p>
    <p v-if="!METRICS.some((metric) => Object.keys(metrics[metric.key] || {}).length)" class="hint">
      还没有任何实例序列：应用可能未启动，或 Prometheus 未完成第一次抓取。拒绝列依赖
      <code>executor_rejected_total</code>，该序列缺失时显示无数据。
    </p>
  </section>
</template>

<style scoped>
.instance-container {
  margin-left: 6px;
}

.instance-metrics-card .data-table td {
  white-space: nowrap;
}

.instance-metrics-card .data-table td:nth-child(2) .badge + .badge {
  margin-left: 4px;
}
</style>
