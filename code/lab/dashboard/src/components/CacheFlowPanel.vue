<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CacheFlow from './CacheFlow.vue'
import { describeError, fetchCacheState, queryProm } from '../api'

/**
 * Per-instance cache flow board for the monitor sheet.
 *
 * Prometheus counters are filtered by this instance's `instance` label, so two
 * app nodes with different L1 states can never be mixed into one picture. When
 * L1 is off the backend stops incrementing cache_l1_requests; the board reroutes
 * the lanes instead of drawing an empty line.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  instances: { type: Array, default: () => [] }
})

const APP_JOB = import.meta.env.VITE_LAB_PROM_JOB || 'seckill-app'
const PAUSE_KEY = 'ha-lab-animation-paused'

const instance = ref('')
const state = ref(null)
const cache = ref(emptyCache())
const error = ref('')
const lastSync = ref(null)
const paused = ref(false)
const reducedMotion = ref(false)

let inFlight = false
let disposed = false
let motionQuery = null
let motionListener = null

function emptyCache() {
  return {
    l1: { total: null, hit: null, miss: null, hitRatio: null, present: false },
    l2: { total: null, hit: null, miss: null, hitRatio: null, present: false },
    fallbacks: { total: null, reasons: [], present: false },
    available: false
  }
}

const names = computed(() => (props.instances.length ? props.instances : ['app-1', 'app-2', 'app-3']))
const l1Enabled = computed(() => {
  if (typeof state.value?.l1Enabled === 'boolean') return state.value.l1Enabled
  return cache.value.l1Enabled ?? null
})
const mode = computed(() => state.value?.defense?.mode || '')
const dataTimeLabel = computed(() => (
  lastSync.value ? lastSync.value.toLocaleTimeString('zh-CN', { hour12: false }) : '—'
))

watch(
  names,
  (list) => {
    if (!list.includes(instance.value)) instance.value = list[0] || ''
  },
  { immediate: true }
)

function promSum(result) {
  let total = 0
  let found = false
  for (const sample of result || []) {
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

function hitsTier(map, present) {
  const hit = map.get('hit') ?? null
  const miss = map.get('miss') ?? null
  const total = present ? [...map.values()].reduce((sum, value) => sum + value, 0) : null
  const hitRatio = hit !== null && Number.isFinite(total) && total > 0 ? hit / total : null
  return { hit, miss, total, hitRatio, present }
}

function scopeFor(name) {
  return `job="${APP_JOB}",instance=~"${name}(:.*)?"`
}

async function refresh() {
  if (!props.active || !props.token || !instance.value || disposed) return
  if (inFlight) return
  inFlight = true
  error.value = ''
  const target = instance.value
  const scope = scopeFor(target)
  try {
    const [l1Result, l2Result, fallbackResult, gauge, appState] = await Promise.all([
      queryProm(`sum by (result) (rate(cache_l1_requests_total{${scope}}[1m]))`),
      queryProm(`sum by (result) (rate(cache_l2_requests_total{${scope}}[1m]))`),
      queryProm(`sum by (reason) (rate(cache_db_fallbacks_total{${scope}}[1m]))`),
      queryProm(`max(cache_l1_enabled{${scope}})`).catch(() => []),
      fetchCacheState(props.token, target).catch(() => null)
    ])
    if (target !== instance.value) return
    const l1Tier = hitsTier(promByLabel(l1Result, 'result'), l1Result.length > 0)
    const l2Tier = hitsTier(promByLabel(l2Result, 'result'), l2Result.length > 0)
    const reasons = [...promByLabel(fallbackResult, 'reason').entries()]
      .map(([reason, value]) => ({ reason, value }))
      .sort((a, b) => b.value - a.value)
    const gaugeValue = promSum(gauge)
    const nextL1 = typeof appState?.l1Enabled === 'boolean'
      ? appState.l1Enabled
      : (gaugeValue === null ? null : gaugeValue > 0)
    cache.value = {
      l1: l1Tier,
      l2: l2Tier,
      fallbacks: { total: fallbackResult.length ? promSum(fallbackResult) : null, reasons, present: fallbackResult.length > 0 },
      available: l1Result.length > 0 || l2Result.length > 0 || fallbackResult.length > 0,
      l1Enabled: nextL1
    }
    state.value = appState
    lastSync.value = new Date()
  } catch (err) {
    if (target === instance.value) {
      error.value = `按实例查询缓存指标失败：${describeError(err)}`
      cache.value = { ...emptyCache(), l1Enabled: null }
      state.value = null
    }
  } finally {
    inFlight = false
  }
}

function togglePause() {
  paused.value = !paused.value
  localStorage.setItem(PAUSE_KEY, String(paused.value))
}

watch(
  () => [props.active, props.syncTick, props.token, instance.value],
  ([active]) => {
    if (active) refresh()
  },
  { immediate: true }
)

onMounted(() => {
  motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)') || null
  reducedMotion.value = Boolean(motionQuery?.matches)
  const stored = localStorage.getItem(PAUSE_KEY)
  paused.value = stored === null ? reducedMotion.value : stored === 'true'
  if (motionQuery) {
    motionListener = (event) => {
      reducedMotion.value = event.matches
      if (event.matches) paused.value = true
    }
    motionQuery.addEventListener?.('change', motionListener)
  }
})

onBeforeUnmount(() => {
  disposed = true
  if (motionQuery && motionListener) {
    motionQuery.removeEventListener?.('change', motionListener)
  }
})
</script>

<template>
  <section class="card cache-flow-panel" data-testid="cache-flow-panel">
    <div class="cache-flow-toolbar">
      <label class="form-label">
        实例
        <select v-model="instance" class="input select" data-testid="cache-flow-instance-select">
          <option v-for="name in names" :key="name" :value="name">{{ name }}</option>
        </select>
      </label>
      <span class="hint" data-testid="cache-flow-data-time">数据时间 {{ dataTimeLabel }}</span>
      <span class="hint">按 <code>instance</code> 标签隔离；L1 开关与防御模式来自该实例的运行时状态。</span>
    </div>

    <div v-if="error" class="error-banner" role="alert" data-testid="cache-flow-error">{{ error }}</div>

    <CacheFlow
      :cache="cache"
      :paused="paused"
      :reduced-motion="reducedMotion"
      :l1-enabled="l1Enabled"
      :mode="mode"
      :instance="instance"
      @toggle-pause="togglePause"
    />
  </section>
</template>

<style scoped>
.cache-flow-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cache-flow-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
}
</style>
