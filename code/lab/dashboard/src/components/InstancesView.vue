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

const props = defineProps({
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  /** name (app-1…) -> { cpu, heapBytes, reqRate, p99Seconds, threads } or nulls. */
  metrics: { type: Object, default: () => ({}) },
  promNote: { type: String, default: '' },
  /** Enabled Java nodes of the newest run; empty means "all configured". */
  enabledNodes: { type: Array, default: () => [] }
})

const emit = defineEmits(['open-thread'])

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
</script>

<template>
  <section class="instances-page" data-testid="instances-page">
    <p class="hint">
      每张卡片对应一个 <code>jl-app-*</code> 容器；数值来自 Prometheus（5s 轮询），
      抓不到序列时显示「无数据」而不是 0。
    </p>

    <div class="notice info" data-testid="instances-b4-note">
      实例/JVM 配置属 B4：本页只读展示「当前状态 + 配置组库」；完整参数在向导第 3 步编辑。
      未启用节点置灰显示「未启用」，不参与实验。Caffeine 过期/刷新按秒配置，0=不启用；关闭 L1 时清空本地条目并显示「不适用」。
    </div>

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
            <span class="metric-label">P99</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.p99Seconds) }"
              :data-testid="`instance-p99-${card.name}`"
            >{{ formatMillis(card.p99Seconds) }}</strong>
            <span class="metric-note">http_server_requests</span>
          </div>
          <div class="metric">
            <span class="metric-label">活线程</span>
            <strong
              class="metric-value"
              :class="{ missing: !Number.isFinite(card.threads) }"
              :data-testid="`instance-threads-${card.name}`"
            >{{ Number.isFinite(card.threads) ? Math.round(card.threads) : '无数据' }}</strong>
            <span class="metric-note">jvm_threads_live_threads</span>
          </div>
        </div>

        <footer class="instance-foot">
          <span v-if="!card.enabled" class="chip" :data-testid="`instance-disabled-note-${card.name}`">未启用 · 完整参数在向导第 3 步</span>
          <span v-else-if="card.faults.length" class="instance-faults">
            <span
              v-for="fault in card.faults"
              :key="fault.id"
              class="badge warn"
              :data-testid="`instance-fault-${card.name}`"
            >{{ fault.action }} · {{ fault.status }}</span>
          </span>
          <span v-else class="instance-clean">无活动故障</span>
          <button
            type="button"
            class="btn ghost"
            :data-testid="`instance-open-thread-${card.name}`"
            :disabled="!card.enabled"
            @click="emit('open-thread', card.name)"
          >线程实验室 →</button>
        </footer>
      </article>
    </div>

    <p v-if="missingMetrics" class="hint">
      {{ missingMetrics }} 个实例没有 CPU / 速率序列：应用可能刚启动，或 Prometheus 尚未完成第一次抓取。
    </p>
    <p class="hint">
      线程卡片里的「线程实验室 →」会把该实例带到线程页（同一实例下拉），不会注入任何故障。
      完整 JVM/线程池/Caffeine/GC 日志参数在向导第 3 步；本页不提供应用或重启。
    </p>
  </section>
</template>

<style scoped>
.instance-card.off {
  opacity: 0.55;
  filter: grayscale(1);
}
.instance-card.off .metric-value {
  color: var(--text-muted);
}
</style>
