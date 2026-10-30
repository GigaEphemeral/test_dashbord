<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import TimelineChart from './TimelineChart.vue'
import { describeError, queryPromRange } from '../api'
import {
  compact,
  gcActionName,
  instanceColor,
  seriesPoints,
  splitSeriesPoints
} from '../timeline'

/**
 * B5-03 run-scoped timelines: k6 pressure, JVM heap and GC counts inside the
 * selected run's own window. The window is the run's [start, end] (an open run
 * ends "now"), so the charts line up with the row in the ledger instead of the
 * page's rolling 15 minutes.
 */
const props = defineProps({
  runId: { type: String, default: '' },
  startedAt: { type: String, default: '' },
  endedAt: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 }
})

const APP_JOB = import.meta.env.VITE_LAB_PROM_JOB || 'seckill-app'

const results = ref({})
const loading = ref(false)
const error = ref('')
const windowStart = ref(null)
const windowEnd = ref(null)

let inFlight = false

const startSeconds = computed(() => {
  if (!props.startedAt) return null
  const value = new Date(props.startedAt).getTime()
  return Number.isNaN(value) ? null : Math.floor(value / 1000)
})
const endSeconds = computed(() => {
  if (!props.endedAt) return null
  const value = new Date(props.endedAt).getTime()
  return Number.isNaN(value) ? null : Math.floor(value / 1000)
})

function safeRunId() {
  return String(props.runId || '').replace(/["\\]/g, '')
}

/** Standalone has no member label; name it instead of printing an empty key. */
function dbName(labels) {
  return labels.member || (labels.job === 'mysqld-exporter' ? 'standalone' : 'db')
}

/** Redis exporters expose `node`; the standalone instance has no node label. */
function redisName(labels) {
  return labels.node || (labels.job === 'redis-exporter' ? 'standalone' : 'redis')
}

function chartSeries(entries, definition) {
  const instances = [...new Set(entries.map((entry) => entry.instance))].sort()
  return entries.map((entry) => ({
    key: entry.key,
    label: entry.name,
    color: instanceColor(entry.instance, instances),
    dash: entry.dash || '',
    points: entry.points,
    format: definition.format
  }))
}

const charts = computed(() => {
  const data = results.value
  const vus = data.vus?.points || []
  const iterations = data.iterations?.points || []
  const heap = chartSeries(data.heap?.series || [], {
    format: (value) => `${value.toFixed(1)} MB`
  })
  const gc = chartSeries(data.gc?.series || [], {
    format: (value) => `${value.toFixed(0)} 次/分`
  })
  const gcPause = chartSeries(data.gcPause?.series || [], {
    format: (value) => `${value.toFixed(1)} ms/s`
  })
  const cacheHit = chartSeries(data.cacheHit?.series || [], {
    format: (value) => `${value.toFixed(1)} %`
  })
  const dbQps = chartSeries(data.dbQps?.series || [], {
    format: (value) => `${value.toFixed(1)} /s`
  })
  const dbThreads = chartSeries(data.dbThreads?.series || [], {
    format: (value) => value.toFixed(0)
  })
  const redis = chartSeries(data.redis?.series || [], {
    format: (value) => `${value.toFixed(1)} /s`
  })
  return [
    {
      id: 'run-k6',
      testid: 'run-chart-k6',
      title: 'k6 压力',
      note: '来自 run_id 过滤',
      axis: compact,
      series: [
        {
          key: 'vus',
          label: 'VU',
          color: '#38d39f',
          dash: '',
          points: vus,
          format: (value) => `${value.toFixed(0)} VU`
        },
        {
          key: 'iterations',
          label: 'iterations/s',
          color: '#5aa9e6',
          dash: '5 3',
          points: iterations,
          format: (value) => `${value.toFixed(2)} /s`
        }
      ]
    },
    {
      id: 'run-jvm',
      testid: 'run-chart-jvm',
      title: 'JVM 堆（按实例）',
      axis: compact,
      series: heap
    },
    {
      id: 'run-gc',
      testid: 'run-chart-gc',
      title: 'GC 次数（按实例 · minor/major）',
      note: '实线 minor · 虚线 major',
      axis: compact,
      series: gc
    },
    {
      id: 'run-gc-pause',
      testid: 'run-chart-gc-pause',
      title: 'GC 停顿时间（按实例）',
      note: 'GC 暂停累计速率',
      axis: compact,
      series: gcPause
    },
    {
      id: 'run-cache',
      testid: 'run-chart-cache',
      title: '缓存 L1 命中率（按实例）',
      note: 'L1 关闭时不适用',
      axis: compact,
      series: cacheHit
    },
    {
      id: 'run-db-qps',
      testid: 'run-chart-db-qps',
      title: '数据库 QPS（按节点）',
      note: 'questions 速率',
      axis: compact,
      series: dbQps
    },
    {
      id: 'run-db-threads',
      testid: 'run-chart-db-threads',
      title: '数据库运行线程（按节点）',
      note: 'threads_running',
      axis: compact,
      series: dbThreads
    },
    {
      id: 'run-redis',
      testid: 'run-chart-redis',
      title: 'Redis 命令速率（按节点）',
      note: 'standalone / HA 分线',
      axis: compact,
      series: redis
    }
  ]
})

async function refresh() {
  if (!props.active || !props.runId) return
  const start = startSeconds.value
  if (start === null) {
    results.value = {}
    return
  }
  if (inFlight) return
  inFlight = true
  loading.value = true
  error.value = ''
  const end = endSeconds.value ?? Math.floor(Date.now() / 1000)
  windowStart.value = start
  windowEnd.value = Math.max(end, start + 60)
  const step = `${Math.max(15, Math.ceil((windowEnd.value - start) / 300))}s`
  const run = safeRunId()
  const job = `job="${APP_JOB}"`
  const jobs = [
    ['vus', `sum(k6_vus{run_id="${run}"})`, 1, null],
    ['iterations', `sum(rate(k6_iterations_total{run_id="${run}"}[1m]))`, 1, null],
    ['heap', `sum by (instance) (jvm_memory_used_bytes{${job},area="heap"})`,
      1 / (1024 * 1024), { groupBy: ['instance'] }],
    ['gc', `sum by (instance, action) (increase(jvm_gc_pause_seconds_count{${job}}[1m]))`,
      1, {
        groupBy: ['instance', 'action'],
        nameFor: (labels, instance) => `${instance} ${gcActionName(labels.action)}`,
        dashFor: (labels) => (labels.action === 'end of major GC' ? '5 3' : '')
      }],
    ['gcPause', `sum by (instance) (rate(jvm_gc_pause_seconds_sum{${job}}[1m]))`,
      1000, { groupBy: ['instance'] }],
    ['cacheHit', `max by (instance) (cache_l1_hit_ratio{${job}})`,
      100, { groupBy: ['instance'] }],
    ['dbQps',
      `sum by (job, member) (rate(mysql_global_status_queries{job=~"mysqld-exporter|mysqld-exporter-ha"}[1m]))`,
      1, { groupBy: ['job', 'member'], nameFor: dbName }],
    ['dbThreads',
      `max by (job, member) (mysql_global_status_threads_running{job=~"mysqld-exporter|mysqld-exporter-ha"})`,
      1, { groupBy: ['job', 'member'], nameFor: dbName }],
    ['redis',
      `sum by (job, node) (rate(redis_commands_processed_total{job=~"redis-exporter|redis-exporter-ha"}[1m]))`,
      1, { groupBy: ['job', 'node'], nameFor: redisName }]
  ]
  try {
    const answers = await Promise.all(jobs.map(([, expr]) =>
      queryPromRange(expr, start, windowEnd.value, step)
    ))
    const next = {}
    jobs.forEach(([key, , factor, split], index) => {
      next[key] = split
        ? { series: splitSeriesPoints(answers[index], factor, split) }
        : { points: seriesPoints(answers[index], factor) }
    })
    results.value = next
  } catch (err) {
    error.value = `run 时间线查询失败：${describeError(err)}`
  } finally {
    inFlight = false
    loading.value = false
  }
}

watch(
  () => [props.active, props.runId, props.startedAt, props.endedAt, props.syncTick],
  ([active]) => {
    if (active) refresh()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  inFlight = false
})
</script>

<template>
  <section class="card run-timelines" data-testid="run-timelines">
    <div class="card-head">
      <h2>run 时间线</h2>
      <div class="bench-actions">
        <span v-if="startedAt" class="hint" data-testid="run-timelines-window">{{ startedAt }} → {{ endedAt || '进行中' }}</span>
        <span v-if="loading" class="hint">查询中…</span>
      </div>
    </div>
    <p v-if="!startedAt" class="hint" data-testid="run-timelines-empty">
      run 尚未启动：启动后这里按 run 起止时间展示 k6 压力、JVM 堆与 GC 次数。
    </p>
    <p v-else class="hint">
      窗口取该 run 的起止时间；k6 曲线按 <code>run_id</code> 过滤，GC 按实例与 minor/major 分线。
    </p>
    <p v-if="error" class="error-banner" role="alert" data-testid="run-timelines-error">{{ error }}</p>
    <div v-if="startedAt" class="chart-grid">
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
