<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { describeError, fetchRunInstanceStats } from '../api'

/**
 * B5-02 负载均衡分流 (design §9.6).
 *
 * All numbers come from GET /lab/runs/{runId}/instance-stats; a null stays
 * "无数据" and is never rendered as 0. The counting rules live in the
 * controller note and are repeated here so the table can be read on its own.
 */
const props = defineProps({
  token: { type: String, default: '' },
  runId: { type: String, default: '' },
  active: { type: Boolean, default: false }
})

const stats = ref(null)
const error = ref('')
const loading = ref(false)

let inFlight = false

const rows = computed(() => stats.value?.instances || [])
const warnings = computed(() => stats.value?.warnings || [])
const quality = computed(() => stats.value?.quality || '')

function fmt(value, digits = 1) {
  return Number.isFinite(value) ? value.toFixed(digits) : '无数据'
}

function pct(value) {
  return Number.isFinite(value) ? `${value.toFixed(1)}%` : '无数据'
}

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

function qualityTone(value) {
  if (value === 'OK') return 'q-ok'
  if (value === 'DEGRADED') return 'q-degraded'
  return 'q-no-data'
}

function imbalance(row) {
  return Number.isFinite(row.sharePercent) && Number.isFinite(row.expectedSharePercent)
    ? Math.abs(row.sharePercent - row.expectedSharePercent)
    : null
}

async function load() {
  if (!props.active || !props.token || !props.runId) return
  if (inFlight) return
  inFlight = true
  loading.value = true
  error.value = ''
  try {
    stats.value = await fetchRunInstanceStats(props.token, props.runId)
  } catch (err) {
    error.value = `实例分流查询失败（/lab/runs/{runId}/instance-stats）：${describeError(err)}`
  } finally {
    inFlight = false
    loading.value = false
  }
}

watch(
  () => [props.active, props.token, props.runId],
  ([active]) => {
    if (!active) return
    stats.value = null
    load()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  inFlight = false
})
</script>

<template>
  <section class="card traffic-split" data-testid="traffic-split">
    <div class="card-head">
      <h2>
        负载均衡分流
        <span v-if="quality" class="badge" :class="qualityTone(quality)" data-testid="traffic-split-quality">{{ quality }}</span>
      </h2>
      <div class="bench-actions">
        <span v-if="stats?.from" class="hint" data-testid="traffic-split-window">{{ stats.from }} → {{ stats.to }}</span>
        <button type="button" class="btn ghost" data-testid="traffic-split-refresh" :disabled="loading || !runId" @click="load">
          {{ loading ? '查询中…' : '刷新分流' }}
        </button>
      </div>
    </div>

    <p v-if="error" class="error-banner" role="alert" data-testid="traffic-split-error">{{ error }}</p>
    <p v-if="stats?.note" class="hint" data-testid="traffic-split-note">{{ stats.note }}</p>

    <ul v-if="warnings.length" class="notice warn" data-testid="traffic-split-warnings">
      <li v-for="(item, index) in warnings" :key="index">{{ item }}</li>
    </ul>

    <div v-if="!rows.length && !loading" class="empty" data-testid="traffic-split-empty">
      该 run 窗口内没有实例级序列：确认 run 已启动、Prometheus 在抓取，且窗口内确有业务请求。
    </div>

    <table v-else class="data-table" data-testid="traffic-split-table">
      <thead>
        <tr>
          <th>实例</th>
          <th>状态</th>
          <th>收到请求</th>
          <th>占比</th>
          <th>成功 / 失败</th>
          <th>平均响应</th>
          <th>P95</th>
          <th>P99</th>
          <th>实例 RPS</th>
          <th>错误率</th>
          <th>线程池拒绝</th>
          <th>权重</th>
          <th>期望占比</th>
          <th>配置组</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in rows"
          :key="row.instance"
          :class="{ 'row-disabled': !row.enabled }"
          :data-testid="`traffic-split-row-${row.instance}`"
        >
          <td><code>{{ row.instance }}</code></td>
          <td>
            <span v-if="row.enabled" class="badge q-ok">启用</span>
            <span v-else class="badge q-no-data">未启用</span>
          </td>
          <td>{{ count(row.hits) }}</td>
          <td>{{ pct(row.sharePercent) }}</td>
          <td>{{ count(row.success) }} / {{ count(row.failed) }}</td>
          <td>{{ ms(row.avgMs) }}</td>
          <td>{{ ms(row.p95Ms) }}</td>
          <td>{{ ms(row.p99Ms) }}</td>
          <td>{{ fmt(row.rps, 2) }}</td>
          <td>{{ ratio(row.errorRate) }}</td>
          <td>{{ count(row.rejected) }}</td>
          <td>{{ row.weight ?? '无数据' }}</td>
          <td>
            {{ pct(row.expectedSharePercent) }}
            <span
              v-if="imbalance(row) !== null && imbalance(row) > 5"
              class="warn-inline"
              title="实测占比与权重期望相差超过阈值，先排查实例健康再比较 JVM 性能"
            >偏差</span>
          </td>
          <td class="hint">{{ row.configLabel || '未配置' }}</td>
        </tr>
      </tbody>
    </table>

    <p class="hint">
      权重与期望占比来自当前 nginx upstream 配置（<code>{{ stats?.upstream?.source || '—' }}</code>）；
      权重不可用时显示原因而不是默认等权。窗口内没有 5xx 序列时“失败数/错误率”显示无数据，
      不推断为 0；4xx（秒杀 409/429 等业务拒绝）不计入失败。
    </p>
  </section>
</template>

<style scoped>
.traffic-split .data-table {
  font-size: 0.82rem;
}

.traffic-split .row-disabled {
  opacity: 0.55;
}

.warn-inline {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(230, 180, 90, 0.18);
  color: #f3d59a;
  font-size: 0.7rem;
}
</style>
