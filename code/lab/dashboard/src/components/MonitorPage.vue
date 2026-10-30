<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import CacheFlowPanel from './CacheFlowPanel.vue'
import CacheKeysPanel from './CacheKeysPanel.vue'
import ChaosActions from './ChaosActions.vue'
import EnvironmentDrawer from './EnvironmentDrawer.vue'
import InstanceMetricsPanel from './InstanceMetricsPanel.vue'
import JvmParamsPanel from './JvmParamsPanel.vue'
import K6Progress from './K6Progress.vue'
import MonitorTimeline from './MonitorTimeline.vue'
import ObservePanel from './ObservePanel.vue'
import SideDrawer from './SideDrawer.vue'
import ThreadLab from './ThreadLab.vue'
import TopologyView from './TopologyView.vue'
import { describeError, fetchTopology } from '../api'

/**
 * B5-04g monitor sheet.
 *
 * The page is one continuous observation surface: KPI, k6 progress, timelines,
 * instance cards, cache flow and topology. Tools that used to live in separate
 * tabs (container faults, thread lab, cache keys, container logs) open as
 * right-hand drawers so the live charts stay visible while they are used.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  syncAt: { type: [Date, String, Number], default: null },
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  toxiproxy: { type: Object, default: null },
  enabledNodes: { type: Array, default: () => [] },
  traffic: { type: Object, default: null }
})

defineEmits(['changed', 'refresh', 'traffic', 'open-config', 'refresh-containers'])

const syncAtLabel = computed(() => {
  const value = props.syncAt
  if (!value) return '—'
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleTimeString('zh-CN', { hour12: false })
})

const appInstances = computed(() => (props.containers || [])
  .filter((item) => /^jl-app-\d+$/.test(item.name))
  .map((item) => item.name.replace(/^jl-/, ''))
  .sort())

// app-3 is the JDK8 business peer, already part of appInstances.
const jvmInstances = computed(() => [...appInstances.value])

const faultInstances = computed(() => (appInstances.value.length ? appInstances.value : ['app-1', 'app-2', 'app-3']))

const openDrawer = ref('')
const threadInstance = ref('')

function showDrawer(name) {
  openDrawer.value = name
}

function openThread(instance) {
  threadInstance.value = instance
  openDrawer.value = 'thread'
}

function closeDrawer() {
  openDrawer.value = ''
}

// ----------------------------------------------------------------- topology
const topology = ref(null)
const topologyError = ref('')
const topologyLoading = ref(false)
const includeSidecars = ref(false)
const topologyUpdatedAt = ref('')

let topologyInFlight = false
let topologyQueued = false

function topologyEnabledParam() {
  return (props.enabledNodes || [])
    .map((value) => String(value || '').trim())
    .filter(Boolean)
    .join(',')
}

async function refreshTopology() {
  if (!props.active || !props.token) return
  if (topologyInFlight) {
    topologyQueued = true
    return
  }
  topologyInFlight = true
  topologyLoading.value = true
  try {
    const snapshot = await fetchTopology(props.token, {
      includeSidecars: includeSidecars.value,
      enabled: topologyEnabledParam()
    })
    topology.value = snapshot
    topologyUpdatedAt.value = snapshot?.generatedAt || new Date().toISOString()
    topologyError.value = ''
  } catch (err) {
    topologyError.value = `拓扑 DEGRADED（GET /lab/topology）：${describeError(err)}`
  } finally {
    topologyInFlight = false
    topologyLoading.value = false
    if (topologyQueued) {
      topologyQueued = false
      refreshTopology()
    }
  }
}

watch(
  () => [props.active, props.syncTick, props.token, includeSidecars.value],
  ([active]) => {
    if (active) refreshTopology()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  topologyInFlight = false
  topologyQueued = false
})
</script>

<template>
  <div class="monitor-page" data-testid="monitor-page">
    <ObservePanel
      :token="token"
      :active="active"
      :sync-tick="syncTick"
      :containers="containers"
      :faults="faults"
      :toxiproxy="toxiproxy"
      :show-topology="false"
    />

    <K6Progress :token="token" :active="active" :sync-tick="syncTick" />

    <MonitorTimeline :token="token" :active="active" :sync-tick="syncTick" />

    <InstanceMetricsPanel
      :token="token"
      :active="active"
      :sync-tick="syncTick"
      :containers="containers"
      :faults="faults"
      :enabled-nodes="enabledNodes"
      @open-thread="openThread"
      @open-config="$emit('open-config', $event)"
    />

    <CacheFlowPanel
      :token="token"
      :active="active"
      :sync-tick="syncTick"
      :instances="appInstances"
    />

    <section class="card">
      <div class="card-head">
        <h2>拓扑</h2>
        <div class="bench-actions">
          <label class="topo-controls" data-testid="topology-sidecar-label">
            <input v-model="includeSidecars" type="checkbox" data-testid="topology-sidecar-toggle">
            显示 sidecar
          </label>
          <span class="hint">
            节点/连线来自 <code>GET /lab-api/lab/topology</code>；悬停节点高亮其依赖。
          </span>
        </div>
      </div>
      <p v-if="topologyError" class="error-banner" role="alert" data-testid="topology-error">
        {{ topologyError }}
        <template v-if="topology"> · 保留上次成功快照（{{ topologyUpdatedAt }}）</template>
        <template v-else> · 已回退到 <code>/lab-api/containers</code> 拓扑数据降级</template>
        <template v-if="topologyLoading"> · 重试中…</template>
      </p>
      <TopologyView
        :topology="topology"
        :include-sidecars="includeSidecars"
        :containers="containers"
        :faults="faults"
        :toxiproxy="toxiproxy"
        :enabled-nodes="enabledNodes"
      />
    </section>

    <JvmParamsPanel
      :token="token"
      :instances="jvmInstances"
      :active="active"
      :sync-tick="syncTick"
    />

    <nav class="monitor-dock" aria-label="实时工具">
      <button
        type="button"
        class="dock-btn"
        data-testid="dock-faults"
        :disabled="!active || !token"
        title="容器故障注入（TTL 自动回滚）"
        @click="showDrawer('faults')"
      >故障注入</button>
      <button
        type="button"
        class="dock-btn"
        data-testid="dock-thread"
        :disabled="!active || !token"
        title="线程故障、单线程生命周期与线程快照"
        @click="showDrawer('thread')"
      >线程工具</button>
      <button
        type="button"
        class="dock-btn"
        data-testid="dock-cache-keys"
        :disabled="!active || !token"
        title="Redis 缓存键分页浏览与单键详情"
        @click="showDrawer('cache')"
      >缓存键</button>
      <button
        type="button"
        class="dock-btn"
        data-testid="dock-logs"
        :disabled="!active || !token"
        title="容器清单、日志与背景流量"
        @click="showDrawer('env')"
      >容器日志</button>
      <button
        type="button"
        class="dock-btn dock-refresh"
        data-testid="monitor-refresh-fab"
        :disabled="!active || !token"
        :title="`立即刷新实时监控数据（上次同步 ${syncAtLabel}）`"
        aria-label="立即刷新实时监控数据"
        @click="$emit('refresh')"
      >
        <span class="dock-icon" aria-hidden="true">⟳</span>
        <span class="dock-time">{{ syncAtLabel }}</span>
      </button>
    </nav>

    <SideDrawer
      :open="openDrawer === 'faults'"
      title="故障注入"
      hint="容器 kill / freeze 带 TTL 自动回滚；202 表示待确认，面板会轮询到终态。"
      testid="drawer-faults"
      width="min(620px, 94vw)"
      @close="closeDrawer"
    >
      <ChaosActions
        :token="token"
        :active="active && openDrawer === 'faults'"
        :instances="faultInstances"
        :faults="faults"
        @changed="$emit('changed')"
      />
    </SideDrawer>

    <SideDrawer
      :open="openDrawer === 'thread'"
      title="线程工具"
      hint="注入线程故障、推进单线程生命周期、抓取线程/堆快照；只影响所选实例。"
      testid="drawer-thread"
      @close="closeDrawer"
    >
      <ThreadLab
        :token="token"
        :active="active && openDrawer === 'thread'"
        :focus-instance="threadInstance"
      />
    </SideDrawer>

    <SideDrawer
      :open="openDrawer === 'cache'"
      title="缓存键浏览"
      hint="Redis SCAN 分页、单键 L1/L2 详情与实验缓存重置；按实例隔离。"
      testid="drawer-cache-keys"
      @close="closeDrawer"
    >
      <CacheKeysPanel :token="token" :active="active && openDrawer === 'cache'" />
    </SideDrawer>

    <SideDrawer
      :open="openDrawer === 'env'"
      title="容器日志与环境"
      hint="容器清单、只读日志与背景流量；正式 k6 由向导创建 run。"
      testid="drawer-env"
      width="min(980px, 96vw)"
      @close="closeDrawer"
    >
      <EnvironmentDrawer
        :token="token"
        :active="active && openDrawer === 'env'"
        :containers="containers"
        :traffic="traffic"
        @refresh-containers="$emit('refresh-containers')"
        @traffic="$emit('traffic', $event)"
        @changed="$emit('changed')"
      />
    </SideDrawer>
  </div>
</template>

<style scoped>
.monitor-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.monitor-dock {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 60;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-end;
}

.dock-btn {
  min-width: 92px;
  padding: 9px 14px;
  border-radius: 12px;
  border: 1px solid rgba(56, 211, 159, 0.35);
  background: rgba(13, 20, 27, 0.94);
  color: var(--text);
  font-size: 0.82rem;
  font-weight: 600;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(6px);
  cursor: pointer;
}

.dock-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.dock-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.dock-refresh {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--accent);
}

.dock-icon {
  font-size: 1rem;
  line-height: 1;
}

.dock-time {
  color: var(--text-muted);
  font-family: var(--mono);
  font-size: 0.7rem;
  font-weight: 400;
}
</style>
