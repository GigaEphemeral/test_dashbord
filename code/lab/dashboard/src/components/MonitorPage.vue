<script setup>
import { computed, ref } from 'vue'
import AnimationLab from './AnimationLab.vue'
import CacheLab from './CacheLab.vue'
import ChaosActions from './ChaosActions.vue'
import InstanceMetricsTable from './InstanceMetricsTable.vue'
import K6Progress from './K6Progress.vue'
import MonitorTimeline from './MonitorTimeline.vue'
import ObservePanel from './ObservePanel.vue'
import ThreadLab from './ThreadLab.vue'
import CacheKeysPanel from './CacheKeysPanel.vue'
import TopologyView from './TopologyView.vue'

/**
 * B3-00d "实时监控与操作".
 *
 * Tabs keep every legacy capability: 监控 (KPI + k6 progress + timelines +
 * instance table + topology), 实时操作 (container faults + cache lab) and
 * 实验工具 (thread lab + animation views). The old FaultsPanel is gone, but no
 * action surface was dropped.
 */
defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  toxiproxy: { type: Object, default: null },
  enabledNodes: { type: Array, default: () => [] }
})

defineEmits(['open-thread', 'changed'])

const TABS = [
  { id: 'monitor', label: '监控', testid: 'monitor-tab-monitor' },
  { id: 'ops', label: '实时操作', testid: 'monitor-tab-ops' },
  { id: 'tools', label: '实验工具', testid: 'monitor-tab-tools' }
]

const tab = ref('monitor')

/** Same instance derivation the removed FaultsPanel used. */
function instancesOf(containers) {
  return (containers || [])
    .filter((item) => /^jl-app-\d+$/.test(item.name))
    .map((item) => item.name.replace(/^jl-/, ''))
    .sort()
}
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
          <span class="hint">节点来自 <code>GET /lab-api/containers</code>；不在当前 run 启用节点内的 app / redis / mysql 节点灰显为未启用</span>
        </div>
        <TopologyView
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
  </div>
</template>

<style scoped>
.monitor-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
