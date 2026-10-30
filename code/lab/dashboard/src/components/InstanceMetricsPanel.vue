<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import InstancesView from './InstancesView.vue'
import { describeError, queryProm } from '../api'

/**
 * Monitor-page instance section.
 *
 * Cards are the primary view (one card per jl-app-N); the detailed table stays
 * available in a collapsed block so the same numbers can be compared row by row
 * without leaving the monitor sheet. Both views read the same PromQL results.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  enabledNodes: { type: Array, default: () => [] }
})

const emit = defineEmits(['open-thread', 'open-config'])

const APP_CONTAINER = /^jl-app-(\d+)$/
const APP_JOB = import.meta.env.VITE_LAB_PROM_JOB || 'seckill-app'
const JOB = `job="${APP_JOB}"`

const METRICS = [
  {
    key: 'cpu',
    label: 'CPU',
    expr: `sum by (instance) (process_cpu_usage{${JOB}})`
  },
  {
    key: 'heap',
    label: '堆',
    expr: `sum by (instance) (jvm_memory_used_bytes{${JOB},area="heap"})`
  },
  {
    key: 'rps',
    label: '请求速率',
    expr: `sum by (instance) (rate(http_server_requests_seconds_count{${JOB}}[1m]))`
  },
  {
    key: 'latency',
    label: 'P95',
    expr: `histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket{${JOB}}[1m])))`
  },
  {
    key: 'threads',
    label: '线程',
    expr: `max by (instance) (jvm_threads_live_threads{${JOB}})`
  },
  {
    key: 'poolActive',
    label: '池活跃',
    expr: `sum by (instance) (executor_active_threads{${JOB}})`
  },
  {
    key: 'poolQueued',
    label: '池队列',
    expr: `sum by (instance) (executor_queued_tasks{${JOB}})`
  },
  {
    key: 'rejected',
    label: '拒绝',
    expr: `sum by (instance) (increase(executor_rejected_total{${JOB}}[5m]))`
  }
]

const metrics = reactive({})
const errors = ref([])
const loading = ref(false)
const lastSync = ref(null)

let inFlight = false
let queued = false
let disposed = false

const rows = computed(() => (props.containers || [])
  .map((container) => {
    const match = APP_CONTAINER.exec(container.name || '')
    if (!match) return null
    const index = Number(match[1])
    return {
      index,
      name: `app-${index}`,
      container: container.name,
      state: container.state || 'unknown',
      health: container.health || 'none'
    }
  })
  .filter(Boolean)
  .sort((a, b) => a.index - b.index))

const enabledSet = computed(() => new Set(
  (props.enabledNodes || [])
    .map((value) => String(value || '').trim().replace(/^jl-/, ''))
    .filter(Boolean)
))
const restrictToEnabled = computed(() => enabledSet.value.size > 0)

const cards = computed(() => Object.fromEntries(rows.value.map((row) => [row.name, {
  cpu: valueOf('cpu', row.name),
  heapBytes: valueOf('heap', row.name),
  reqRate: valueOf('rps', row.name),
  latencySeconds: valueOf('latency', row.name),
  threads: valueOf('threads', row.name),
  poolActive: valueOf('poolActive', row.name),
  poolQueued: valueOf('poolQueued', row.name),
  rejected: valueOf('rejected', row.name)
}])))

const dataTimeLabel = computed(() => (
  lastSync.value ? lastSync.value.toLocaleTimeString('zh-CN', { hour12: false }) : '—'
))

const executorMissing = computed(() => Boolean(lastSync.value) && ['poolActive', 'poolQueued', 'rejected']
  .every((key) => !Object.keys(metrics[key] || {}).length))

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
  if (restrictToEnabled.value && !enabledSet.value.has(row.name)) return '无数据'
  const value = valueOf(metric.key, row.name)
  return value === null ? '无数据' : format(metric.key, value)
}

function format(key, value) {
  if (key === 'cpu') return `${(value * 100).toFixed(1)}%`
  if (key === 'heap') {
    const mb = value / (1024 * 1024)
    return mb >= 1024 ? `${(mb / 1024).toFixed(2)} GB` : `${mb.toFixed(1)} MB`
  }
  if (key === 'rps') return `${value.toFixed(2)} /s`
  if (key === 'latency') return `${(value * 1000).toFixed(1)} ms`
  if (key === 'rejected') return String(Math.round(value))
  return String(Math.round(value))
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
  <section class="card instance-metrics-card" data-testid="instance-metrics">
    <div class="card-head">
      <h2>实例指标 <span class="count">{{ rows.length }}</span></h2>
      <div class="bench-actions">
        <span class="hint" data-testid="instance-metrics-data-time">数据时间 {{ dataTimeLabel }}</span>
        <span v-if="loading" class="hint">刷新中…</span>
      </div>
    </div>
    <p class="hint">
      每张卡片对应一个 <code>jl-app-N</code> 容器；数值来自 Prometheus 按
      <code>instance="app-N:8080"</code> 聚合。未启用或没有序列时显示“无数据”，不填 0。
    </p>

    <div v-if="errors.length" class="notice warn" role="alert" data-testid="instance-metrics-error">
      部分查询失败：{{ errors.join('；') }}
    </div>

    <p v-if="!rows.length" class="empty" data-testid="instance-metrics-empty">
      无数据：容器列表里没有 jl-app-N。
    </p>

    <InstancesView
      v-else
      :containers="containers"
      :faults="faults"
      :metrics="cards"
      :enabled-nodes="enabledNodes"
      @open-thread="emit('open-thread', $event)"
      @open-config="emit('open-config', $event)"
    />

    <p v-if="executorMissing" class="notice warn" data-testid="instance-metrics-executor-missing">
      Prometheus 当前没有 <code>executor_*</code> 序列（应用未导出线程池指标），池活跃 / 池队列 / 拒绝显示无数据。
    </p>

    <details class="instance-table-details">
      <summary>表格明细（同一批 PromQL，便于逐行对照）</summary>
      <table class="data-table" data-testid="instance-metrics-table">
        <thead>
          <tr>
            <th>实例</th>
            <th>状态</th>
            <th v-for="metric in METRICS" :key="metric.key">{{ metric.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.name"
            :class="{ disabled: restrictToEnabled && !enabledSet.has(row.name) }"
            :data-testid="`instance-row-${row.name}`"
          >
            <td><code>{{ row.name }}</code><span class="hint instance-container">{{ row.container }}</span></td>
            <td>
              <span class="badge" :class="stateClass(row.state)">{{ row.state }}</span>
              <span
                class="badge"
                :class="!restrictToEnabled || enabledSet.has(row.name) ? 'ok' : 'muted'"
                :data-testid="`instance-enabled-${row.index}`"
              >{{ !restrictToEnabled || enabledSet.has(row.name) ? '已启用' : '未启用' }}</span>
            </td>
            <td v-for="metric in METRICS" :key="metric.key">{{ cellText(metric, row) }}</td>
          </tr>
        </tbody>
      </table>
    </details>
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

.instance-table-details summary {
  cursor: pointer;
  color: var(--text-muted);
  font-size: 0.78rem;
  margin: 6px 0;
}
</style>
