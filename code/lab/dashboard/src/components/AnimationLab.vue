<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CacheFlow from './CacheFlow.vue'
import InstancesView from './InstancesView.vue'
import TopologyView from './TopologyView.vue'
import {
  describeError,
  fetchActiveFaults,
  fetchContainers,
  fetchGuideState,
  fetchToxiproxyState,
  queryProm
} from '../api'
import { APP_CONTAINER_NAME, instanceName } from '../labModel'

const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false }
})
const emit = defineEmits(['open-thread'])

const SYNC_INTERVAL_MS = Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000)
const APP_JOB = import.meta.env.VITE_LAB_PROM_JOB || 'seckill-app'
const PAUSE_KEY = 'ha-lab-animation-paused'

const TABS = [
  { id: 'topology', label: '拓扑视图', hint: '实际在跑的容器与活动故障' },
  { id: 'instances', label: '实例视图', hint: '每实例 CPU / 堆 / 速率 / P99' },
  { id: 'cache', label: '缓存流动', hint: 'L1 → L2 → DB 粒子动画' }
]

const tab = ref('topology')
const containers = ref([])
const faults = ref([])
const guide = ref(null)
const toxiproxy = ref(null)
const instanceMetrics = ref({})
const cache = ref({
  l1: { total: null, hit: null, miss: null, hitRatio: null, present: false },
  l2: { total: null, hit: null, miss: null, hitRatio: null, present: false },
  fallbacks: { total: null, reasons: [], present: false },
  available: false
})
const error = ref('')
const promNote = ref('')
const busy = ref('')
const lastSync = ref(null)
const paused = ref(false)
const reducedMotion = ref(false)

let pollTimer = null
let motionQuery = null
let motionListener = null
let inFlight = false
let queued = false

const guideStatus = computed(() => guide.value?.status || 'unknown')
const appNames = computed(() =>
  containers.value.filter((item) => APP_CONTAINER_NAME.test(item.name)).map((item) => instanceName(item.name))
)
const runningContainers = computed(() => containers.value.filter((item) => item.state === 'running').length)
const lastSyncLabel = computed(() =>
  lastSync.value ? lastSync.value.toLocaleTimeString('zh-CN', { hour12: false }) : '—'
)

// ------------------------------------------------------------------ promql
function promSum(result) {
  const samples = result || []
  let total = 0
  let found = false
  for (const sample of samples) {
    const value = Number(sample.value?.[1])
    if (Number.isFinite(value)) {
      total += value
      found = true
    }
  }
  return found ? total : null
}

function promByLabel(result, label) {
  const map = new Map()
  for (const sample of result || []) {
    const key = sample.metric?.[label]
    const value = Number(sample.value?.[1])
    if (!key || !Number.isFinite(value)) continue
    map.set(key, (map.get(key) || 0) + value)
  }
  return map
}

function promByInstance(result) {
  const map = new Map()
  for (const sample of result || []) {
    const key = String(sample.metric?.instance || '').split(':')[0]
    const value = Number(sample.value?.[1])
    if (!key || !Number.isFinite(value)) continue
    map.set(key, value)
  }
  return map
}

function hitsTier(map, present) {
  const hit = map.get('hit') ?? null
  const miss = map.get('miss') ?? null
  const total = present ? [...map.values()].reduce((sum, value) => sum + value, 0) : null
  const hitRatio = hit !== null && Number.isFinite(total) && total > 0 ? hit / total : null
  return { hit, miss, total, hitRatio, present }
}

// ------------------------------------------------------------------ loading
async function loadInstanceMetrics() {
  const names = appNames.value
  if (!names.length) {
    instanceMetrics.value = {}
    return
  }
  try {
    const [cpu, heap, rate, p99, threads] = await Promise.all([
      queryProm(`max by (instance) (process_cpu_usage{job="${APP_JOB}"})`),
      queryProm(`sum by (instance) (jvm_memory_used_bytes{job="${APP_JOB}",area="heap"})`),
      queryProm(`sum by (instance) (rate(http_server_requests_seconds_count{job="${APP_JOB}"}[1m]))`),
      queryProm(`histogram_quantile(0.99, sum by (le, instance) (rate(http_server_requests_seconds_bucket{job="${APP_JOB}"}[1m])))`),
      queryProm(`max by (instance) (jvm_threads_live_threads{job="${APP_JOB}"})`)
    ])
    const cpuMap = promByInstance(cpu)
    const heapMap = promByInstance(heap)
    const rateMap = promByInstance(rate)
    const p99Map = promByInstance(p99)
    const threadMap = promByInstance(threads)
    instanceMetrics.value = Object.fromEntries(
      names.map((name) => [name, {
        cpu: cpuMap.get(name) ?? null,
        heapBytes: heapMap.get(name) ?? null,
        reqRate: rateMap.get(name) ?? null,
        p99Seconds: p99Map.get(name) ?? null,
        threads: threadMap.get(name) ?? null
      }])
    )
    promNote.value = ''
  } catch (err) {
    promNote.value = `Prometheus 查询失败（/prom/ 代理）：${describeError(err)}。实例卡片全部显示「无数据」。`
    instanceMetrics.value = Object.fromEntries(
      names.map((name) => [name, { cpu: null, heapBytes: null, reqRate: null, p99Seconds: null, threads: null }])
    )
  }
}

async function loadCacheMetrics() {
  try {
    const [l1Result, l2Result, fallbackResult] = await Promise.all([
      queryProm(`sum by (result) (rate(cache_l1_requests_total{job="${APP_JOB}"}[1m]))`),
      queryProm(`sum by (result) (rate(cache_l2_requests_total{job="${APP_JOB}"}[1m]))`),
      queryProm(`sum by (reason) (rate(cache_db_fallbacks_total{job="${APP_JOB}"}[1m]))`)
    ])
    const l1Tier = hitsTier(promByLabel(l1Result, 'result'), l1Result.length > 0)
    const l2Tier = hitsTier(promByLabel(l2Result, 'result'), l2Result.length > 0)
    const reasons = [...promByLabel(fallbackResult, 'reason').entries()]
      .map(([reason, value]) => ({ reason, value }))
      .sort((a, b) => b.value - a.value)
    const fallbackTotal = fallbackResult.length ? promSum(fallbackResult) : null
    cache.value = {
      l1: l1Tier,
      l2: l2Tier,
      fallbacks: { total: fallbackTotal, reasons, present: fallbackResult.length > 0 },
      available: l1Result.length > 0 || l2Result.length > 0 || fallbackResult.length > 0
    }
    promNote.value = ''
  } catch (err) {
    promNote.value = `Prometheus 查询失败（/prom/ 代理）：${describeError(err)}。缓存图显示「无数据」。`
    cache.value = {
      l1: { total: null, hit: null, miss: null, hitRatio: null, present: false },
      l2: { total: null, hit: null, miss: null, hitRatio: null, present: false },
      fallbacks: { total: null, reasons: [], present: false },
      available: false
    }
  }
}

async function refresh({ quiet = false } = {}) {
  if (!props.active || !props.token) return
  if (inFlight) {
    queued = true
    return
  }
  inFlight = true
  if (!quiet) busy.value = 'refresh'
  error.value = ''
  const scope = tab.value
  try {
    const [list, actions, guideState] = await Promise.all([
      fetchContainers(props.token),
      fetchActiveFaults(props.token),
      fetchGuideState(props.token)
    ])
    containers.value = list || []
    faults.value = actions || []
    guide.value = guideState
    // The proxy layer is optional: only ask when its container is running, and
    // never let its absence fail the whole poll.
    if (containers.value.some((item) => item.name === 'jl-toxiproxy')) {
      try {
        toxiproxy.value = await fetchToxiproxyState(props.token)
      } catch {
        toxiproxy.value = null
      }
    } else {
      toxiproxy.value = null
    }
    if (scope === tab.value) {
      if (scope === 'instances') await loadInstanceMetrics()
      else if (scope === 'cache') await loadCacheMetrics()
      else promNote.value = ''
    }
    lastSync.value = new Date()
  } catch (err) {
    error.value = describeError(err)
  } finally {
    inFlight = false
    if (!quiet) busy.value = ''
    if (queued) {
      queued = false
      if (props.active) refresh({ quiet: true })
    }
  }
}

function selectTab(id) {
  if (tab.value === id) return
  tab.value = id
  error.value = ''
  promNote.value = ''
  refresh()
}

function togglePause() {
  paused.value = !paused.value
  localStorage.setItem(PAUSE_KEY, String(paused.value))
}

// ------------------------------------------------------------------ polling
function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => {
    if (!busy.value) refresh({ quiet: true })
  }, SYNC_INTERVAL_MS)
}

watch(
  () => props.active,
  (active) => {
    if (active) {
      refresh()
      startPolling()
    } else {
      stopPolling()
    }
  },
  { immediate: true }
)

watch(() => props.token, () => {
  if (props.active) refresh()
})

onMounted(() => {
  motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)') || null
  reducedMotion.value = Boolean(motionQuery?.matches)
  const stored = localStorage.getItem(PAUSE_KEY)
  paused.value = stored === null ? reducedMotion.value : stored === 'true'
  if (motionQuery) {
    motionListener = (event) => {
      reducedMotion.value = event.matches
      // Honour a mid-session OS change immediately; the user can still press play.
      if (event.matches) paused.value = true
    }
    motionQuery.addEventListener?.('change', motionListener)
  }
})

onBeforeUnmount(() => {
  stopPolling()
  if (motionQuery && motionListener) {
    motionQuery.removeEventListener?.('change', motionListener)
  }
})
</script>

<template>
  <section class="animation-page" data-testid="animation-page">
    <section class="status-bar">
      <div class="status-item">
        <span class="status-label">向导</span>
        <span class="pill" :class="guideStatus">{{ guideStatus }}</span>
        <span class="status-value">{{ guide?.scenarioName || '未载入场景' }}</span>
      </div>
      <div class="status-item">
        <span class="status-label">活动故障</span>
        <span class="pill" :class="faults.length ? 'warn' : 'ok'" data-testid="animation-fault-count">{{ faults.length }}</span>
      </div>
      <div class="status-item">
        <span class="status-label">容器</span>
        <span class="pill" :class="runningContainers === containers.length && containers.length ? 'ok' : 'warn'">
          {{ runningContainers }}/{{ containers.length }}
        </span>
      </div>
      <div class="status-item">
        <button type="button" class="btn ghost" data-testid="animation-refresh" :disabled="Boolean(busy) || !token" @click="refresh()">
          {{ busy === 'refresh' ? '同步中…' : '同步数据' }}
        </button>
      </div>
      <div class="status-item">
        <span class="status-label">上次同步</span>
        <span class="status-value" data-testid="animation-last-sync">{{ lastSyncLabel }}</span>
      </div>
      <div class="status-item status-note">
        令牌在「实验操作台」页设置 · 指标经 <code>/prom/</code> 查询
      </div>
    </section>

    <nav class="view-tabs" aria-label="动画视图">
      <button
        v-for="item in TABS"
        :key="item.id"
        type="button"
        class="nav-btn"
        :class="{ active: tab === item.id }"
        :data-testid="`animation-tab-${item.id}`"
        :aria-current="tab === item.id ? 'page' : undefined"
        :title="item.hint"
        @click="selectTab(item.id)"
      >{{ item.label }}</button>
    </nav>

    <div v-if="error" class="error-banner" role="alert" data-testid="animation-error">{{ error }}</div>
    <div v-if="promNote && tab !== 'topology'" class="notice" role="status" data-testid="animation-prom-note">
      {{ promNote }}
    </div>

    <TopologyView
      v-if="tab === 'topology'"
      :containers="containers"
      :faults="faults"
      :toxiproxy="toxiproxy"
    />
    <InstancesView
      v-else-if="tab === 'instances'"
      :containers="containers"
      :faults="faults"
      :metrics="instanceMetrics"
      :prom-note="promNote"
      @open-thread="emit('open-thread', $event)"
    />
    <CacheFlow
      v-else
      :cache="cache"
      :paused="paused"
      :reduced-motion="reducedMotion"
      @toggle-pause="togglePause"
    />
  </section>
</template>
