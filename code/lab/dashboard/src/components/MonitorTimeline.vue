<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { describeError, queryPromRange } from '../api'

/**
 * Four hand-drawn SVG timelines over the last 15 minutes (B3-00d).
 *
 * Every series is one PromQL range query; a failed query only removes its own
 * line and puts the error in the notice, so one broken metric never blanks the
 * page. An absent series is "无数据", never a synthetic 0.
 */
const props = defineProps({
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 }
})

const WINDOW_SECONDS = 900
const STEP_SECONDS = 15
const SCRAPE_SECONDS = 5
const REFRESH_SECONDS = Math.max(1, Math.round(
  Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000) / 1000
))
const APP_JOB = import.meta.env.VITE_LAB_PROM_JOB || 'seckill-app'

const COLORS = ['#38d39f', '#5aa9e6', '#e6b45a', '#e06b6b']
// One colour per JVM instance, so 堆 and GC keep the same hue per instance and
// only the stroke style (solid/dashed) tells the two metrics apart.
const INSTANCE_COLORS = ['#38d39f', '#5aa9e6', '#e6b45a', '#e06b6b', '#a78bfa', '#5eead4']
const SVG_W = 600
const SVG_H = 140
const PAD_L = 54
const PAD_R = 10
const PAD_T = 12
const PAD_B = 22

const JOB = `job="${APP_JOB}"`
const requestsTotal = `sum(rate(http_server_requests_seconds_count{${JOB}}[1m]))`

const CHARTS = [
  {
    id: 'requests',
    testid: 'chart-requests',
    title: '请求性能',
    axis: compact,
    series: [
      {
        key: 'rps',
        label: 'RPS',
        expr: requestsTotal,
        factor: 1,
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
    axis: compact,
    series: [
      {
        key: 'vus',
        label: 'VU',
        expr: 'sum(k6_vus{run_id!=""})',
        factor: 1,
        format: (value) => `${value.toFixed(0)} VU`
      },
      {
        key: 'iterations',
        label: 'iterations/s',
        expr: 'sum(rate(k6_iterations_total{run_id!=""}[1m]))',
        factor: 1,
        format: (value) => `${value.toFixed(2)} /s`
      }
    ]
  },
  {
    id: 'jvm',
    testid: 'chart-jvm',
    title: 'JVM 堆 / GC（按实例）',
    axis: compact,
    series: [
      {
        key: 'heap',
        label: '堆',
        // jvm_memory_used_bytes is bytes; the chart plots MB. One line per JVM
        // (app-1/2/3) instead of a summed total, so a single instance's heap
        // growth stays visible next to its peers.
        expr: `sum by (instance) (jvm_memory_used_bytes{${JOB},area="heap"})`,
        factor: 1 / (1024 * 1024),
        format: (value) => `${value.toFixed(1)} MB`,
        split: true,
        dash: ''
      },
      {
        key: 'gc',
        label: 'GC 暂停/s',
        // jvm_gc_pause_seconds_sum is a counter of seconds spent paused.
        expr: `sum by (instance) (rate(jvm_gc_pause_seconds_sum{${JOB}}[1m]))`,
        factor: 1000,
        format: (value) => `${value.toFixed(2)} ms/s`,
        split: true,
        dash: '5 3'
      }
    ]
  },
  {
    id: 'pool',
    testid: 'chart-pool',
    title: '线程池 / 连接池',
    axis: compact,
    series: [
      {
        key: 'active',
        label: '线程池活跃',
        expr: `sum(executor_active_threads{${JOB}})`,
        factor: 1,
        format: (value) => value.toFixed(0)
      },
      {
        key: 'poolSize',
        label: '线程池大小',
        // Spring's name is executor_pool_size_threads (not executor_pool_size).
        expr: `sum(executor_pool_size_threads{${JOB}})`,
        factor: 1,
        format: (value) => value.toFixed(0)
      },
      {
        key: 'queued',
        label: '线程池队列',
        expr: `sum(executor_queued_tasks{${JOB}})`,
        factor: 1,
        format: (value) => value.toFixed(0)
      },
      {
        key: 'pending',
        label: '连接池 pending',
        expr: `sum(hikaricp_connections_pending{${JOB}})`,
        factor: 1,
        format: (value) => value.toFixed(0)
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
let queued = false
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

function compact(value) {
  if (!Number.isFinite(value)) return '—'
  if (Math.abs(value) >= 1000) return value.toFixed(0)
  if (Math.abs(value) >= 100) return value.toFixed(0)
  if (Math.abs(value) >= 10) return value.toFixed(1)
  return value.toFixed(2)
}

function niceCeil(value) {
  if (!Number.isFinite(value) || value <= 0) return 1
  const exponent = Math.floor(Math.log10(value))
  const base = 10 ** exponent
  const normalized = value / base
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
  return step * base
}

function clockLabel(stamp) {
  const date = new Date(stamp * 1000)
  return date.toLocaleTimeString('zh-CN', { hour12: false })
}

function clockWithSeconds(stamp) {
  return clockLabel(stamp)
}

/** Matrix responses are transformed to [{ t, v }] with the series factor applied. */
function seriesPoints(result, factor) {
  const byTime = new Map()
  for (const series of result || []) {
    for (const [rawStamp, rawValue] of series.values || []) {
      const stamp = Number(rawStamp)
      const value = Number(rawValue)
      if (!Number.isFinite(stamp) || !Number.isFinite(value)) continue
      const scaled = value * factor
      byTime.set(stamp, (byTime.get(stamp) || 0) + scaled)
    }
  }
  return [...byTime.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([t, v]) => ({ t, v }))
}

/** "instance" is host:port (app-1:8080); the chart only needs the host. */
function instanceName(labels) {
  const raw = String(labels?.instance || '').split(':')[0]
  return raw || 'unknown'
}

/** Same transform as seriesPoints, but one [{ name, points }] entry per instance. */
function splitSeriesPoints(result, factor) {
  return (result || [])
    .map((series) => {
      const points = (series.values || [])
        .map(([rawStamp, rawValue]) => ({ t: Number(rawStamp), v: Number(rawValue) * factor }))
        .filter((point) => Number.isFinite(point.t) && Number.isFinite(point.v))
        .sort((a, b) => a.t - b.t)
      return { name: instanceName(series.metric), points }
    })
    .filter((entry) => entry.points.length > 0)
    .sort((a, b) => a.name.localeCompare(b.name))
}

function instanceColor(name, index) {
  return INSTANCE_COLORS[index % INSTANCE_COLORS.length]
}

function buildChart(chart) {
  const series = []
  chart.series.forEach((definition, definitionIndex) => {
    const state = results[`${chart.id}:${definition.key}`] || {}
    if (definition.split) {
      const entries = state.series || []
      entries.forEach((entry, index) => {
        series.push({
          ...definition,
          key: `${definition.key}:${entry.name}`,
          label: `${entry.name} ${definition.label}`,
          color: instanceColor(entry.name, index),
          dash: definition.dash || '',
          points: entry.points,
          last: entry.points.length ? entry.points[entry.points.length - 1] : null
        })
      })
      return
    }
    const points = state.points || []
    series.push({
      ...definition,
      color: COLORS[definitionIndex % COLORS.length],
      dash: '',
      points,
      last: points.length ? points[points.length - 1] : null
    })
  })
  const all = series.flatMap((item) => item.points.map((point) => point.v))
  const hasData = all.length > 0
  const yMax = niceCeil(Math.max(...(all.length ? all : [0]), 0))
  const start = windowStart.value
  const end = windowEnd.value
  const span = start !== null && end !== null && end > start ? end - start : 1
  const innerW = SVG_W - PAD_L - PAD_R
  const innerH = SVG_H - PAD_T - PAD_B
  const x = (stamp) => PAD_L + ((stamp - start) / span) * innerW
  const y = (value) => PAD_T + innerH - (Math.max(0, value) / yMax) * innerH

  const rendered = series.map((item) => ({
    ...item,
    lastText: item.last ? item.format(item.last.v) : '无数据',
    path: item.points.map((point) => `${x(point.t).toFixed(1)},${y(point.v).toFixed(1)}`).join(' '),
    dots: item.points.map((point) => ({
      cx: x(point.t).toFixed(1),
      cy: y(point.v).toFixed(1),
      title: `${item.label} · ${clockWithSeconds(point.t)} · ${item.format(point.v)}`
    }))
  }))

  const yTicks = [0, yMax / 2, yMax].map((value) => ({
    value,
    label: chart.axis(value),
    y: y(value).toFixed(1)
  }))
  const xTicks = start === null || end === null
    ? []
    : [0, 1, 2, 3].map((index) => {
      const stamp = start + (span * index) / 3
      return {
        x: x(stamp).toFixed(1),
        label: clockLabel(stamp),
        anchor: index === 0 ? 'start' : index === 3 ? 'end' : 'middle'
      }
    })

  return {
    ...chart,
    series: rendered,
    hasData,
    yTicks,
    xTicks,
    yMax
  }
}

const charts = computed(() => CHARTS.map(buildChart))

async function refresh() {
  if (!props.active || disposed) return
  if (inFlight) {
    queued = true
    return
  }
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
      jobs.push(
        queryPromRange(series.expr, start, end, `${STEP_SECONDS}s`)
          .then((result) => {
            results[key] = series.split
              ? { series: splitSeriesPoints(result, series.factor), error: '' }
              : { points: seriesPoints(result, series.factor), error: '' }
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
  if (queued) {
    queued = false
    refresh()
  }
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
  queued = false
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
      最近 15 分钟的时间轴：请求 RPS / P95 / 5xx 错误率、k6 VU 与迭代速率、JVM 堆与 GC（每个实例一条线，
      同色=同一 JVM，实线=堆、虚线=GC）、线程池与连接池。
      单个查询失败只影响对应曲线；没有序列时显示无数据，不显示 0。
    </p>

    <div v-if="chartErrors.length" class="notice warn" role="alert" data-testid="monitor-timeline-error">
      部分查询失败：{{ chartErrors.join('；') }}
    </div>

    <div class="chart-grid">
      <div v-for="chart in charts" :key="chart.id" class="chart-box" :data-testid="chart.testid">
        <div class="chart-head">
          <strong>{{ chart.title }}</strong>
          <span class="hint">峰值 {{ chart.axis(chart.yMax) }}</span>
        </div>
        <svg v-if="chart.hasData" :viewBox="`0 0 ${SVG_W} ${SVG_H}`" role="img" :aria-label="chart.title">
          <line
            v-for="tick in chart.yTicks"
            :key="`y-${tick.value}`"
            :x1="PAD_L"
            :x2="SVG_W - PAD_R"
            :y1="tick.y"
            :y2="tick.y"
            class="chart-gridline"
          />
          <text v-for="tick in chart.yTicks" :key="`yl-${tick.value}`" :x="PAD_L - 6" :y="Number(tick.y) + 3" text-anchor="end" class="chart-axis">{{ tick.label }}</text>
          <text v-for="tick in chart.xTicks" :key="`xl-${tick.x}`" :x="tick.x" :y="SVG_H - 6" :text-anchor="tick.anchor" class="chart-axis">{{ tick.label }}</text>
          <g v-for="item in chart.series" :key="item.key">
            <polyline
              v-if="item.points.length > 1"
              :points="item.path"
              fill="none"
              :stroke="item.color"
              :stroke-dasharray="item.dash || null"
              stroke-width="1.5"
              stroke-linejoin="round"
              stroke-linecap="round"
            />
            <circle
              v-for="(dot, index) in item.dots"
              :key="`${item.key}-${index}`"
              :cx="dot.cx"
              :cy="dot.cy"
              r="2"
              :fill="item.color"
            >
              <title>{{ dot.title }}</title>
            </circle>
          </g>
        </svg>
        <p v-else class="empty chart-empty">无数据</p>
        <div class="legend-inline">
          <span v-for="item in chart.series" :key="`legend-${item.key}`">
            <i class="dot" :style="{ background: item.color }"></i>{{ item.label }}：{{ item.lastText }}
          </span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.chart-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 10px;
}

.chart-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 4px;
}

.chart-box svg {
  height: 140px;
}

.chart-gridline {
  stroke: rgba(148, 163, 184, 0.18);
  stroke-width: 1;
}

.chart-axis {
  fill: var(--text-muted);
  font-size: 9px;
}

.chart-empty {
  margin: 24px 0;
  text-align: center;
}
</style>
