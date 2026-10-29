<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import InstancesView from './InstancesView.vue'
import ThreadLab from './ThreadLab.vue'
import { describeError, fetchRuns, fetchWindowMetrics } from '../api'

/**
 * Instance and JVM workspace. The instance cards read the same B1-03 window as
 * the KPI page (five catalog keys), and the thread lab is mounted unchanged.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  focusInstance: { type: String, default: '' },
  focusNonce: { type: Number, default: 0 },
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] }
})

const INSTANCE_KEYS = [
  'jvm.cpu.usage',
  'jvm.heap.used',
  'http.rps',
  'http.latency.p99',
  'jvm.threads.live'
]
// Card refresh follows the global sync tick and always covers the last window.
const INSTANCE_WINDOW_SECONDS = Number(import.meta.env.VITE_LAB_LIVE_WINDOW_SECONDS || 300)

const tab = ref('cards')
const targetInstance = ref(props.focusInstance)
const instanceMetrics = ref({})
const promNote = ref('')
// The newest run's node selection is the only source of "enabled" today; an
// empty list means the run predates B3-00a ("all configured").
const enabledNodes = ref([])
let inFlight = false
let queued = false

const threadActive = computed(() => props.active && tab.value === 'thread')

function instanceKey(labels) {
  return String(labels?.instance || '').split(':')[0]
}

function pick(map, name) {
  const value = map.get(name)
  return Number.isFinite(value) ? value : null
}

async function loadMetrics() {
  if (!props.active || !props.token || tab.value !== 'cards') return
  if (inFlight) {
    queued = true
    return
  }
  inFlight = true
  const now = new Date()
  const from = new Date(now.getTime() - INSTANCE_WINDOW_SECONDS * 1000)
  try {
    const payload = await fetchWindowMetrics(props.token, {
      from: from.toISOString(),
      to: now.toISOString(),
      keys: INSTANCE_KEYS.join(','),
      phase: 'adhoc'
    })
    const byKey = new Map((payload?.metrics || []).map((metric) => [metric.key, metric]))
    const maps = Object.fromEntries(
      INSTANCE_KEYS.map((key) => {
        const metric = byKey.get(key)
        const map = new Map()
        for (const series of metric?.series || []) {
          const name = instanceKey(series.labels)
          const value = series?.stats?.last
          if (name && Number.isFinite(value)) map.set(name, value)
        }
        return [key, map]
      })
    )
    const names = new Set([
      ...props.containers.filter((item) => /^jl-app-\d+$/.test(item.name)).map((item) => item.name.replace(/^jl-/, '')),
      ...maps['jvm.cpu.usage'].keys()
    ])
    instanceMetrics.value = Object.fromEntries(
      [...names].map((name) => [name, {
        cpu: pick(maps['jvm.cpu.usage'], name),
        heapBytes: pick(maps['jvm.heap.used'], name),
        reqRate: pick(maps['http.rps'], name),
        p99Seconds: pick(maps['http.latency.p99'], name),
        threads: pick(maps['jvm.threads.live'], name)
      }])
    )
    const missing = payload?.quality === 'OK'
      ? ''
      : `指标质量 ${payload?.quality || 'NO_DATA'}：${payload?.reason || '窗口内没有序列'}。卡片按无数据显示，不填 0。`
    promNote.value = missing
  } catch (err) {
    promNote.value = `指标聚合查询失败（/lab/metrics/window）：${describeError(err)}。实例卡片按无数据显示。`
    instanceMetrics.value = {}
  } finally {
    inFlight = false
    if (queued) {
      queued = false
      loadMetrics()
    }
  }
}

function selectTab(id) {
  if (tab.value === id) return
  tab.value = id
  if (id === 'cards') loadMetrics()
}

async function loadEnabledNodes() {
  if (!props.active || !props.token) return
  try {
    const page = await fetchRuns(props.token, { limit: 1 })
    enabledNodes.value = page?.items?.[0]?.nodes || []
  } catch {
    // A ledger hiccup must not blank the instance page; treat it as "all on".
    enabledNodes.value = []
  }
}

watch(
  () => [props.active, props.syncTick, props.token],
  ([active]) => {
    if (active && tab.value === 'cards') loadMetrics()
    if (active) loadEnabledNodes()
  },
  { immediate: true }
)

watch(
  () => [props.focusInstance, props.focusNonce],
  ([instance]) => {
    if (instance) {
      targetInstance.value = instance
      tab.value = 'thread'
    }
  },
  { immediate: true }
)

/** A card's "线程实验室 →" opens the local thread tab at that instance. */
function openThread(instance) {
  targetInstance.value = instance
  tab.value = 'thread'
}

onBeforeUnmount(() => {
  inFlight = false
  queued = false
})
</script>

<template>
  <div class="instances-panel">
    <nav class="view-tabs" aria-label="实例与 JVM">
      <button
        type="button"
        class="nav-btn"
        :class="{ active: tab === 'cards' }"
        data-testid="instances-tab-cards"
        :aria-current="tab === 'cards' ? 'page' : undefined"
        @click="selectTab('cards')"
      >实例卡片</button>
      <button
        type="button"
        class="nav-btn"
        :class="{ active: tab === 'thread' }"
        data-testid="instances-tab-thread"
        :aria-current="tab === 'thread' ? 'page' : undefined"
        @click="selectTab('thread')"
      >线程实验室</button>
    </nav>

    <InstancesView
      v-if="tab === 'cards'"
      :containers="containers"
      :faults="faults"
      :metrics="instanceMetrics"
      :prom-note="promNote"
      :enabled-nodes="enabledNodes"
      @open-thread="openThread"
    />
    <ThreadLab
      v-else
      :token="token"
      :active="threadActive"
      :focus-instance="targetInstance"
    />
  </div>
</template>
