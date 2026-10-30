<script setup>
import { computed, ref, watch } from 'vue'
import { describeError, fetchLogs } from '../api'

/**
 * Environment drawer: the only surviving part of the old Benchmark page plus
 * the background-traffic switch. Log access is read-only (Docker stdout/stderr
 * through the controller) and the container list is the controller whitelist.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  containers: { type: Array, default: () => [] },
  traffic: { type: Object, default: null }
})

const emit = defineEmits(['changed', 'refresh-containers'])

const DEFAULT_TAIL = Number(import.meta.env.VITE_LAB_LOG_TAIL || 200)
const DEFAULT_CONTAINER = import.meta.env.VITE_LAB_LOG_DEFAULT_CONTAINER || 'jl-app-1'

const selected = ref('')
const tail = ref(DEFAULT_TAIL)
const logs = ref('')
const loading = ref(false)
const error = ref('')
const trafficBusy = ref('')

const trafficRunning = computed(() => Boolean(props.traffic?.running))
const trafficState = computed(() => props.traffic?.state || 'unknown')

watch(
  () => props.containers,
  (list) => {
    if (!list.length) return
    if (!list.some((item) => item.name === selected.value)) {
      selected.value = list.find((item) => item.name === DEFAULT_CONTAINER)?.name || list[0].name
    }
  },
  { immediate: true }
)

async function loadLogs() {
  if (!props.token || !selected.value) return
  const value = Number(tail.value)
  if (!Number.isInteger(value) || value < 1 || value > 1000) {
    error.value = 'tail 必须是 1–1000 之间的整数。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    logs.value = await fetchLogs(props.token, selected.value, value)
  } catch (err) {
    logs.value = ''
    error.value = describeError(err)
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.active, selected.value, props.token],
  ([active]) => {
    if (active) loadLogs()
  },
  { immediate: true }
)

function selectContainer(name) {
  selected.value = name
  loadLogs()
}

function stateClass(state) {
  return state === 'running' ? 'ok' : 'muted'
}

function healthClass(health) {
  if (health === 'healthy') return 'ok'
  if (health === 'unhealthy') return 'down'
  return 'muted'
}

function setTraffic(action) {
  trafficBusy.value = action
  emit('traffic', action)
  // The parent owns the API call; the button re-enables on the next state poll.
  setTimeout(() => { trafficBusy.value = '' }, 1500)
}
</script>

<template>
  <div class="environment-drawer">
    <section class="card">
      <div class="card-head">
        <h2>背景流量 · k6</h2>
        <span class="pill" :class="trafficRunning ? 'ok' : 'muted'" data-testid="traffic-state">{{ trafficState }}</span>
      </div>
      <p class="hint">
        受 profile 控制的 <code>jl-traffic</code> 容器（按需启动，不常驻）。
        正式实验由向导创建 run，这里只用于背景负载。
      </p>
      <div class="rowline">
        <button
          type="button"
          class="btn"
          data-testid="traffic-start"
          :disabled="trafficRunning || Boolean(trafficBusy)"
          @click="setTraffic('start')"
        >{{ trafficBusy === 'start' ? '启动中…' : '开启背景流量' }}</button>
        <button
          type="button"
          class="btn ghost"
          data-testid="traffic-stop"
          :disabled="!trafficRunning || Boolean(trafficBusy)"
          @click="setTraffic('stop')"
        >{{ trafficBusy === 'stop' ? '停止中…' : '停止背景流量' }}</button>
      </div>
    </section>

    <div class="env-grid">
      <section class="card env-containers">
        <div class="card-head">
          <h2>容器 <span class="count">{{ containers.length }}</span></h2>
          <button type="button" class="btn ghost" @click="emit('refresh-containers')">刷新</button>
        </div>
        <ul class="container-list">
          <li v-for="item in containers" :key="item.name">
            <button
              type="button"
              class="container-row"
              :class="{ selected: item.name === selected }"
              :data-testid="`env-container-${item.name}`"
              @click="selectContainer(item.name)"
            >
              <span class="container-name">{{ item.name }}</span>
              <span class="badges">
                <span class="badge" :class="stateClass(item.state)">{{ item.state }}</span>
                <span class="badge" :class="healthClass(item.health)">{{ item.health }}</span>
              </span>
            </button>
          </li>
          <li v-if="!containers.length" class="empty">暂无容器。</li>
        </ul>
      </section>

      <section class="card env-logs">
        <div class="card-head">
          <h2>容器日志</h2>
          <div class="log-controls">
            <select :value="selected" class="input select" aria-label="选择容器" data-testid="log-container" @change="selectContainer($event.target.value)">
              <option value="" disabled>选择容器</option>
              <option v-for="item in containers" :key="item.name" :value="item.name">{{ item.name }}</option>
            </select>
            <label class="tail-label">
              tail
              <input v-model.number="tail" type="number" class="input tail-input" min="1" max="1000" />
            </label>
            <button type="button" class="btn" data-testid="log-refresh" :disabled="loading || !selected" @click="loadLogs">
              {{ loading ? '读取中…' : '刷新日志' }}
            </button>
          </div>
        </div>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <pre class="logs" data-testid="log-view">{{ logs || '（暂无日志，选择容器后刷新）' }}</pre>
        <p class="hint">只读 · tail 1–1000 · 经 Docker API 读取 stdout/stderr</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.environment-drawer {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.env-grid {
  display: grid;
  grid-template-columns: minmax(200px, 0.8fr) minmax(0, 1.6fr);
  gap: 12px;
  align-items: start;
}

.env-containers .container-list {
  max-height: 60vh;
  overflow: auto;
}

.env-logs .logs {
  max-height: 52vh;
  overflow: auto;
}

@media (max-width: 720px) {
  .env-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
