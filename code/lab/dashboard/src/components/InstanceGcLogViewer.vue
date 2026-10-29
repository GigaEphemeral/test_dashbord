<script setup>
import { computed, ref } from 'vue'
import { describeError, fetchInstanceGcLog } from '../api'

/**
 * B4-03 GC-log readback. The controller owns the file access; this component
 * only renders the documented states and never invents lines. A missing or
 * unreadable file is NO_DATA, GC logging off is DISABLED and an instance with
 * no stored config is NO_CONFIG - all three render as 无数据/未启用, never as OK.
 */
const props = defineProps({
  token: { type: String, default: '' },
  instance: { type: String, required: true },
  /** Greyed jdk8 row or a container that is not running: disable the button. */
  disabled: { type: Boolean, default: false }
})

const lines = ref(200)
const state = ref(null)
const error = ref('')
const loading = ref(false)
const expanded = ref(false)

const statusText = computed(() => {
  const status = state.value?.status
  if (!status) return ''
  if (status === 'OK') return 'OK 有数据'
  if (status === 'DISABLED') return 'DISABLED 未启用'
  if (status === 'NO_CONFIG') return 'NO_CONFIG 未配置'
  if (status === 'NO_DATA') return 'NO_DATA 无数据'
  if (status === 'ERROR') return 'ERROR 读取失败'
  return status
})

const hasContent = computed(() => state.value?.status === 'OK' && Boolean(state.value?.content))

async function readLog() {
  const value = Number(lines.value)
  if (!Number.isInteger(value) || value < 1 || value > 2000) {
    error.value = '行数必须是 1–2000 之间的整数。'
    return
  }
  error.value = ''
  loading.value = true
  expanded.value = true
  try {
    state.value = await fetchInstanceGcLog(props.token, props.instance, value)
  } catch (err) {
    state.value = null
    error.value = describeError(err)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="gc-log" :data-testid="`instance-gc-log-${instance}`">
    <div class="gc-log-head">
      <button
        type="button"
        class="btn ghost"
        :data-testid="`instance-gc-log-toggle-${instance}`"
        :disabled="disabled || loading"
        :title="disabled ? '容器未运行或为受限外壳实例' : '读取容器内的 GC 日志尾部'"
        @click="readLog"
      >{{ loading ? '读取中…' : '读取 GC 日志' }}</button>
      <label class="gc-log-lines" data-tip="由控制器在容器内执行有界 tail；默认 200 行，上限 2000。">
        行数
        <input
          v-model="lines"
          class="input gc-log-lines-input"
          type="number"
          min="1"
          max="2000"
          :data-testid="`instance-gc-log-lines-${instance}`"
          :disabled="disabled || loading"
        />
      </label>
      <span
        v-if="state || error"
        class="badge"
        :class="state?.status === 'OK' ? 'ok' : 'warn'"
        :data-testid="`instance-gc-log-state-${instance}`"
      >{{ error ? 'ERROR 读取失败' : statusText }}</span>
    </div>

    <div v-if="error" class="notice danger" :data-testid="`instance-gc-log-error-${instance}`">{{ error }}</div>

    <template v-if="state && !error">
      <p class="hint gc-log-meta">
        路径 <code>{{ state.path || '—' }}</code>
        <span v-if="state.requestedLines"> · 请求 {{ state.requestedLines }} 行</span>
        <span v-if="state.truncated"> · 已截断（仅保留尾部）</span>
      </p>
      <p v-if="state.note" class="hint" :data-testid="`instance-gc-log-note-${instance}`">{{ state.note }}</p>
      <pre
        v-if="hasContent"
        class="gc-log-content"
        :data-testid="`instance-gc-log-content-${instance}`"
      >{{ state.content }}</pre>
      <p v-else-if="state.status !== 'OK'" class="hint">
        {{ state.status === 'DISABLED' ? '未启用' : '无数据' }}：没有可展示的日志内容（不补造行）。
      </p>
      <p v-else class="hint">无数据：日志文件为空或尚未产生 GC 事件。</p>
    </template>
    <p v-else-if="!expanded" class="hint">
      只读回读：<code>DISABLED / NO_DATA / NO_CONFIG</code> 一律显示未启用或无数据。
    </p>
  </section>
</template>

<style scoped>
.gc-log {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-top: 1px solid var(--panel-border);
  padding-top: 10px;
}
.gc-log-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.gc-log-lines {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 0.78rem;
}
.gc-log-lines-input {
  width: 88px;
  padding: 5px 8px;
}
.gc-log-meta {
  margin: 0;
  overflow-wrap: anywhere;
}
.gc-log-content {
  margin: 0;
  max-height: 300px;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--panel-border);
  border-radius: 10px;
  background: #070c12;
  color: #cfe3d9;
  font-family: var(--mono);
  font-size: 0.74rem;
  line-height: 1.45;
  white-space: pre;
}
</style>
