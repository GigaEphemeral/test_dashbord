<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import InstanceGcLogViewer from './InstanceGcLogViewer.vue'
import {
  applyInstanceConfig,
  describeError,
  fetchInstanceConfig,
  fetchInstanceEffective,
  fetchInstanceEvents,
  restartInstanceConfig,
  rollbackInstanceConfig,
  saveInstanceConfig,
  validateInstanceConfig
} from '../api'

/**
 * Stage2 B4-04 per-instance configuration editor (design §16.4–§16.7).
 *
 * The stored config (DRAFT/APPLIED/…), the generated argv and the live
 * container readback are three different truths, so they are rendered side by
 * side. A readback of UNAVAILABLE/READBACK_UNAVAILABLE is shown as 未验证 and
 * never as success. Apply/rollback are explicit, confirmed actions: the
 * controller rebuilds the container synchronously (~40–120s, budget 240s).
 */
const props = defineProps({
  token: { type: String, default: '' },
  instance: { type: String, required: true },
  /** The jdk8 shell is a limited-observability instance (no app-level readback). */
  limitedObservability: { type: Boolean, default: false },
  active: { type: Boolean, default: true }
})

const STATUS_LABELS = {
  LOADING: '加载中',
  DRAFT: '待应用',
  PENDING_RESTART: '待重启',
  APPLYING: '应用中',
  APPLIED: '已生效',
  ROLLED_BACK: '已回滚',
  FAILED: '应用失败',
  DEGRADED: '需人工',
  NOT_CONFIGURED: '未配置'
}
const STATUS_TONES = {
  APPLIED: 'ok',
  DRAFT: 'warn',
  PENDING_RESTART: 'warn',
  APPLYING: 'warn',
  FAILED: 'down',
  DEGRADED: 'down',
  ROLLED_BACK: 'muted',
  NOT_CONFIGURED: 'muted'
}
const ROLLBACK_LABELS = {
  NOT_NEEDED: '无需回滚',
  RESTORED: '已恢复旧容器',
  FAILED: '自动恢复失败'
}

// A safe starting point for an instance that has no stored config. It mirrors
// the verified baseline of app-1; nothing is persisted until "仅保存".
const SAFE_BASELINE = {
  jdkVersion: '21',
  imageTag: 'jdk21',
  xmsMb: '256',
  xmxMb: '512',
  metaspaceMb: '',
  directMemoryMb: '64',
  extraJvmArgs: '',
  gcCollector: 'G1',
  maxGcPauseMillis: '200',
  g1NewSizePercent: '5',
  g1MaxNewSizePercent: '60',
  g1HeapRegionSize: '4m',
  initiatingHeapOccupancyPercent: '45',
  parallelGcThreads: '',
  newRatio: '',
  survivorRatio: '',
  corePoolSize: '4',
  maxPoolSize: '8',
  queueCapacity: '50',
  keepAliveSeconds: '60',
  rejectionPolicy: 'AbortPolicy',
  caffeineEnabled: 'true',
  maximumSize: '10000',
  expireAfterWriteSeconds: '60',
  expireAfterAccessSeconds: '0',
  refreshAfterWriteSeconds: '10',
  recordStats: 'true',
  gcLogEnabled: 'false',
  gcLogPath: '',
  gcLogFileSizeMb: '',
  gcLogFileCount: '',
  heapDumpOnOutOfMemory: '',
  heapDumpPath: ''
}

const NUMBER_FIELDS = [
  'xmsMb', 'xmxMb', 'metaspaceMb', 'directMemoryMb',
  'maxGcPauseMillis', 'g1NewSizePercent', 'g1MaxNewSizePercent',
  'initiatingHeapOccupancyPercent', 'parallelGcThreads', 'newRatio', 'survivorRatio',
  'corePoolSize', 'maxPoolSize', 'queueCapacity', 'keepAliveSeconds',
  'maximumSize', 'expireAfterWriteSeconds', 'expireAfterAccessSeconds',
  'refreshAfterWriteSeconds', 'gcLogFileSizeMb', 'gcLogFileCount'
]
const BOOLEAN_FIELDS = [
  'caffeineEnabled', 'recordStats', 'gcLogEnabled', 'heapDumpOnOutOfMemory'
]
const TEXT_FIELDS = [
  'imageTag', 'g1HeapRegionSize', 'rejectionPolicy', 'gcLogPath', 'heapDumpPath'
]
const G1_FIELDS = [
  { key: 'maxGcPauseMillis', label: '最大停顿', flag: 'MaxGCPauseMillis', tip: 'G1 目标停顿毫秒数，是目标不是硬保证。' },
  { key: 'g1NewSizePercent', label: '新代初始占比', flag: 'G1NewSizePercent', tip: 'G1 新代初始比例（%）；必须 ≤ 新代最大占比。' },
  { key: 'g1MaxNewSizePercent', label: '新代最大占比', flag: 'G1MaxNewSizePercent', tip: 'G1 新代上限比例（%）；需 ≥ 初始占比。' },
  { key: 'g1HeapRegionSize', label: 'Region 大小', flag: 'G1HeapRegionSize', tip: 'G1 堆分区大小；只接受 1m/2m/4m/8m/16m/32m。' },
  { key: 'initiatingHeapOccupancyPercent', label: '并发标记触发', flag: 'InitiatingHeapOccupancyPercent', tip: '堆占用达到该百分比时启动并发标记周期。' }
]
const HEAP_FIELDS = [
  { key: 'xmsMb', label: '初始堆', unit: 'MB', flag: '-Xms', tip: '启动时申请的最小堆；与 -Xmx 接近可减少运行期扩缩。' },
  { key: 'xmxMb', label: '最大堆', unit: 'MB', flag: '-Xmx', tip: '堆上限；与 Metaspace、直接内存、线程栈一起受容器内存预算约束。' },
  { key: 'metaspaceMb', label: 'Metaspace 上限', unit: 'MB', flag: 'MaxMetaspaceSize', tip: '类元数据上限；留空使用 JVM 默认。实测 128m 会 Metaspace OOM。' },
  { key: 'directMemoryMb', label: '直接内存上限', unit: 'MB', flag: 'MaxDirectMemorySize', tip: 'NIO/Netty 等堆外直接内存上限。' },
  { key: 'newRatio', label: '新老代比', unit: '', flag: 'NewRatio', tip: '老年代/新生代比例；G1 不使用该参数，Serial/Parallel 可配。' },
  { key: 'survivorRatio', label: 'Survivor 比', unit: '', flag: 'SurvivorRatio', tip: 'Eden 与单个 Survivor 的比例；Serial/Parallel 可配。' }
]
const POOL_FIELDS = [
  { key: 'corePoolSize', label: '核心线程', flag: 'corePoolSize', tip: '常驻核心线程数；即使空闲也保留。' },
  { key: 'maxPoolSize', label: '最大线程', flag: 'maxPoolSize', tip: '峰值线程数；队列满后才扩到该值，也计入内存预算。' },
  { key: 'queueCapacity', label: '队列长度', flag: 'queueCapacity', tip: '等待队列长度；满后触发拒绝策略。' },
  { key: 'keepAliveSeconds', label: '空闲存活（秒）', flag: 'keepAliveSeconds', tip: '非核心线程空闲存活秒数。' }
]
const CAFFEINE_FIELDS = [
  { key: 'maximumSize', label: '最大条目', flag: 'maximumSize', tip: '本地缓存条目上限；超出按淘汰策略处理。' },
  { key: 'expireAfterWriteSeconds', label: '写入后过期（秒）', flag: 'expireAfterWriteSeconds', tip: '写入后多久过期；0 表示不启用。' },
  { key: 'expireAfterAccessSeconds', label: '访问后过期（秒）', flag: 'expireAfterAccessSeconds', tip: '最后一次访问后多久过期；0 表示不启用。' },
  { key: 'refreshAfterWriteSeconds', label: '写入后刷新（秒）', flag: 'refreshAfterWriteSeconds', tip: '异步刷新间隔，不等于过期；0 表示不启用。' }
]

const view = ref(null)
const effective = ref(null)
const effectiveError = ref('')
const form = ref(blankForm())
const validation = ref(null)
const error = ref('')
const busy = ref('')
const confirmAction = ref('')
const lastApply = ref(null)
const events = ref(null)
const eventsError = ref('')
const eventsLoading = ref(false)

const emit = defineEmits(['changed'])

const rules = computed(() => ({
  jdk8Shell: props.instance === 'seckill-app-jdk8',
  appInstance: /^app-\d+$/.test(props.instance)
}))

const jdk8Selected = computed(() => (
  form.value.imageTag === 'jdk8' || form.value.jdkVersion === '8'
))
const jdk8Rejected = computed(() => rules.value.appInstance && jdk8Selected.value)
const limited = computed(() => (
  props.limitedObservability
  || rules.value.jdk8Shell
  || effective.value?.observability === 'LIMITED-OBSERVABILITY'
))

const status = computed(() => (busy.value === 'load' && !view.value ? 'LOADING' : view.value?.status || 'NOT_CONFIGURED'))
const statusLabel = computed(() => STATUS_LABELS[status.value] || '未知状态')
const statusText = computed(() => `${status.value} ${statusLabel.value}`)
const statusTone = computed(() => STATUS_TONES[status.value] || 'muted')

const actionsLocked = computed(() => busy.value === 'apply' || busy.value === 'rollback' || busy.value === 'restart')
const canValidate = computed(() => (
  !busy.value && !jdk8Rejected.value && Boolean(String(form.value.jdkVersion || '').trim())
))
const canSave = computed(() => (
  !busy.value && !jdk8Rejected.value && Boolean(String(form.value.jdkVersion || '').trim())
))
const canApply = computed(() => (
  !busy.value && !jdk8Rejected.value && Boolean(view.value?.config)
  && status.value !== 'APPLYING'
))
const canRollback = computed(() => (
  !busy.value && !jdk8Rejected.value && Boolean(view.value?.config)
  && status.value !== 'APPLYING'
))
/** A restart only makes sense for the config that is already running. */
const canRestart = computed(() => (
  !busy.value && !jdk8Rejected.value && status.value === 'APPLIED'
))

const targetArgs = computed(() => view.value?.jvmArgs || [])
const targetArgsText = computed(() => targetArgs.value.join(' ') || '未配置')
const effectiveArgsText = computed(() => effective.value?.javaToolOptions || '无回读')

const readbackUnavailable = computed(() => (
  !effective.value
  || !effective.value.javaToolOptions
  || ['UNAVAILABLE', 'READBACK_UNAVAILABLE'].includes(effective.value.readback)
))
const readbackText = computed(() => {
  if (readbackUnavailable.value) return '未验证'
  return effective.value.readback === 'OK' ? '已回读' : '未验证'
})
const readbackTone = computed(() => (readbackText.value === '已回读' ? 'ok' : 'warn'))

const argsMatch = computed(() => {
  if (readbackUnavailable.value) return false
  const target = targetArgsText.value
  const live = effectiveArgsText.value
  if (!target || target === '未配置' || live === '无回读') return false
  return normalizeArgs(target) === normalizeArgs(live)
})
const argsChip = computed(() => {
  if (readbackText.value !== '已回读') return { text: '未验证', tone: 'warn' }
  return argsMatch.value
    ? { text: '与目标一致', tone: 'ok' }
    : { text: '与目标不一致', tone: 'warn' }
})

const targetImage = computed(() => (
  view.value?.appliedImageTag || view.value?.config?.imageTag || '未配置'
))
const effectiveImage = computed(() => effective.value?.imageTag || '无回读')
const effectiveImageId = computed(() => shortenId(effective.value?.imageId))

const budgetSource = computed(() => validation.value || view.value)
const requiredMemoryMb = computed(() => Number(budgetSource.value?.requiredMemoryMb || 0))
const memoryLimitMb = computed(() => Number(budgetSource.value?.memoryLimitMb || 0))
const overBudget = computed(() => memoryLimitMb.value > 0 && requiredMemoryMb.value > memoryLimitMb.value)

const stableText = computed(() => {
  const value = view.value?.stableUntil
  if (!value) return ''
  const time = clockText(value)
  return time ? `稳定期至 ${time}` : ''
})

const warnings = computed(() => {
  const list = [...(validation.value?.warnings || view.value?.warnings || [])]
  const metaspace = Number(form.value.metaspaceMb)
  if (Number.isFinite(metaspace) && metaspace > 0 && metaspace < 192) {
    list.push('实测 128m 会 Metaspace OOM，建议留空使用 JVM 默认或 ≥192m')
  }
  if (jdk8Rejected.value) {
    list.push('JDK8 是独立外壳实例：app-1/2/3 必须使用 jdk21 镜像。')
  }
  if (rules.value.jdk8Shell) {
    list.push('JDK8 外壳标记 LIMITED-OBSERVABILITY：应用级热回读不可用，以 GC 日志/JMX 兜底。')
  }
  return [...new Set(list.filter(Boolean))]
})

const effectiveNotes = computed(() => {
  const notes = [...(effective.value?.notes || [])]
  if (effectiveError.value) notes.push(effectiveError.value)
  return notes
})

const progressText = computed(() => {
  if (busy.value === 'apply') return '应用并重启进行中：容器重建约 40–120s，最多等待 240s；期间已禁用全部操作。'
  if (busy.value === 'rollback') return '回滚进行中：容器重建约 40–120s，最多等待 240s；期间已禁用全部操作。'
  if (busy.value === 'restart') return '重启进行中：以当前已应用配置重建容器，约 40–120s；期间已禁用全部操作。'
  if (busy.value === 'validate') return '校验中…'
  if (busy.value === 'save') return '保存中…'
  return ''
})

const jdk8Hint = computed(() => (
  jdk8Rejected.value
    ? 'JDK8 是独立外壳实例：app-1/2/3 只能使用 jdk21 镜像；服务端也会拒绝应用。'
    : ''
))

function blankForm() {
  return {
    jdkVersion: '',
    imageTag: '',
    extraJvmArgs: '',
    gcCollector: '',
    g1HeapRegionSize: '',
    rejectionPolicy: '',
    gcLogPath: '',
    heapDumpPath: '',
    ...Object.fromEntries(NUMBER_FIELDS.map((key) => [key, ''])),
    ...Object.fromEntries(BOOLEAN_FIELDS.map((key) => [key, '']))
  }
}

function toForm(config) {
  const next = blankForm()
  if (!config) return next
  next.jdkVersion = config.jdkVersion == null ? '' : String(config.jdkVersion)
  // gcCollector is neither a TEXT nor a NUMBER/BOOLEAN field: buildPayload
  // handles it explicitly. Forgetting it here silently dropped the collector
  // on every UI save (validate/save produced a config without -XX:+UseG1GC).
  next.gcCollector = config.gcCollector == null ? '' : String(config.gcCollector)
  for (const key of TEXT_FIELDS) {
    next[key] = config[key] == null ? '' : String(config[key])
  }
  for (const key of NUMBER_FIELDS) {
    next[key] = config[key] == null ? '' : String(config[key])
  }
  for (const key of BOOLEAN_FIELDS) {
    next[key] = config[key] == null ? '' : String(config[key])
  }
  next.extraJvmArgs = Array.isArray(config.extraJvmArgs) ? config.extraJvmArgs.join('\n') : ''
  return next
}

function normalizeArgs(text) {
  return String(text || '')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .sort()
    .join(' ')
}

function shortenId(id) {
  const value = String(id || '')
  if (!value) return ''
  const bare = value.replace(/^sha256:/, '')
  return bare.length > 12 ? `${bare.slice(0, 12)}…` : bare
}

function clockText(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (part) => String(part).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function buildPayload() {
  const payload = {}
  const jdk = String(form.value.jdkVersion || '').trim()
  if (jdk) payload.jdkVersion = Number(jdk)
  const tag = String(form.value.imageTag || '').trim()
  if (tag) payload.imageTag = tag
  const collector = String(form.value.gcCollector || '').trim()
  if (collector) payload.gcCollector = collector

  for (const key of TEXT_FIELDS) {
    if (key === 'imageTag') continue
    const value = String(form.value[key] || '').trim()
    if (value) payload[key] = value
  }
  for (const key of NUMBER_FIELDS) {
    const raw = String(form.value[key] ?? '').trim()
    if (!raw) continue
    const parsed = Number(raw)
    if (Number.isFinite(parsed)) payload[key] = parsed
  }
  for (const key of BOOLEAN_FIELDS) {
    const raw = String(form.value[key] ?? '').trim()
    if (raw === 'true' || raw === 'false') payload[key] = raw === 'true'
  }
  const extra = String(form.value.extraJvmArgs || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  if (extra.length) payload.extraJvmArgs = extra

  // Hidden fields are omitted rather than sent stale: the controller rejects
  // G1 fields on a non-G1 collector and ignores log flags when logging is off.
  if (payload.gcCollector !== 'G1') {
    for (const field of G1_FIELDS) delete payload[field.key]
  }
  if (payload.gcCollector !== 'Parallel') delete payload.parallelGcThreads
  if (payload.gcLogEnabled !== true) {
    delete payload.gcLogPath
    delete payload.gcLogFileSizeMb
    delete payload.gcLogFileCount
  }
  if (payload.heapDumpOnOutOfMemory !== true) delete payload.heapDumpPath
  return payload
}

function loadBaseline() {
  form.value = { ...SAFE_BASELINE }
  validation.value = null
  error.value = ''
}

function onJdkChange() {
  const value = String(form.value.jdkVersion)
  if (value === '8') form.value.imageTag = 'jdk8'
  else if (value === '21' && form.value.imageTag === 'jdk8') form.value.imageTag = 'jdk21'
  validation.value = null
}

function onImageChange() {
  const value = String(form.value.imageTag)
  if (value === 'jdk8') form.value.jdkVersion = '8'
  else if (value === 'jdk21' && form.value.jdkVersion === '8') form.value.jdkVersion = '21'
  validation.value = null
}

function onCollectorChange() {
  const value = String(form.value.gcCollector)
  if (value !== 'G1') {
    for (const field of G1_FIELDS) form.value[field.key] = ''
  }
  if (value !== 'Parallel') form.value.parallelGcThreads = ''
  validation.value = null
}

function onGcLogChange() {
  if (String(form.value.gcLogEnabled) !== 'true') {
    form.value.gcLogPath = ''
    form.value.gcLogFileSizeMb = ''
    form.value.gcLogFileCount = ''
  }
  validation.value = null
}

/** Enabling a dump without a path prefills the server-side default location. */
function onHeapDumpChange() {
  if (String(form.value.heapDumpOnOutOfMemory) === 'true'
    && !String(form.value.heapDumpPath || '').trim()) {
    // Mirrors InstanceConfigValidator.defaultPath(gcLogRoot = /app/lab-snapshots).
    form.value.heapDumpPath = `/app/lab-snapshots/heap-${props.instance}.hprof`
  }
  validation.value = null
}

async function loadEffective() {
  effectiveError.value = ''
  try {
    effective.value = await fetchInstanceEffective(props.token, props.instance)
  } catch (err) {
    effective.value = null
    effectiveError.value = `有效回读失败：${describeError(err)}`
  }
}

/** B5-04b: bounded OOM / heap-dump scan; an empty list is NO_DATA, never an event. */
async function loadEvents() {
  eventsError.value = ''
  eventsLoading.value = true
  try {
    events.value = await fetchInstanceEvents(props.token, props.instance)
  } catch (err) {
    events.value = null
    eventsError.value = `诊断事件读取失败：${describeError(err)}`
  } finally {
    eventsLoading.value = false
  }
}

async function load() {
  if (!props.active || !props.token) return
  busy.value = 'load'
  error.value = ''
  validation.value = null
  lastApply.value = null
  try {
    view.value = await fetchInstanceConfig(props.token, props.instance)
    form.value = toForm(view.value?.config)
  } catch (err) {
    view.value = null
    form.value = blankForm()
    error.value = `读取实例配置失败：${describeError(err)}`
  } finally {
    await loadEffective()
    busy.value = ''
  }
}

function guardJdk8() {
  if (!jdk8Rejected.value) return false
  error.value = 'JDK8 是独立外壳实例：app-1/2/3 只能使用 jdk21 镜像。'
  return true
}

async function runValidate() {
  if (!canValidate.value) return
  if (guardJdk8()) return
  error.value = ''
  validation.value = null
  busy.value = 'validate'
  try {
    validation.value = await validateInstanceConfig(props.token, props.instance, buildPayload())
  } catch (err) {
    error.value = `校验失败：${describeError(err)}`
  } finally {
    busy.value = ''
  }
}

async function runSave() {
  if (!canSave.value) return
  if (guardJdk8()) return
  error.value = ''
  busy.value = 'save'
  try {
    view.value = await saveInstanceConfig(props.token, props.instance, buildPayload())
    form.value = toForm(view.value?.config)
    validation.value = null
    await loadEffective()
    emit('changed')
  } catch (err) {
    error.value = `保存失败：${describeError(err)}`
  } finally {
    busy.value = ''
  }
}

function ask(action) {
  if (action === 'apply' && !canApply.value) return
  if (action === 'rollback' && !canRollback.value) return
  if (action === 'restart' && !canRestart.value) return
  confirmAction.value = action
}

async function confirmActionRun() {
  const action = confirmAction.value
  confirmAction.value = ''
  if (action === 'apply') await runRebuild('apply')
  if (action === 'rollback') await runRebuild('rollback')
  if (action === 'restart') await runRebuild('restart')
}

async function runRebuild(action) {
  error.value = ''
  lastApply.value = null
  validation.value = null
  busy.value = action
  try {
    const request = action === 'apply'
      ? applyInstanceConfig(props.token, props.instance)
      : action === 'restart'
        ? restartInstanceConfig(props.token, props.instance)
        : rollbackInstanceConfig(props.token, props.instance)
    const result = await request
    try {
      view.value = await fetchInstanceConfig(props.token, props.instance)
      form.value = toForm(view.value?.config)
    } catch (err) {
      error.value = `重建已返回，但重新读取配置失败：${describeError(err)}`
    }
    await loadEffective()
    // Publish the result only after the stored status/effective readback is
    // refreshed, so the badge and the apply/rollback result never disagree.
    lastApply.value = result
    emit('changed')
  } catch (err) {
    const label = action === 'apply' ? '应用' : action === 'restart' ? '重启' : '回滚'
    error.value = `${label}失败：${describeError(err)}`
  } finally {
    busy.value = ''
  }
}

function checkTone(checkStatus) {
  if (checkStatus === 'PASS') return 'ok'
  if (checkStatus === 'FAIL') return 'down'
  return 'warn'
}

onMounted(load)
watch(() => props.token, load)
watch(() => props.active, (active) => {
  if (active && !view.value && !busy.value) load()
})
</script>

<template>
  <article
    class="card instance-config-card"
    :class="{ 'tone-down': status === 'FAILED' || status === 'DEGRADED' }"
    :data-testid="`instance-config-editor-${instance}`"
  >
    <header class="instance-config-head">
      <div>
        <h3><code>jl-{{ instance }}</code></h3>
        <p class="instance-sub">{{ instance }} · 实例/JVM 配置组</p>
      </div>
      <div class="instance-badges">
        <span class="badge" :class="statusTone" :data-testid="`instance-config-status-${instance}`">{{ statusText }}</span>
        <span
          v-if="limited"
          class="badge warn"
          :data-testid="`instance-config-observability-${instance}`"
        >LIMITED-OBSERVABILITY</span>
        <span
          v-if="view?.revision"
          class="chip"
          :data-testid="`instance-config-revision-${instance}`"
        >revision {{ view.revision }}</span>
        <span v-if="stableText" class="chip warn" :data-testid="`instance-config-stable-${instance}`">{{ stableText }}</span>
      </div>
    </header>

    <div v-if="progressText" class="notice info" role="status" :data-testid="`instance-config-progress-${instance}`">
      {{ progressText }}
    </div>
    <div v-if="error" class="notice danger" role="alert" :data-testid="`instance-config-error-${instance}`">{{ error }}</div>
    <div v-if="view?.lastError" class="notice warn" :data-testid="`instance-config-last-error-${instance}`">
      上次结果：{{ view.lastError }}
    </div>

    <section class="config-facts">
      <div class="config-fact">
        <span class="metric-label">目标参数（服务端生成）</span>
        <code class="fact-value" :data-testid="`instance-config-target-args-${instance}`">{{ targetArgsText }}</code>
      </div>
      <div class="config-fact">
        <span class="metric-label">有效参数（容器回读）</span>
        <div class="fact-row">
          <code class="fact-value" :data-testid="`instance-config-effective-args-${instance}`">{{ effectiveArgsText }}</code>
          <span class="badge" :class="argsChip.tone">{{ argsChip.text }}</span>
        </div>
        <span class="metric-label">
          回读状态
          <span class="badge" :class="readbackTone" :data-testid="`instance-config-readback-${instance}`">{{ readbackText }}</span>
        </span>
      </div>
      <div class="config-fact">
        <span class="metric-label">镜像</span>
        <code class="fact-value" :data-testid="`instance-config-image-${instance}`">目标 {{ targetImage }} · 实际 {{ effectiveImage }}</code>
        <span v-if="effectiveImageId" class="metric-label" :data-testid="`instance-config-image-id-${instance}`">imageId {{ effectiveImageId }}</span>
        <span v-if="view?.previousImageTag" class="metric-label">上一版本 {{ view.previousImageTag }}</span>
      </div>
      <div class="config-fact">
        <span class="metric-label">内存预算（required / limit）</span>
        <strong
          class="fact-value"
          :class="{ 'over-budget': overBudget }"
          :data-testid="`instance-config-budget-${instance}`"
        >{{ requiredMemoryMb }} / {{ memoryLimitMb }} MB</strong>
        <span v-if="overBudget" class="badge down">超出容器预算</span>
        <span v-else-if="memoryLimitMb" class="badge ok">预算内</span>
        <span class="metric-label">
          更新 {{ view?.updatedAt ? clockText(view.updatedAt) : '—' }}
          · 应用 {{ view?.lastApplyAt ? clockText(view.lastApplyAt) : (view?.appliedAt ? clockText(view.appliedAt) : '—') }}
        </span>
      </div>
    </section>

    <div v-if="jdk8Hint" class="notice danger" :data-testid="`instance-config-jdk8-hint-${instance}`">{{ jdk8Hint }}</div>
    <div
      v-if="limited && readbackUnavailable"
      class="notice warn"
      :data-testid="`instance-config-limited-note-${instance}`"
    >
      JDK8 外壳：{{ effectiveNotes.length ? effectiveNotes.join('；') : 'app 级热回读不可用（READBACK_UNAVAILABLE）' }}；
      池/GC 日志等回读不可用项保持「无数据」。
    </div>
    <ul v-else-if="effectiveNotes.length" class="hint config-notes" :data-testid="`instance-config-effective-note-${instance}`">
      <li v-for="note in effectiveNotes" :key="note">{{ note }}</li>
    </ul>

    <section v-if="view?.status === 'NOT_CONFIGURED'" class="notice info">
      未配置：表单为空。可先「载入安全基线」，校验通过后「仅保存」为 DRAFT；应用前必须先保存。
      <button type="button" class="btn ghost" :data-testid="`instance-config-baseline-${instance}`" @click="loadBaseline">载入安全基线</button>
    </section>

    <fieldset class="config-fieldset" :disabled="actionsLocked">
      <section class="config-group">
        <div class="subhead">堆 / 运行时</div>
        <div class="field-row">
          <div class="field">
            <label data-tip="只能选 8 或 21；app-1/2/3 必须使用 JDK 21，JDK8 是独立外壳实例。">
              JDK 版本 <span class="key">jdkVersion</span>
            </label>
            <select
              v-model="form.jdkVersion"
              class="input select"
              :data-testid="`instance-config-jdk-${instance}`"
              @change="onJdkChange"
            >
              <option value="">未设置</option>
              <option value="21">21</option>
              <option value="8">8（独立外壳）</option>
            </select>
            <div class="help">必填；8 只用于 seckill-app-jdk8 外壳。</div>
          </div>
          <div class="field">
            <label data-tip="镜像 tag 必须与 JDK 版本一致，并由控制器拼成 java-learn-lab/seckill-app:<tag>。">
              镜像 <span class="key">imageTag</span>
            </label>
            <select
              v-model="form.imageTag"
              class="input select"
              :data-testid="`instance-config-image-select-${instance}`"
              @change="onImageChange"
            >
              <option value="">未设置</option>
              <option value="jdk21">jdk21</option>
              <option value="jdk8">jdk8</option>
            </select>
            <div class="help">JDK8 是独立外壳实例；app-1/2/3 拒绝 jdk8，服务端同样拒绝应用。</div>
          </div>
          <div v-for="field in HEAP_FIELDS.slice(0, 4)" :key="field.key" class="field">
            <label :data-tip="field.tip">
              {{ field.label }} <span class="key">{{ field.flag }}</span>
            </label>
            <input
              v-model="form[field.key]"
              class="input"
              inputmode="numeric"
              :placeholder="field.unit ? `MB，留空=JVM 默认` : '留空=JVM 默认'"
              :data-testid="`instance-config-field-${instance}-${field.key}`"
            />
            <div class="help">{{ field.tip }}</div>
          </div>
        </div>
        <div class="field config-extra-args">
          <label data-tip="仅接受 -X/-XX:/-D 开头、无空格/路径的单个参数；每行一个，最多 16 个。">
            额外 JVM 参数 <span class="key">extraJvmArgs</span>
          </label>
          <textarea
            v-model="form.extraJvmArgs"
            class="input"
            rows="2"
            placeholder="-XX:+AlwaysPreTouch"
            :data-testid="`instance-config-field-${instance}-extraJvmArgs`"
          ></textarea>
          <div class="help">每行一个参数；控制器白名单校验，危险诊断/agent 参数会被拒绝。</div>
        </div>
      </section>

      <section class="config-group">
        <div class="subhead">GC</div>
        <div class="field-row">
          <div class="field">
            <label data-tip="G1/Parallel/Serial；G1 专属字段只在 G1 下显示，ParallelGCThreads 只在 Parallel 下显示。">
              GC 收集器 <span class="key">gcCollector</span>
            </label>
            <select
              v-model="form.gcCollector"
              class="input select"
              :data-testid="`instance-config-gc-${instance}`"
              @change="onCollectorChange"
            >
              <option value="">未设置</option>
              <option value="G1">G1</option>
              <option value="Parallel">Parallel</option>
              <option value="Serial">Serial</option>
            </select>
            <div class="help">切换收集器会清空不适用的专属字段。</div>
          </div>
          <template v-if="form.gcCollector === 'G1'">
            <div v-for="field in G1_FIELDS" :key="field.key" class="field">
              <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
              <input
                v-model="form[field.key]"
                class="input"
                :data-testid="`instance-config-field-${instance}-${field.key}`"
              />
              <div class="help">{{ field.tip }}</div>
            </div>
          </template>
          <div v-if="form.gcCollector === 'Parallel'" class="field">
            <label data-tip="Parallel GC 工作线程数；只在 Parallel 收集器下生效。">
              并行线程数 <span class="key">ParallelGCThreads</span>
            </label>
            <input
              v-model="form.parallelGcThreads"
              class="input"
              :data-testid="`instance-config-field-${instance}-parallelGcThreads`"
            />
            <div class="help">只在 Parallel 收集器下显示与校验。</div>
          </div>
          <div v-for="field in HEAP_FIELDS.slice(4)" :key="field.key" class="field">
            <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
            <input
              v-model="form[field.key]"
              class="input"
              :data-testid="`instance-config-field-${instance}-${field.key}`"
            />
            <div class="help">{{ field.tip }}</div>
          </div>
        </div>
      </section>

      <section class="config-group">
        <div class="subhead">业务池</div>
        <div class="field-row">
          <div v-for="field in POOL_FIELDS" :key="field.key" class="field">
            <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
            <input
              v-model="form[field.key]"
              class="input"
              :data-testid="`instance-config-field-${instance}-${field.key}`"
            />
            <div class="help">{{ field.tip }}</div>
          </div>
          <div class="field">
            <label data-tip="Abort 抛异常；CallerRuns 回压调用线程；Discard 丢弃新任务；DiscardOldest 丢最旧任务。">
              拒绝策略 <span class="key">rejectionPolicy</span>
            </label>
            <select
              v-model="form.rejectionPolicy"
              class="input select"
              :data-testid="`instance-config-field-${instance}-rejectionPolicy`"
            >
              <option value="">未设置</option>
              <option value="AbortPolicy">AbortPolicy</option>
              <option value="CallerRunsPolicy">CallerRunsPolicy</option>
              <option value="DiscardPolicy">DiscardPolicy</option>
              <option value="DiscardOldestPolicy">DiscardOldestPolicy</option>
            </select>
            <div class="help">线程池满载后的处理方式。</div>
          </div>
        </div>
      </section>

      <section class="config-group">
        <div class="subhead">Caffeine</div>
        <div class="field-row">
          <div class="field">
            <label data-tip="关闭后不再命中本地缓存并清空条目；关闭时其容量/过期字段仍存储但不生效。">
              L1 开关 <span class="key">caffeineEnabled</span>
            </label>
            <select
              v-model="form.caffeineEnabled"
              class="input select"
              :data-testid="`instance-config-field-${instance}-caffeineEnabled`"
            >
              <option value="">未设置</option>
              <option value="true">开启</option>
              <option value="false">关闭</option>
            </select>
            <div class="help">关闭时本地条目被清空。</div>
          </div>
          <div v-for="field in CAFFEINE_FIELDS" :key="field.key" class="field">
            <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
            <input
              v-model="form[field.key]"
              class="input"
              :data-testid="`instance-config-field-${instance}-${field.key}`"
            />
            <div class="help">{{ field.tip }}</div>
          </div>
          <div class="field">
            <label data-tip="开启命中率/驱逐统计；关闭时相关指标显示不适用。">
              统计 <span class="key">recordStats</span>
            </label>
            <select
              v-model="form.recordStats"
              class="input select"
              :data-testid="`instance-config-field-${instance}-recordStats`"
            >
              <option value="">未设置</option>
              <option value="true">开启</option>
              <option value="false">关闭</option>
            </select>
            <div class="help">命中率/驱逐统计开关。</div>
          </div>
        </div>
      </section>

      <section class="config-group">
        <div class="subhead">GC 日志</div>
        <div class="field-row">
          <div class="field">
            <label data-tip="开启后由 JVM 写 GC 日志；路径必须位于 /app/lab-snapshots 下。">
              GC 日志开关 <span class="key">gcLogEnabled</span>
            </label>
            <select
              v-model="form.gcLogEnabled"
              class="input select"
              :data-testid="`instance-config-gclog-${instance}`"
              @change="onGcLogChange"
            >
              <option value="">未设置</option>
              <option value="true">开启</option>
              <option value="false">关闭</option>
            </select>
            <div class="help">关闭时不生成任何 GC 日志参数。</div>
          </div>
          <template v-if="form.gcLogEnabled === 'true'">
            <div class="field">
              <label data-tip="容器内 POSIX 路径；留空由控制器生成 /app/lab-snapshots/gc-<instance>.log。">
                日志路径 <span class="key">gcLogPath</span>
              </label>
              <input
                v-model="form.gcLogPath"
                class="input"
                placeholder="/app/lab-snapshots/gc-app-1.log"
                :data-testid="`instance-config-field-${instance}-gcLogPath`"
              />
              <div class="help">只允许 /app/lab-snapshots 下的文件路径。</div>
            </div>
            <div class="field">
              <label data-tip="滚动前单文件大小（MB）；JDK21 映射为 filesize，JDK8 映射为 GCLogFileSize。">
                单文件上限 <span class="key">gcLogFileSizeMb</span>
              </label>
              <input v-model="form.gcLogFileSizeMb" class="input" :data-testid="`instance-config-field-${instance}-gcLogFileSizeMb`" />
              <div class="help">MB；留空使用 JVM 默认。</div>
            </div>
            <div class="field">
              <label data-tip="滚动保留文件数；JDK21 映射为 filecount，JDK8 映射为 NumberOfGCLogFiles。">
                保留文件数 <span class="key">gcLogFileCount</span>
              </label>
              <input v-model="form.gcLogFileCount" class="input" :data-testid="`instance-config-field-${instance}-gcLogFileCount`" />
              <div class="help">滚动保留数量。</div>
            </div>
          </template>
          <div class="field">
            <label data-tip="OOM 时写 heap dump；路径同样限制在 /app/lab-snapshots 下。">
              OOM 堆转储 <span class="key">heapDumpOnOutOfMemory</span>
            </label>
            <select
              v-model="form.heapDumpOnOutOfMemory"
              class="input select"
              :data-testid="`instance-config-field-${instance}-heapDumpOnOutOfMemory`"
              @change="onHeapDumpChange"
            >
              <option value="">未设置</option>
              <option value="true">开启</option>
              <option value="false">关闭</option>
            </select>
            <div class="help">开启后生成 -XX:+HeapDumpOnOutOfMemoryError；路径留空时服务端默认 <code>/app/lab-snapshots/heap-{{ instance }}.hprof</code>。</div>
          </div>
          <div v-if="form.heapDumpOnOutOfMemory === 'true'" class="field">
            <label data-tip="留空由控制器生成 /app/lab-snapshots/heap-<instance>.hprof。">
              堆转储路径 <span class="key">heapDumpPath</span>
            </label>
            <input
              v-model="form.heapDumpPath"
              class="input"
              :placeholder="`/app/lab-snapshots/heap-${instance}.hprof（留空自动）`"
              :data-testid="`instance-config-field-${instance}-heapDumpPath`"
            />
            <div class="help">OOM 转储文件路径；开启时若为空，这里会自动填默认位置。</div>
          </div>
        </div>
      </section>
    </fieldset>

    <div v-if="warnings.length" class="notice warn" :data-testid="`instance-config-warnings-${instance}`">
      <strong>提示</strong>
      <ul>
        <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
      </ul>
    </div>

    <div class="config-actions">
      <button
        type="button"
        class="btn ghost"
        :disabled="!canValidate"
        :data-testid="`instance-config-validate-${instance}`"
        @click="runValidate"
      >校验</button>
      <button
        type="button"
        class="btn ghost"
        :disabled="!canSave"
        :data-testid="`instance-config-save-${instance}`"
        @click="runSave"
      >仅保存</button>
      <button
        type="button"
        class="btn"
        :disabled="!canApply"
        :title="rules.jdk8Shell ? 'JDK8 外壳走同一受控重建：池回读 SKIPPED、指标用 seckill-app-jdk8 job、GC 日志按轮转命名' : (jdk8Rejected ? 'app 实例拒绝 jdk8' : '重建容器并使用已保存的配置')"
        :data-testid="`instance-config-apply-${instance}`"
        @click="ask('apply')"
      >应用并重启</button>
      <button
        type="button"
        class="btn ghost"
        :disabled="!canRollback"
        :title="rules.jdk8Shell ? 'JDK8 外壳走同一受控重建（使用上一版配置）' : '使用上一版配置重建容器'"
        :data-testid="`instance-config-rollback-${instance}`"
        @click="ask('rollback')"
      >回滚</button>
      <button
        type="button"
        class="btn ghost"
        :disabled="!canRestart"
        :title="canRestart ? '以当前已应用（APPLIED）配置重启容器，不改变配置' : '仅在配置已 APPLIED 时可重启；草稿请用「应用并重启」'"
        :data-testid="`instance-config-restart-${instance}`"
        @click="ask('restart')"
      >重启 JVM</button>
      <span v-if="rules.jdk8Shell" class="hint">JDK8 外壳实例：LIMITED-OBSERVABILITY（池回读 SKIPPED，GC 日志轮转命名）；应用/回滚与其他实例走同一受控重建。</span>
      <span v-else-if="!view?.config" class="hint">先「仅保存」为 DRAFT，才能应用。</span>
      <span v-else-if="status !== 'APPLIED'" class="hint">「重启 JVM」仅在配置已 APPLIED 时可用；当前为 {{ statusText }}。</span>
    </div>

    <div v-if="validation" class="validation" :data-testid="`instance-config-validation-${instance}`">
      <span class="badge" :class="validation.valid ? 'ok' : 'down'">{{ validation.valid ? '校验通过' : '校验未通过' }}</span>
      <span class="chip" :class="{ down: validation.requiredMemoryMb > validation.memoryLimitMb }">
        预算 {{ validation.requiredMemoryMb }} / {{ validation.memoryLimitMb }} MB
      </span>
      <span class="chip">{{ validation.limitedObservability ? 'LIMITED-OBSERVABILITY' : 'FULL-OBSERVABILITY' }}</span>
      <div class="argv-preview" :data-testid="`instance-config-argv-${instance}`">
        <code v-for="(arg, index) in validation.jvmArgs" :key="`${arg}-${index}`">{{ arg }}</code>
      </div>
    </div>

    <div v-if="lastApply" class="apply-result" :data-testid="`instance-apply-result-${instance}`">
      <div class="rowline">
        <span class="badge" :class="STATUS_TONES[lastApply.status] || 'muted'" :data-testid="`instance-apply-status-${instance}`">
          {{ lastApply.status }} {{ STATUS_LABELS[lastApply.status] || '' }}
        </span>
        <span class="chip" :data-testid="`instance-apply-rollback-${instance}`">
          回滚：{{ ROLLBACK_LABELS[lastApply.rollback] || lastApply.rollback }}
        </span>
        <span v-if="lastApply.stableUntil" class="chip warn">稳定期至 {{ clockText(lastApply.stableUntil) }}</span>
        <span v-if="lastApply.revision" class="chip">revision {{ lastApply.revision }}</span>
        <span v-if="lastApply.durationMs" class="chip">耗时 {{ lastApply.durationMs }}ms</span>
      </div>
      <p v-if="lastApply.error" class="hint">{{ lastApply.error }}</p>
      <ul class="check-list">
        <li
          v-for="(check, index) in lastApply.checks || []"
          :key="`${check.name}-${index}`"
          :data-testid="`instance-apply-check-${instance}-${index}`"
        >
          <span class="badge" :class="checkTone(check.status)">{{ check.status }}</span>
          <code>{{ check.name }}</code>
          <span class="check-detail">{{ check.detail }}</span>
          <span class="check-duration">{{ check.durationMs }}ms</span>
        </li>
      </ul>
    </div>

    <InstanceGcLogViewer :token="token" :instance="instance" :disabled="actionsLocked" />

    <section class="config-events" :data-testid="`instance-events-${instance}`">
      <div class="subhead">诊断事件（OOM / 堆转储）</div>
      <p class="hint">
        扫描有界容器日志中的 <code>OutOfMemoryError</code> / <code>Heap dump file created</code> /
        <code>Dumping heap</code>；无匹配显示 NO_DATA，日志不可读显示 UNAVAILABLE，不造事件。
      </p>
      <button type="button" class="btn ghost" :disabled="eventsLoading" :data-testid="`instance-events-load-${instance}`" @click="loadEvents">
        {{ eventsLoading ? '读取中…' : '读取事件' }}
      </button>
      <p v-if="eventsError" class="notice danger" role="alert" :data-testid="`instance-events-error-${instance}`">{{ eventsError }}</p>
      <p v-else-if="events && !events.events?.length" class="hint" :data-testid="`instance-events-empty-${instance}`">
        {{ events.note === 'UNAVAILABLE' ? '容器日志不可读（UNAVAILABLE）。' : '窗口内没有 OOM / 堆转储事件（NO_DATA）。' }}
      </p>
      <ul v-else-if="events" class="config-notes" :data-testid="`instance-events-list-${instance}`">
        <li v-for="(item, index) in events.events" :key="index">
          <span class="badge warn">{{ item.kind }}</span>
          <code>{{ item.observed }}</code> {{ item.line }}
        </li>
      </ul>
    </section>

    <div v-if="confirmAction" class="modal-backdrop" role="dialog" aria-modal="true" :data-testid="`instance-apply-modal-${instance}`">
      <div class="modal-card">
        <h3>{{ confirmAction === 'apply' ? '应用并重启' : confirmAction === 'restart' ? '重启 JVM' : '回滚' }} · {{ instance }}</h3>
        <p>
          将对 <code>jl-{{ instance }}</code> 执行受控重建：停止并重建容器（约 40–120s），
          期间流量可能短暂抖动；健康检查、Nginx 上游、Prometheus up 与镜像核对全部通过后才算完成。
          {{ confirmAction === 'apply'
            ? '将使用已保存的配置。'
            : confirmAction === 'restart'
              ? '将以当前已应用（APPLIED）的配置重启；草稿修改不会生效。'
              : '将使用上一版配置（previous_config）。' }}
        </p>
        <div class="rowline">
          <button
            type="button"
            class="btn"
            :data-testid="`instance-apply-confirm-${instance}`"
            @click="confirmActionRun"
          >{{ confirmAction === 'apply' ? '确认应用并重启' : confirmAction === 'restart' ? '确认重启' : '确认回滚' }}</button>
          <button
            type="button"
            class="btn ghost"
            :data-testid="`instance-apply-cancel-${instance}`"
            @click="confirmAction = ''"
          >取消</button>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.instance-config-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.instance-config-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.instance-config-head h3 {
  margin: 0;
  font-size: 0.94rem;
}
.instance-sub {
  margin: 3px 0 0;
  color: var(--text-muted);
  font-size: 0.74rem;
}
.config-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 10px;
}
.config-fact {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--panel-border);
  border-radius: 10px;
  background: #0a1119;
}
.fact-value {
  font-family: var(--mono);
  font-size: 0.76rem;
  overflow-wrap: anywhere;
}
.fact-value.over-budget {
  color: var(--red);
}
.fact-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
}
.config-fieldset {
  border: 0;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.config-group {
  border: 1px solid var(--panel-border);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--bg-soft);
}
.subhead {
  font-size: 0.8rem;
  color: var(--text-muted);
  letter-spacing: 0.06em;
  margin-bottom: 8px;
}
.config-extra-args {
  margin-top: 10px;
}
.config-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  position: sticky;
  bottom: 0;
  z-index: 5;
  padding: 10px 12px;
  margin: 4px -4px 0;
  border-top: 1px solid var(--panel-border);
  background: rgba(13, 20, 27, 0.94);
  backdrop-filter: blur(4px);
}
.config-notes {
  margin: 0;
  padding-left: 18px;
}
.validation {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--panel-border);
  border-radius: 10px;
  background: var(--bg-soft);
}
.argv-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  width: 100%;
}
.argv-preview code {
  font-family: var(--mono);
  font-size: 0.72rem;
  padding: 2px 6px;
  border: 1px solid var(--panel-border);
  border-radius: 4px;
  background: #070c12;
}
.apply-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--panel-border);
  border-radius: 10px;
  background: var(--bg-soft);
}
.check-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.check-list li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  font-size: 0.78rem;
}
.check-list code {
  font-family: var(--mono);
}
.check-detail {
  color: var(--text-muted);
  overflow-wrap: anywhere;
}
.check-duration {
  color: var(--text-muted);
  font-family: var(--mono);
  margin-left: auto;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(3, 7, 12, 0.72);
}
.modal-card {
  max-width: 560px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  border: 1px solid var(--panel-border);
  border-radius: 14px;
  background: var(--panel);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.45);
}
.modal-card h3 {
  margin: 0;
  font-size: 1rem;
}
.modal-card p {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.84rem;
  line-height: 1.55;
}
</style>
