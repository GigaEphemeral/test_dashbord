<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  describeError,
  fetchBenchScenarios,
  fetchOperation,
  stopAllFaults
} from '../api'

/**
 * Benchmark / operations corner: the read-only scenario catalog, the background
 * k6 traffic container, the container inventory and the log viewer. Nothing here
 * starts a benchmark script directly (B2 owns k6 execution).
 *
 * The catalog is the manifest projection served by GET /lab/bench/scenarios.
 * Only views.ui is rendered, so stress/soak (deliberately CLI-only) can never
 * appear. A missing/invalid manifest is 503/50303 and is presented as
 * DEGRADED "场景目录不可用" - an empty list must never look like a healthy OK.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  containers: { type: Array, default: () => [] },
  selected: { type: String, default: '' },
  tail: { type: [Number, String], default: 200 },
  logs: { type: String, default: '' },
  loadingContainers: { type: Boolean, default: false },
  loadingLogs: { type: Boolean, default: false },
  traffic: { type: Object, default: null },
  busy: { type: String, default: '' }
})

const emit = defineEmits([
  'refresh-containers',
  'select-container',
  'refresh-logs',
  'update:tail',
  'traffic',
  'changed'
])

const trafficRunning = computed(() => Boolean(props.traffic?.running))
const trafficState = computed(() => props.traffic?.state || 'unknown')

// B2-04b1/c: one stop-all reply carries four independent layer outcomes
// (containers, toxiproxy, traffic/threads, orphaned k6). Failures stay local
// to this card - they must never paint a global error or a DEGRADED state.
const OPERATION_POLL_INTERVAL_MS = Number(import.meta.env.VITE_LAB_OPERATION_POLL_INTERVAL_MS || 1000)
const OPERATION_POLL_TIMEOUT_MS = Number(import.meta.env.VITE_LAB_OPERATION_POLL_TIMEOUT_MS || 30000)

const stopAllBusy = ref(false)
const stopAllResult = ref(null)
const stopAllError = ref('')
const stopAllPending = ref('')

const threadEntries = computed(() => Object.entries(stopAllResult.value?.threads || {}))
const threadText = computed(() => (
  threadEntries.value.length
    ? threadEntries.value.map(([name, outcome]) => `${name}:${outcome}`).join(' ')
    : '未配置实例'
))
const trafficOutcome = computed(() => stopAllResult.value?.traffic || '—')
const toxiproxyStatus = computed(() => stopAllResult.value?.toxiproxy?.status || '—')
const containerFailed = computed(() => Number(stopAllResult.value?.failed || 0))
const containerPending = computed(() => Number(stopAllResult.value?.pending || 0))
const threadFailed = computed(() => threadEntries.value.filter(([, outcome]) => outcome !== 'ok').length)
const k6Failed = computed(() => Number(stopAllResult.value?.k6Failed || 0))
const toxiproxyWarn = computed(() => ['failed', 'partial'].includes(toxiproxyStatus.value))

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** A 202 reply only proves PENDING_CONFIRMATION; poll to the terminal state. */
async function pollStopAll(operationId) {
  if (!operationId) return null
  const deadline = Date.now() + OPERATION_POLL_TIMEOUT_MS
  let latest = null
  while (Date.now() < deadline) {
    latest = await fetchOperation(props.token, operationId)
    if (['APPLIED', 'RESTORED', 'FAILED', 'UNKNOWN'].includes(latest?.status)) {
      return latest
    }
    await sleep(OPERATION_POLL_INTERVAL_MS)
  }
  stopAllPending.value = `stop-all operation ${operationId} 仍在 PENDING_CONFIRMATION；刷新后继续观察。`
  return latest
}

async function clearAllFaults() {
  stopAllBusy.value = true
  stopAllError.value = ''
  stopAllPending.value = ''
  stopAllResult.value = null
  try {
    const { status, data } = await stopAllFaults(props.token)
    if (status === 202 || data?.status === 'PENDING_CONFIRMATION') {
      // The settled OperationView keeps the same afterSummary shape as the
      // synchronous StopAllResult, so one renderer serves both paths.
      const settled = await pollStopAll(data?.operationId)
      stopAllResult.value = settled?.afterSummary || settled || null
    } else {
      stopAllResult.value = data
    }
    emit('changed')
  } catch (err) {
    stopAllError.value = describeError(err)
  } finally {
    stopAllBusy.value = false
  }
}

const catalog = ref(null)
const catalogLoading = ref(false)
const catalogError = ref('')
const catalogUnavailable = ref(false)
let catalogInFlight = false
let catalogQueued = false

// Keys worth showing on a catalog row: load shape and SLO thresholds.
const PARAM_HINTS = /(VUS|DURATION|STAGES|P95_MS|P99_MS|ERROR_RATE|CHECKS_RATE)/
const MAX_KEY_PARAMS = 6

const catalogState = computed(() => {
  if (catalogError.value) return catalogUnavailable.value ? 'DEGRADED' : 'ERROR'
  if (catalog.value) return 'OK'
  return catalogLoading.value ? 'LOADING' : 'IDLE'
})

const catalogPillClass = computed(() => {
  if (catalogState.value === 'OK') return 'ok'
  if (catalogState.value === 'LOADING' || catalogState.value === 'IDLE') return 'muted'
  return 'down'
})

const uiScenarios = computed(() => {
  const views = catalog.value?.views?.ui
  const scenarios = catalog.value?.scenarios
  if (!Array.isArray(views) || !scenarios) return []
  return views
    .map((name) => ({ name, ...(scenarios[name] || {}) }))
    .filter((item) => item.script)
})

function formatParam(value) {
  if (Array.isArray(value)) return `${value.length} stages`
  if (value === null || value === undefined) return 'null'
  return String(value)
}

function keyParams(item) {
  return Object.entries(item.params || {})
    .filter(([key]) => PARAM_HINTS.test(key))
    .slice(0, MAX_KEY_PARAMS)
    .map(([key, value]) => `${key}=${formatParam(value)}`)
}

async function loadCatalog() {
  if (!props.active || !props.token) return
  if (catalogInFlight) {
    catalogQueued = true
    return
  }
  catalogInFlight = true
  catalogLoading.value = true
  try {
    catalog.value = await fetchBenchScenarios(props.token)
    catalogError.value = ''
    catalogUnavailable.value = false
  } catch (err) {
    // Never keep the previous rows as if they were current: a broken mount is
    // DEGRADED, not an empty-but-OK catalog.
    catalog.value = null
    catalogError.value = describeError(err)
    catalogUnavailable.value = err?.status === 503 || err?.code === '50303'
  } finally {
    catalogLoading.value = false
    catalogInFlight = false
    if (catalogQueued) {
      catalogQueued = false
      loadCatalog()
    }
  }
}

watch(
  () => [props.active, props.syncTick, props.token],
  ([active]) => {
    if (active) loadCatalog()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  catalogInFlight = false
  catalogQueued = false
})

function stateClass(state) {
  return state === 'running' ? 'ok' : 'warn'
}

function healthClass(health) {
  if (health === 'healthy') return 'ok'
  if (health === 'unhealthy') return 'down'
  return 'muted'
}

function onContainerChange(event) {
  emit('select-container', event.target.value)
}

function onTailChange(event) {
  emit('update:tail', Number(event.target.value))
}
</script>

<template>
  <div class="bench-panel">
    <section class="card" data-testid="bench-scenarios">
      <div class="card-head">
        <h2>
          场景目录
          <span v-if="catalogState === 'OK'" class="count">{{ uiScenarios.length }}</span>
        </h2>
        <span class="pill" :class="catalogPillClass" data-testid="bench-scenarios-state">{{ catalogState }}</span>
      </div>
      <p class="hint">
        只读 <code>scenarios/manifest.json</code>
        <template v-if="catalog?.version">（v{{ catalog.version }}）</template>，
        仅渲染 <code>views.ui</code>；stress/soak 按已确认口径只在 CLI 运行。
      </p>
      <p v-if="catalogState === 'DEGRADED'" class="hint scenario-hint" data-testid="bench-scenarios-hint">
        场景目录不可用：{{ catalogError }}
      </p>
      <p v-else-if="catalogState === 'ERROR'" class="hint scenario-hint error" data-testid="bench-scenarios-hint">
        场景目录加载失败：{{ catalogError }}
      </p>
      <p v-else-if="catalogState === 'LOADING'" class="hint" data-testid="bench-scenarios-hint">
        正在加载场景目录…
      </p>
      <ul class="scenario-list">
        <li
          v-for="item in uiScenarios"
          :key="item.name"
          class="scenario-row"
          :data-testid="`bench-scenario-${item.name}`"
        >
          <div class="scenario-head">
            <span class="scenario-name">{{ item.name }}</span>
            <span class="badge muted">profile {{ item.requires?.profile || '—' }}</span>
            <span class="badge" :class="item.requires?.chaos ? 'warn' : 'muted'">
              chaos {{ item.requires?.chaos ? '是' : '否' }}
            </span>
            <span class="badge" :class="item.requires?.cache ? 'warn' : 'muted'">
              cache {{ item.requires?.cache ? '是' : '否' }}
            </span>
            <span class="badge" :class="item.writesData ? 'warn' : 'muted'">
              写数据 {{ item.writesData ? '是' : '否' }}
            </span>
            <span class="badge" :class="item.thresholdsExpected ? 'ok' : 'muted'">
              阈值{{ item.thresholdsExpected ? '预期' : '不判定' }}
            </span>
          </div>
          <p class="scenario-params">{{ keyParams(item).join(' · ') || '无关键参数' }}</p>
          <p class="scenario-script">
            {{ item.script }}<span v-if="!item.scriptExists"> · 脚本未挂载（scriptExists=false）</span>
          </p>
        </li>
      </ul>
      <p v-if="catalogState === 'OK' && !uiScenarios.length" class="empty">
        目录里没有 UI 场景（views.ui 为空）。
      </p>
    </section>

    <section class="card">
      <div class="card-head">
        <h2>背景流量 · k6</h2>
        <span class="pill" :class="trafficRunning ? 'ok' : 'muted'" data-testid="traffic-state">{{ trafficState }}</span>
      </div>
      <p class="hint">
        受 profile 控制的 <code>jl-traffic</code> 容器（按需启动，不随栈常驻）。
        启停结果来自 <code>POST /lab/traffic/start|stop</code>；当前状态每轮同步刷新。
      </p>
      <div class="bench-actions">
        <button
          type="button"
          class="btn"
          data-testid="traffic-start"
          :disabled="trafficRunning || Boolean(busy)"
          @click="emit('traffic', 'start')"
        >{{ busy === 'traffic-start' ? '启动中…' : '开启背景流量' }}</button>
        <button
          type="button"
          class="btn ghost"
          data-testid="traffic-stop"
          :disabled="!trafficRunning || Boolean(busy)"
          @click="emit('traffic', 'stop')"
        >{{ busy === 'traffic-stop' ? '停止中…' : '停止背景流量' }}</button>
        <button
          type="button"
          class="btn ghost danger"
          data-testid="stop-all"
          :disabled="Boolean(busy) || stopAllBusy || !token"
          @click="clearAllFaults"
        >{{ stopAllBusy ? '还原中…' : '清除全部故障' }}</button>
      </div>
      <div v-if="stopAllError" class="error-banner" role="alert" data-testid="stop-all-error">{{ stopAllError }}</div>
      <p v-if="stopAllPending" class="notice" role="status" data-testid="stop-all-pending">{{ stopAllPending }}</p>
      <div v-if="stopAllResult" class="chaos-result" data-testid="stop-all-summary">
        <span>
          容器 已恢复 {{ stopAllResult.reverted ?? 0 }} ·
          失败
          <span class="badge" :class="containerFailed ? 'warn' : 'muted'" data-testid="stop-all-containers-failed">{{ containerFailed }}</span>
          · 延迟
          <span class="badge" :class="containerPending ? 'warn' : 'muted'" data-testid="stop-all-containers-pending">{{ containerPending }}</span>
        </span>
        <span>
          toxiproxy
          <span class="badge" :class="toxiproxyWarn ? 'warn' : 'muted'" data-testid="stop-all-toxiproxy">{{ toxiproxyStatus }}</span>
          <span class="hint">{{ stopAllResult.toxiproxy?.proxiesReset ?? 0 }}/{{ stopAllResult.toxiproxy?.proxiesTotal ?? 0 }} 已复位</span>
        </span>
        <span class="badge" :class="trafficOutcome === 'failed' ? 'warn' : trafficOutcome === 'stopped' ? 'ok' : 'muted'" data-testid="stop-all-traffic">traffic={{ trafficOutcome }}</span>
        <span class="badge" :class="threadFailed ? 'warn' : 'ok'" data-testid="stop-all-threads">threads={{ threadText }}</span>
        <span class="badge" :class="k6Failed ? 'warn' : 'muted'" data-testid="stop-all-k6">
          k6=stop {{ stopAllResult.k6Stopped ?? 0 }}/clean {{ stopAllResult.k6Cleaned ?? 0 }}/fail {{ k6Failed }}
        </span>
      </div>
      <p class="hint" data-testid="bench-k6-hint">
        正式 k6 场景仍走 CLI：<code>bash code/lab/scripts/bench.sh smoke</code>（B2 才提供受控执行器）。
      </p>
    </section>

    <div class="grid">
      <section class="card">
        <div class="card-head">
          <h2>容器清单 <span class="count">{{ containers.length }}</span></h2>
          <button type="button" class="btn ghost" :disabled="loadingContainers" @click="emit('refresh-containers')">
            {{ loadingContainers ? '加载中…' : '刷新' }}
          </button>
        </div>
        <p class="hint">仅显示 Docker 标签 <code>lab=java-learn</code> 的白名单容器。</p>
        <ul class="container-list">
          <li v-for="item in containers" :key="item.name">
            <button
              type="button"
              class="container-row"
              :class="{ selected: item.name === selected }"
              @click="emit('select-container', item.name)"
            >
              <span class="container-name">{{ item.name }}</span>
              <span class="badges">
                <span class="badge" :class="stateClass(item.state)">{{ item.state }}</span>
                <span class="badge" :class="healthClass(item.health)">{{ item.health }}</span>
              </span>
            </button>
          </li>
          <li v-if="!containers.length" class="empty">暂无容器，请检查令牌或控制器状态。</li>
        </ul>
      </section>

      <section class="card logs-card">
        <div class="card-head">
          <h2>日志</h2>
          <div class="log-controls">
            <select :value="selected" class="input select" aria-label="选择容器" data-testid="log-container" @change="onContainerChange">
              <option value="" disabled>选择容器</option>
              <option v-for="item in containers" :key="item.name" :value="item.name">{{ item.name }}</option>
            </select>
            <label class="tail-label">
              tail
              <input
                :value="tail"
                type="number"
                class="input tail-input"
                min="1"
                max="1000"
                @change="onTailChange"
              />
            </label>
            <button
              type="button"
              class="btn"
              data-testid="log-refresh"
              :disabled="loadingLogs || !selected"
              @click="emit('refresh-logs')"
            >{{ loadingLogs ? '读取中…' : '刷新日志' }}</button>
          </div>
        </div>
        <pre class="logs" data-testid="log-view">{{ logs || '（暂无日志，选择容器后刷新）' }}</pre>
        <p class="hint">只读 · tail 1–1000 · 经 Docker API 读取 stdout/stderr</p>
      </section>
    </div>
  </div>
</template>
