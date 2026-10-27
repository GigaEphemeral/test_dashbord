<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  describeError,
  fetchCachePreheat,
  fetchCacheState,
  runCachePreheat,
  seedCache,
  updateCacheMode
} from '../api'

const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  /** Instance preselected by another page (kept for symmetry with ThreadLab). */
  focusInstance: { type: String, default: '' }
})

// Same 5s budget as the other views. Unlike a mode switch, a seed does NOT take
// the busy flag: it can run for seconds, so the poller keeps refreshing state
// while the button shows progress.
const SYNC_INTERVAL_MS = Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000)
const INSTANCES = (import.meta.env.VITE_LAB_APP_INSTANCES || 'app-1,app-2,app-3')
  .split(',')
  .map((name) => name.trim())
  .filter(Boolean)
const GRAFANA_URL = (import.meta.env.VITE_LAB_GRAFANA_URL || 'http://127.0.0.1:3000')
  .replace(/\/+$/, '')
// D10 is opened in a new tab; the console never embeds an iframe.
const GRAFANA_D10 = `${GRAFANA_URL}/d/ha-d10`

/** Keys are the public contract of CacheDefenseMode; labels are console-only. */
const MODES = [
  { key: 'off', label: 'off · 无防护' },
  { key: 'null-cache', label: 'null-cache · 空值缓存' },
  { key: 'bloom', label: 'bloom · 布隆过滤器' },
  { key: 'mutex', label: 'mutex · 互斥重建' },
  { key: 'logical-expire', label: 'logical-expire · 逻辑过期' },
  { key: 'ttl-jitter', label: 'ttl-jitter · TTL 抖动' },
  { key: 'l1-cache', label: 'l1-cache · L1 镜像' },
  { key: 'db-fallback', label: 'db-fallback · DB 兜底限流' }
]
const MODE_KEYS = MODES.map((mode) => mode.key)

const instance = ref(INSTANCES[0] || 'app-1')
const state = ref(null)
const preheat = ref(null)
const selectedMode = ref('')
const seedResult = ref(null)
const busy = ref('')
const seeding = ref(false)
const error = ref('')

const currentMode = computed(() => state.value?.defense?.mode || '')
const cacheToggle = computed(() => Boolean(state.value?.defense?.cacheToggleEnabled))
const productCount = computed(() => Number(state.value?.dataset?.productCount) || 1000)

const defense = computed(() => state.value?.defense || {})
const breakdown = computed(() => state.value?.breakdown || {})
const avalanche = computed(() => state.value?.avalanche || {})
const dataset = computed(() => state.value?.dataset || {})
const keys = computed(() => state.value?.keys || {})
const bloom = computed(() => state.value?.bloom || {})
const nullCache = computed(() => state.value?.nullCache || {})

/** One row per strategy parameter group; empty groups collapse to “未启用”. */
const strategyFacts = computed(() => {
  const rows = [
    {
      label: '击穿防护',
      value: `${breakdown.value.strategy || '—'} · mutex ${onOff(breakdown.value.mutexActive)} · logical ${onOff(breakdown.value.logicalExpireActive)}`
    },
    {
      label: 'mutex 参数',
      value: `${num(breakdown.value.mutexWaitMs)}ms wait · ${num(breakdown.value.mutexLeaseMs)}ms lease · 超时 ${breakdown.value.mutexOnLockTimeout || '—'}`
    },
    {
      label: 'logical-expire',
      value: `ttl ${num(breakdown.value.logicalTtlSeconds)}s · 保留 ${num(breakdown.value.retentionSeconds)}s · 回源池 ${num(breakdown.value.refreshPoolSize)}/${num(breakdown.value.refreshQueueCapacity)}`
    },
    {
      label: '雪崩防护',
      value: `${avalanche.value.strategy || '—'} · jitter ${onOff(avalanche.value.ttlJitterActive)} · L1 ${onOff(avalanche.value.l1CacheActive)} · DB ${onOff(avalanche.value.dbFallbackActive)}`
    },
    {
      label: 'TTL 抖动',
      value: `±${formatNumber(avalanche.value.ttlJitterPercent)}% · 雪崩键存活 ${num(keys.value.l2AvalancheKeysAlive)}/${num(dataset.value.avalancheKeyCount)}`
    },
    {
      label: 'L1 镜像',
      value: `刷新 ${num(avalanche.value.l1RefreshIntervalSeconds)}s · 过期窗口 ${num(avalanche.value.l1StaleWindowSeconds)}s · 镜像 ${num(avalanche.value.l1StaleEntriesCached)} · 刷新中 ${num(avalanche.value.l1RefreshInFlight)}`
    },
    {
      label: 'DB 兜底预算',
      value: `${num(avalanche.value.limiterPermits)} / ${num(avalanche.value.limiterPeriodMs)}ms · 已放行 ${num(avalanche.value.limiterAcquired)} · 拒绝 ${num(avalanche.value.limiterRejected)}`
    },
    {
      label: '实验数据集',
      value: `ids ${num(dataset.value.startId)}–${num(dataset.value.endId)} · ${num(dataset.value.productCount)} 个 · rows ${num(dataset.value.rowsInRange)} · missing ${num(dataset.value.missingRows)}`
    },
    {
      label: '缓存键',
      value: `Redis ${keys.value.redisReachable ? 'reachable' : 'unreachable'} · L1 ${num(keys.value.l1ExperimentKeys)} · L2 ${num(keys.value.l2ExperimentKeys)} · 热键 ${keys.value.hotKeyCached ? 'cached' : 'miss'}`
    },
    {
      label: 'Bloom',
      value: `${bloom.value.initialised ? 'initialised' : '未初始化'} · size ${num(bloom.value.size)} / ${num(bloom.value.capacity)} · 误判率 ${formatProbability(bloom.value.falseProbability)}`
    },
    {
      label: '空值标记',
      value: `${nullCache.value.keyPrefix || '—'} · ttl ${num(nullCache.value.ttlSeconds)}s + ${num(nullCache.value.jitterSeconds)}s · live ${num(nullCache.value.liveMarkerKeys)}`
    }
  ]
  if (state.value?.breakdown?.staleEntriesCached !== undefined) {
    rows.splice(3, 0, {
      label: '逻辑过期存量',
      value: `stale ${num(breakdown.value.staleEntriesCached)} · refresh 进行中 ${num(breakdown.value.refreshInFlight)}`
    })
  }
  return rows
})

const preheatLast = computed(() => preheat.value?.lastRun || null)

function onOff(value) {
  return value ? 'on' : 'off'
}

function num(value) {
  return Number.isFinite(Number(value)) ? Number(value).toLocaleString('zh-CN') : '—'
}

function formatNumber(value) {
  return Number.isFinite(Number(value)) ? Number(value).toFixed(1) : '—'
}

function formatProbability(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return `${(number * 100).toFixed(3)}%`
}

function modeLabel(key) {
  return MODES.find((mode) => mode.key === key)?.label || key || '未知'
}

function preheatOutcomeClass(outcome) {
  if (outcome === 'success') return 'ok'
  if (outcome === 'busy' || outcome === 'cache-disabled') return 'warn'
  return 'down'
}

let pollTimer = null
let pollInFlight = false

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => {
    // A seed does not set busy, so state keeps refreshing while it runs.
    if (!busy.value) {
      refresh({ quiet: true })
    }
  }, SYNC_INTERVAL_MS)
}

async function refresh({ quiet = false } = {}) {
  if (!props.active || !props.token || pollInFlight) return
  pollInFlight = true
  if (!quiet) busy.value = 'refresh'
  const target = instance.value
  error.value = ''
  try {
    const [nextState, nextPreheat] = await Promise.all([
      fetchCacheState(props.token, target),
      fetchCachePreheat(props.token, target)
    ])
    // A slow reply for a previously selected instance must not overwrite the
    // state of the instance the operator is looking at now.
    if (target !== instance.value) return
    state.value = nextState
    preheat.value = nextPreheat
    if (!selectedMode.value && nextState?.defense?.mode) {
      selectedMode.value = nextState.defense.mode
    }
  } catch (err) {
    if (target === instance.value) {
      error.value = describeError(err)
    }
  } finally {
    pollInFlight = false
    if (!quiet) busy.value = ''
  }
}

async function applyMode() {
  if (!props.token) {
    error.value = '请先在上方填写访问令牌。'
    return
  }
  if (!MODE_KEYS.includes(selectedMode.value) || selectedMode.value === currentMode.value) {
    return
  }
  error.value = ''
  busy.value = 'mode'
  try {
    const reply = await updateCacheMode(props.token, instance.value, selectedMode.value)
    if (state.value) {
      state.value = {
        ...state.value,
        defense: { ...state.value.defense, mode: reply?.mode || selectedMode.value }
      }
    }
    await refresh({ quiet: true })
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

/** Long synchronous write: seeding disables itself but never the 5s poller. */
async function runSeed() {
  if (!props.token) {
    error.value = '请先在上方填写访问令牌。'
    return
  }
  error.value = ''
  seedResult.value = null
  seeding.value = true
  try {
    seedResult.value = await seedCache(props.token, instance.value)
    await refresh({ quiet: true })
  } catch (err) {
    error.value = describeError(err)
  } finally {
    seeding.value = false
  }
}

async function triggerPreheat() {
  if (!props.token) {
    error.value = '请先在上方填写访问令牌。'
    return
  }
  error.value = ''
  busy.value = 'preheat'
  try {
    preheat.value = await runCachePreheat(props.token, instance.value)
    await refresh({ quiet: true })
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

watch(
  () => props.focusInstance,
  (value) => {
    if (value && INSTANCES.includes(value) && value !== instance.value) {
      instance.value = value
    }
  },
  { immediate: true }
)

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

watch([() => props.token, instance], () => {
  selectedMode.value = ''
  seedResult.value = null
  preheat.value = null
  state.value = null
  if (props.active) {
    refresh()
  }
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="cache-page" data-testid="cache-page">
    <section class="status-bar">
      <div class="status-item">
        <span class="status-label">实例</span>
        <select v-model="instance" class="input select" data-testid="cache-instance" aria-label="应用实例">
          <option v-for="name in INSTANCES" :key="name" :value="name">{{ name }}</option>
        </select>
      </div>
      <div class="status-item">
        <span class="status-label">当前模式</span>
        <span class="pill" :class="currentMode && currentMode !== 'off' ? 'warn' : 'ok'" data-testid="cache-mode">
          {{ currentMode || 'unknown' }}
        </span>
      </div>
      <div class="status-item">
        <span class="status-label">缓存开关</span>
        <span class="pill" :class="cacheToggle ? 'ok' : 'muted'" data-testid="cache-toggle">
          {{ cacheToggle ? 'on' : 'off' }}
        </span>
      </div>
      <div class="status-item">
        <button type="button" class="btn ghost" data-testid="cache-refresh" :disabled="Boolean(busy)" @click="refresh()">
          {{ busy === 'refresh' ? '同步中…' : '同步状态' }}
        </button>
        <a class="link" :href="GRAFANA_D10" target="_blank" rel="noreferrer" data-testid="cache-grafana-link">
          Grafana D10 ↗
        </a>
      </div>
    </section>

    <div v-if="error" class="error-banner" role="alert" data-testid="cache-error">{{ error }}</div>

    <div class="cache-grid">
      <section class="card">
        <div class="card-head">
          <h2>当前模式与策略</h2>
          <span class="hint">来源 GET /lab/cache/state · 5s 轮询</span>
        </div>
        <p class="hint">
          模式与参数都是应用实例自己的真值，不是从 Prometheus 推断的。
          切换只影响所选实例，便于受保护/未保护实例对照。
        </p>
        <dl class="cache-facts" data-testid="cache-strategy">
          <div class="cache-fact">
            <dt>defense.mode</dt>
            <dd><code>{{ defense.mode || '—' }}</code> · {{ modeLabel(defense.mode) }}</dd>
          </div>
          <div v-for="row in strategyFacts" :key="row.label" class="cache-fact">
            <dt>{{ row.label }}</dt>
            <dd>{{ row.value }}</dd>
          </div>
        </dl>
      </section>

      <section class="card">
        <div class="card-head">
          <h2>切换防御模式</h2>
          <span class="hint">8 种模式 · 写入所选实例</span>
        </div>
        <div class="cache-form">
          <label class="form-label">
            目标模式
            <select v-model="selectedMode" class="input select" data-testid="cache-mode-select">
              <option v-for="mode in MODES" :key="mode.key" :value="mode.key">{{ mode.label }}</option>
            </select>
          </label>
          <button
            type="button"
            class="btn"
            data-testid="cache-mode-apply"
            :disabled="Boolean(busy) || seeding || !token || !selectedMode || selectedMode === currentMode"
            @click="applyMode"
          >
            {{ busy === 'mode' ? '切换中…' : '应用模式' }}
          </button>
        </div>
        <p class="hint">
          off 恢复无防护；null-cache/bloom 对抗穿透；mutex/logical-expire 对抗击穿；
          ttl-jitter/l1-cache/db-fallback 对抗雪崩。请求失败会显示服务端错误码与原因。
        </p>
        <div class="cache-callout">
          <span>当前 <code>{{ currentMode || 'unknown' }}</code></span>
          <span>选择 <code>{{ selectedMode || '—' }}</code></span>
          <span v-if="selectedMode === currentMode" class="badge ok">无需切换</span>
        </div>
      </section>
    </div>

    <div class="cache-grid">
      <section class="card">
        <div class="card-head">
          <h2>实验数据播种</h2>
          <span class="hint">idempotent · MySQL + Redis</span>
        </div>
        <p class="hint">
          写入保留 id 区间并预热 L2 雪崩键、热键与 Bloom。首次约 {{ productCount }} 行，需要数秒；
          重复执行只补缺失行（inserted=0）。播种期间状态轮询继续，不会卡住页面。
        </p>
        <div class="cache-form">
          <button
            type="button"
            class="btn"
            data-testid="cache-seed"
            :disabled="seeding || !token"
            @click="runSeed"
          >
            {{ seeding ? '播种中…' : `生成实验数据（${productCount}）` }}
          </button>
          <span v-if="seeding" class="pill running" data-testid="cache-seed-progress">正在写入，请稍候…</span>
        </div>
        <div v-if="seedResult" class="cache-seed-result" data-testid="cache-seed-result">
          <span class="badge ok">seed 完成</span>
          <span data-testid="cache-seed-range">ids {{ seedResult.startId }}–{{ seedResult.endId }}</span>
          <span data-testid="cache-seed-rows">rows {{ seedResult.rowsInRange }}</span>
          <span>本次新增 {{ seedResult.inserted }} · 已有 {{ seedResult.alreadyPresent }}</span>
          <span>雪崩键 {{ seedResult.avalancheKeysWritten }} @ {{ seedResult.avalancheTtlSeconds }}s</span>
          <span>热键 {{ seedResult.hotKeyProductId }} {{ seedResult.hotKeyWritten ? 'written' : 'miss' }}</span>
          <span>Bloom {{ seedResult.bloomIdsAdded }} {{ seedResult.bloomInitialised ? 'initialised' : '未初始化' }}</span>
          <span>mode <code>{{ seedResult.mode }}</code></span>
        </div>
        <p v-else class="hint">尚未在本页执行播种。</p>
      </section>

      <section class="card">
        <div class="card-head">
          <h2>L1 预热</h2>
          <button
            type="button"
            class="btn ghost"
            data-testid="cache-preheat-run"
            :disabled="Boolean(busy) || seeding || !token"
            @click="triggerPreheat"
          >
            {{ busy === 'preheat' ? '预热中…' : '立即预热' }}
          </button>
        </div>
        <p class="hint">
          从共享 Redis 热榜回填本实例 Caffeine L1；只写 L1，不改写 L2 的 TTL。
          单飞：运行中再次触发返回 busy。
        </p>
        <dl v-if="preheat" class="cache-facts" data-testid="cache-preheat-state">
          <div class="cache-fact">
            <dt>enabled / cache</dt>
            <dd>{{ preheat.enabled ? 'true' : 'false' }} / {{ preheat.cacheEnabled ? 'true' : 'false' }}</dd>
          </div>
          <div class="cache-fact">
            <dt>热榜</dt>
            <dd><code>{{ preheat.hotListKey }}</code> · topN {{ preheat.topN }} · size {{ preheat.hotListSize }}</dd>
          </div>
          <div class="cache-fact">
            <dt>tracked / running</dt>
            <dd>{{ preheat.trackedKeys }} / {{ preheat.running ? 'true' : 'false' }}</dd>
          </div>
        </dl>
        <p v-else class="hint">预热状态尚未载入。</p>
        <p v-if="preheatLast" class="cache-last-run" data-testid="cache-preheat-last">
          <span class="badge" :class="preheatOutcomeClass(preheatLast.outcome)">{{ preheatLast.outcome }}</span>
          <span>trigger {{ preheatLast.trigger }}</span>
          <span>{{ preheatLast.at }}</span>
          <span>{{ preheatLast.durationMs }}ms</span>
          <span>requested {{ preheatLast.requested }}</span>
          <span>loaded {{ preheatLast.loaded }}</span>
          <span>fresh {{ preheatLast.skippedFresh }}</span>
          <span>missing {{ preheatLast.missing }}</span>
          <span>failed {{ preheatLast.failed }}</span>
          <span v-if="preheatLast.error" class="cache-last-error">{{ preheatLast.error }}</span>
        </p>
        <p v-else class="hint">尚无预热记录；周期任务或手动触发后这里显示最近一次结果。</p>
      </section>
    </div>
  </section>
</template>
