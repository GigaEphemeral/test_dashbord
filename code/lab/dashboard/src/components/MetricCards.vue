<script setup>
import { computed } from 'vue'
import { formatBytes, formatCpu, formatMillis, formatPercent, formatRate } from '../labModel'

/**
 * Renders one card per MetricView of the B1-03 aggregation API.
 *
 * The quality badge is the contract: OK may show a number, while NO_DATA /
 * COLLECTING / DEGRADED must never render a synthetic 0. A missing series is
 * shown as 无数据 with the controller's reason, and the per-instance series are
 * only available behind an explicit expander.
 */
const props = defineProps({
  metrics: { type: Array, default: () => [] },
  overall: { type: String, default: '' },
  reason: { type: String, default: '' },
  generatedAt: { type: String, default: '' },
  window: { type: Object, default: null },
  title: { type: String, default: '核心指标' }
})

const QUALITY_TONES = {
  OK: 'q-ok',
  NO_DATA: 'q-no-data',
  COLLECTING: 'q-collecting',
  DEGRADED: 'q-degraded'
}

const cards = computed(() => props.metrics || [])

function tone(quality) {
  return QUALITY_TONES[quality] || 'q-no-data'
}

function qualityLabel(quality) {
  return quality || 'NO_DATA'
}

function updatedLabel() {
  if (!props.generatedAt) return '—'
  const date = new Date(props.generatedAt)
  return Number.isNaN(date.getTime())
    ? props.generatedAt
    : date.toLocaleTimeString('zh-CN', { hour12: false })
}

function windowLabel() {
  if (!props.window?.from || !props.window?.to) return ''
  const from = new Date(props.window.from)
  const to = new Date(props.window.to)
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) return ''
  const format = (date) => date.toLocaleTimeString('zh-CN', { hour12: false })
  return `${format(from)} → ${format(to)} · 步长 ${props.window.stepSeconds}s`
}

function formatValue(unit, value) {
  if (!Number.isFinite(value)) return '无数据'
  switch (unit) {
    case 'ratio':
      return formatPercent(value)
    case 'bytes':
      return formatBytes(value)
    case 's':
      return formatMillis(value)
    case 'req/s':
      return formatRate(value)
    case 'bool':
      return value >= 1 ? 'UP' : 'DOWN'
    case 'threads':
    case 'connections':
    case 'tasks':
    case 'entries':
    case 'keys':
    case 'orders':
    case 'events':
      return Math.round(value).toLocaleString('zh-CN')
    default:
      return Number.isInteger(value) ? value.toLocaleString('zh-CN') : value.toFixed(2)
  }
}

function primary(metric) {
  if (metric.quality !== 'OK') {
    return metric.quality === 'DEGRADED' ? '采集异常' : '无数据'
  }
  return formatValue(metric.unit, metric.series?.[0]?.stats?.last ?? null)
}

function unitLabel(unit) {
  switch (unit) {
    case 'ratio':
      return '比例'
    case 'bytes':
      return '字节'
    case 's':
      return '秒'
    case 'req/s':
      return '请求/秒'
    case 'bool':
      return '1=UP / 0=DOWN'
    default:
      return unit || ''
  }
}

function seriesLabel(series) {
  const labels = series?.labels || {}
  const instance = String(labels.instance || '').split(':')[0]
  const extra = ['name', 'pool', 'job'].map((key) => labels[key]).filter(Boolean)
  const parts = [instance, ...extra].filter(Boolean)
  return parts.length ? parts.join(' · ') : '合计'
}

function statsLine(metric, series) {
  const stats = series?.stats || {}
  return [
    `avg ${formatValue(metric.unit, stats.avg)}`,
    `min ${formatValue(metric.unit, stats.min)}`,
    `max ${formatValue(metric.unit, stats.max)}`,
    `n=${stats.samples ?? 0}`
  ].join(' · ')
}
</script>

<template>
  <section class="card metric-cards" data-testid="metric-cards">
    <div class="card-head">
      <h2>
        {{ title }}
        <span class="count">{{ cards.length }}</span>
      </h2>
      <div class="metric-head-meta">
        <span
          v-if="overall"
          class="badge"
          :class="tone(overall)"
          data-testid="metrics-overall-quality"
        >{{ qualityLabel(overall) }}</span>
        <span class="hint" data-testid="metrics-generated-at">更新 {{ updatedLabel() }}</span>
      </div>
    </div>
    <p v-if="windowLabel()" class="hint">窗口 {{ windowLabel() }}</p>
    <p v-if="reason" class="hint metric-overall-reason" data-testid="metrics-reason">{{ reason }}</p>

    <div v-if="!cards.length" class="empty" data-testid="metrics-empty">
      还没有指标结果：点击刷新或先选择一个 run。
    </div>

    <div v-else class="metric-grid">
      <article
        v-for="metric in cards"
        :key="metric.key"
        class="metric-card"
        :class="tone(metric.quality)"
        :data-testid="`metric-card-${metric.key}`"
      >
        <header class="metric-card-head">
          <span class="metric-card-label">{{ metric.label }}</span>
          <span
            class="badge"
            :class="tone(metric.quality)"
            :data-testid="`metric-quality-${metric.key}`"
          >{{ qualityLabel(metric.quality) }}</span>
        </header>
        <div class="metric-card-body">
          <strong
            class="metric-primary"
            :class="{ missing: metric.quality !== 'OK' }"
            :data-testid="`metric-value-${metric.key}`"
          >{{ primary(metric) }}</strong>
          <span class="metric-unit">{{ unitLabel(metric.unit) }}</span>
        </div>
        <span class="hint metric-updated" :data-testid="`metric-updated-${metric.key}`">
          更新 {{ updatedLabel() }}
        </span>
        <p v-if="metric.reason" class="metric-reason">{{ metric.reason }}</p>
        <p v-else-if="metric.quality === 'NO_DATA'" class="metric-reason">
          没有序列：未接线或窗口内无采集，按无数据展示而不是 0。
        </p>

        <details v-if="metric.series?.length" class="metric-series" :data-testid="`metric-series-${metric.key}`">
          <summary>按实例展开（{{ metric.series.length }} 条序列）</summary>
          <ul>
            <li v-for="(series, index) in metric.series" :key="index">
              <code>{{ seriesLabel(series) }}</code>
              <span class="metric-series-value">{{ formatValue(metric.unit, series.stats?.last ?? null) }}</span>
              <span class="metric-series-stats">{{ statsLine(metric, series) }}</span>
            </li>
          </ul>
        </details>
        <p v-else class="hint metric-series-empty">没有可展开的实例序列。</p>
      </article>
    </div>
  </section>
</template>
