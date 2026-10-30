<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ExperimentWizard from './components/ExperimentWizard.vue'
import MonitorPage from './components/MonitorPage.vue'
import OverviewPanel from './components/OverviewPanel.vue'
import RunLedger from './components/RunLedger.vue'
import WorkspaceSidebar from './components/WorkspaceSidebar.vue'
import {
  describeError,
  fetchActiveFaults,
  fetchContainers,
  fetchGuideScenarios,
  fetchGuideState,
  fetchHealth,
  fetchLedgerStatus,
  fetchRuns,
  fetchToxiproxyState,
  fetchTrafficState,
  runGuideAction,
  runTrafficAction,
  startGuide,
  stopAllFaults
} from './api'

const TOKEN_STORAGE_KEY = 'ha-lab-token'
const SYNC_INTERVAL_MS = Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000)
const SUMMARY_RUN_LIMIT = Number(import.meta.env.VITE_LAB_LEDGER_SUMMARY_LIMIT || 5)
const GRAFANA_URL = (import.meta.env.VITE_LAB_GRAFANA_URL || 'http://127.0.0.1:3000')
  .replace(/\/+$/, '')

// B5-04g: four sections. Instance configuration lives in wizard step 3 and the
// instance cards live on the monitor sheet; Benchmark became the environment
// drawer (container logs + background traffic).
const SECTIONS = [
  { id: 'overview', label: '总览', hint: '环境、容器池、故障、台账摘要' },
  { id: 'wizard', label: '场景向导', hint: '目标、变量与配置、k6、故障时间线与预检查' },
  { id: 'observe', label: '实时监控与操作', hint: '时间轴、实例卡片、缓存流动、拓扑与悬浮工具' },
  { id: 'runs', label: '实验记录', hint: '台账、报告、归档与删除' }
]

// Enabled Java nodes of the newest run; empty means "all configured", which
// keeps the pre-B3 visual state for runs created before B3-00a.
const enabledNodes = computed(() => latestRun.value?.nodes || [])

const token = ref(localStorage.getItem(TOKEN_STORAGE_KEY) || 'lab-token')
const showToken = ref(false)
const autoSync = ref(true)
const section = ref('overview')

const healthState = ref('checking')
const containers = ref([])
const loadingContainers = ref(false)
const error = ref('')

const scenarios = ref([])
const guide = ref(null)
const traffic = ref(null)
const faults = ref([])
const toxiproxy = ref(null)
const busy = ref('')
const syncTick = ref(0)
const lastSyncAt = ref(null)

const ledgerStatus = ref(null)
const ledgerError = ref('')
const latestRun = ref(null)

// Instance handed to wizard step 3 by a monitor card's "JVM 参数" button.
const wizardStep = ref(0)
const wizardFocusInstance = ref('')
const wizardNonce = ref(0)

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

async function refreshContainers() {
  loadingContainers.value = true
  try {
    containers.value = await fetchContainers(token.value.trim())
  } catch (err) {
    containers.value = []
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

function navigate(id) {
  if (id === 'wizard') wizardStep.value = 0
  section.value = id
}

/** A monitor instance card deep-links into the wizard's configuration step. */
function openInstanceConfig(instance) {
  wizardFocusInstance.value = instance
  wizardNonce.value += 1
  wizardStep.value = 3
  section.value = 'wizard'
}

/** A started experiment jumps straight to the live view. */
async function onRunStarted() {
  section.value = 'observe'
  await refreshLedgerSummary()
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
  lastSyncAt.value = new Date()
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
  lastSyncAt.value = new Date()
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
  <div class="page workspace-page" :class="{ 'page-wide': section === 'observe' }">
    <header class="topbar">
      <div class="brand">
        <div class="brand-mark">HA</div>
        <div>
          <h1>HA Lab · 实验工作区</h1>
          <p class="subtitle">侧边导航 · 总览 / 场景向导 / 实时监控与操作 / 实验记录</p>
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

        <ExperimentWizard
          v-else-if="section === 'wizard'"
          :token="token.trim()"
          :scenarios="scenarios"
          :state="guide"
          :busy="busy"
          :containers="containers"
          :latest-run="latestRun"
          :focus-step="wizardStep"
          :focus-instance="wizardFocusInstance"
          :focus-nonce="wizardNonce"
          @start="(id) => callGuide('start', id)"
          @step="() => callGuide('step')"
          @skip="() => callGuide('skip')"
          @pause="() => callGuide('pause')"
          @resume="() => callGuide('resume')"
          @reset="() => callGuide('reset')"
          @refresh="syncEnvironment"
          @started="onRunStarted"
          @navigate="navigate"
        />

        <MonitorPage
          v-else-if="section === 'observe'"
          :token="token.trim()"
          :active="section === 'observe'"
          :sync-tick="syncTick"
          :sync-at="lastSyncAt"
          :containers="containers"
          :faults="faults"
          :toxiproxy="toxiproxy"
          :enabled-nodes="enabledNodes"
          :traffic="traffic"
          @open-config="openInstanceConfig"
          @refresh="globalSync"
          @changed="syncEnvironment"
          @traffic="callTraffic"
          @refresh-containers="refreshContainers"
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
