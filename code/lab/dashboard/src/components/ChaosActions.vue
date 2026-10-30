<script setup>
import { ref } from 'vue'
import {
  applyChaosAction,
  describeError,
  fetchOperation,
  stopAllFaults
} from '../api'

/**
 * Manual container-fault card. It uses the existing /chaos action endpoints and
 * the operation contract: a 202 reply means PENDING_CONFIRMATION, so the UI
 * polls GET /lab/operations/{id} until APPLIED/RESTORED/FAILED instead of
 * claiming the fault is in place.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  instances: { type: Array, default: () => ['app-1', 'app-2', 'app-3'] },
  faults: { type: Array, default: () => [] }
})

const emit = defineEmits(['changed'])

const ACTIONS = ['freeze', 'kill', 'restart', 'start', 'resume']
const OPERATION_POLL_INTERVAL_MS = Number(import.meta.env.VITE_LAB_OPERATION_POLL_INTERVAL_MS || 1000)
const OPERATION_POLL_TIMEOUT_MS = Number(import.meta.env.VITE_LAB_OPERATION_POLL_TIMEOUT_MS || 30000)
const DEFAULT_TTL_SECONDS = Number(import.meta.env.VITE_LAB_CHAOS_DEFAULT_TTL_SECONDS || 60)

const instance = ref(props.instances[0] || 'app-1')
const action = ref('freeze')
const ttlSeconds = ref(DEFAULT_TTL_SECONDS)
const busy = ref('')
const error = ref('')
const operation = ref(null)
const pendingNote = ref('')

function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `lab-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function pollOperation(operationId) {
  if (!operationId) return null
  const deadline = Date.now() + OPERATION_POLL_TIMEOUT_MS
  let latest = null
  while (Date.now() < deadline) {
    latest = await fetchOperation(props.token, operationId)
    operation.value = latest
    if (['APPLIED', 'RESTORED', 'FAILED', 'UNKNOWN'].includes(latest?.status)) {
      return latest
    }
    await sleep(OPERATION_POLL_INTERVAL_MS)
  }
  pendingNote.value = `操作 ${operationId} 仍在 PENDING_CONFIRMATION；刷新后继续观察，不要重复注入。`
  return latest
}

async function inject() {
  error.value = ''
  pendingNote.value = ''
  operation.value = null
  busy.value = 'inject'
  try {
    const { data } = await applyChaosAction(
      props.token,
      instance.value,
      action.value,
      Number(ttlSeconds.value),
      newIdempotencyKey()
    )
    operation.value = data
    if (data?.status === 'PENDING_CONFIRMATION' || data?.operationId) {
      if (data?.status === 'PENDING_CONFIRMATION') {
        await pollOperation(data.operationId)
      }
    }
    emit('changed')
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

async function clearAll() {
  error.value = ''
  pendingNote.value = ''
  busy.value = 'clear'
  try {
    const { status, data } = await stopAllFaults(props.token)
    if (status === 202 || data?.status === 'PENDING_CONFIRMATION') {
      // Same operation contract as the inject path: never claim RESTORED while
      // the controller still reports PENDING_CONFIRMATION.
      operation.value = data?.operationId ? { operationId: data.operationId, status: data.status } : null
      await pollOperation(data?.operationId)
    } else {
      operation.value = data?.operationId ? { operationId: data.operationId, status: 'RESTORED' } : null
    }
    emit('changed')
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

function timeLabel(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

/**
 * B2-04a/c liveness label: `remainingMs` is the only countdown source, frozen
 * while the guide is paused. REVERTED/FAILED already settled, so their status
 * (kept on the row) is the only meaningful text; a null remainder means the
 * action has no TTL (one-shot start/restart/resume).
 */
function remainingLabel(item) {
  if (!item) return '—'
  if (['REVERTED', 'FAILED'].includes(item.status)) return item.status
  if (item.remainingMs === null || item.remainingMs === undefined) return '不过期/一次性'
  const ms = Math.max(0, Number(item.remainingMs) || 0)
  if (ms < 1000) return `${ms}ms`
  if (ms < 10000) return `${(ms / 1000).toFixed(1)}s`
  if (ms < 60000) return `${Math.round(ms / 1000)}s`
  const minutes = ms / 60000
  return `${minutes < 10 ? Math.round(minutes * 10) / 10 : Math.round(minutes)}m`
}
</script>

<template>
  <section class="card chaos-card">
    <div class="card-head">
      <h2>故障注入 <span class="count">{{ faults.length }} 活动</span></h2>
      <span class="hint">目标 <code>{{ instance }}</code> · 动作 <code>{{ action }}</code></span>
    </div>
    <p class="hint">
      动作走 <code>POST /lab-api/chaos/instances/{name}/actions</code>；控制台传 <code>app-N</code>，
      服务端规范化为容器名 <code>jl-app-N</code>。这里操作的是<strong>整个容器/JVM 进程</strong>，不是某个线程。
      kill/freeze 带 TTL 自动回滚；202 表示 PENDING_CONFIRMATION，卡片会轮询 operation 到终态。
      「已请求」不等于「已生效」，以 operation 状态为准。详见《故障注入使用说明》。
    </p>

    <div class="chaos-form">
      <label class="form-label">
        实例
        <select v-model="instance" class="input select" data-testid="chaos-instance">
          <option v-for="name in instances" :key="name" :value="name">{{ name }}</option>
        </select>
      </label>
      <label class="form-label">
        动作
        <select v-model="action" class="input select" data-testid="chaos-action">
          <option v-for="item in ACTIONS" :key="item" :value="item">{{ item }}</option>
        </select>
      </label>
      <label class="form-label">
        TTL 秒
        <input v-model.number="ttlSeconds" type="number" min="5" max="600" class="input tail-input" data-testid="chaos-ttl" />
      </label>
      <button type="button" class="btn" data-testid="chaos-inject" :disabled="Boolean(busy) || !token" @click="inject">
        {{ busy === 'inject' ? '执行中…' : '注入故障' }}
      </button>
      <button type="button" class="btn ghost danger" data-testid="chaos-clear-all" :disabled="Boolean(busy) || !token" @click="clearAll">
        {{ busy === 'clear' ? '还原中…' : '清除全部故障' }}
      </button>
    </div>

    <div v-if="error" class="error-banner" role="alert" data-testid="chaos-error">{{ error }}</div>
    <p v-if="pendingNote" class="notice" role="status" data-testid="chaos-pending">{{ pendingNote }}</p>

    <div v-if="operation" class="chaos-result" data-testid="chaos-result">
      <span>operation <code data-testid="chaos-operation-id">{{ operation.operationId }}</code></span>
      <span class="badge" :class="operation.status === 'FAILED' ? 'down' : 'ok'" data-testid="chaos-operation-status">{{ operation.status }}</span>
      <span class="hint">{{ timeLabel(operation.appliedAt || operation.requestedAt) }}</span>
      <span v-if="operation.lastError" class="hint">错误：{{ operation.lastError }}</span>
    </div>

    <ul v-if="faults.length" class="fault-tags" data-testid="chaos-active-faults">
      <li v-for="(item, index) in faults" :key="item.id" :data-testid="`chaos-fault-row-${index}`">
        <code>{{ item.action }} · {{ item.target }} · {{ item.status }}</code>
        <span class="badge muted" data-testid="chaos-fault-remaining">{{ remainingLabel(item) }}</span>
        <span v-if="item.paused" class="badge warn" data-testid="chaos-fault-paused">已冻结</span>
        <span v-if="item.expiresAt" class="hint">到期 {{ timeLabel(item.expiresAt) }}</span>
      </li>
    </ul>
  </section>
</template>
