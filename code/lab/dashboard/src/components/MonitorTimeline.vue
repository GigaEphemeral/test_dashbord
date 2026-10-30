<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import TimelineChart from './TimelineChart.vue'
import { describeError, queryPromRange } from '../api'
import {
  COLORS,
  compact,
  gcActionName,
  instanceColor,
  seriesPoints,
  splitSeriesPoints
} from '../timeline'

/**
 * Six hand-drawn SVG timelines over the last 15 minutes (B3-00d, B5-01c).
 *
 * JVM heap and GC are separate charts because they share no unit; GC lines are
 * split per instance AND per GC action (minor solid / major dashed), and the
 * pool charts are split per instance so one saturated node is visible.
 *
 * Every series is one PromQL range query; a failed query only removes its own
 * line and puts the error in the notice. An absent series is "无数据".
 */
const props = defineProps({
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 }
})

const WINDOW_SECONDS = Number(import.meta.env.VITE_LAB_MONITOR_WINDOW_SECONDS || 900)
const STEP_SECONDS = Number(import.meta.env.VITE_LAB_MONITOR_STEP_SECONDS || 15)
const REFRESH_SECONDS = Math.max(1, Math.round(
  Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000) / 1000
))
const APP_JOB = import.meta.env.VITE_LAB_PROM_JOB || 'seckill-app'

const JOB = `job="${APP_JOB}"`
const requestsTotal = `sum(rate(http_server_requests_seconds_count{${JOB}}[1m]))`

const CHARTS = [
  {
    id: 'requests',
    testid: 'chart-requests',
    title: '请求性能',
    series: [
      {
        key: 'rps',
        label: 'RPS',
        expr: requestsTotal,
        format: (value) => `${value.toFixed(2)} req/s`
      },
      {
        key: 'p95',
        label: 'P95',
        expr: `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{${JOB}}[1m])))`,
        factor: 1000,
        format: (value) => `${value.toFixed(1)} ms`
      },
      {
        key: 'error',
        label: '错误率',
        expr: `sum(rate(http_server_requests_seconds_count{${JOB},status=~"5.."}[1m])) / clamp_min(${requestsTotal}, 1e-9)`,
        factor: 100,
        format: (value) => `${value.toFixed(2)} %`
      }
    ]
  },
  {
    id: 'k6',
    testid: 'chart-k6',
    title: 'k6 负载',
    series: [
      {
        key: 'vus',
        label: 'VU',
        expr: 'sum(k6_vus{run_id!=""})',
        format: (value) => `${value.toFixed(0)} VU`
      },
      {
        key: 'iterations',
        label: 'iterations/s',
        expr: 'sum(rate(k6_iterations_total{run_id!=""}[1m]))',
        format: (value) => `${value.toFixed(2)} /s`
      }
    ]
  },
  {
    id: 'jvm',
    testid: 'chart-jvm',
    title: 'JVM 堆（按实例）',
    note: '每实例一条',
    series: [
      {
        key: 'heap',
        label: '堆',
        expr: `sum by (instance) (jvm_memory_used_bytes{${JOB},area="heap"})`,
        factor: 1 / (1024 * 1024),
        format: (value) => `${value.toFixed(1)} MB`,
        split: true
      }
    ]
  },
  {
    id: 'gc',
    testid: 'chart-gc',
    title: 'GC 次数（按实例 · minor/major）',
    note: '实线 minor · 虚线 major',
    series: [
      {
        key: 'count',
        label: 'GC',
        expr: `sum by (instance, action) (increase(jvm_gc_pause_seconds_count{${JOB}}[1m]))`,
        format: (value) => `${value.toFixed(0)} 次/分`,
        split: true,
        groupBy: ['instance', 'action'],
        nameFor: (labels, instance) => `${instance} ${gcActionName(labels.action)}`,
        dashFor: (labels) => (labels.action === 'end of major GC' ? '5 3' : '')
      }
    ]
  },
  {
    id: 'threads',
    testid: 'chart-threads',
    title: '业务线程池（按实例）',
    note: '实线 active · 虚线 queue',
    series: [
      {
        key: 'active',
        label: 'active',
        expr: `sum by (instance) (executor_active_threads{${JOB}})`,
        format: (value) => value.toFixed(0),
        split: true
      },
      {
        key: 'queued',
        label: 'queue',
        expr: `sum by (instance) (executor_queued_tasks{${JOB}})`,
        format: (value) => value.toFixed(0),
        split: true,
        dash: '5 3'
      },
      {
        // Reference line: without it an idle pool (active=queue=0) looks blank.
        key: 'max',
        label: 'pool max（参考）',
        expr: `max(executor_pool_max_threads{${JOB}})`,
        format: (value) => value.toFixed(0)
      }
    ]
  },
  {
    id: 'pool',
    testid: 'chart-pool',
    title: '连接池（按实例）',
    note: '实线 active · 虚线 pending',
    series: [
      {
        key: 'active',
        label: 'Hikari active',
        expr: `sum by (instance) (hikaricp_connections_active{${JOB}})`,
        format: (value) => value.toFixed(0),
        split: true
      },
      {
        key: 'pending',
        label: 'Hikari pending',
        expr: `sum by (instance) (hikaricp_connections_pending{${JOB}})`,
        format: (value) => value.toFixed(0),
        split: true,
        dash: '5 3'
      }
    ]
  }
]

const results = reactive({})
const windowStart = ref(null)
const windowEnd = ref(null)
const lastSync = ref(null)
const loading = ref(false)

let inFlight = false
let disposed = false

const chartErrors = computed(() => {
  const seen = new Set()
  for (const chart of CHARTS) {
    for (const series of chart.series) {
      const failure = results[`${chart.id}:${series.key}`]?.error
      if (failure) seen.add(`${series.label}：${failure}`)
    }
  }
  return [...seen]
})

const dataTimeLabel = computed(() => (
  lastSync.value ? lastSync.value.toLocaleTimeString('zh-CN', { hour12: false }) : '—'
))

/** Turn one chart definition + query results into TimelineChart props. */
function buildChart(chart) {
  const series = []
  chart.series.forEach((definition, definitionIndex) => {
    const state = results[`${chart.id}:${definition.key}`] || {}
    if (definition.split) {
      const entries = state.series || []
      const instances = [...new Set(entries.map((entry) => entry.instance))].sort()
      for (const entry of entries) {
        series.push({
          key: `${definition.key}:${entry.key}`,
          label: entry.name,
          color: instanceColor(entry.instance, instances),
          dash: entry.dash || definition.dash || '',
          points: entry.points,
          format: definition.format
        })
      }
      return
    }
    series.push({
      key: definition.key,
      label: definition.label,
      color: COLORS[definitionIndex % COLORS.length],
      dash: '',
      points: state.points || [],
      format: definition.format
    })
  })
  return { ...chart, axis: compact, series }
}

const charts = computed(() => CHARTS.map(buildChart))

async function refresh() {
  if (!props.active || disposed) return
  if (inFlight) return
  inFlight = true
  loading.value = true
  const end = Math.floor(Date.now() / 1000)
  const start = end - WINDOW_SECONDS
  windowStart.value = start
  windowEnd.value = end
  const jobs = []
  for (const chart of CHARTS) {
    for (const series of chart.series) {
      const key = `${chart.id}:${series.key}`
      const factor = series.factor || 1
      jobs.push(
        queryPromRange(series.expr, start, end, `${STEP_SECONDS}s`)
          .then((result) => {
            results[key] = series.split
              ? {
                series: splitSeriesPoints(result, factor, {
                  groupBy: series.groupBy,
                  nameFor: series.nameFor,
                  dashFor: series.dashFor
                }),
                error: ''
              }
              : { points: seriesPoints(result, factor), error: '' }
          })
          .catch((err) => {
            results[key] = series.split
              ? { series: [], error: describeError(err) }
              : { points: [], error: describeError(err) }
          })
      )
    }
  }
  await Promise.all(jobs)
  if (!disposed) {
    lastSync.value = new Date()
    loading.value = false
  }
  inFlight = false
}

watch(
  () => [props.active, props.syncTick],
  ([active]) => {
    if (active) refresh()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  disposed = true
  inFlight = false
})
</script>

<template>
  <section class="card monitor-timeline" data-testid="monitor-timeline">
    <div class="card-head">
      <h2>监控时间轴</h2>
      <div class="bench-actions">
        <span class="hint" data-testid="timeline-data-time">数据时间 {{ dataTimeLabel }}</span>
        <span class="hint">窗口 15 分钟 · 步长 15s · 抓取 5s · 刷新 {{ REFRESH_SECONDS }}s</span>
        <span v-if="loading" class="hint">刷新中…</span>
      </div>
    </div>

    <p class="hint">
      最近 15 分钟：请求 RPS/P95/5xx、k6 VU 与迭代速率、JVM 堆（每实例一条）、
      GC 次数（每实例 · minor 实线 / major 虚线）、业务线程池与连接池（按实例，实线 active / 虚线排队）。
      单个查询失败只影响对应曲线；没有序列时显示无数据，不显示 0。
    </p>

    <div v-if="chartErrors.length" class="notice warn" role="alert" data-testid="monitor-timeline-error">
      部分查询失败：{{ chartErrors.join('；') }}
    </div>

    <div class="chart-grid">
      <TimelineChart
        v-for="chart in charts"
        :key="chart.id"
        :chart="chart"
        :window-start="windowStart"
        :window-end="windowEnd"
      />
    </div>
  </section>
</template>

<style scoped>
.chart-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 10px;
}
</style>
