<script setup>
import { computed } from 'vue'

/**
 * Benchmark / operations corner: the background k6 traffic container, the
 * container inventory and the log viewer. These are the same read-only Docker
 * surfaces the old 实验操作台 used; nothing here starts a benchmark script
 * directly (B2 owns k6 execution).
 */
const props = defineProps({
  containers: { type: Array, default: () => [] },
  selected: { type: String, default: '' },
  tail: { type: [Number, String], default: 200 },
  logs: { type: String, default: '' },
  loadingContainers: { type: Boolean, default: false },
  loadingLogs: { type: Boolean, default: false },
  traffic: { type: Object, default: null },
  busy: { type: String, default: '' }
})

const emit = defineEmits([
  'refresh-containers',
  'select-container',
  'refresh-logs',
  'update:tail',
  'traffic',
  'stop-all'
])

const trafficRunning = computed(() => Boolean(props.traffic?.running))
const trafficState = computed(() => props.traffic?.state || 'unknown')

function stateClass(state) {
  return state === 'running' ? 'ok' : 'warn'
}

function healthClass(health) {
  if (health === 'healthy') return 'ok'
  if (health === 'unhealthy') return 'down'
  return 'muted'
}

function onContainerChange(event) {
  emit('select-container', event.target.value)
}

function onTailChange(event) {
  emit('update:tail', Number(event.target.value))
}
</script>

<template>
  <div class="bench-panel">
    <section class="card">
      <div class="card-head">
        <h2>背景流量 · k6</h2>
        <span class="pill" :class="trafficRunning ? 'ok' : 'muted'" data-testid="traffic-state">{{ trafficState }}</span>
      </div>
      <p class="hint">
        受 profile 控制的 <code>jl-traffic</code> 容器（按需启动，不随栈常驻）。
        启停结果来自 <code>POST /lab/traffic/start|stop</code>；当前状态每轮同步刷新。
      </p>
      <div class="bench-actions">
        <button
          type="button"
          class="btn"
          data-testid="traffic-start"
          :disabled="trafficRunning || Boolean(busy)"
          @click="emit('traffic', 'start')"
        >{{ busy === 'traffic-start' ? '启动中…' : '开启背景流量' }}</button>
        <button
          type="button"
          class="btn ghost"
          data-testid="traffic-stop"
          :disabled="!trafficRunning || Boolean(busy)"
          @click="emit('traffic', 'stop')"
        >{{ busy === 'traffic-stop' ? '停止中…' : '停止背景流量' }}</button>
        <button
          type="button"
          class="btn ghost danger"
          data-testid="stop-all"
          :disabled="Boolean(busy)"
          @click="emit('stop-all')"
        >{{ busy === 'stop-all' ? '还原中…' : '清除全部故障' }}</button>
      </div>
      <p class="hint" data-testid="bench-k6-hint">
        正式 k6 场景仍走 CLI：<code>bash code/lab/scripts/bench.sh smoke</code>（B2 才提供受控执行器）。
      </p>
    </section>

    <div class="grid">
      <section class="card">
        <div class="card-head">
          <h2>容器清单 <span class="count">{{ containers.length }}</span></h2>
          <button type="button" class="btn ghost" :disabled="loadingContainers" @click="emit('refresh-containers')">
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
              @click="emit('select-container', item.name)"
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
            <select :value="selected" class="input select" aria-label="选择容器" data-testid="log-container" @change="onContainerChange">
              <option value="" disabled>选择容器</option>
              <option v-for="item in containers" :key="item.name" :value="item.name">{{ item.name }}</option>
            </select>
            <label class="tail-label">
              tail
              <input
                :value="tail"
                type="number"
                class="input tail-input"
                min="1"
                max="1000"
                @change="onTailChange"
              />
            </label>
            <button
              type="button"
              class="btn"
              data-testid="log-refresh"
              :disabled="loadingLogs || !selected"
              @click="emit('refresh-logs')"
            >{{ loadingLogs ? '读取中…' : '刷新日志' }}</button>
          </div>
        </div>
        <pre class="logs" data-testid="log-view">{{ logs || '（暂无日志，选择容器后刷新）' }}</pre>
        <p class="hint">只读 · tail 1–1000 · 经 Docker API 读取 stdout/stderr</p>
      </section>
    </div>
  </div>
</template>
