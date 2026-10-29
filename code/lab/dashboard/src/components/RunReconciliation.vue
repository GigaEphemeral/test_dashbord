<script setup>
import { computed, ref, watch } from 'vue'
import { describeError, fetchRunReconciliation, reconcileRun } from '../api'

/**
 * B3-04 run-scoped business reconciliation. The controller owns the verdict:
 * PASS only when every item passes, DEGRADED when a probe is unwired/unreachable
 * (never a synthetic 0), FAIL when a business invariant breaks. Performance
 * thresholds are intentionally not part of this panel.
 *
 * The main session mounts this component; it never edits RunLedger.vue.
 */
const props = defineProps({
  token: { type: String, default: '' },
  runId: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 }
})

const result = ref(null)
const loading = ref(false)
const running = ref(false)
const noData = ref(false)
const error = ref('')
let loadSeq = 0

const busy = computed(() => loading.value || running.value)
const verdict = computed(() => result.value?.verdict || '')
const verdictTone = computed(() => ({
  PASS: 'ok',
  FAIL: 'down',
  DEGRADED: 'warn'
}[verdict.value] || 'muted'))
const checkedAtLabel = computed(() => {
  if (!result.value?.checkedAt) return '—'
  const date = new Date(result.value.checkedAt)
  return Number.isNaN(date.getTime()) ? result.value.checkedAt : date.toLocaleString()
})

async function load() {
  if (!props.runId) {
    result.value = null
    noData.value = true
    error.value = ''
    return
  }
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    const value = await fetchRunReconciliation(props.token, props.runId)
    if (seq !== loadSeq) return
    result.value = value
    noData.value = false
  } catch (err) {
    if (seq !== loadSeq) return
    if (err?.status === 404) {
      // 404/NO_DATA: the run exists but no manual reconciliation was persisted.
      result.value = null
      noData.value = true
      error.value = ''
    } else {
      error.value = describeError(err)
    }
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function runReconcile() {
  if (!props.runId || busy.value) return
  running.value = true
  error.value = ''
  try {
    result.value = await reconcileRun(props.token, props.runId)
    noData.value = false
  } catch (err) {
    error.value = describeError(err)
  } finally {
    running.value = false
  }
}

watch(() => props.runId, () => load(), { immediate: true })
watch(() => props.syncTick, () => {
  if (props.active) load()
})
</script>

<template>
  <section class="card" data-testid="run-reconciliation">
    <div class="card-head">
      <h2>业务对账 <span v-if="verdict" class="badge" :class="verdictTone" data-testid="reconcile-verdict">{{ verdict }}</span></h2>
      <div class="bench-actions">
        <button
          type="button"
          class="btn ghost"
          data-testid="reconcile-run"
          :disabled="!runId || busy"
          @click="runReconcile"
        >
          {{ running ? '对账中…' : '重新对账' }}
        </button>
      </div>
    </div>
    <p class="hint">
      只读对比 run 启动基线（订单数/数量、库存、MQ 深度）与当前业务库；超卖计数来自 Prometheus，
      队列排空来自 RabbitMQ 管理 API。性能阈值不参与本判定。
      <span v-if="result">最近一次：{{ checkedAtLabel }}</span>
    </p>

    <p v-if="loading" class="hint" data-testid="reconcile-loading">读取最近一次对账结果…</p>
    <p v-else-if="error" class="notice" data-testid="reconcile-error">{{ error }}</p>
    <p v-else-if="noData" class="hint" data-testid="reconcile-no-data">
      该 run 尚未对账（NO_DATA）。点击“重新对账”生成基线对比。
    </p>

    <p v-if="result?.verdict === 'DEGRADED'" class="notice" data-testid="reconcile-degraded-note">
      DEGRADED：存在未接线或不可达的检查项，不能当作 PASS，也不会用 0 代替缺失数据；
      请查看下方 evidence 后重试或补接线。
    </p>

    <div v-if="result?.items?.length" class="reconcile-table" data-testid="reconcile-items">
      <div class="reconcile-row reconcile-head">
        <span>检查项</span>
        <span>expected</span>
        <span>actual</span>
        <span>verdict</span>
        <span>evidence</span>
        <span>sample</span>
      </div>
      <div
        v-for="item in result.items"
        :key="item.key"
        class="reconcile-row"
        :data-testid="`reconcile-item-${item.key}`"
      >
        <code>{{ item.key }}</code>
        <span>{{ item.expected || '—' }}</span>
        <span>{{ item.actual || '—' }}</span>
        <span class="badge" :class="{ PASS: 'ok', FAIL: 'down', DEGRADED: 'warn' }[item.verdict] || 'muted'">{{ item.verdict }}</span>
        <span class="reconcile-evidence">{{ item.evidence || '—' }}</span>
        <span>{{ item.sample?.length ? item.sample.join(' · ') : '—' }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.reconcile-table {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 10px;
  overflow-x: auto;
}

.reconcile-row {
  display: grid;
  grid-template-columns: 1.1fr 1.2fr 1.4fr 0.7fr 2fr 1.2fr;
  gap: 8px;
  align-items: start;
  min-width: 900px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--panel-border);
  font-size: 0.75rem;
}

.reconcile-head {
  color: var(--text-muted);
  font-weight: 600;
}

.reconcile-evidence {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
