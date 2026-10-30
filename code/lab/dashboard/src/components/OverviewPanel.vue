<script setup>
import { computed } from 'vue'

/**
 * Read-only summary of the environment. Every number comes from an existing
 * API call owned by App.vue (health, containers, chaos actions, guide state,
 * traffic state, ledger status + newest run); this panel never invents a value
 * and never turns a missing reading into 0.
 */
const props = defineProps({
  healthState: { type: String, default: 'checking' },
  healthLabel: { type: String, default: '检测中…' },
  steadyContainers: { type: Array, default: () => [] },
  healthyContainers: { type: Number, default: 0 },
  faults: { type: Array, default: () => [] },
  guide: { type: Object, default: null },
  traffic: { type: Object, default: null },
  ledgerStatus: { type: Object, default: null },
  ledgerError: { type: String, default: '' },
  latestRun: { type: Object, default: null },
  loadingContainers: { type: Boolean, default: false }
})

const emit = defineEmits(['navigate', 'refresh'])

const guideStatus = computed(() => props.guide?.status || 'unknown')
const resetMode = computed(() => (props.guide?.resetMode === 'auto' ? 'auto 结束自动还原' : 'manual 手动还原'))
const trafficLabel = computed(() => props.traffic?.state || 'unknown')
const ledgerState = computed(() => {
  if (props.ledgerError) return 'DEGRADED'
  return props.ledgerStatus?.state || 'NO_DATA'
})
const ledgerReason = computed(() => (
  props.ledgerError || props.ledgerStatus?.lastError || '台账未返回状态。'
))
const notRunning = computed(() => (
  props.steadyContainers.filter((item) => item.state !== 'running').map((item) => item.name)
))
const ledgerTone = computed(() => {
  if (ledgerState.value === 'OK') return 'ok'
  if (ledgerState.value === 'DEGRADED') return 'down'
  return 'muted'
})
const healthTone = computed(() => {
  if (props.healthState === 'up') return 'ok'
  if (props.healthState === 'down') return 'down'
  return 'muted'
})

const quickLinks = [
  { id: 'wizard', label: '场景向导', hint: '目标、变量与配置、k6、预检查' },
  { id: 'observe', label: '实时监控与操作', hint: '时间轴、实例卡片、缓存流动、拓扑' },
  { id: 'runs', label: '实验记录', hint: '台账、报告、归档与删除' }
]
</script>

<template>
  <div class="overview-panel">
    <section class="overview-grid">
      <article class="card overview-card">
        <div class="card-head">
          <h2>控制器</h2>
          <span class="badge" :class="healthTone">{{ healthLabel }}</span>
        </div>
        <p class="overview-big" :class="healthTone">{{ healthState === 'up' ? 'ONLINE' : healthState === 'down' ? 'OFFLINE' : 'CHECKING' }}</p>
        <p class="hint">健康探测 <code>GET /lab-api/health</code>；离线时页面上的所有动作都会被拒绝。</p>
      </article>

      <article class="card overview-card">
        <div class="card-head">
          <h2>容器池</h2>
          <span
            class="badge"
            :class="healthyContainers === steadyContainers.length && steadyContainers.length ? 'ok' : 'warn'"
            data-testid="container-rollup"
            :title="`常驻容器 ${healthyContainers}/${steadyContainers.length} 运行中；按需/一次性容器不计入`"
          >{{ healthyContainers }}/{{ steadyContainers.length }}</span>
        </div>
        <p class="hint">常驻容器运行中；按需容器（traffic / init / jdk8）单独管理。</p>
        <p v-if="notRunning.length" class="overview-warn">未运行：<code v-for="name in notRunning" :key="name">{{ name }}</code></p>
        <p v-else-if="steadyContainers.length" class="hint">所有常驻容器都在运行。</p>
        <p v-else class="hint">容器清单尚未加载；先确认令牌与控制器状态。</p>
        <button type="button" class="btn ghost" :disabled="loadingContainers" @click="emit('refresh')">
          {{ loadingContainers ? '刷新中…' : '刷新环境' }}
        </button>
      </article>

      <article class="card overview-card">
        <div class="card-head">
          <h2>活动故障</h2>
          <span class="badge" :class="faults.length ? 'warn' : 'ok'" data-testid="fault-count">{{ faults.length }}</span>
        </div>
        <p v-if="faults.length" class="overview-faults">
          <code v-for="item in faults" :key="item.id">{{ item.action }} · {{ item.target }} · {{ item.status }}</code>
        </p>
        <p v-else class="hint">当前没有跟踪中的故障动作。</p>
        <button type="button" class="btn ghost" @click="emit('navigate', 'observe')">去实时监控 · 故障注入 →</button>
      </article>

      <article class="card overview-card">
        <div class="card-head">
          <h2>当前 run</h2>
          <span v-if="latestRun" class="badge" :class="`run-${String(latestRun.status).toLowerCase()}`">{{ latestRun.status }}</span>
          <span v-else class="badge muted">NO_DATA</span>
        </div>
        <template v-if="latestRun">
          <p class="overview-big"><code data-testid="overview-current-run">{{ latestRun.runId }}</code></p>
          <p class="hint">{{ latestRun.scenario }} · 更新 {{ latestRun.updatedAt || latestRun.createdAt }}</p>
        </template>
        <p v-else class="hint" data-testid="overview-current-run">台账里还没有 run。</p>
        <button type="button" class="btn ghost" @click="emit('navigate', 'runs')">打开实验记录 →</button>
      </article>

      <article class="card overview-card">
        <div class="card-head">
          <h2>台账</h2>
          <span class="badge" :class="ledgerTone" data-testid="overview-ledger-state">{{ ledgerState }}</span>
        </div>
        <p class="hint">
          {{ ledgerState === 'OK' ? (ledgerStatus?.mode || 'ledger') : ledgerReason }}
        </p>
        <p v-if="ledgerStatus" class="hint">
          schema {{ ledgerStatus.schemaVersion || '—' }} · 暂存 {{ ledgerStatus.pendingStash ?? 0 }} ·
          最近写入 {{ ledgerStatus.lastWriteAt || '—' }}
        </p>
        <p v-if="ledgerError" class="overview-warn" data-testid="overview-ledger-error">{{ ledgerError }}</p>
      </article>

      <article class="card overview-card">
        <div class="card-head">
          <h2>场景与流量</h2>
          <span class="pill" :class="guideStatus" data-testid="guide-status">{{ guideStatus }}</span>
        </div>
        <p class="hint">{{ guide?.scenarioName || '未载入场景' }}</p>
        <p class="hint">
          <span class="badge muted" data-testid="reset-mode">{{ resetMode }}</span>
          背景流量 <span class="badge" :class="trafficLabel === 'running' ? 'ok' : 'muted'" data-testid="overview-traffic-state">{{ trafficLabel }}</span>
        </p>
        <button type="button" class="btn ghost" @click="emit('navigate', 'wizard')">打开场景向导 →</button>
      </article>
    </section>

    <section class="card overview-quick">
      <div class="card-head">
        <h2>快捷入口</h2>
        <span class="hint">四个工作分区：总览 / 场景向导 / 实时监控与操作 / 实验记录</span>
      </div>
      <div class="quick-grid">
        <button
          v-for="link in quickLinks"
          :key="link.id"
          type="button"
          class="quick-btn"
          :data-testid="`overview-goto-${link.id}`"
          @click="emit('navigate', link.id)"
        >
          <strong>{{ link.label }}</strong>
          <span>{{ link.hint }}</span>
        </button>
      </div>
    </section>
  </div>
</template>
