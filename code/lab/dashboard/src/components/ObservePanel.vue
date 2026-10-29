<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import MetricCards from './MetricCards.vue'
import TopologyView from './TopologyView.vue'
import { describeError, fetchWindowMetrics } from '../api'

/**
 * Live observation: the topology built from the same /containers call as the
 * rest of the console, plus the B1-03 aggregation window. PromQL never leaves
 * the controller; only catalog keys cross the boundary. A missing series is
 * rendered as NO_DATA/COLLECTING with its reason, never as 0.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  toxiproxy: { type: Object, default: null },
  showTopology: { type: Boolean, default: true }
})

const DEFAULT_KPI_KEYS = [
  'http.rps',
  'http.latency.p95',
  'http.failure.ratio',
  'jvm.heap.used',
  'jvm.gc.pause.max',
  'jvm.threads.live',
  'hikari.active',
  'executor.rejected',
  'cache.l1.hit.ratio',
  'cache.l2.keys',
  'business.orders.created',
  'business.stock.oversold',
  'mysql.cluster.probe.up'
]
const KPI_KEYS = (import.meta.env.VITE_LAB_KPI_KEYS || DEFAULT_KPI_KEYS.join(','))
  .split(',')
  .map((key) => key.trim())
  .filter(Boolean)
// The KPI window is a view over the last N seconds; not a poll interval, so it
// has its own VITE_* override instead of reusing the sync interval.
const LIVE_WINDOW_SECONDS = Number(import.meta.env.VITE_LAB_LIVE_WINDOW_SECONDS || 300)

const query = ref(null)
const error = ref('')
const loading = ref(false)
const lastSync = ref(null)

let inFlight = false
let queued = false

const metrics = computed(() => query.value?.metrics || [])
const overall = computed(() => query.value?.quality || '')
const reason = computed(() => query.value?.reason || '')
const windowLabel = computed(() => {
  const from = query.value?.window?.from
  const to = query.value?.window?.to
  if (!from || !to) return ''
  return `${from} → ${to}`
})

function timeLabel(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString('zh-CN', { hour12: false })
}

async function refresh() {
  if (!props.active || !props.token) return
  if (inFlight) {
    queued = true
    return
  }
  inFlight = true
  loading.value = true
  error.value = ''
  const now = new Date()
  const from = new Date(now.getTime() - LIVE_WINDOW_SECONDS * 1000)
  try {
    query.value = await fetchWindowMetrics(props.token, {
      from: from.toISOString(),
      to: now.toISOString(),
      keys: KPI_KEYS.join(','),
      phase: 'adhoc'
    })
    lastSync.value = new Date()
  } catch (err) {
    error.value = `指标聚合查询失败（/lab/metrics/window）：${describeError(err)}`
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
  inFlight = false
  queued = false
})
</script>

<template>
  <div class="observe-panel">
    <section class="card">
      <div class="card-head">
        <h2>实时监控</h2>
        <div class="bench-actions">
          <span class="hint" data-testid="observe-last-sync">上次同步 {{ timeLabel(lastSync) }}</span>
          <button type="button" class="btn ghost" data-testid="observe-refresh" :disabled="loading || !token" @click="refresh">
            {{ loading ? '查询中…' : '刷新指标' }}
          </button>
        </div>
      </div>
      <p class="hint">
        KPI 来自 <code>GET /lab-api/lab/metrics/window</code>（目录键白名单，PromQL 不下发）。
        质量徽标 <code>OK / NO_DATA / COLLECTING / DEGRADED</code> 是数据事实：无序列时显示无数据，不显示 0。
      </p>
      <p v-if="windowLabel" class="hint">窗口 {{ windowLabel }} · 条目 {{ metrics.length }} · 质量 {{ overall || '—' }}</p>
      <p v-if="query?.links?.grafanaUrl" class="hint">
        <a :href="query.links.grafanaUrl" target="_blank" rel="noreferrer">在 Grafana 打开当前时间范围</a>
        <template v-if="query.links.tempoUrl"> · <a :href="query.links.tempoUrl" target="_blank" rel="noreferrer">Tempo 链路检索（Grafana Explore）</a></template>
      </p>
      <p v-if="query?.links?.tempoUrl" class="hint">
        Tempo 链路检索按当前时间窗打开 Grafana Explore 的 Tempo 数据源（TraceQL 空查询 = 全部链路）；
        Tempo 的 3200 端口只在 compose 网络内，浏览器不能直连。
      </p>
    </section>

    <div v-if="error" class="error-banner" role="alert" data-testid="observe-error">{{ error }}</div>

    <MetricCards
      :metrics="metrics"
      :overall="overall"
      :reason="reason"
      :generated-at="query?.generatedAt || ''"
      :window="query?.window || null"
      title="核心 KPI"
    />

    <section v-if="showTopology" class="card">
      <div class="card-head">
        <h2>拓扑</h2>
        <span class="hint">节点全部来自 <code>GET /lab-api/containers</code>，故障徽标来自活动故障列表</span>
      </div>
      <TopologyView :containers="containers" :faults="faults" :toxiproxy="toxiproxy" />
    </section>
  </div>
</template>
