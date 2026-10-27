<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ScenarioWizard from './components/ScenarioWizard.vue'
import {
  ApiError,
  fetchActiveFaults,
  fetchContainers,
  fetchGuideScenarios,
  fetchGuideState,
  fetchHealth,
  fetchLogs,
  fetchTrafficState,
  runGuideAction,
  runTrafficAction,
  startGuide,
  stopAllFaults
} from './api'

const TOKEN_STORAGE_KEY = 'ha-lab-token'
const DEFAULT_TAIL = 200
const SYNC_INTERVAL_MS = Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000)

const token = ref(localStorage.getItem(TOKEN_STORAGE_KEY) || 'lab-token')
const showToken = ref(false)
const containers = ref([])
const selected = ref('')
const tail = ref(DEFAULT_TAIL)
const logs = ref('')
const error = ref('')
const loadingContainers = ref(false)
const loadingLogs = ref(false)
const healthState = ref('checking')

const scenarios = ref([])
const guide = ref(null)
const traffic = ref(null)
const faults = ref([])
const busy = ref('')
const autoSync = ref(true)

watch(token, (value) => {
  localStorage.setItem(TOKEN_STORAGE_KEY, value)
})

const healthLabel = computed(() => {
  if (healthState.value === 'up') return '控制器在线'
  if (healthState.value === 'down') return '控制器离线'
  return '检测中…'
})

const healthPillClass = computed(() => {
  if (healthState.value === 'up') return 'ok'
  if (healthState.value === 'down') return 'down'
  return 'muted'
})

const guideStatus = computed(() => guide.value?.status || 'unknown')
const runningFaults = computed(() => faults.value.length)
// Profile-gated and one-shot services are down by design: jl-traffic is
// created-but-stopped, the db-ha init jobs and the toxiproxy init exit(0) after
// bootstrapping. The rollup only promises the always-on services, so the
// exclusion list is configurable (comma separated) instead of hardcoded.
const onDemandContainerNames = computed(() => {
  const configured = (import.meta.env.VITE_LAB_ON_DEMAND_CONTAINERS || '')
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean)
  const names = new Set(
    configured.length > 0
      ? configured
      : ['jl-traffic', 'jl-mysql-cluster-init', 'jl-mysql-router-init', 'jl-toxiproxy-init', 'jl-seckill-app-jdk8'],
  )
  if (traffic.value?.container) {
    names.add(traffic.value.container)
  }
  return names
})
const steadyContainers = computed(() =>
  containers.value.filter((item) => !onDemandContainerNames.value.has(item.name))
)
const healthyContainers = computed(
  () => steadyContainers.value.filter((item) => item.state === 'running').length
)
const trafficRunning = computed(() => Boolean(traffic.value?.running))

function describeError(err) {
  if (err instanceof ApiError) {
    if (err.status === 401) return '401 未授权：X-Lab-Token 不正确，修改令牌后重试。'
    if (err.status === 403) return `403 禁止访问：${err.message}`
    if (err.status === 404) return `404 未找到：${err.message}`
    if (err.status === 409) return `409 状态冲突：${err.message}`
    if (err.status === 503) return `503 服务不可用：${err.message}`
    return `${err.status}：${err.message}`
  }
  return err?.message || '请求失败，请检查网络或控制器状态。'
}

function stateClass(state) {
  return state === 'running' ? 'ok' : 'warn'
}

function healthClass(health) {
  if (health === 'healthy') return 'ok'
  if (health === 'unhealthy') return 'down'
  return 'muted'
}

async function checkHealth() {
  try {
    const payload = await fetchHealth()
    healthState.value = payload?.status === 'UP' ? 'up' : 'down'
  } catch {
    healthState.value = 'down'
  }
}

async function refreshLogs() {
  if (!selected.value) return
  const value = Number(tail.value)
  if (!Number.isInteger(value) || value < 1 || value > 1000) {
    error.value = 'tail 必须是 1–1000 之间的整数。'
    return
  }
  error.value = ''
  loadingLogs.value = true
  try {
    logs.value = await fetchLogs(token.value.trim(), selected.value, value)
  } catch (err) {
    logs.value = ''
    error.value = describeError(err)
  } finally {
    loadingLogs.value = false
  }
}

async function refreshContainers() {
  loadingContainers.value = true
  try {
    const list = await fetchContainers(token.value.trim())
    containers.value = list
    if (!list.some((item) => item.name === selected.value)) {
      selected.value = list.find((item) => item.name === 'jl-app-1')?.name || list[0]?.name || ''
    }
    if (selected.value) {
      await refreshLogs()
    } else {
      logs.value = ''
    }
  } catch (err) {
    containers.value = []
    logs.value = ''
    error.value = describeError(err)
  } finally {
    loadingContainers.value = false
  }
}

async function syncEnvironment() {
  const value = token.value.trim()
  try {
    const [guideState, actions, trafficState] = await Promise.all([
      fetchGuideState(value),
      fetchActiveFaults(value),
      fetchTrafficState(value)
    ])
    guide.value = guideState
    faults.value = actions || []
    traffic.value = trafficState
    healthState.value = 'up'
  } catch (err) {
    error.value = describeError(err)
    healthState.value = 'down'
  }
}

async function refreshScenarios() {
  try {
    scenarios.value = (await fetchGuideScenarios(token.value.trim())) || []
  } catch (err) {
    scenarios.value = []
    error.value = describeError(err)
  }
}

/** Every guide transition answers with the new state, so the reply is the truth. */
async function callGuide(action, scenarioId) {
  error.value = ''
  busy.value = action
  try {
    const value = token.value.trim()
    guide.value = scenarioId ? await startGuide(value, scenarioId) : await runGuideAction(value, action)
    await Promise.all([refreshFaultsQuiet(), refreshContainersQuiet()])
  } catch (err) {
    error.value = describeError(err)
    await syncEnvironment()
  } finally {
    busy.value = ''
  }
}

async function callTraffic(action) {
  error.value = ''
  busy.value = `traffic-${action}`
  try {
    traffic.value = await runTrafficAction(token.value.trim(), action)
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
    await syncEnvironment()
  }
}

async function clearFaults() {
  error.value = ''
  busy.value = 'stop-all'
  try {
    await stopAllFaults(token.value.trim())
    await syncEnvironment()
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

async function refreshFaultsQuiet() {
  try {
    faults.value = (await fetchActiveFaults(token.value.trim())) || []
  } catch {
    // The sync poll picks this up; a stale fault list must not mask the action result.
  }
}

async function refreshContainersQuiet() {
  try {
    containers.value = (await fetchContainers(token.value.trim())) || []
  } catch {
    // Same reason as refreshFaultsQuiet.
  }
}

function selectContainer(name) {
  selected.value = name
  refreshLogs()
}

let syncTimer = null

function stopPolling() {
  if (syncTimer) {
    clearInterval(syncTimer)
    syncTimer = null
  }
}

function startPolling() {
  stopPolling()
  syncTimer = setInterval(() => {
    if (!busy.value) {
      syncEnvironment()
    }
  }, SYNC_INTERVAL_MS)
}

watch(autoSync, (enabled) => {
  localStorage.setItem('ha-lab-auto-sync', String(enabled))
  if (enabled) {
    startPolling()
  } else {
    stopPolling()
  }
})

async function reloadAll() {
  await checkHealth()
  await Promise.all([syncEnvironment(), refreshScenarios(), refreshContainers()])
  if (autoSync.value) {
    startPolling()
  }
}

onMounted(() => {
  autoSync.value = localStorage.getItem('ha-lab-auto-sync') !== 'false'
  reloadAll()
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="page">
    <header class="topbar">
      <div class="brand">
        <div class="brand-mark">HA</div>
        <div>
          <h1>HA Lab · 实验操作台</h1>
          <p class="subtitle">M2 · 场景向导、故障状态、背景流量与只读日志</p>
        </div>
      </div>
      <div class="topbar-meta">
        <span class="pill" :class="healthPillClass">{{ healthLabel }}</span>
        <a class="link" href="/prom/" target="_blank" rel="noreferrer">Prometheus</a>
        <a class="link" href="http://127.0.0.1:3000" target="_blank" rel="noreferrer">Grafana</a>
      </div>
    </header>

    <main class="content">
      <section class="status-bar">
        <div class="status-item">
          <span class="status-label">向导</span>
          <span class="pill" :class="guideStatus" data-testid="guide-status">{{ guideStatus }}</span>
          <span class="status-value">{{ guide?.scenarioName || '未载入场景' }}</span>
        </div>
        <div class="status-item">
          <span class="status-label">活动故障</span>
          <span class="pill" :class="runningFaults ? 'warn' : 'ok'" data-testid="fault-count">{{ runningFaults }}</span>
          <span class="status-value">
            <code v-for="item in faults" :key="item.id" class="fault-tag">
              {{ item.action }} · {{ item.target }} · {{ item.status }}
            </code>
            <template v-if="!runningFaults">无</template>
          </span>
        </div>
        <div class="status-item">
          <span class="status-label">容器</span>
          <span
            class="pill"
            :class="healthyContainers === steadyContainers.length ? 'ok' : 'warn'"
            data-testid="container-rollup"
            :title="`常驻容器 ${healthyContainers}/${steadyContainers.length} 运行中；背景流量容器按需启停`"
          >
            {{ healthyContainers }}/{{ steadyContainers.length }}
          </span>
        </div>
        <div class="status-item">
          <span class="status-label">还原</span>
          <span class="pill muted" data-testid="reset-mode">{{ guide?.resetMode === 'auto' ? 'auto 结束自动还原' : 'manual 手动还原' }}</span>
        </div>
        <div class="status-item traffic-item">
          <span class="status-label">背景流量</span>
          <span class="pill" :class="trafficRunning ? 'ok' : 'muted'" data-testid="traffic-state">
            {{ traffic?.state || 'unknown' }}
          </span>
          <button
            type="button"
            class="btn ghost"
            data-testid="traffic-start"
            :disabled="trafficRunning || Boolean(busy)"
            @click="callTraffic('start')"
          >
            {{ busy === 'traffic-start' ? '启动中…' : '开启' }}
          </button>
          <button
            type="button"
            class="btn ghost"
            data-testid="traffic-stop"
            :disabled="!trafficRunning || Boolean(busy)"
            @click="callTraffic('stop')"
          >
            {{ busy === 'traffic-stop' ? '停止中…' : '停止' }}
          </button>
        </div>
        <div class="status-item">
          <button type="button" class="btn ghost danger" data-testid="stop-all" :disabled="Boolean(busy)" @click="clearFaults">
            {{ busy === 'stop-all' ? '还原中…' : '清除全部故障' }}
          </button>
          <label class="auto-sync">
            <input v-model="autoSync" type="checkbox" />
            每 {{ Math.round(SYNC_INTERVAL_MS / 1000) }}s 自动同步
          </label>
        </div>
      </section>

      <section class="card token-card">
        <div class="card-head">
          <h2>访问令牌</h2>
          <span class="hint">请求头 X-Lab-Token · 保存在浏览器 localStorage</span>
        </div>
        <div class="token-row">
          <input
            data-testid="token-input"
            v-model="token"
            :type="showToken ? 'text' : 'password'"
            class="input token-input"
            autocomplete="off"
            spellcheck="false"
            aria-label="实验室访问令牌"
            @keyup.enter="reloadAll"
          />
          <button type="button" class="btn ghost" @click="showToken = !showToken">
            {{ showToken ? '隐藏' : '显示' }}
          </button>
          <button type="button" data-testid="connect" class="btn" :disabled="loadingContainers" @click="reloadAll">
            {{ loadingContainers ? '连接中…' : '连接 / 刷新' }}
          </button>
        </div>
      </section>

      <div v-if="error" class="error-banner" role="alert" data-testid="error-banner">{{ error }}</div>

      <ScenarioWizard
        :scenarios="scenarios"
        :state="guide"
        :busy="busy"
        @start="(id) => callGuide('start', id)"
        @step="() => callGuide('step')"
        @skip="() => callGuide('skip')"
        @pause="() => callGuide('pause')"
        @resume="() => callGuide('resume')"
        @reset="() => callGuide('reset')"
        @refresh="syncEnvironment"
      />

      <div class="grid">
        <section class="card">
          <div class="card-head">
            <h2>容器清单 <span class="count">{{ containers.length }}</span></h2>
            <button type="button" class="btn ghost" :disabled="loadingContainers" @click="refreshContainers">
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
                @click="selectContainer(item.name)"
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
              <select v-model="selected" class="input select" aria-label="选择容器" data-testid="log-container" @change="refreshLogs">
                <option value="" disabled>选择容器</option>
                <option v-for="item in containers" :key="item.name" :value="item.name">{{ item.name }}</option>
              </select>
              <label class="tail-label">
                tail
                <input v-model.number="tail" type="number" class="input tail-input" min="1" max="1000" />
              </label>
              <button type="button" class="btn" data-testid="log-refresh" :disabled="loadingLogs || !selected" @click="refreshLogs">
                {{ loadingLogs ? '读取中…' : '刷新日志' }}
              </button>
            </div>
          </div>
          <pre class="logs" data-testid="log-view">{{ logs || '（暂无日志，选择容器后刷新）' }}</pre>
          <p class="hint">只读 · tail 1–1000 · 经 Docker API 读取 stdout/stderr</p>
        </section>
      </div>
    </main>

    <footer class="footer">
      <span>业务入口 <a href="/api/hello" target="_blank" rel="noreferrer">/api/hello</a></span>
      <span>压测 <code>bash code/lab/scripts/bench.sh smoke</code></span>
      <span>暂停即冻结 TTL：可以先查日志和面板，再继续场景</span>
    </footer>
  </div>
</template>
