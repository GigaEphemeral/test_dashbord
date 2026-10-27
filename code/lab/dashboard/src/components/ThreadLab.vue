<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  captureThreadSnapshot,
  describeError,
  fetchThreadLifecycle,
  fetchThreadState,
  runThreadFaultAction,
  runThreadLifecycleAction,
  startThreadFault,
  stopAllThreadFaults
} from '../api'

const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  /** Instance preselected by another page (e.g. the instance cards). */
  focusInstance: { type: String, default: '' }
})

// Same polling budget as the console so both views feel identical. The timer
// never fires while an action or a manual refresh holds the busy flag.
const SYNC_INTERVAL_MS = Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000)
const INSTANCES = (import.meta.env.VITE_LAB_APP_INSTANCES || 'app-1,app-2,app-3')
  .split(',')
  .map((name) => name.trim())
  .filter(Boolean)
const MODES = ['deadlock', 'stuck', 'cpu-burn', 'heap-leak', 'oom', 'thread-leak', 'starvation']
const DEMO_STATES = ['NEW', 'RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING', 'TERMINATED']

const instance = ref(INSTANCES[0] || 'app-1')
const faults = ref([])
const lifecycle = ref(null)
const snapshot = ref(null)
const busy = ref('')
const error = ref('')
const form = reactive({ mode: 'cpu-burn', hold: 'auto', ttlSeconds: 120, virtual: false })

const demoState = computed(() => lifecycle.value?.state || 'unknown')
const demoIndex = computed(() =>
  DEMO_STATES.indexOf(demoState.value)
)
const started = computed(() => Boolean(lifecycle.value?.started))
const frozen = computed(() => Boolean(lifecycle.value?.frozen))
const terminated = computed(() => demoState.value === 'TERMINATED')
const faultCount = computed(() => faults.value.length)

let pollTimer = null
let pollInFlight = false

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => {
    if (!busy.value) {
      refresh({ quiet: true })
    }
  }, SYNC_INTERVAL_MS)
}

async function refresh({ quiet = false } = {}) {
  if (!props.active || !props.token || pollInFlight) return
  pollInFlight = true
  if (!quiet) busy.value = 'refresh'
  error.value = ''
  try {
    const [state, demo] = await Promise.all([
      fetchThreadState(props.token, instance.value),
      fetchThreadLifecycle(props.token, instance.value)
    ])
    faults.value = state || []
    lifecycle.value = demo
  } catch (err) {
    error.value = describeError(err)
  } finally {
    pollInFlight = false
    if (!quiet) busy.value = ''
  }
}

async function inject() {
  if (!props.token) {
    error.value = '请先在上方填写访问令牌。'
    return
  }
  const ttl = Number(form.ttlSeconds)
  if (form.hold === 'auto' && (!Number.isInteger(ttl) || ttl < 1 || ttl > 600)) {
    error.value = 'TTL 必须是 1–600 之间的整数秒。'
    return
  }
  error.value = ''
  busy.value = 'inject'
  try {
    const payload = { mode: form.mode, hold: form.hold }
    if (form.hold === 'auto') payload.ttlSeconds = ttl
    if (form.mode === 'thread-leak') payload.virtual = form.virtual
    await startThreadFault(props.token, instance.value, payload)
    await refresh({ quiet: true })
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

async function faultAction(mode, action) {
  error.value = ''
  busy.value = `${action}-${mode}`
  try {
    await runThreadFaultAction(props.token, instance.value, mode, action)
    await refresh({ quiet: true })
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

async function stopAll() {
  error.value = ''
  busy.value = 'stop-all'
  try {
    await stopAllThreadFaults(props.token, instance.value)
    await refresh({ quiet: true })
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

async function lifecycleAction(action) {
  error.value = ''
  busy.value = `lifecycle-${action}`
  try {
    lifecycle.value = await runThreadLifecycleAction(props.token, instance.value, action)
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

async function takeSnapshot() {
  error.value = ''
  busy.value = 'snapshot'
  try {
    snapshot.value = await captureThreadSnapshot(props.token, instance.value)
  } catch (err) {
    error.value = describeError(err)
  } finally {
    busy.value = ''
  }
}

function ttlLabel(fault) {
  if (fault.remainingTtlMillis < 0) return 'manual 保持'
  return `${Math.ceil(fault.remainingTtlMillis / 1000)}s`
}

function startedLabel(epochMs) {
  return new Date(epochMs).toLocaleTimeString('zh-CN', { hour12: false })
}

function demoChipClass(index) {
  if (index === demoIndex.value) return 'current'
  if (demoIndex.value >= 0 && index < demoIndex.value) return 'done'
  return ''
}

// Runs before the activation watcher so the first fetch already targets the
// instance another page asked for, instead of fetching app-1 then re-fetching.
watch(
  () => props.focusInstance,
  (value) => {
    if (value && INSTANCES.includes(value) && value !== instance.value) {
      instance.value = value
    }
  },
  { immediate: true }
)

watch(
  () => props.active,
  (active) => {
    if (active) {
      refresh()
      startPolling()
    } else {
      stopPolling()
    }
  },
  { immediate: true }
)

watch([() => props.token, instance], () => {
  if (props.active) {
    refresh()
  }
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="thread-page" data-testid="thread-page">
    <section class="status-bar">
      <div class="status-item">
        <span class="status-label">实例</span>
        <select v-model="instance" class="input select" data-testid="thread-instance" aria-label="应用实例">
          <option v-for="name in INSTANCES" :key="name" :value="name">{{ name }}</option>
        </select>
      </div>
      <div class="status-item">
        <span class="status-label">活动故障</span>
        <span class="pill" :class="faultCount ? 'warn' : 'ok'" data-testid="thread-fault-count">{{ faultCount }}</span>
      </div>
      <div class="status-item">
        <span class="status-label">演示线程</span>
        <span
          class="pill"
          :class="frozen ? 'paused' : (started ? 'running' : 'muted')"
          data-testid="thread-lifecycle-state"
        >{{ demoState }}</span>
      </div>
      <div class="status-item">
        <button type="button" class="btn ghost" data-testid="thread-refresh" :disabled="Boolean(busy)" @click="refresh()">
          {{ busy === 'refresh' ? '同步中…' : '同步状态' }}
        </button>
      </div>
    </section>

    <div v-if="error" class="error-banner" role="alert" data-testid="thread-error">{{ error }}</div>

    <div class="thread-grid">
      <section class="card">
        <div class="card-head">
          <h2>注入线程故障</h2>
          <span class="hint">七种模式 · TTL 或 manual 保持</span>
        </div>
        <div class="thread-form">
          <label class="form-label">
            模式
            <select v-model="form.mode" class="input select" data-testid="thread-mode">
              <option v-for="mode in MODES" :key="mode" :value="mode">{{ mode }}</option>
            </select>
          </label>
          <label class="form-label">
            保持
            <select v-model="form.hold" class="input select" data-testid="thread-hold">
              <option value="auto">auto（到期自动回退）</option>
              <option value="manual">manual（保持）</option>
            </select>
          </label>
          <label class="form-label">
            TTL 秒
            <input
              v-model.number="form.ttlSeconds"
              type="number"
              class="input tail-input"
              data-testid="thread-ttl"
              min="1"
              max="600"
              :disabled="form.hold === 'manual'"
            />
          </label>
          <label v-if="form.mode === 'thread-leak'" class="form-check">
            <input v-model="form.virtual" type="checkbox" data-testid="thread-virtual" />
            虚拟线程（JDK 21 对照）
          </label>
          <button type="button" class="btn" data-testid="thread-inject" :disabled="Boolean(busy) || !token" @click="inject">
            {{ busy === 'inject' ? '注入中…' : '注入故障' }}
          </button>
        </div>
        <p class="hint">
          pause 只冻结 TTL，不停止参与线程；manual 故障不会自动回退，演示后请停止。
          同一模式重复注入返回 409。
        </p>
      </section>

      <section class="card thread-faults-card">
        <div class="card-head">
          <h2>当前故障 <span class="count">{{ faultCount }}</span></h2>
          <button type="button" class="btn ghost danger" data-testid="thread-stop-all" :disabled="Boolean(busy) || !faultCount" @click="stopAll">
            {{ busy === 'stop-all' ? '停止中…' : '全部停止' }}
          </button>
        </div>
        <ul class="thread-faults" data-testid="thread-fault-list">
          <li v-for="fault in faults" :key="fault.mode" class="thread-fault-row" :data-testid="`thread-fault-${fault.mode}`">
            <div class="thread-fault-main">
              <strong>{{ fault.mode }}</strong>
              <span class="badge" :class="fault.state === 'ACTIVE' ? 'warn' : 'ok'">{{ fault.state }}</span>
              <code>{{ fault.hold }} · {{ ttlLabel(fault) }}</code>
            </div>
            <div class="thread-fault-meta">
              <span>开始 {{ startedLabel(fault.startedAtEpochMs) }}</span>
              <span>线程 {{ fault.threadIds?.length ? fault.threadIds.join(', ') : '—' }}</span>
            </div>
            <div class="thread-fault-actions">
              <button
                type="button"
                class="btn ghost"
                :data-testid="`thread-pause-${fault.mode}`"
                :disabled="Boolean(busy) || fault.state !== 'ACTIVE'"
                @click="faultAction(fault.mode, 'pause')"
              >暂停</button>
              <button
                type="button"
                class="btn ghost"
                :data-testid="`thread-resume-${fault.mode}`"
                :disabled="Boolean(busy) || fault.state !== 'PAUSED'"
                @click="faultAction(fault.mode, 'resume')"
              >继续</button>
              <button
                type="button"
                class="btn ghost danger"
                :data-testid="`thread-stop-${fault.mode}`"
                :disabled="Boolean(busy)"
                @click="faultAction(fault.mode, 'stop')"
              >停止</button>
            </div>
          </li>
          <li v-if="!faultCount" class="empty" data-testid="thread-empty">当前实例没有活动线程故障</li>
        </ul>
      </section>
    </div>

    <div class="thread-grid">
      <section class="card">
        <div class="card-head">
          <h2>单线程生命周期</h2>
          <span class="pill" :class="frozen ? 'paused' : (started ? 'running' : 'muted')">
            {{ demoState }} · 步骤 {{ lifecycle?.step ?? 0 }}/{{ lifecycle?.maxSteps ?? 0 }}
          </span>
        </div>
        <ol class="lifecycle-track" data-testid="thread-lifecycle-track">
          <li
            v-for="(state, index) in DEMO_STATES"
            :key="state"
            class="lifecycle-chip"
            :class="demoChipClass(index)"
          >
            <span class="lifecycle-code">{{ index }}</span>
            <span>{{ state }}</span>
          </li>
        </ol>
        <p class="hint">
          state / stateCode 是该线程真实的 <code>Thread.getState()</code>（0–5）；
          freeze 只暂停推进并立即抓取快照，不冻结 JVM。
          <template v-if="lifecycle">
            · {{ lifecycle.threadName }}#{{ lifecycle.threadId }} ·
            {{ lifecycle.alive ? 'alive' : 'not alive' }} · machineStage {{ lifecycle.machineStage }}
          </template>
        </p>
        <div class="thread-actions">
          <button type="button" class="btn" data-testid="thread-lifecycle-start" :disabled="Boolean(busy) || started" @click="lifecycleAction('start')">
            {{ busy === 'lifecycle-start' ? '启动中…' : '启动线程' }}
          </button>
          <button type="button" class="btn" data-testid="thread-lifecycle-step" :disabled="Boolean(busy) || !started || frozen || terminated" @click="lifecycleAction('step')">
            {{ busy === 'lifecycle-step' ? '推进中…' : '推进一步' }}
          </button>
          <button type="button" class="btn ghost" data-testid="thread-lifecycle-freeze" :disabled="Boolean(busy) || !started || frozen || terminated" @click="lifecycleAction('freeze')">
            {{ busy === 'lifecycle-freeze' ? '冻结中…' : '冻结并抓快照' }}
          </button>
          <button type="button" class="btn ghost" data-testid="thread-lifecycle-resume" :disabled="Boolean(busy) || !frozen" @click="lifecycleAction('resume')">
            {{ busy === 'lifecycle-resume' ? '恢复中…' : '恢复推进' }}
          </button>
          <button type="button" class="btn ghost danger" data-testid="thread-lifecycle-reset" :disabled="Boolean(busy) || !started" @click="lifecycleAction('reset')">
            {{ busy === 'lifecycle-reset' ? '复位中…' : '复位到 NEW' }}
          </button>
        </div>
      </section>

      <section class="card">
        <div class="card-head">
          <h2>线程快照</h2>
          <button type="button" class="btn" data-testid="thread-snapshot" :disabled="Boolean(busy) || !token" @click="takeSnapshot">
            {{ busy === 'snapshot' ? '抓取中…' : '抓取快照' }}
          </button>
        </div>
        <p v-if="snapshot" class="snapshot-result" data-testid="thread-snapshot-result">
          <span class="badge" :class="snapshot.captured ? 'ok' : 'down'">
            {{ snapshot.captured ? 'captured=true 已捕获' : 'captured=false 未捕获' }}
          </span>
          <span>trigger {{ snapshot.trigger }}</span>
          <span>{{ snapshot.durationMs }}ms</span>
          <span v-if="snapshot.files?.length">{{ snapshot.files.length }} 个文件</span>
          <code v-if="snapshot.directory" class="snapshot-dir">{{ snapshot.directory }}</code>
          <span v-if="snapshot.error" class="snapshot-error">{{ snapshot.error }}</span>
        </p>
        <p v-else class="hint">
          按需执行 jcmd Thread.print / GC.heap_info / class histogram，写入快照目录并打 Grafana annotation。
          freeze 也会自动抓取一次；抓取失败时端点仍返回 200，但 captured=false。
        </p>
      </section>
    </div>
  </section>
</template>
