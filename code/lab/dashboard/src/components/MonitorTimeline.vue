<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import TimelineChart from './TimelineChart.vue'
import { describeError, fetchInstanceConfig, queryPromRange } from '../api'
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
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 }
})

const WINDOW_SECONDS = Number(import.meta.env.VITE_LAB_MONITOR_WINDOW_SECONDS || 900)
const STEP_SECONDS = Number(import.meta.env.VITE_LAB_MONITOR_STEP_SECONDS || 15)
const REFRESH_SECONDS = Math.max(1, Math.round(
  Number(import.meta.env.VITE_LAB_SYNC_INTERVAL_MS || 5000) / 1000
))
const APP_JOB = import.meta.env.VITE_LAB_PROM_JOB || 'seckill-app'
const APP_INSTANCES = (import.meta.env.VITE_LAB_APP_INSTANCES || 'app-1,app-2,app-3')
  .split(',')
  .map((name) => name.trim())
  .filter(Boolean)
const HINT_REFRESH_MS = Number(import.meta.env.VITE_LAB_JVM_HINT_REFRESH_MS || 15000)

const JOB = `job="${APP_JOB}"`
const requestsTotal = `sum(rate(http_server_requests_seconds_count{${JOB}}[1m]))`

/** Redis HA nodes expose `node`; the standalone instance has no node label. */
function redisName(labels) {
  return labels.node || (labels.job === 'redis-exporter' ? 'standalone' : labels.job || 'redis')
}

/** MySQL HA members expose `member`; the standalone instance has no member label. */
function mysqlName(labels) {
  return labels.member || (labels.job === 'mysqld-exporter' ? 'standalone' : labels.job || 'db')
}

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
  },
  {
    id: 'redis',
    testid: 'chart-redis',
    title: 'Redis 命令速率（按节点）',
    note: 'standalone / HA 分线',
    series: [
      {
        key: 'commands',
        label: 'commands/s',
        expr: 'sum by (job, node) (rate(redis_commands_processed_total{job=~"redis-exporter.*"}[1m]))',
        format: (value) => `${value.toFixed(1)} /s`,
        split: true,
        groupBy: ['job', 'node'],
        nameFor: redisName
      }
    ]
  },
  {
    id: 'mysql',
    testid: 'chart-mysql',
    title: 'MySQL QPS（按节点）',
    note: 'standalone / HA 分线',
    series: [
      {
        key: 'queries',
        label: 'questions/s',
        expr: 'sum by (job, member) (rate(mysql_global_status_queries{job=~"mysqld-exporter.*"}[1m]))',
        format: (value) => `${value.toFixed(1)} /s`,
        split: true,
        groupBy: ['job', 'member'],
        nameFor: mysqlName
      }
    ]
  }
]

const results = reactive({})
const windowStart = ref(null)
const windowEnd = ref(null)
const lastSync = ref(null)
const loading = ref(false)
const jvmHints = ref({})

let inFlight = false
let hintsInFlight = false
let hintsLoadedAt = 0
let disposed = false

/** Compact config hint for legends: "256/512MB · G1 · 200ms". */
function hintFor(instance) {
  return jvmHints.value[instance] || ''
}

function summarizeConfig(view) {
  const config = view?.config
  if (!config) return ''
  const heap = config.xmsMb || config.xmxMb
    ? `${config.xmsMb ?? '默认'}/${config.xmxMb ?? '默认'}MB`
    : ''
  const gc = config.gcCollector || ''
  const pause = config.maxGcPauseMillis ? `${config.maxGcPauseMillis}ms` : ''
  const summary = [heap, gc, pause].filter(Boolean).join(' · ')
  if (summary && view?.status && view.status !== 'APPLIED') {
    return `${summary} · ${view.status === 'DRAFT' ? '未生效' : view.status}`
  }
  return summary
}

async function loadJvmHints(force = false) {
  if (!props.active || !props.token || hintsInFlight || disposed) return
  if (!force && Date.now() - hintsLoadedAt < HINT_REFRESH_MS) return
  hintsInFlight = true
  try {
    const entries = await Promise.all(APP_INSTANCES.map(async (node) => {
      try {
        return [node, summarizeConfig(await fetchInstanceConfig(props.token, node))]
      } catch {
        return [node, '']
      }
    }))
    jvmHints.value = Object.fromEntries(entries)
    hintsLoadedAt = Date.now()
  } finally {
    hintsInFlight = false
  }
}

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
        const hint = hintFor(entry.instance)
        series.push({
          key: `${definition.key}:${entry.key}`,
          label: hint ? `${entry.name} · ${hint}` : entry.name,
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
// Two charts per row: 请求/k6, 堆/GC, 线程池/连接池.
const chartRows = computed(() => {
  const list = charts.value
  const rows = []
  for (let index = 0; index < list.length; index += 2) {
    rows.push(list.slice(index, index + 2))
  }
  return rows
})

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
  () => [props.active, props.syncTick, props.token],
  ([active]) => {
    if (active) {
      refresh()
      loadJvmHints()
    }
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
      最近 15 分钟，前三排是应用层：请求性能 / k6 负载、JVM 堆 / GC、业务线程池 / 连接池；
      第四排是数据层：Redis 命令速率与 MySQL QPS（standalone 与 HA 节点分别成线）。
      图例里的 JVM 参数来自实例配置台账（Xms/Xmx · GC · 目标停顿），便于直接比对。
      单个查询失败只影响对应曲线；没有序列时显示无数据，不显示 0。
    </p>
    <p class="hint">
      <strong>standalone 与 HA 的流量取决于应用配置</strong>：当前环境若 <code>MYSQL_MODE=standalone</code> 且
      <code>REDIS_HOST=redis</code>，应用只会打单机库，HA 节点保持基线属于正常，不是采集故障。
    </p>

    <div v-if="chartErrors.length" class="notice warn" role="alert" data-testid="monitor-timeline-error">
      部分查询失败：{{ chartErrors.join('；') }}
    </div>

    <div class="chart-rows">
      <div
        v-for="(row, index) in chartRows"
        :key="`row-${index}`"
        class="chart-row"
        :data-testid="`chart-row-${index}`"
      >
        <TimelineChart
          v-for="chart in row"
          :key="chart.id"
          :chart="chart"
          :window-start="windowStart"
          :window-end="windowEnd"
          :height="220"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.chart-rows {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chart-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 1100px) {
  .chart-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
