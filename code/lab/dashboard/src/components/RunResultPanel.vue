<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { cloneRun, describeError, downloadRunReport, fetchRunReport } from '../api'

/**
 * B5-03 "本次结果": one run's comparison-relevant facts in one place, plus the
 * JSON export and the "clone as new draft" shortcut for manual repetitions.
 * Nothing is inferred: missing sections are listed under quality.missing.
 */
const props = defineProps({
  token: { type: String, default: '' },
  runId: { type: String, default: '' },
  active: { type: Boolean, default: false }
})

const emit = defineEmits(['cloned'])

const report = ref(null)
const error = ref('')
const loading = ref(false)
const exportError = ref('')
const exportNote = ref('')
const cloneError = ref('')
const cloneNote = ref('')
const cloning = ref(false)

let inFlight = false

const k6 = computed(() => report.value?.k6 || null)
const environment = computed(() => report.value?.environment || [])
const missing = computed(() => report.value?.quality?.missing || [])
const thresholds = computed(() => k6.value?.thresholds || [])

function count(value) {
  return Number.isFinite(value) ? Math.round(value).toLocaleString('zh-CN') : '无数据'
}

function ms(value) {
  if (!Number.isFinite(value)) return '无数据'
  return value >= 1000 ? `${(value / 1000).toFixed(2)} s` : `${value.toFixed(1)} ms`
}

function ratio(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '无数据'
}

function badgeTone(quality) {
  if (quality === 'OK') return 'q-ok'
  if (quality === 'DEGRADED') return 'q-degraded'
  return 'q-no-data'
}

async function load() {
  if (!props.active || !props.runId || !props.token) return
  if (inFlight) return
  inFlight = true
  loading.value = true
  error.value = ''
  try {
    report.value = await fetchRunReport(props.token, props.runId)
  } catch (err) {
    report.value = null
    error.value = `本次结果读取失败（/lab/runs/{runId}/report）：${describeError(err)}`
  } finally {
    inFlight = false
    loading.value = false
  }
}

async function exportJson() {
  exportError.value = ''
  exportNote.value = ''
  try {
    const { blob, filename, bytes } = await downloadRunReport(props.token, props.runId)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
    exportNote.value = `已导出 ${filename}（${Math.round(bytes / 1024)} KB）`
  } catch (err) {
    exportError.value = `导出失败：${describeError(err)}`
  }
}

async function clone() {
  cloneError.value = ''
  cloneNote.value = ''
  cloning.value = true
  try {
    const created = await cloneRun(props.token, props.runId)
    cloneNote.value = `已创建 ${created.runId}（DRAFT）`
    emit('cloned', created.runId)
  } catch (err) {
    cloneError.value = `复制失败：${describeError(err)}`
  } finally {
    cloning.value = false
  }
}

watch(
  () => [props.active, props.runId, props.token],
  ([active]) => {
    if (!active) return
    report.value = null
    load()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  inFlight = false
})
</script>

<template>
  <section class="card run-result" data-testid="run-result">
    <div class="card-head">
      <h2>
        本次结果
        <span v-if="report" class="badge" :class="badgeTone(report.quality?.k6Report)" data-testid="run-result-k6-quality">{{ report.quality?.k6Report }}</span>
        <span v-if="report" class="badge" :class="badgeTone(report.quality?.metrics)" data-testid="run-result-metrics-quality">{{ report.quality?.metrics }}</span>
      </h2>
      <div class="bench-actions">
        <span v-if="loading" class="hint">读取中…</span>
        <button type="button" class="btn" data-testid="run-export-json" :disabled="!report || loading" @click="exportJson">导出 JSON</button>
        <button type="button" class="btn ghost" data-testid="run-clone" :disabled="cloning || !runId" @click="clone">
          {{ cloning ? '复制中…' : '复制为新草稿' }}
        </button>
      </div>
    </div>

    <p v-if="report?.repeatVerified === false" class="notice info" data-testid="run-result-single-note">
      {{ report.note }}
    </p>
    <p v-if="error" class="error-banner" role="alert" data-testid="run-result-error">{{ error }}</p>
    <p v-if="exportError" class="error-banner" role="alert" data-testid="run-export-error">{{ exportError }}</p>
    <p v-if="exportNote" class="hint" data-testid="run-export-note">{{ exportNote }}</p>
    <p v-if="cloneError" class="error-banner" role="alert" data-testid="run-clone-error">{{ cloneError }}</p>
    <p v-if="cloneNote" class="hint" data-testid="run-clone-note">{{ cloneNote }}</p>

    <template v-if="report">
      <div class="result-grid">
        <div class="result-stat"><span>请求数</span><strong data-testid="run-result-requests">{{ count(k6?.requests) }}</strong></div>
        <div class="result-stat"><span>迭代数</span><strong>{{ count(k6?.iterations) }}</strong></div>
        <div class="result-stat"><span>VU 峰值</span><strong>{{ count(k6?.vusMax) }}</strong></div>
        <div class="result-stat"><span>RPS</span><strong>{{ k6?.rps == null ? '无数据' : k6.rps.toFixed(2) }}</strong></div>
        <div class="result-stat"><span>错误率</span><strong>{{ ratio(k6?.failedRate) }}</strong></div>
        <div class="result-stat"><span>平均</span><strong>{{ ms(k6?.duration?.avg) }}</strong></div>
        <div class="result-stat"><span>中位</span><strong>{{ ms(k6?.duration?.med) }}</strong></div>
        <div class="result-stat"><span>P95</span><strong>{{ ms(k6?.duration?.p95) }}</strong></div>
        <div class="result-stat"><span>P99</span><strong>{{ ms(k6?.duration?.p99) }}</strong></div>
        <div class="result-stat"><span>时长</span><strong>{{ report.run?.durationSeconds == null ? '无数据' : `${report.run.durationSeconds} s` }}</strong></div>
        <div class="result-stat"><span>流量模式</span><strong>{{ report.run?.trafficMode || '—' }}</strong></div>
        <div class="result-stat"><span>目标实例</span><strong>{{ report.run?.targetInstance || '—' }}</strong></div>
      </div>

      <h3>k6 阈值</h3>
      <p v-if="!k6?.available" class="hint" data-testid="run-result-k6-missing">k6 summary 未生成或不可读，阈值与分位数显示无数据。</p>
      <p v-else-if="!thresholds.length" class="hint" data-testid="run-result-no-thresholds">该场景未配置阈值（NO_THRESHOLDS），不判定 PASS。</p>
      <ul v-else class="threshold-list" data-testid="run-result-thresholds">
        <li v-for="(item, index) in thresholds" :key="index">
          <span class="badge" :class="item.passed ? 'q-ok' : 'q-degraded'">{{ item.passed ? 'PASS' : 'FAIL' }}</span>
          <code>{{ item.metric }}</code> {{ item.condition }}
        </li>
      </ul>

      <h3>环境身份</h3>
      <p v-if="!environment.length" class="hint" data-testid="run-result-env-empty">该 run 没有环境快照（未启动或未采集）。</p>
      <table v-else class="data-table" data-testid="run-result-environment">
        <thead>
          <tr><th>实例</th><th>镜像 tag</th><th>镜像 ID</th><th>Java</th><th>配置 revision</th><th>状态</th><th>采集时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in environment" :key="row.instance">
            <td><code>{{ row.instance }}</code></td>
            <td>{{ row.imageTag || '—' }}</td>
            <td class="hint">{{ row.imageId ? row.imageId.slice(0, 19) : '—' }}</td>
            <td>{{ row.javaVersion || '—' }}</td>
            <td>{{ row.configRevision ?? '—' }}</td>
            <td>{{ row.configStatus || '—' }}</td>
            <td class="hint">{{ row.capturedAt || '—' }}</td>
          </tr>
        </tbody>
      </table>

      <h3>缺失项</h3>
      <p v-if="!missing.length" class="hint" data-testid="run-result-missing-empty">无缺失：k6 summary、环境与指标窗口均已采到。</p>
      <ul v-else class="notice warn" data-testid="run-result-missing">
        <li v-for="(item, index) in missing" :key="index">{{ item }}</li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 8px;
}

.result-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  background: rgba(15, 23, 32, 0.55);
}

.result-stat span {
  color: var(--text-muted);
  font-size: 0.72rem;
}

.result-stat strong {
  font-family: var(--mono);
  font-size: 0.92rem;
}

.threshold-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
