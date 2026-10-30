<script setup>
import { computed } from 'vue'
import {
  APP_CONTAINER_NAME,
  containerTone,
  formatBytes,
  formatCpu,
  formatMillis,
  formatRate,
  instanceName,
  sortContainers
} from '../labModel'

/**
 * Read-only instance cards embedded in the monitor page. Configuration lives in
 * the wizard's step 3; the cards only show live values and open the thread or
 * JVM-parameter tool. Missing series are "无数据", never 0.
 */
const props = defineProps({
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  /** name (app-1…) -> { cpu, heapBytes, reqRate, latencySeconds, threads, … }. */
  metrics: { type: Object, default: () => ({}) },
  promNote: { type: String, default: '' },
  /** Enabled Java nodes of the newest run; empty means "all configured". */
  enabledNodes: { type: Array, default: () => [] }
})

const emit = defineEmits(['open-thread', 'open-config'])

const enabledSet = computed(() => (props.enabledNodes.length ? new Set(props.enabledNodes) : null))

const faultsByTarget = computed(() => {
  const map = new Map()
  for (const fault of props.faults || []) {
    const list = map.get(fault.target) || []
    list.push(fault)
    map.set(fault.target, list)
  }
  return map
})

const cards = computed(() =>
  sortContainers(props.containers.filter((container) => APP_CONTAINER_NAME.test(container.name))).map((container) => {
    const name = instanceName(container.name)
    const metric = props.metrics[name] || {}
    return {
      name,
      container: container.name,
      state: container.state,
      health: container.health,
      enabled: !enabledSet.value || enabledSet.value.has(name),
      faults: faultsByTarget.value.get(container.name) || [],
      ...metric
    }
  })
)

const missingMetrics = computed(() =>
  cards.value.filter((card) => !Number.isFinite(card.reqRate) && !Number.isFinite(card.cpu)).length
)

function healthClass(health) {
  if (health === 'healthy') return 'ok'
  if (health === 'unhealthy') return 'down'
  if (health === 'starting') return 'warn'
  return 'muted'
}

function integerText(value) {
  return Number.isFinite(value) ? String(Math.round(value)) : '无数据'
}
</script>

<template>
  <div class="instances-page" data-testid="instances-page">
    <div v-if="promNote" class="notice" role="status" data-testid="instances-prom-note">{{ promNote }}</div>

    <div v-if="!cards.length" class="empty" data-testid="instances-empty">
      容器清单里没有应用实例：检查访问令牌或控制器状态。
    </div>

    <div v-else class="instance-grid">
      <article
        v-for="card in cards"
        :key="card.name"
        class="card instance-card"
        :class="[`tone-${containerTone(card)}`, { off: !card.enabled }]"
        :data-testid="`instance-card-${card.name}`"
      >
        <header class="instance-head">
          <div>
            <h3><code>{{ card.container }}</code></h3>
            <p class="instance-sub">{{ card.name }} · 应用实例</p>
          </div>
          <div class="instance-badges">
            <span class="badge" :class="card.state === 'running' ? 'ok' : 'muted'">{{ card.state }}</span>
            <span class="badge" :class="healthClass(card.health)">{{ card.health }}</span>
            <span v-if="!card.enabled" class="badge muted" :data-testid="`instance-disabled-${card.name}`">未启用</span>
          </div>
        </header>

        <div class="instance-metrics">
          <div class="metric">
            <span class="metric-label">CPU</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.cpu) }"
              :data-testid="`instance-cpu-${card.name}`"
            >{{ formatCpu(card.cpu) }}</strong>
            <span class="metric-note">单核占比</span>
          </div>
          <div class="metric">
            <span class="metric-label">堆内存</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.heapBytes) }"
              :data-testid="`instance-heap-${card.name}`"
            >{{ formatBytes(card.heapBytes) }}</strong>
            <span class="metric-note">jvm_memory_used_bytes</span>
          </div>
          <div class="metric">
            <span class="metric-label">请求速率</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.reqRate) }"
              :data-testid="`instance-rate-${card.name}`"
            >{{ formatRate(card.reqRate) }}</strong>
            <span class="metric-note">rate[1m]</span>
          </div>
          <div class="metric">
            <span class="metric-label">P95</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.latencySeconds) }"
              :data-testid="`instance-latency-${card.name}`"
            >{{ formatMillis(card.latencySeconds) }}</strong>
            <span class="metric-note">http_server_requests</span>
          </div>
          <div class="metric">
            <span class="metric-label">活线程</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.threads) }"
              :data-testid="`instance-threads-${card.name}`"
            >{{ integerText(card.threads) }}</strong>
            <span class="metric-note">jvm_threads_live_threads</span>
          </div>
          <div class="metric">
            <span class="metric-label">业务池</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.poolActive) }"
              :data-testid="`instance-pool-${card.name}`"
            >{{ integerText(card.poolActive) }}<span class="metric-sub"> / {{ integerText(card.poolQueued) }}</span></strong>
            <span class="metric-note">active / queue</span>
          </div>
          <div class="metric">
            <span class="metric-label">拒绝</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.rejected) }"
              :data-testid="`instance-rejected-${card.name}`"
            >{{ integerText(card.rejected) }}</strong>
            <span class="metric-note">5 分钟增量</span>
          </div>
        </div>

        <footer class="instance-foot">
          <span v-if="!card.enabled" class="chip" :data-testid="`instance-disabled-note-${card.name}`">未启用 · 不参与本次实验</span>
          <span v-else-if="card.faults.length" class="instance-faults">
            <span
              v-for="fault in card.faults"
              :key="fault.id"
              class="badge warn"
              :data-testid="`instance-fault-${card.name}`"
            >{{ fault.action }} · {{ fault.status }}</span>
          </span>
          <span v-else class="instance-clean">无活动故障</span>
          <span class="instance-actions">
            <button
              type="button"
              class="btn ghost"
              :data-testid="`instance-open-thread-${card.name}`"
              @click="emit('open-thread', card.name)"
            >线程工具</button>
            <button
              type="button"
              class="btn ghost"
              :data-testid="`instance-open-config-${card.name}`"
              @click="emit('open-config', card.name)"
            >JVM 参数</button>
          </span>
        </footer>
      </article>
    </div>

    <p v-if="missingMetrics" class="hint">
      {{ missingMetrics }} 个实例没有 CPU / 速率序列：应用可能刚启动，或 Prometheus 尚未完成第一次抓取。
    </p>
  </div>
</template>

<style scoped>
.instance-card.off {
  opacity: 0.55;
  filter: grayscale(1);
}
.instance-card.off .metric-value {
  color: var(--text-muted);
}
.instance-actions {
  display: inline-flex;
  gap: 6px;
  margin-left: auto;
}
.metric-sub {
  color: var(--text-muted);
  font-size: 0.72rem;
  font-weight: 400;
}
</style>
