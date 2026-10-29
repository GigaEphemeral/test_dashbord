<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AnimationLab from './AnimationLab.vue'
import CacheLab from './CacheLab.vue'
import ChaosActions from './ChaosActions.vue'
import InstanceMetricsTable from './InstanceMetricsTable.vue'
import JvmParamsPanel from './JvmParamsPanel.vue'
import K6Progress from './K6Progress.vue'
import MonitorTimeline from './MonitorTimeline.vue'
import ObservePanel from './ObservePanel.vue'
import ThreadLab from './ThreadLab.vue'
import CacheKeysPanel from './CacheKeysPanel.vue'
import TopologyView from './TopologyView.vue'
import { describeError, fetchTopology } from '../api'

/**
 * B3-00d "实时监控与操作".
 *
 * Tabs keep every legacy capability: 监控 (KPI + k6 progress + timelines +
 * instance table + topology), 实时操作 (container faults + cache lab) and
 * 实验工具 (thread lab + animation views). The old FaultsPanel is gone, but no
 * action surface was dropped.
 *
 * B5-01b: the topology block polls GET /lab/topology on every environment sync
 * while the section is active. A failure keeps the last good snapshot (if any)
 * and otherwise falls back to the /containers structure marked "拓扑数据降级".
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  syncAt: { type: [Date, String, Number], default: null },
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  toxiproxy: { type: Object, default: null },
  enabledNodes: { type: Array, default: () => [] }
})

defineEmits(['open-thread', 'changed', 'refresh'])

const TABS = [
  { id: 'monitor', label: '监控', testid: 'monitor-tab-monitor' },
  { id: 'ops', label: '实时操作', testid: 'monitor-tab-ops' },
  { id: 'tools', label: '实验工具', testid: 'monitor-tab-tools' }
]

const tab = ref('monitor')

const syncAtLabel = computed(() => {
  const value = props.syncAt
  if (!value) return '—'
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleTimeString('zh-CN', { hour12: false })
})

const topology = ref(null)
const topologyError = ref('')
const topologyLoading = ref(false)
const includeSidecars = ref(false)
const topologyUpdatedAt = ref('')

let topologyInFlight = false
let topologyQueued = false

/** Same instance derivation the removed FaultsPanel used. */
function instancesOf(containers) {
  return (containers || [])
    .filter((item) => /^jl-app-\d+$/.test(item.name))
    .map((item) => item.name.replace(/^jl-/, ''))
    .sort()
}

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
    // Keep the last successful contract snapshot; only a page with no snapshot
    // falls back to the containers view. Never blank the topology block.
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
    <nav class="tabs" role="tablist" aria-label="实时监控与操作">
      <button
        v-for="item in TABS"
        :key="item.id"
        type="button"
        role="tab"
        class="tab"
        :class="{ active: tab === item.id }"
        :aria-selected="tab === item.id"
        :data-testid="item.testid"
        @click="tab = item.id"
      >{{ item.label }}</button>
    </nav>

    <template v-if="tab === 'monitor'">
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

      <JvmParamsPanel
        :token="token"
        :instances="instancesOf(containers)"
        :active="active"
        :sync-tick="syncTick"
      />

      <MonitorTimeline :active="active" :sync-tick="syncTick" />

      <InstanceMetricsTable
        :token="token"
        :active="active"
        :sync-tick="syncTick"
        :containers="containers"
        :enabled-nodes="enabledNodes"
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
              节点/连线来自 <code>GET /lab-api/lab/topology</code>；失败时回退到 containers 并标注“拓扑数据降级”
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
    </template>

    <template v-else-if="tab === 'ops'">
      <ChaosActions
        :token="token"
        :active="active"
        :instances="instancesOf(containers).length ? instancesOf(containers) : ['app-1', 'app-2', 'app-3']"
        :faults="faults"
        @changed="$emit('changed')"
      />
      <CacheLab :token="token" :active="active" />
    </template>

    <template v-else>
      <!-- Both labs poll their own state; the page only owns the open-thread hop. -->
      <ThreadLab
        :token="token"
        :active="active"
        @open-thread="$emit('open-thread', $event)"
      />
      <AnimationLab
        :token="token"
        :active="active"
        @open-thread="$emit('open-thread', $event)"
      />
      <CacheKeysPanel :token="token" :active="active" />
    </template>

    <!-- Always reachable while the page is long: one click refreshes every
         child block through the global sync tick. -->
    <button
      type="button"
      class="monitor-fab"
      data-testid="monitor-refresh-fab"
      :disabled="!active || !token"
      :title="`立即刷新实时监控数据（上次同步 ${syncAtLabel}）`"
      aria-label="立即刷新实时监控数据"
      @click="$emit('refresh')"
    >
      <span class="monitor-fab-icon" aria-hidden="true">⟳</span>
      <span class="monitor-fab-text">刷新</span>
      <span class="monitor-fab-time">{{ syncAtLabel }}</span>
    </button>
  </div>
</template>

<style scoped>
.monitor-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.monitor-fab {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 60;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 999px;
  border: 1px solid rgba(56, 211, 159, 0.5);
  background: rgba(13, 20, 27, 0.92);
  color: var(--accent);
  font-weight: 600;
  font-size: 0.85rem;
  box-shadow: 0 10px 26px rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(6px);
  cursor: pointer;
}

.monitor-fab:hover:not(:disabled) {
  background: rgba(22, 34, 45, 0.96);
  border-color: var(--accent);
}

.monitor-fab:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.monitor-fab-icon {
  font-size: 1rem;
  line-height: 1;
}

.monitor-fab-time {
  color: var(--text-muted);
  font-family: var(--mono);
  font-size: 0.72rem;
  font-weight: 400;
}
</style>
