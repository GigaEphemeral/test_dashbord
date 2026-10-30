<script setup>
import { computed, ref, watch } from 'vue'
import CacheABPanel from './CacheABPanel.vue'
import CacheLab from './CacheLab.vue'
import ScenarioWizard from './ScenarioWizard.vue'
import ConfigGroupEditor from './ConfigGroupEditor.vue'
import K6ParamsForm from './K6ParamsForm.vue'
import FaultTimelineEditor from './FaultTimelineEditor.vue'
import { createRun, describeError, fetchRunPrecheck, startRun } from '../api'

/**
 * B3-00c experiment wizard (design §16.2, plan §16.2–§16.9).
 *
 * Two tabs share the page: the new 7-step experiment wizard (steps 0..7 match
 * the confirmed mockup) and the legacy 故障演练 runner, embedded unchanged so
 * no existing capability or testid disappears.
 *
 * Gate model: node selection drives every later step; goals, traffic targets,
 * config rows and fault targets follow the same enabled set. The backend
 * pre-check remains the only authority on start.
 */
const props = defineProps({
  token: { type: String, default: '' },
  scenarios: { type: Array, default: () => [] },
  state: { type: Object, default: null },
  busy: { type: String, default: '' },
  containers: { type: Array, default: () => [] },
  latestRun: { type: Object, default: null },
  /** Monitor cards deep-link to step 3; nonce makes repeated clicks re-apply. */
  focusStep: { type: Number, default: 0 },
  focusInstance: { type: String, default: '' },
  focusNonce: { type: Number, default: 0 }
})
const emit = defineEmits(['start', 'step', 'skip', 'pause', 'resume', 'reset', 'refresh', 'started', 'navigate'])

const STEPS = [
  { num: '前置', title: '基础配置' },
  { num: '01', title: '实验目标' },
  { num: '02', title: '目标与流量' },
  { num: '03', title: '变量与配置组' },
  { num: '04', title: '流量与执行配置' },
  { num: '05', title: '故障时间线' },
  { num: '06', title: '预检查与执行' },
  { num: '07', title: '报告与对比' }
]

const GOALS = [
  { key: 'throughput', label: '吞吐基线', desc: '基线 RPS / P95 / 错误率，作为所有对照的参照。' },
  { key: 'penetration', label: '缓存穿透', desc: '越界 ID + 平均/正态分布，观察空值缓存与布隆过滤。' },
  { key: 'cacheab', label: '缓存 A/B', desc: 'L1+L2 vs 仅 L2；关闭 L1 时指标显示不适用。' },
  { key: 'jvm', label: 'JVM 对照', desc: '基线组 vs 实验组：堆 / GC / 线程池 / Caffeine。' },
  { key: 'instance', label: '实例故障', desc: '定向 app-N 与实例摘除，需 ≥2 个 Java 节点。' },
  { key: 'database', label: '数据库切换', desc: 'Router / 主节点切换，需 MySQL 集群。' },
  { key: 'redis', label: 'Redis 切换', desc: 'Sentinel 晋升，需 Redis 高可用。' },
  { key: 'network', label: '网络故障', desc: 'chaos-proxy 延迟/断网，需启用代理层。' }
]

const tab = ref('experiment')
const step = ref(0)
const nodeNotice = ref('')
const showSidecars = ref(false)

watch(
  () => [props.focusStep, props.focusNonce],
  ([target]) => {
    const index = Number(target)
    if (Number.isFinite(index) && index >= 0) step.value = Math.min(7, index)
  },
  { immediate: true }
)

// JDK 8 is a separate Boot 2.7 shell, never one of the app slots: app-1..3 run
// the Boot 3 / JDK 21 application (cache lab, toggles, thread lab). The shell can
// still be configured (heap/GC/flags) and rebuilt for the JDK comparison.
const jdk8Container = computed(() => props.containers
  .find((container) => container.name === 'jl-seckill-app-jdk8') || null)
const jdk8Enabled = ref(true)

const JAVA_CONTAINER = /^jl-app-\d+$/
const SENTINEL_CONTAINER = /^jl-sentinel-\d+$/

function nodeNumber(name) {
  const match = /^app-(\d+)$/.exec(name)
  return match ? Number(match[1]) : 999
}

function sortNodes(list) {
  return [...new Set(list)].sort((a, b) => nodeNumber(a) - nodeNumber(b))
}

const javaContainers = computed(() =>
  props.containers
    .filter((container) => JAVA_CONTAINER.test(container.name))
    .map((container) => ({ node: container.name.replace(/^jl-/, ''), container: container.name }))
    .sort((a, b) => nodeNumber(a.node) - nodeNumber(b.node))
)

const javaNodes = computed(() => {
  const detected = javaContainers.value.map((item) => item.node)
  if (detected.length) return detected
  // Nothing loaded yet: keep the lab's configured trio so the form stays usable.
  return props.containers.length ? [] : ['app-1', 'app-2', 'app-3']
})

const selectedNodes = ref([])
const knownNodes = ref([])
const nodesSeeded = ref(false)
watch(
  javaNodes,
  (list) => {
    if (!list.length) {
      selectedNodes.value = []
      return
    }
    if (!nodesSeeded.value) {
      selectedNodes.value = [...list]
      knownNodes.value = [...list]
      nodesSeeded.value = true
      return
    }
    const kept = selectedNodes.value.filter((node) => list.includes(node))
    for (const node of list) {
      if (!knownNodes.value.includes(node)) kept.push(node)
    }
    knownNodes.value = sortNodes([...knownNodes.value, ...list])
    selectedNodes.value = kept.length ? sortNodes(kept) : [list[0]]
  },
  { immediate: true }
)

function containerOf(node) {
  const hit = javaContainers.value.find((item) => item.node === node)
  return hit ? hit.container : node
}

function toggleNode(node) {
  nodeNotice.value = ''
  if (selectedNodes.value.includes(node)) {
    if (selectedNodes.value.length === 1) {
      nodeNotice.value = '至少保留 1 个 Java 节点。'
      // Re-assign so the checkbox snaps back: its :checked binding did not change.
      selectedNodes.value = [...selectedNodes.value]
      return
    }
    selectedNodes.value = selectedNodes.value.filter((item) => item !== node)
    return
  }
  selectedNodes.value = sortNodes([...selectedNodes.value, node])
}

const mysqlMode = ref('cluster')
const redisHa = ref(true)
const chaosProxy = ref(false)
const gatesSeeded = ref(false)
watch(
  () => props.containers,
  (list) => {
    if (gatesSeeded.value || !list.length) return
    mysqlMode.value = list.some((container) => container.name === 'jl-mysql-router') ? 'cluster' : 'single'
    redisHa.value = list.some((container) => SENTINEL_CONTAINER.test(container.name))
    chaosProxy.value = list.some((container) => container.name === 'jl-toxiproxy')
    gatesSeeded.value = true
  },
  { immediate: true }
)

const instanceGate = computed(() => selectedNodes.value.length >= 2)
const gateSummary = computed(() => {
  const env = props.containers.length ? '' : '（未检测到容器清单，形态按默认显示）'
  return `当前：${selectedNodes.value.length} 个 Java 节点、MySQL ${mysqlMode.value === 'cluster' ? '集群（Router + 3 节点）' : '单机'}、`
    + `Redis ${redisHa.value ? '高可用' : '未启用高可用'}、chaos-proxy ${chaosProxy.value ? '已启用' : '未启用'}${env}。`
})

const goals = ref(['throughput'])
const locks = computed(() => ({
  instance: instanceGate.value ? '' : '需 ≥2 个 Java 节点',
  database: mysqlMode.value === 'cluster' ? '' : '需 MySQL 集群（Router + 3 节点）',
  redis: redisHa.value ? '' : '需 Redis 高可用（Sentinel）',
  network: chaosProxy.value ? '' : '需启用 chaos-proxy'
}))

function goalLocked(key) {
  return Boolean(locks.value[key])
}

function toggleGoal(key) {
  if (goalLocked(key)) return
  if (goals.value.includes(key)) {
    goals.value = goals.value.filter((item) => item !== key)
    if (!goals.value.length) goals.value = ['throughput']
    return
  }
  goals.value = [...goals.value, key]
}

watch(
  locks,
  (next) => {
    const kept = goals.value.filter((key) => !next[key])
    goals.value = kept.length ? kept : ['throughput']
  },
  { deep: true }
)

const goalSummary = computed(() => {
  const labels = goals.value.map((key) => GOALS.find((goal) => goal.key === key)?.label || key)
  return labels.join(' + ')
})
const pairMode = computed(() => goals.value.includes('jvm'))

const trafficMode = ref('via-nginx')
const targetInstance = ref('')
watch(
  selectedNodes,
  (list) => {
    if (trafficMode.value === 'target-instance' && !list.includes(targetInstance.value)) {
      targetInstance.value = list[0] || ''
    }
  },
  { immediate: true }
)
watch(trafficMode, (mode) => {
  if (mode === 'target-instance') {
    if (!selectedNodes.value.includes(targetInstance.value)) targetInstance.value = selectedNodes.value[0] || ''
  } else {
    targetInstance.value = ''
  }
})

const weightSummary = computed(() =>
  selectedNodes.value.length ? selectedNodes.value.map((node) => `${node}=1`).join(' · ') : '无已启用节点'
)

const config = ref({})
const scenario = ref('load')
const params = ref({})
const faultRows = ref([])

const paramsForSend = computed(() => {
  const effective = Object.fromEntries(
    Object.entries(params.value || {}).filter(([, value]) => value !== '' && value != null)
  )
  effective.TRAFFIC_MODE = trafficMode.value
  if (trafficMode.value === 'target-instance' && targetInstance.value) {
    effective.TARGET_INSTANCE = targetInstance.value
  }
  return effective
})

const faultSchedule = computed(() =>
  faultRows.value.map((row) => ({
    atSeconds: Number.isFinite(Number(row.atSeconds)) ? Math.max(0, Number(row.atSeconds)) : 0,
    target: row.target,
    action: row.action,
    recovery: row.recovery,
    ttlSeconds: row.recovery === 'manual' ? -1 : Math.max(1, Number(row.ttlSeconds) || 120)
  }))
)

const appFaultTargets = computed(() =>
  selectedNodes.value.map((node) => ({ value: containerOf(node), label: node }))
)
const storageFaultTargets = computed(() =>
  props.containers
    .filter((container) => /^jl-(redis|mysql)(-\d+)?$/.test(container.name))
    .map((container) => ({ value: container.name, label: container.name.replace(/^jl-/, '') }))
)

const name = ref('')
const draftRun = ref(null)
const precheck = ref(null)
const creating = ref(false)
const prechecking = ref(false)
const starting = ref(false)
const startedRunId = ref('')
const formError = ref('')
const startConflict = ref(null)

function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `lab-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function createDraft() {
  if (creating.value) return
  if (!props.token) {
    formError.value = '缺少访问令牌：请在页头填入 X-Lab-Token。'
    return
  }
  if (faultRows.value.some((row) => !row.target)) {
    formError.value = '故障时间线存在未选择目标的行：请补全目标或删除该行。'
    return
  }
  creating.value = true
  formError.value = ''
  startConflict.value = null
  startedRunId.value = ''
  try {
    const run = await createRun(props.token, {
      scenario: scenario.value,
      params: paramsForSend.value,
      name: name.value.trim() || undefined,
      nodes: selectedNodes.value,
      // JVM config is instance-level; the controller snapshots the selected
      // nodes' stored configs when the run is created (no browser race).
      faultSchedule: faultSchedule.value
    })
    draftRun.value = run
    precheck.value = null
    await runPrecheck()
  } catch (err) {
    draftRun.value = null
    precheck.value = null
    formError.value = `创建草稿失败：${describeError(err)}`
  } finally {
    creating.value = false
  }
}

async function runPrecheck() {
  if (!draftRun.value?.runId || prechecking.value) return
  prechecking.value = true
  formError.value = ''
  try {
    precheck.value = await fetchRunPrecheck(props.token, draftRun.value.runId)
  } catch (err) {
    precheck.value = null
    formError.value = `预检查失败：${describeError(err)}`
  } finally {
    prechecking.value = false
  }
}

const draftStatus = computed(() => draftRun.value?.status || '')
const canStart = computed(() =>
  Boolean(draftRun.value?.runId)
  && draftStatus.value === 'DRAFT'
  && (precheck.value?.verdict || '') !== 'BLOCKED'
  && !startedRunId.value
  && !starting.value
)

const statusLabel = computed(() => {
  if (startedRunId.value) return '已启动'
  if (draftRun.value?.status) return draftRun.value.status
  return '未创建'
})

function statusClass() {
  const status = statusLabel.value
  if (status === 'RUNNING') return 'running'
  if (status === '已启动') return 'ok'
  if (status === 'DRAFT') return 'idle'
  if (['COMPLETED'].includes(status)) return 'completed'
  if (['FAILED', 'INVALID', 'ABORTED'].includes(status)) return 'failed'
  return 'muted'
}

async function startExperiment() {
  if (!canStart.value || !draftRun.value) return
  starting.value = true
  formError.value = ''
  startConflict.value = null
  try {
    const { status } = await startRun(props.token, draftRun.value.runId, newIdempotencyKey())
    if (status === 200 || status === 202) {
      startedRunId.value = draftRun.value.runId
      draftRun.value = { ...draftRun.value, status: status === 200 ? 'RUNNING' : 'PENDING_CONFIRMATION' }
      emit('started', draftRun.value.runId)
    }
  } catch (err) {
    if (err?.status === 409) {
      startConflict.value = {
        code: err.code || '',
        message: err.message || '',
        report: err.data || null
      }
    } else {
      formError.value = `启动失败：${describeError(err)}`
    }
  } finally {
    starting.value = false
  }
}

async function step6Action() {
  if (startedRunId.value) {
    emit('navigate', 'observe')
    return
  }
  if (!draftRun.value) {
    await createDraft()
    return
  }
  if (!precheck.value || precheck.value.verdict === 'BLOCKED') {
    await runPrecheck()
    return
  }
  await startExperiment()
}

const step6Label = computed(() => {
  if (startedRunId.value) return '去实时监控 →'
  if (!draftRun.value) return creating.value ? '预检并创建草稿…' : '预检并创建草稿'
  if (!precheck.value) return prechecking.value ? '预检中…' : '运行预检查'
  if (precheck.value.verdict === 'BLOCKED') return '预检查阻断 · 重新预检'
  return starting.value ? '启动中…' : '启动实验'
})

const step6Disabled = computed(() => (
  !startedRunId.value
  && (creating.value || prechecking.value || starting.value || !scenario.value)
))

const CONFLICT_TEXT = {
  '40901': '状态冲突（40901）：run 已不是 DRAFT，可能已被启动、停止或结束；请重新创建草稿。',
  '40902': '环境锁被占用（40902）：另一个 run 持有写锁；先停止它或等待其结束。',
  '40903': '预检查阻断（40903）：run 保持 DRAFT，修复阻断项后可原键重试。',
  '40904': 'k6 容器冲突（40904）：同名压测容器仍在；先停止旧 run。'
}

const conflictText = computed(() => {
  const code = startConflict.value?.code
  if (code && CONFLICT_TEXT[code]) return CONFLICT_TEXT[code]
  return startConflict.value?.message || '启动被拒绝。'
})

function evidenceText(detail) {
  if (!detail) return ''
  if (typeof detail === 'string') return detail
  try {
    return Object.entries(detail)
      .map(([key, value]) => {
        if (value == null) return `${key}=—`
        if (Array.isArray(value)) {
          return `${key}=${value.map((item) => (typeof item === 'object' ? JSON.stringify(item) : item)).join(',')}`
        }
        if (typeof value === 'object') return `${key}=${JSON.stringify(value)}`
        return `${key}=${value}`
      })
      .join(' · ')
  } catch {
    return String(detail)
  }
}

function itemClass(status) {
  if (status === 'PASS') return 'ok'
  if (status === 'WARN') return 'warn'
  return 'down'
}

function verdictClass(verdict) {
  if (verdict === 'PASS') return 'ok'
  if (verdict === 'WARN') return 'warn'
  return 'down'
}

const TERMINAL = ['COMPLETED', 'FAILED', 'INVALID', 'ABORTED', 'UNKNOWN']
const reportRun = computed(() =>
  props.latestRun && TERMINAL.includes(props.latestRun.status) ? props.latestRun : null
)

const reportDuration = computed(() => {
  const run = reportRun.value
  if (!run) return '—'
  const start = Date.parse(run.startedAt || run.createdAt || '')
  const end = Date.parse(run.endedAt || run.updatedAt || '')
  if (!Number.isFinite(start)) return '—'
  const millis = Math.max(0, (Number.isFinite(end) ? end : Date.now()) - start)
  const seconds = Math.round(millis / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  return `${minutes}m ${seconds % 60}s`
})

function goStep(index) {
  step.value = Math.min(7, Math.max(0, index))
}
</script>

<template>
  <div class="wizard-page" data-testid="wizard-page">
    <nav class="tabs" aria-label="场景向导">
      <button
        type="button"
        class="tab"
        :class="{ active: tab === 'experiment' }"
        data-testid="wizard-tab-experiment"
        :aria-current="tab === 'experiment' ? 'page' : undefined"
        @click="tab = 'experiment'"
      >实验向导</button>
      <button
        type="button"
        class="tab"
        :class="{ active: tab === 'guide' }"
        data-testid="wizard-tab-guide"
        :aria-current="tab === 'guide' ? 'page' : undefined"
        @click="tab = 'guide'"
      >故障演练</button>
    </nav>

    <template v-if="tab === 'experiment'">
      <header class="wizard-head">
        <div>
          <h2>实验向导</h2>
          <p class="hint">前置基础配置 + 7 步：目标与流量分离，k6 是统一流量触发层，故障按时间线注入。</p>
        </div>
        <span class="pill" :class="statusClass()" data-testid="wizard-status">{{ statusLabel }}</span>
      </header>

      <div class="stepper" data-testid="wizard-stepper">
        <button
          v-for="(item, index) in STEPS"
          :key="index"
          type="button"
          class="stepper-step"
          :class="{ current: step === index, done: step > index }"
          :data-testid="`wizard-step-${index}`"
          @click="goStep(index)"
        >
          <span class="step-num">{{ item.num }}</span> {{ item.title }}
        </button>
      </div>

      <!-- 0 基础配置 -->
      <section v-if="step === 0" class="card wizard-panel" data-testid="wizard-panel-0">
        <h3>基础配置</h3>
        <p class="hint">
          与总览页共用同一组开关；这里只确认环境形态，不改变容器。节点未启用时，拓扑保持完整但置灰。
        </p>
        <div class="notice info" data-testid="wizard-gate-notice">{{ gateSummary }}</div>

        <div class="base-grid">
          <div class="field">
            <label data-tip="Java 节点可 1/2/3 个；少于 2 个时实例分流/实例故障禁用，定向实例仍可用。">Java 节点</label>
            <label v-for="node in javaNodes" :key="node" class="check-row">
              <input
                type="checkbox"
                :checked="selectedNodes.includes(node)"
                :data-testid="`wizard-node-${node}`"
                @change="toggleNode(node)"
              />
              <code>{{ node }}</code>
              <span class="hint">（{{ containerOf(node) }}）</span>
            </label>
            <p v-if="!javaNodes.length" class="hint">容器清单里没有 <code>jl-app-*</code> 实例。</p>
          </div>

          <div class="field">
            <label data-tip="MySQL 只分单机与 Router 三节点；单机时数据库切换/故障禁用。">MySQL 形态</label>
            <label class="check-row">
              <input v-model="mysqlMode" type="radio" value="single" data-testid="wizard-mysql-single" />
              单机
            </label>
            <label class="check-row">
              <input v-model="mysqlMode" type="radio" value="cluster" data-testid="wizard-mysql-cluster" />
              Router + 3 节点
            </label>
            <label class="check-row">
              <input v-model="redisHa" type="checkbox" data-testid="wizard-redis-ha" />
              Redis 高可用（Sentinel）
            </label>
            <label class="check-row">
              <input v-model="chaosProxy" type="checkbox" data-testid="wizard-chaos-proxy" />
              网络故障层（chaos-proxy）
            </label>
            <label class="check-row">
              <input v-model="showSidecars" type="checkbox" data-testid="wizard-sidecars" />
              显示 exporter / sidecar（仅拓扑展示）
            </label>
            <p class="hint">关闭后，相关实验目标、目标实例与故障目标会在后续步骤置灰，并显示原因。</p>
          </div>
        </div>

        <p v-if="nodeNotice" class="notice warn" data-testid="wizard-node-notice">{{ nodeNotice }}</p>

        <ul class="gate-list">
          <li v-if="!instanceGate" class="notice warn">实例分流/实例故障不可用：当前少于 2 个 Java 节点；定向实例仍可用。</li>
          <li v-if="mysqlMode !== 'cluster'" class="notice warn">数据库切换/故障不可用：MySQL 为单机形态。</li>
          <li v-if="!redisHa" class="notice warn">Redis 切换不可用：Redis 高可用未启用。</li>
          <li v-if="!chaosProxy" class="notice warn">网络故障不可用：chaos-proxy 未启用。</li>
          <li v-if="showSidecars" class="notice info">exporter / sidecar 只在拓扑显示，不参与实验与故障目标。</li>
        </ul>

        <div class="jdk8-gate" data-testid="wizard-jdk8-gate">
          <label class="check-row">
            <input v-model="jdk8Enabled" type="checkbox" data-testid="wizard-jdk8-shell" />
            在「变量与配置组」中配置 JDK8 对照外壳
          </label>
          <p class="hint">
            app-1/2/3 固定 JDK 21：它们是带缓存实验室、开关和线程实验室的 Boot 3 应用，不能替换为 JDK 8 镜像。
            JDK 8 是独立的 <code>seckill-app-jdk8</code> 外壳（Boot 2.7，只覆盖读路径 / Actuator / 快照），
            不加入本次 run 的分流节点，只用于堆/GC/启动耗时的对照。
          </p>
          <p class="hint">
            外壳状态：
            <span
              class="badge"
              :class="jdk8Container?.state === 'running' ? 'ok' : 'muted'"
              data-testid="wizard-jdk8-state"
            >{{ jdk8Container ? `${jdk8Container.state} · ${jdk8Container.health}` : '未运行（dual-jdk profile）' }}</span>
            配置/应用/重启仍可在第 3 步完成；容器未运行时应用会失败并提示。
          </p>
        </div>
      </section>

      <!-- 1 实验目标 -->
      <section v-if="step === 1" class="card wizard-panel" data-testid="wizard-panel-1">
        <div class="panel-head">
          <h3>实验目标</h3>
          <span class="hint">可多选；k6 流量在下一步统一配置</span>
        </div>
        <div class="goal-grid">
          <button
            v-for="goal in GOALS"
            :key="goal.key"
            type="button"
            class="goal-card"
            :class="{ selected: goals.includes(goal.key), locked: goalLocked(goal.key) }"
            :disabled="goalLocked(goal.key)"
            :data-testid="`wizard-goal-${goal.key}`"
            @click="toggleGoal(goal.key)"
          >
            <h4>{{ goal.label }}</h4>
            <p>{{ goal.desc }}</p>
            <div v-if="goalLocked(goal.key)" class="lock">{{ locks[goal.key] }}</div>
          </button>
        </div>
        <p class="hint">已选目标：<strong>{{ goalSummary }}</strong>。目标决定后续变量/故障时间线的默认项，但 k6 流量配置始终存在。</p>
      </section>

      <!-- 2 目标与流量 -->
      <section v-if="step === 2" class="card wizard-panel" data-testid="wizard-panel-2">
        <h3>目标与流量路径</h3>
        <div class="field-row">
          <div class="field">
            <label data-tip="经 Nginx 用于整体演示与分流观察；定向实例绕过 Nginx，用于实例级对照。">
              流量模式 <span class="key">TRAFFIC_MODE</span>
            </label>
            <select v-model="trafficMode" class="input select" data-testid="wizard-traffic-mode">
              <option value="via-nginx">经 Nginx（upstream 分流）</option>
              <option value="target-instance">定向实例（绕过 Nginx）</option>
            </select>
            <div class="help">经 Nginx 用于整体演示与分流观察；定向用于实例级对照。</div>
          </div>
          <div class="field">
            <label data-tip="只列出已启用的 Java 节点；定向模式必须选一个。">目标实例 <span class="key">TARGET_INSTANCE</span></label>
            <select
              v-model="targetInstance"
              class="input select"
              data-testid="wizard-target-instance"
              :disabled="trafficMode !== 'target-instance'"
            >
              <option v-if="trafficMode !== 'target-instance'" value="">全部（经 Nginx）</option>
              <option v-for="node in selectedNodes" :key="node" :value="node">{{ node }}</option>
            </select>
            <div class="help">只列出已启用的 Java 节点；定向模式必须选一个。</div>
          </div>
        </div>
        <div class="notice info" style="margin-top: 10px">
          upstream 权重：<strong>{{ weightSummary }}</strong>（等权）。定向模式绕过 Nginx，权重不参与。
        </div>
        <div class="notice warn" style="margin-top: 8px">
          实验限制：经 Nginx 的请求仍会到达全部 upstream 节点，无法只打某一台；需要精确隔离时请选「定向实例」。
        </div>
        <div v-if="!instanceGate" class="notice warn" style="margin-top: 8px">
          当前少于 2 个 Java 节点：实例分流/实例故障不可用；定向仍可用。
        </div>
        <div v-if="!selectedNodes.length" class="notice danger" style="margin-top: 8px">
          没有启用的 Java 节点：定向模式无法选择目标，请回到基础配置启用节点。
        </div>
      </section>

      <!-- 3 变量与配置组 -->
      <section v-show="step === 3" class="card wizard-panel" data-testid="wizard-panel-3">
        <h3>变量与配置组</h3>
        <ConfigGroupEditor
          v-model:config="config"
          :token="token"
          :nodes="selectedNodes"
          :all-nodes="javaNodes"
          :pair-mode="pairMode"
          :show-jdk8="jdk8Enabled"
          :jdk8-running="Boolean(jdk8Container && jdk8Container.state === 'running')"
          :focus-instance="focusInstance"
          :focus-nonce="focusNonce"
        />

        <div class="cache-setup">
          <div class="panel-head">
            <h3>缓存与防御策略（按实例，实时生效）</h3>
            <span class="hint">写入所选 JVM 实例的运行时状态，不需要重启</span>
          </div>
          <p class="hint">
            防御模式、L1 开关与预热都只作用于当前选中的实例：同一份代码下可让一个节点无防护、另一个开布隆或空值缓存，
            用于受保护/未保护实例的对照。切换会立刻改变该实例的缓存路径，指标随之变化。
          </p>
          <CacheLab :token="token" :active="tab === 'experiment' && step === 3" />
        </div>
      </section>

      <!-- 4 流量与执行配置 -->
      <section v-show="step === 4" class="card wizard-panel" data-testid="wizard-panel-4">
        <h3>流量与执行配置</h3>
        <K6ParamsForm
          v-model:scenario="scenario"
          v-model:params="params"
          :token="token"
          :preview-params="paramsForSend"
        />
      </section>

      <!-- 5 故障时间线 -->
      <section v-if="step === 5" class="card wizard-panel" data-testid="wizard-panel-5">
        <h3>故障时间线（可选）</h3>
        <FaultTimelineEditor
          v-model="faultRows"
          :app-targets="appFaultTargets"
          :storage-targets="storageFaultTargets"
          :enabled-node-count="selectedNodes.length"
        />
      </section>

      <!-- 6 预检查与执行 -->
      <section v-if="step === 6" class="card wizard-panel" data-testid="wizard-panel-6">
        <h3>预检查与执行</h3>
        <div class="field-row">
          <div class="field">
            <label data-tip="测试名称用于实验记录展示/筛选与报告标题；runId 仍是不可变主键。">测试名称 <span class="key">name</span></label>
            <input v-model="name" class="input" data-testid="wizard-name" placeholder="例如：缓存穿透基线" />
            <div class="help">可留空，台账按 runId 展示。</div>
          </div>
          <div class="field">
            <label>Run ID</label>
            <input class="input" :value="draftRun?.runId || '创建草稿后生成'" disabled />
            <div class="help">启动成功后自动跳转「实时监控与操作」。</div>
          </div>
        </div>

        <div class="runline">
          <span class="chip" :class="statusClass()" data-testid="wizard-run-status">{{ statusLabel }}</span>
          <span class="hint">场景 <code>{{ scenario }}</code> · 节点 {{ selectedNodes.join(', ') || '—' }}</span>
          <span v-if="precheck" class="chip" :class="verdictClass(precheck.verdict)">预检查 {{ precheck.verdict }}</span>
        </div>

        <p class="hint" style="margin-top: 10px">
          使用右下角的「预检/启动实验」：第一次点击创建草稿并预检查，通过后再点击启动；
          启动成功后自动跳转实时监控。启动使用一次性幂等键，本页不会重复发起。
        </p>

        <p v-if="formError" class="notice danger" role="alert" data-testid="wizard-form-error">{{ formError }}</p>

        <div data-testid="wizard-precheck" class="precheck-block">
          <div class="rowline">
            <button
              type="button"
              class="btn ghost"
              data-testid="wizard-precheck-refresh"
              :disabled="!draftRun?.runId || prechecking || creating"
              @click="runPrecheck"
            >{{ prechecking ? '预检查中…' : '重新预检查' }}</button>
            <span v-if="draftRun" class="hint">草稿 {{ draftRun.runId }} · 预检查只读，不改 run 状态。</span>
          </div>
          <div v-if="!draftRun" class="empty">点击右下角的「预检并创建草稿」；预检查报告会逐项列出证据。</div>
          <div v-else-if="!precheck" class="notice info">草稿已创建（{{ draftRun.runId }}），等待预检查结果。</div>
          <template v-else>
            <div class="precheck-head">
              <span class="chip" :class="verdictClass(precheck.verdict)">verdict {{ precheck.verdict }}</span>
              <span class="chip down">blocking {{ (precheck.blocking || []).length }}</span>
              <span class="chip warn">warnings {{ (precheck.warnings || []).length }}</span>
              <span class="chip ok">passed {{ (precheck.passed || []).length }}</span>
              <span class="hint">checkedAt {{ precheck.checkedAt || '—' }} · run {{ precheck.runId || draftRun.runId }}</span>
            </div>
            <div v-if="precheck.verdict === 'BLOCKED'" class="notice danger">
              BLOCKED 不可强制跳过；run 保持 DRAFT。修复环境后重新预检查即可。
            </div>
            <div v-else class="notice warn">
              WARN 可继续；启动成功后自动跳转「实时监控与操作」。
            </div>
            <table class="data-table">
              <thead>
                <tr><th>检查项</th><th>结果</th><th>说明</th><th>证据</th></tr>
              </thead>
              <tbody>
                <tr v-for="item in precheck.items || []" :key="item.key">
                  <td><code>{{ item.key }}</code></td>
                  <td><span class="chip" :class="itemClass(item.status)">{{ item.status }}</span></td>
                  <td>{{ item.message }}</td>
                  <td class="evidence">{{ evidenceText(item.detail) || '—' }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="!(precheck.items || []).length" class="empty">报告没有 item 明细。</div>
          </template>
        </div>

        <div v-if="startConflict" class="notice danger" data-testid="wizard-start-conflict">
          <p><strong>{{ conflictText }}</strong></p>
          <p class="hint">HTTP 409 · code {{ startConflict.code || '—' }} · {{ startConflict.message }}</p>
          <template v-if="startConflict.report?.blocking?.length">
            <div class="rowline">
              <span
                v-for="item in startConflict.report.blocking"
                :key="item.key"
                class="chip down"
              >{{ item.key }}</span>
            </div>
            <ul class="conflict-list">
              <li v-for="item in startConflict.report.blocking" :key="item.key">
                <code>{{ item.key }}</code>：{{ item.message }}
                <span class="hint">{{ evidenceText(item.detail) }}</span>
              </li>
            </ul>
          </template>
        </div>

        <p class="hint" style="margin-top: 10px">
          409 证据：40901 状态冲突 / 40902 环境锁 / 40903 预检查阻断 / 40904 k6 容器冲突；阻断时 run 保持 DRAFT。
        </p>
      </section>

      <!-- 7 报告与对比 -->
      <section v-if="step === 7" class="card wizard-panel" data-testid="wizard-panel-7">
        <h3>报告与对比</h3>
        <template v-if="reportRun">
          <div class="kv-grid">
            <div class="kv-row"><dt>测试名称</dt><dd>{{ reportRun.name || reportRun.runId }}</dd></div>
            <div class="kv-row"><dt>状态</dt><dd>{{ reportRun.status }}</dd></div>
            <div class="kv-row"><dt>时长</dt><dd>{{ reportDuration }}</dd></div>
            <div class="kv-row"><dt>场景</dt><dd>{{ reportRun.scenario || '—' }}</dd></div>
          </div>
          <p class="hint">
            k6 报告（summary.json）与逐项指标在「实验记录」页下载；单次结果，未重复验证。
          </p>
          <div class="rowline" style="margin-top: 8px">
            <button type="button" class="btn" data-testid="wizard-report" @click="emit('navigate', 'runs')">打开实验记录并下载报告</button>
            <span class="chip" data-tip="单次 run 只代表一次执行；B5 提供 N 次中位数/离散程度。">演示性结果</span>
          </div>
        </template>
        <template v-else>
          <div class="empty">最新 run 还未进入终态：{{ latestRun?.status || '没有 run' }}。实验结束后回到本步查看报告。</div>
          <div class="rowline" style="margin-top: 8px">
            <button type="button" class="btn" data-testid="wizard-report" disabled>打开实验记录并下载报告</button>
            <span class="chip">单次结果，未重复验证</span>
          </div>
        </template>
        <p class="notice info" style="margin-top: 10px">单次结果，未重复验证；B5 提供重复实验的中位数与离散程度。</p>

        <div class="ab-setup">
          <div class="panel-head">
            <h3>缓存 A/B 公平对照</h3>
            <span class="hint">自动跑 A（L1 开+预热）/ B（L1 关+清空）并对比同一批指标</span>
          </div>
          <CacheABPanel
            :token="token"
            :instances="selectedNodes.length ? selectedNodes : ['app-1', 'app-2', 'app-3']"
            :active="tab === 'experiment' && step === 7"
          />
        </div>
      </section>

      <div class="wizard-nav">
        <button type="button" class="btn ghost" data-testid="wizard-prev" :disabled="step === 0" @click="goStep(step - 1)">上一步</button>
        <div class="rowline">
          <span class="hint">{{ STEPS[step].title }} · {{ step + 1 }}/8</span>
          <template v-if="step === 6">
            <span class="hint" data-testid="wizard-step6-hint">预检不可跳过：BLOCKED 时修复环境后重新预检</span>
            <span data-testid="wizard-step6-cta">
              <button
                type="button"
                class="btn primary"
                :data-testid="startedRunId ? 'wizard-cta-observe' : (!draftRun ? 'wizard-create' : 'wizard-start')"
                :disabled="step6Disabled"
                @click="step6Action"
              >{{ step6Label }}</button>
            </span>
          </template>
          <button v-else type="button" class="btn primary" data-testid="wizard-next" :disabled="step === 7" @click="goStep(step + 1)">下一步</button>
        </div>
      </div>
    </template>

    <ScenarioWizard
      v-else
      :scenarios="scenarios"
      :state="state"
      :busy="busy"
      @start="emit('start', $event)"
      @step="emit('step')"
      @skip="emit('skip')"
      @pause="emit('pause')"
      @resume="emit('resume')"
      @reset="emit('reset')"
      @refresh="emit('refresh')"
    />
  </div>
</template>

<style scoped>
.wizard-page {
  display: flex;
  flex-direction: column;
}
.wizard-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-top: 4px;
}
.wizard-head h2 {
  margin: 0;
  font-size: 18px;
}
.wizard-head .hint {
  margin: 4px 0 0;
}
.step-num {
  opacity: 0.7;
  margin-right: 4px;
}
.wizard-panel {
  padding: 14px;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
}
.base-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin-top: 10px;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  padding: 3px 0;
}
.gate-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lock {
  margin-top: 6px;
  font-size: 11px;
  color: var(--amber);
  border-top: 1px dashed var(--panel-border);
  padding-top: 5px;
}
.wizard-nav {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  gap: 10px;
}
.runline {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.precheck-block {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.precheck-head {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.evidence {
  font-family: var(--mono);
  font-size: 11px;
  word-break: break-all;
}
.conflict-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
}
.chip.ok,
.pill.ok {
  color: var(--accent);
  border-color: rgba(56, 211, 159, 0.5);
}
.chip.warn {
  color: #f3d7a1;
  border-color: rgba(217, 164, 65, 0.5);
}
.chip.down,
.chip.danger {
  color: #f3b3b3;
  border-color: rgba(239, 107, 107, 0.5);
}
.chip.info {
  color: var(--text);
}

.jdk8-gate {
  margin-top: 12px;
  padding: 10px 14px;
  border: 1px solid var(--panel-border);
  border-radius: 12px;
  background: var(--bg-soft);
}

.jdk8-gate .hint {
  margin: 6px 0 0;
}

.cache-setup,
.ab-setup {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}

.cache-setup h3,
.ab-setup h3 {
  margin: 0;
  font-size: 0.95rem;
}

.wizard-nav .rowline {
  flex-wrap: wrap;
  justify-content: flex-end;
}
</style>
