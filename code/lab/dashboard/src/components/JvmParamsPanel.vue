<script setup>
import { computed, ref, watch } from 'vue'
import { describeError, fetchInstanceConfig, fetchInstanceEffective } from '../api'

/**
 * Monitor-page JVM parameter summary (one row per app instance).
 *
 * Goal: compare the instances at a glance - heap, collector, the G1 knobs that
 * actually differ, image and the readback state. Values come from the instance
 * config ledger plus the container readback, the same sources as the
 * 实例与 JVM page, so the two pages can never disagree.
 */
const props = defineProps({
  token: { type: String, default: '' },
  instances: { type: Array, default: () => [] },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 }
})

const REFRESH_MIN_MS = 15000

const views = ref({})
const effective = ref({})
const error = ref('')
const loading = ref(false)
let lastLoaded = 0
let inFlight = false

const nodes = computed(() => (props.instances.length ? props.instances : ['app-1', 'app-2', 'app-3']))

function summarize(view) {
  const config = view?.config
  if (!config) return null
  return {
    heap: `${config.xmsMb ?? '默认'} / ${config.xmxMb ?? '默认'} MB`,
    gc: config.gcCollector || '默认',
    pause: config.maxGcPauseMillis ? `${config.maxGcPauseMillis} ms` : '—',
    young: config.g1NewSizePercent == null && config.g1MaxNewSizePercent == null
      ? '—'
      : `${config.g1NewSizePercent ?? '—'}–${config.g1MaxNewSizePercent ?? '—'}%`,
    ihop: config.initiatingHeapOccupancyPercent == null ? '—' : `${config.initiatingHeapOccupancyPercent}%`,
    region: config.g1HeapRegionSize || '—',
    extra: Array.isArray(config.extraJvmArgs) ? config.extraJvmArgs.length : 0
  }
}

function matchState(node) {
  const view = views.value[node]
  const live = effective.value[node]
  if (!view?.jvmArgs?.length || !live?.javaToolOptions) return { text: '未验证', tone: 'warn' }
  const normalize = (text) => String(text || '').trim().split(/\s+/).filter(Boolean).sort().join(' ')
  return normalize(view.jvmArgs.join(' ')) === normalize(live.javaToolOptions)
    ? { text: '与目标一致', tone: 'ok' }
    : { text: '与目标不一致', tone: 'warn' }
}

const rows = computed(() => nodes.value.map((node) => {
  const view = views.value[node]
  return {
    node,
    status: view?.status || 'NOT_CONFIGURED',
    revision: view?.revision || 0,
    summary: summarize(view),
    image: effective.value[node]?.imageTag || view?.appliedImageTag || '未配置',
    readback: effective.value[node]?.javaToolOptions || '无回读',
    match: matchState(node)
  }
}))

function tone(status) {
  if (status === 'APPLIED') return 'q-ok'
  if (status === 'FAILED' || status === 'DEGRADED') return 'q-degraded'
  return 'q-no-data'
}

async function load(force = false) {
  if (!props.active || !props.token) return
  if (inFlight) return
  const now = Date.now()
  if (!force && now - lastLoaded < REFRESH_MIN_MS) return
  inFlight = true
  loading.value = true
  error.value = ''
  try {
    await Promise.all(nodes.value.map(async (node) => {
      try {
        // Await first, then merge: spreading views inside the literal would
        // snapshot the object BEFORE the await and lose concurrent writes.
        const view = await fetchInstanceConfig(props.token, node)
        views.value = { ...views.value, [node]: view }
      } catch (err) {
        error.value = `读取 ${node} 配置失败：${describeError(err)}`
      }
      try {
        const live = await fetchInstanceEffective(props.token, node)
        effective.value = { ...effective.value, [node]: live }
      } catch {
        effective.value = { ...effective.value, [node]: null }
      }
    }))
    lastLoaded = Date.now()
  } finally {
    inFlight = false
    loading.value = false
  }
}

watch(
  () => [props.active, props.token, props.syncTick],
  ([active]) => {
    if (active) load()
  },
  { immediate: true }
)
</script>

<template>
  <section class="card jvm-params" data-testid="jvm-params">
    <div class="card-head">
      <h2>JVM 参数对照</h2>
      <div class="bench-actions">
        <span class="hint">来源：实例配置台账 + 容器回读（每 15s 最多刷新一次）</span>
        <button type="button" class="btn ghost" data-testid="jvm-params-refresh" :disabled="loading" @click="load(true)">
          {{ loading ? '查询中…' : '刷新参数' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="error-banner" role="alert" data-testid="jvm-params-error">{{ error }}</p>
    <table class="data-table" data-testid="jvm-params-table">
      <thead>
        <tr>
          <th>实例</th>
          <th>状态</th>
          <th>rev</th>
          <th>Xms / Xmx</th>
          <th>GC</th>
          <th>目标停顿</th>
          <th>Young 区间</th>
          <th>IHOP</th>
          <th>Region</th>
          <th>额外参数</th>
          <th>镜像</th>
          <th>目标 vs 回读</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.node" :data-testid="`jvm-params-row-${row.node}`">
          <td><code>{{ row.node }}</code></td>
          <td><span class="badge" :class="tone(row.status)">{{ row.status }}</span></td>
          <td>{{ row.revision || '—' }}</td>
          <td>{{ row.summary?.heap || '未配置' }}</td>
          <td>{{ row.summary?.gc || '—' }}</td>
          <td>{{ row.summary?.pause || '—' }}</td>
          <td>{{ row.summary?.young || '—' }}</td>
          <td>{{ row.summary?.ihop || '—' }}</td>
          <td>{{ row.summary?.region || '—' }}</td>
          <td>{{ row.summary ? `${row.summary.extra} 条` : '—' }}</td>
          <td class="hint">{{ row.image }}</td>
          <td>
            <span class="badge" :class="row.match.tone" :title="row.readback">{{ row.match.text }}</span>
          </td>
        </tr>
      </tbody>
    </table>
    <p class="hint">
      参数不同的实例可直接按行对比；改参数去「实例与 JVM」页或场景向导第 3 步（同一份台账），应用/重启后这里 15s 内刷新。
    </p>
  </section>
</template>

<style scoped>
.jvm-params .data-table {
  font-size: 0.8rem;
}
</style>
