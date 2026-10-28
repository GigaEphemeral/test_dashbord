<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BenchmarkPanel from './components/BenchmarkPanel.vue'
import FaultsPanel from './components/FaultsPanel.vue'
import InstancesPanel from './components/InstancesPanel.vue'
import ObservePanel from './components/ObservePanel.vue'
import OverviewPanel from './components/OverviewPanel.vue'
import RunLedger from './components/RunLedger.vue'
import ScenarioWizard from './components/ScenarioWizard.vue'
import WorkspaceSidebar from './components/WorkspaceSidebar.vue'
import {
  describeError,
  fetchActiveFaults,
  fetchContainers,
  fetchGuideScenarios,
  fetchGuideState,
  fetchHealth,
  fetchLedgerStatus,
  fetchLogs,
  fetchRuns,
  fetchToxiproxyState,
  fetchTrafficState,
  runGuideAction,
  runTrafficAction,
  startGuide,
  stopAllFaults
} from './api'

const TOKEN_STORAGE_KEY = 'ha-lab-token'
const DEFAULT_TAIL = 200
const SYNC_INTERVAL_MS = Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000)
const SUMMARY_RUN_LIMIT = Number(import.meta.env.VITE_LAB_LEDGER_SUMMARY_LIMIT || 5)
const GRAFANA_URL = (import.meta.env.VITE_LAB_GRAFANA_URL || 'http://127.0.0.1:3000')
  .replace(/\/+$/, '')

// Order is the operator workflow. testids follow nav-<section>; the old
// nav-console/nav-thread/nav-cache/nav-animation ids are gone with the rewrite.
const SECTIONS = [
  { id: 'overview', label: '总览', hint: '环境、容器池、故障、台账摘要' },
  { id: 'wizard', label: '场景向导', hint: '逐步注入故障并观察' },
  { id: 'instances', label: '实例与 JVM', hint: '实例卡片与线程实验室' },
  { id: 'bench', label: 'Benchmark', hint: '背景流量、容器清单与日志' },
  { id: 'observe', label: '实时监控', hint: '质量徽标 KPI 与拓扑' },
  { id: 'faults', label: '故障与缓存实验', hint: '容器故障、缓存防护与动画' },
  { id: 'runs', label: '实验记录', hint: '台账、时间线、预检查与操作' }
]

const token = ref(localStorage.getItem(TOKEN_STORAGE_KEY) || 'lab-token')
const showToken = ref(false)
const autoSync = ref(true)
const section = ref('overview')

const healthState = ref('checking')
const containers = ref([])
const selected = ref('')
const tail = ref(DEFAULT_TAIL)
const logs = ref('')
const error = ref('')
const loadingContainers = ref(false)
const loadingLogs = ref(false)

const scenarios = ref([])
const guide = ref(null)
const traffic = ref(null)
const faults = ref([])
const toxiproxy = ref(null)
const busy = ref('')
const syncTick = ref(0)

const ledgerStatus = ref(null)
const ledgerError = ref('')
const latestRun = ref(null)

// Instance handed to the thread lab by an instance card / animation view.
const threadInstance = ref('')
const threadNonce = ref(0)

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
      : ['jl-traffic', 'jl-mysql-cluster-init', 'jl-mysql-router-init', 'jl-toxiproxy-init', 'jl-seckill-app-jdk8']
  )
  if (traffic.value?.container) {
    names.add(traffic.value.container)
  }
  return names
})
const steadyContainers = computed(() =>
  containers.value.filter((item) => (
    !onDemandContainerNames.value.has(item.name)
    // Per-run k6 containers (jl-k6-<runId>) are ephemeral and must never
    // inflate the always-on count, even when the exclusion list is configured.
    && !String(item.name).startsWith('jl-k6-')
  ))
)
const healthyContainers = computed(
  () => steadyContainers.value.filter((item) => item.state === 'running').length
)

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

/** The proxy layer is optional: never let its absence fail the whole sync. */
async function refreshToxiproxyQuiet() {
  if (!containers.value.some((item) => item.name === 'jl-toxiproxy')) {
    toxiproxy.value = null
    return
  }
  try {
    toxiproxy.value = await fetchToxiproxyState(token.value.trim())
  } catch {
    toxiproxy.value = null
  }
}

async function syncEnvironment() {
  const value = token.value.trim()
  try {
    const [guideState, actions, trafficState, list] = await Promise.all([
      fetchGuideState(value),
      fetchActiveFaults(value),
      fetchTrafficState(value),
      fetchContainers(value)
    ])
    guide.value = guideState
    faults.value = actions || []
    traffic.value = trafficState
    containers.value = list || []
    if (!containers.value.some((item) => item.name === selected.value)) {
      selected.value = containers.value.find((item) => item.name === 'jl-app-1')?.name
        || containers.value[0]?.name
        || ''
    }
    await refreshToxiproxyQuiet()
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

/** Ledger summary is independent of the environment sync: a 503 must not
 *  turn the controller health pill red, it turns the ledger card DEGRADED. */
async function refreshLedgerSummary() {
  const value = token.value.trim()
  try {
    ledgerStatus.value = await fetchLedgerStatus(value)
  } catch {
    ledgerStatus.value = null
  }
  try {
    const page = await fetchRuns(value, { limit: SUMMARY_RUN_LIMIT })
    const items = page?.items || []
    latestRun.value = items.find(
      (run) => !['COMPLETED', 'FAILED', 'INVALID', 'ABORTED'].includes(run.status)
    ) || items[0] || null
    ledgerError.value = ''
  } catch (err) {
    latestRun.value = null
    ledgerError.value = describeError(err)
  }
}

/** Every guide transition answers with the new state, so the reply is the truth. */
async function callGuide(action, scenarioId) {
  error.value = ''
  busy.value = action
  try {
    const value = token.value.trim()
    guide.value = scenarioId ? await startGuide(value, scenarioId) : await runGuideAction(value, action)
    await syncEnvironment()
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

function selectContainer(name) {
  selected.value = name
  refreshLogs()
}

function setTail(value) {
  tail.value = value
}

/** Instance cards and the animation view link into the thread lab. */
function openThread(instance) {
  threadInstance.value = instance
  threadNonce.value += 1
  section.value = 'instances'
}

function navigate(id) {
  section.value = id
}

let syncTimer = null

function stopPolling() {
  if (syncTimer) {
    clearInterval(syncTimer)
    syncTimer = null
  }
}

async function globalSync() {
  if (busy.value) return
  await Promise.all([syncEnvironment(), refreshLedgerSummary()])
  syncTick.value += 1
}

function startPolling() {
  stopPolling()
  syncTimer = setInterval(globalSync, SYNC_INTERVAL_MS)
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
  error.value = ''
  await checkHealth()
  await Promise.all([syncEnvironment(), refreshScenarios(), refreshContainers(), refreshLedgerSummary()])
  syncTick.value += 1
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
  <div class="page workspace-page">
    <header class="topbar">
      <div class="brand">
        <div class="brand-mark">HA</div>
        <div>
          <h1>HA Lab · 实验工作区</h1>
          <p class="subtitle">侧边导航 · 总览 / 向导 / 实例 / Benchmark / 监控 / 故障 / 台账</p>
        </div>
      </div>
      <div class="topbar-meta">
        <span class="pill" :class="healthPillClass">{{ healthLabel }}</span>
        <a class="link" href="/prom/" target="_blank" rel="noreferrer">Prometheus</a>
        <a class="link" :href="GRAFANA_URL" target="_blank" rel="noreferrer">Grafana</a>
      </div>
    </header>

    <section class="card shell-bar">
      <div class="token-row">
        <span class="status-label">访问令牌</span>
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
      <div class="shell-flags">
        <label class="auto-sync">
          <input v-model="autoSync" type="checkbox" data-testid="auto-sync" />
          每 {{ Math.round(SYNC_INTERVAL_MS / 1000) }}s 自动同步
        </label>
        <span class="hint">请求头 <code>X-Lab-Token</code> · 保存在 localStorage</span>
        <button type="button" class="btn ghost danger" data-testid="global-stop-all" :disabled="Boolean(busy)" @click="clearFaults">
          {{ busy === 'stop-all' ? '还原中…' : '全部停止' }}
        </button>
      </div>
    </section>

    <div v-if="error" class="error-banner" role="alert" data-testid="error-banner">{{ error }}</div>

    <div class="workspace">
      <WorkspaceSidebar :sections="SECTIONS" :active="section" @select="navigate" />

      <main class="content workspace-main">
        <OverviewPanel
          v-if="section === 'overview'"
          :health-state="healthState"
          :health-label="healthLabel"
          :steady-containers="steadyContainers"
          :healthy-containers="healthyContainers"
          :faults="faults"
          :guide="guide"
          :traffic="traffic"
          :ledger-status="ledgerStatus"
          :ledger-error="ledgerError"
          :latest-run="latestRun"
          :loading-containers="loadingContainers"
          @navigate="navigate"
          @refresh="refreshContainers"
        />

        <ScenarioWizard
          v-else-if="section === 'wizard'"
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

        <InstancesPanel
          v-else-if="section === 'instances'"
          :token="token.trim()"
          :active="section === 'instances'"
          :sync-tick="syncTick"
          :focus-instance="threadInstance"
          :focus-nonce="threadNonce"
          :containers="containers"
          :faults="faults"
        />

        <BenchmarkPanel
          v-else-if="section === 'bench'"
          :token="token.trim()"
          :active="section === 'bench'"
          :sync-tick="syncTick"
          :containers="containers"
          :selected="selected"
          :tail="tail"
          :logs="logs"
          :loading-containers="loadingContainers"
          :loading-logs="loadingLogs"
          :traffic="traffic"
          :busy="busy"
          @refresh-containers="refreshContainers"
          @select-container="selectContainer"
          @refresh-logs="refreshLogs"
          @update:tail="setTail"
          @traffic="callTraffic"
          @changed="syncEnvironment"
        />

        <ObservePanel
          v-else-if="section === 'observe'"
          :token="token.trim()"
          :active="section === 'observe'"
          :sync-tick="syncTick"
          :containers="containers"
          :faults="faults"
          :toxiproxy="toxiproxy"
        />

        <FaultsPanel
          v-else-if="section === 'faults'"
          :token="token.trim()"
          :active="section === 'faults'"
          :sync-tick="syncTick"
          :containers="containers"
          :faults="faults"
          @open-thread="openThread"
          @changed="syncEnvironment"
        />

        <RunLedger
          v-else
          :token="token.trim()"
          :active="section === 'runs'"
          :sync-tick="syncTick"
        />
      </main>
    </div>

    <footer class="footer">
      <span>业务入口 <a href="/api/hello" target="_blank" rel="noreferrer">/api/hello</a></span>
      <span>压测 <code>bash code/lab/scripts/bench.sh smoke</code></span>
      <span>暂停即冻结 TTL：可以先查日志和面板，再继续场景</span>
    </footer>
  </div>
</template>
