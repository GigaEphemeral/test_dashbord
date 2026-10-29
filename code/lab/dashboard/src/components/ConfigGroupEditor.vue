<script setup>
import { computed, ref, watch } from 'vue'

/**
 * B3-00c per-instance configuration group editor (design §16.4–§16.7).
 *
 * Fields use the confirmed v4 vocabulary (no G1ReservePercent,
 * G1ConcRefinementThreads or ZGC). Applying a startup change needs a controlled
 * restart, which is B4 work: the buttons render, but apply/rollback stay
 * disabled. "仅保存" is the draft state the run records. Disabled nodes keep a
 * greyed row and never appear in the editor's instance dropdown.
 */
const props = defineProps({
  /** Enabled Java service names (app-1…). */
  nodes: { type: Array, default: () => [] },
  /** Every detected Java node, including disabled ones (greyed rows). */
  allNodes: { type: Array, default: () => [] },
  /** { "<node>": { ...fields } } */
  config: { type: Object, default: () => ({}) },
  /** JVM/GC goal selected: render the baseline/experiment pair. */
  pairMode: { type: Boolean, default: false }
})
const emit = defineEmits(['update:config'])

const DEFAULTS = {
  jdkVersion: '21',
  imageTag: 'jdk21',
  xms: '256m',
  xmx: '512m',
  maxMetaspaceSize: '128m',
  maxDirectMemorySize: '64m',
  extraJvmArgs: '-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heap.hprof',
  gcCollector: 'g1',
  maxGcPauseMillis: '200',
  g1NewSizePercent: '5',
  g1MaxNewSizePercent: '60',
  g1HeapRegionSize: '4m',
  initiatingHeapOccupancyPercent: '45',
  parallelGcThreads: '4',
  newRatio: '2',
  survivorRatio: '8',
  corePoolSize: '16',
  maxPoolSize: '64',
  queueCapacity: '500',
  keepAliveSeconds: '60',
  rejectionPolicy: 'AbortPolicy',
  tomcatMaxThreads: '200',
  tomcatAcceptCount: '100',
  tomcatMinSpareThreads: '10',
  caffeineEnabled: 'on',
  maximumSize: '10000',
  expireAfterWriteSeconds: '60',
  expireAfterAccessSeconds: '30',
  refreshAfterWriteSeconds: '20',
  recordStats: 'on',
  gcLogEnabled: 'on',
  gcLogPath: '/app/logs/gc.log',
  gcLogFileSize: '20m',
  gcLogFileCount: '5',
  heapDumpOnOutOfMemory: 'on',
  heapDumpPath: '/app/logs/heap.hprof'
}

const CAFFEINE_DEFAULTS = {
  maximumSize: '10000',
  expireAfterWriteSeconds: '60',
  expireAfterAccessSeconds: '30',
  refreshAfterWriteSeconds: '20',
  recordStats: 'on'
}

const HEAP_FIELDS = [
  { key: 'xms', label: '初始堆', flag: '-Xms', tip: '启动时申请的最小堆；建议与 -Xmx 接近以减少扩缩。', help: '启动时申请的最小堆。' },
  { key: 'xmx', label: '最大堆', flag: '-Xmx', tip: '堆上限；与容器内存预算一起校验。', help: '堆上限，不能超过容器内存预算。' },
  { key: 'maxMetaspaceSize', label: 'Metaspace 上限', flag: 'MaxMetaspaceSize', tip: '类元数据上限；过大挤压堆外预算。', help: '类元数据上限。' },
  { key: 'maxDirectMemorySize', label: '直接内存上限', flag: 'MaxDirectMemorySize', tip: 'NIO/Netty 等堆外直接内存上限。', help: '堆外直接内存上限。' }
]

const G1_FIELDS = [
  { key: 'maxGcPauseMillis', label: '最大停顿', flag: 'MaxGCPauseMillis', tip: 'G1 目标停顿，非硬保证。', help: 'G1 目标停顿毫秒数。' },
  { key: 'g1NewSizePercent', label: '新代初始占比', flag: 'G1NewSizePercent', tip: 'G1 新代初始比例（%）。', help: 'G1 新代初始占比。' },
  { key: 'g1MaxNewSizePercent', label: '新代最大占比', flag: 'G1MaxNewSizePercent', tip: 'G1 新代上限比例（%）。', help: 'G1 新代最大占比。' },
  { key: 'g1HeapRegionSize', label: 'Region 大小', flag: 'G1HeapRegionSize', tip: 'G1 堆分区大小，通常 1–32m。', help: 'G1 堆分区大小。' },
  { key: 'initiatingHeapOccupancyPercent', label: '并发标记触发', flag: 'InitiatingHeapOccupancyPercent', tip: '堆占用达到该比例启动并发标记。', help: '堆占用达到该比例启动并发标记。' }
]

const PARALLEL_FIELDS = [
  { key: 'parallelGcThreads', label: '并行线程数', flag: 'ParallelGCThreads', tip: 'Parallel GC 工作线程数。', help: 'Parallel GC 工作线程数。' }
]

const COMMON_GC_FIELDS = [
  { key: 'newRatio', label: '新老代比', flag: 'NewRatio', tip: '老年代/新生代比例；Serial 不适用。', help: '老年代/新生代比例。' },
  { key: 'survivorRatio', label: 'Survivor 比', flag: 'SurvivorRatio', tip: 'Eden 与单个 Survivor 的比例；Serial 不适用。', help: 'Eden 与单个 Survivor 的比例。' }
]

const POOL_FIELDS = [
  { key: 'corePoolSize', label: '核心线程', flag: 'corePoolSize', tip: '常驻核心线程数；即使空闲也保留。', help: '常驻核心线程数。' },
  { key: 'maxPoolSize', label: '最大线程', flag: 'maxPoolSize', tip: '峰值线程数；队列满后才会扩到该值。', help: '峰值线程数。' },
  { key: 'queueCapacity', label: '队列长度', flag: 'queueCapacity', tip: '等待队列长度；满后触发拒绝策略。', help: '等待队列长度。' },
  { key: 'keepAliveSeconds', label: '空闲存活（秒）', flag: 'keepAliveSeconds', tip: '非核心线程空闲存活时间。', help: '非核心线程空闲存活秒数。' },
  { key: 'tomcatMaxThreads', label: 'Tomcat 最大线程', flag: 'server.tomcat.threads.max', tip: 'Tomcat 最大工作线程数；请求处理并发上限。', help: '请求处理并发上限。' },
  { key: 'tomcatAcceptCount', label: 'Tomcat 接受队列', flag: 'acceptCount', tip: 'Tomcat 接受队列长度；满后拒绝连接。', help: '连接等待队列；满后拒绝连接。' },
  { key: 'tomcatMinSpareThreads', label: '最小空闲线程', flag: 'minSpareThreads', tip: 'Tomcat 保持的空闲工作线程。', help: '保持的空闲工作线程。' }
]

const CAFFEINE_FIELDS = [
  { key: 'maximumSize', label: '最大条目', flag: 'maximumSize', tip: '本地缓存条目上限；超出按策略淘汰。', help: '本地缓存条目上限。' },
  { key: 'expireAfterWriteSeconds', label: '写入后过期（秒）', flag: 'expireAfterWriteSeconds', tip: '写入后多久过期；0 表示不启用。', help: '按秒配置；0 表示不启用。' },
  { key: 'expireAfterAccessSeconds', label: '访问后过期（秒）', flag: 'expireAfterAccessSeconds', tip: '最后一次访问后多久过期；0 表示不启用。', help: '按秒配置；0 表示不启用。' },
  { key: 'refreshAfterWriteSeconds', label: '写入后刷新（秒）', flag: 'refreshAfterWriteSeconds', tip: '异步刷新间隔，不等于过期；0 表示不启用。', help: '异步刷新间隔；0 表示不启用。' }
]

const GCLOG_FIELDS = [
  { key: 'gcLogPath', label: '日志路径', flag: 'gcLogPath', tip: '路径外置；只读回读。', help: 'GC 日志文件路径。' },
  { key: 'gcLogFileSize', label: '单文件上限', flag: 'gcLogFileSize', tip: '滚动前单文件大小。', help: '滚动前单文件大小。' },
  { key: 'gcLogFileCount', label: '保留文件数', flag: 'gcLogFileCount', tip: '滚动保留数量。', help: '滚动保留数量。' },
  { key: 'heapDumpPath', label: '堆转储路径', flag: 'heapDumpPath', tip: '转储文件路径；注意磁盘预算。', help: 'OOM 转储文件路径。' }
]

const selected = ref(props.nodes[0] || '')
const baselineNode = ref(props.nodes[0] || '')
const experimentGroup = ref('experiment-A')
const configStatus = ref('待重启')

watch(
  () => [props.nodes, props.allNodes],
  () => {
    const enabled = props.nodes || []
    const next = { ...(props.config || {}) }
    let changed = false
    for (const node of enabled) {
      if (!next[node]) {
        next[node] = { ...DEFAULTS }
        changed = true
      }
    }
    if (changed) emit('update:config', next)
    if (!enabled.includes(selected.value)) {
      selected.value = enabled[0] || ''
      configStatus.value = '待重启'
    }
    if (!enabled.includes(baselineNode.value)) baselineNode.value = enabled[0] || ''
  },
  { immediate: true }
)

const rows = computed(() => {
  const list = props.allNodes?.length ? props.allNodes : props.nodes
  return list.map((node) => ({
    node,
    enabled: (props.nodes || []).includes(node),
    container: `jl-${node}`
  }))
})

const conf = computed(() => ({ ...DEFAULTS, ...(props.config?.[selected.value] || {}) }))
const gcCollector = computed(() => conf.value.gcCollector || 'g1')
const caffeineOff = computed(() => conf.value.caffeineEnabled === 'off')
const gcLogOff = computed(() => conf.value.gcLogEnabled === 'off')
const gcLogNote = computed(() =>
  conf.value.jdkVersion === '8'
    ? `JDK8 使用 -Xloggc:${conf.value.gcLogPath}；GC 日志参数受 JDK8 兼容校验。`
    : `JDK21 使用 -Xlog:gc*:file=${conf.value.gcLogPath}；路径外置，只读回读。`
)

function baseOf(node) {
  return { ...DEFAULTS, ...(props.config?.[node] || {}) }
}

function setFields(part) {
  if (!selected.value) return
  configStatus.value = '待重启（未保存）'
  emit('update:config', {
    ...(props.config || {}),
    [selected.value]: { ...baseOf(selected.value), ...part }
  })
}

function setField(key, raw, type = 'text') {
  let value = raw
  if (type === 'number') {
    if (raw === '') {
      value = ''
    } else {
      const parsed = Number(raw)
      if (!Number.isFinite(parsed)) return
      value = parsed
    }
  }
  setFields({ [key]: value })
}

function setJdk(value) {
  setFields({ jdkVersion: value, imageTag: value === '8' ? 'jdk8' : 'jdk21' })
}

function setCaffeineEnabled(value) {
  if (value === 'off') {
    setFields({
      caffeineEnabled: 'off',
      maximumSize: 0,
      expireAfterWriteSeconds: 0,
      expireAfterAccessSeconds: 0,
      refreshAfterWriteSeconds: 0,
      recordStats: 'off'
    })
    return
  }
  setFields({ caffeineEnabled: 'on', ...CAFFEINE_DEFAULTS })
}

function selectNode(node, enabled) {
  if (!enabled) return
  selected.value = node
  configStatus.value = '待重启'
}

function parseMb(value) {
  const match = /^(\d+(?:\.\d+)?)\s*([mg])?$/i.exec(String(value || '').trim())
  if (!match) return null
  const amount = Number(match[1])
  return (match[2] || 'm').toLowerCase() === 'g' ? amount * 1024 : amount
}

const memoryBudget = computed(() => {
  const heap = parseMb(conf.value.xmx)
  const metaspace = parseMb(conf.value.maxMetaspaceSize)
  const direct = parseMb(conf.value.maxDirectMemorySize)
  const stacks = 64
  if (heap == null || metaspace == null || direct == null) {
    return '内存预算：请用 m/g 单位填写 -Xmx、Metaspace 与直接内存。'
  }
  const used = heap + metaspace + direct + stacks
  return `-Xmx ${conf.value.xmx} + Metaspace ${conf.value.maxMetaspaceSize} + 直接内存 ${conf.value.maxDirectMemorySize} + 线程栈 ≈ ${used}m，容器 1024m，余量 ${1024 - used}m。`
})

function diffOf(node) {
  if (!props.pairMode) {
    const item = { ...DEFAULTS, ...(props.config?.[node] || {}) }
    return `-Xmx ${item.xmx} · ${String(item.gcCollector).toUpperCase()} · L1 ${item.caffeineEnabled === 'off' ? '关闭' : '开启'}`
  }
  if (node === baselineNode.value) return '基线组（对照）'
  const base = { ...DEFAULTS, ...(props.config?.[baselineNode.value] || {}) }
  const item = { ...DEFAULTS, ...(props.config?.[node] || {}) }
  const parts = []
  if (base.xmx !== item.xmx) parts.push(`-Xmx ${base.xmx}→${item.xmx}`)
  if (base.gcCollector !== item.gcCollector) parts.push(`GC ${base.gcCollector}→${item.gcCollector}`)
  if (base.caffeineEnabled !== item.caffeineEnabled) parts.push(`L1 ${item.caffeineEnabled === 'off' ? '关闭' : '开启'}`)
  if (base.corePoolSize !== item.corePoolSize) parts.push(`核心线程 ${base.corePoolSize}→${item.corePoolSize}`)
  if (base.queueCapacity !== item.queueCapacity) parts.push(`队列 ${base.queueCapacity}→${item.queueCapacity}`)
  return parts.length ? parts.join(' · ') : '无差异（对照组）'
}
</script>

<template>
  <div class="config-editor" data-testid="wizard-config-editor">
    <div class="editor-head">
      <div>
        <h4>基线组 / 实验组</h4>
        <p class="hint">
          只改变要对照的变量；应用/重启必须在预检查前完成，run 中禁止修改启动参数。
        </p>
      </div>
      <span class="chip info" data-tip="只对基础配置中已启用的节点可配；未启用行置灰且不可选择。">
        已启用 {{ nodes.length }}/{{ rows.length }}
      </span>
    </div>

    <div v-if="pairMode" class="pair-bar">
      <div class="field">
        <label data-tip="基线组取哪个已启用节点；差异列以它为参照。">基线组节点 <span class="key">baseline</span></label>
        <select v-model="baselineNode" class="input select">
          <option v-for="node in nodes" :key="node" :value="node">{{ node }}</option>
        </select>
      </div>
      <div class="field">
        <label data-tip="实验组标签仅用于展示对照关系；实际启动参数仍逐实例保存。">实验组标签 <span class="key">experiment</span></label>
        <select v-model="experimentGroup" class="input select">
          <option value="experiment-A">experiment-A</option>
          <option value="experiment-B">experiment-B</option>
        </select>
      </div>
      <div class="hint">只改变要对照的变量；两个组都取已启用节点，未启用节点不参与。</div>
    </div>

    <table class="data-table config-table">      <thead>
        <tr>
          <th>实例</th>
          <th>状态</th>
          <th v-if="pairMode">组</th>
          <th>差异 / 摘要</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.node" :class="{ disabled: !row.enabled }" :data-testid="`wizard-config-row-${row.node}`">
          <td><code>{{ row.container }}</code></td>
          <td>
            <span v-if="row.enabled" class="chip ok" data-tip="已启用。">已启用</span>
            <span v-else class="chip" data-testid="wizard-config-disabled">{{ '未启用' }}</span>
          </td>
          <td v-if="pairMode">
            <span v-if="!row.enabled" class="muted">—</span>
            <span v-else-if="row.node === baselineNode" class="chip">基线组 baseline</span>
            <span v-else class="chip info">实验组 {{ experimentGroup }}</span>
          </td>
          <td>
            <span v-if="row.enabled" class="diff">{{ diffOf(row.node) }}</span>
            <span v-else class="muted">未启用节点不参与本实验。</span>
          </td>
          <td>
            <button
              type="button"
              class="btn ghost"
              :disabled="!row.enabled"
              :data-testid="`wizard-config-edit-${row.node}`"
              @click="selectNode(row.node, row.enabled)"
            >编辑</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="notice info" style="margin-top: 8px">
      未启用的节点行置灰，且不会出现在下方编辑器的实例下拉框；拓扑仍保留完整结构。
    </div>

    <div class="divider"></div>

    <div v-if="!nodes.length" class="notice warn">当前没有启用的 Java 节点：请先在基础配置中启用至少一个节点。</div>

    <template v-else>
      <div class="field-row">
        <div class="field">
          <label data-tip="只列出已启用节点；未启用节点不能编辑。">编辑实例 <span class="key">instance</span></label>
          <select v-model="selected" class="input select" data-testid="wizard-config-instance">
            <option v-for="node in nodes" :key="node" :value="node">{{ node }}</option>
          </select>
          <div class="help">只列出已启用节点。</div>
        </div>
        <div class="field">
          <label data-tip="应用配置必须在预检查前完成；run 中禁止修改启动参数。">配置状态 <span class="key">status</span></label>
          <div class="rowline">
            <span class="chip warn" data-testid="wizard-config-status">{{ configStatus }}</span>
            <button
              type="button"
              class="btn"
              disabled
              data-tip="B4 落地应用与重启"
              title="B4 落地应用与重启"
              data-testid="wizard-config-apply"
            >应用并重启</button>
            <button type="button" class="btn ghost" data-testid="wizard-config-save" @click="configStatus = '草稿已保存（待 B4 应用）'">仅保存</button>
            <button
              type="button"
              class="btn ghost"
              disabled
              data-tip="B4 落地应用与重启"
              title="B4 落地应用与重启"
              data-testid="wizard-config-rollback"
            >回滚</button>
          </div>
          <div class="help">B4 落地应用与重启：重建/重启 → 健康检查 → Nginx 上游 → Prometheus up → 真实探针。</div>
        </div>
        <div class="field">
          <label data-tip="堆 + Metaspace + 直接内存 + 线程栈应低于容器内存上限。">内存预算 <span class="key">budget</span></label>
          <div class="hint">{{ memoryBudget }}</div>
        </div>
      </div>

      <div class="editor-grid">
        <section class="card tight">
          <div class="subhead">运行时与堆</div>
          <div class="field-row">
            <div class="field">
              <label data-tip="JDK8 外壳标记 LIMITED-OBSERVABILITY；GC 参数受 JDK 兼容校验。">JDK <span class="key">jdkVersion</span></label>
              <select class="input select" data-testid="wizard-config-jdk" :value="conf.jdkVersion" @change="setJdk($event.target.value)">
                <option value="21">21</option>
                <option value="8">8（已验证镜像）</option>
              </select>
              <div class="help">需选择已验证镜像；JDK8 为 LIMITED-OBSERVABILITY。</div>
            </div>
            <div class="field">
              <label data-tip="镜像必须与 JDK 版本一致。">镜像 <span class="key">imageTag</span></label>
              <select class="input select" :value="conf.imageTag" @change="setFields({ imageTag: $event.target.value })">
                <option value="jdk21">jdk21</option>
                <option value="jdk8">jdk8</option>
              </select>
              <div class="help">与 JDK 版本保持一致的已验证镜像。</div>
            </div>
            <div v-for="field in HEAP_FIELDS" :key="field.key" class="field">
              <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
              <input class="input" :value="conf[field.key]" @input="setField(field.key, $event.target.value)" />
              <div class="help">{{ field.help }}</div>
            </div>
          </div>
          <div class="field" style="margin-top: 8px">
            <label data-tip="仅允许白名单参数；拒绝危险诊断/命令执行类选项、任意路径与 shell。">
              额外 JVM 参数 <span class="key">extraJvmArgs</span>
            </label>
            <textarea class="input" rows="2" :value="conf.extraJvmArgs" @input="setField('extraJvmArgs', $event.target.value)"></textarea>
            <div class="help">仅白名单；每行一个参数。</div>
          </div>
        </section>

        <section class="card tight">
          <div class="subhead">GC 与 GC 日志</div>
          <div class="field-row">
            <div class="field">
              <label data-tip="需与 JDK 兼容；JDK8 可选 G1/Parallel；不提供 ZGC。">GC 类型 <span class="key">gcCollector</span></label>
              <select class="input select" data-testid="wizard-config-gc" :value="gcCollector" @change="setFields({ gcCollector: $event.target.value })">
                <option value="g1">G1</option>
                <option value="parallel">Parallel</option>
                <option value="serial">Serial</option>
              </select>
              <div class="help">需与 JDK 兼容；JDK8 可选 G1/Parallel。</div>
            </div>
            <template v-if="gcCollector === 'g1'">
              <div v-for="field in G1_FIELDS" :key="field.key" class="field">
                <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
                <input class="input" :value="conf[field.key]" @input="setField(field.key, $event.target.value)" />
                <div class="help">{{ field.help }}</div>
              </div>
            </template>
            <template v-if="gcCollector === 'parallel'">
              <div v-for="field in PARALLEL_FIELDS" :key="field.key" class="field">
                <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
                <input class="input" :value="conf[field.key]" @input="setField(field.key, $event.target.value)" />
                <div class="help">{{ field.help }}</div>
              </div>
            </template>
            <template v-if="gcCollector !== 'serial'">
              <div v-for="field in COMMON_GC_FIELDS" :key="field.key" class="field">
                <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
                <input class="input" :value="conf[field.key]" @input="setField(field.key, $event.target.value)" />
                <div class="help">{{ field.help }}</div>
              </div>
            </template>
          </div>

          <div class="divider"></div>

          <div class="field-row">
            <div class="field">
              <label data-tip="JDK8 用 -Xloggc；JDK21 用 -Xlog:gc*。">GC 日志 <span class="key">gcLogEnabled</span></label>
              <select class="input select" :value="conf.gcLogEnabled" @change="setFields({ gcLogEnabled: $event.target.value })">
                <option value="on">开启</option>
                <option value="off">关闭</option>
              </select>
              <div class="help">JDK8 用 -Xloggc；JDK21 用 -Xlog:gc*。</div>
            </div>
            <div v-for="field in GCLOG_FIELDS" :key="field.key" class="field">
              <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
              <input
                class="input"
                :value="conf[field.key]"
                :disabled="gcLogOff && field.key !== 'heapDumpPath'"
                @input="setField(field.key, $event.target.value)"
              />
              <div class="help">{{ field.help }}</div>
            </div>
            <div class="field">
              <label data-tip="OOM 时写 heap dump；路径需可写。">OOM 堆转储 <span class="key">heapDumpOnOutOfMemory</span></label>
              <select class="input select" :value="conf.heapDumpOnOutOfMemory" @change="setFields({ heapDumpOnOutOfMemory: $event.target.value })">
                <option value="on">开启</option>
                <option value="off">关闭</option>
              </select>
              <div class="help">OOM 时写 heap dump。</div>
            </div>
          </div>
          <div class="hint" style="margin-top: 6px">{{ gcLogNote }}</div>
        </section>

        <section class="card tight">
          <div class="subhead">业务线程池 / Tomcat 线程</div>
          <div class="field-row">
            <div v-for="field in POOL_FIELDS" :key="field.key" class="field">
              <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
              <input class="input" :value="conf[field.key]" @input="setField(field.key, $event.target.value)" />
              <div class="help">{{ field.help }}</div>
            </div>
            <div class="field">
              <label data-tip="Abort 抛异常；CallerRuns 回压调用线程；Discard 丢弃；DiscardOldest 丢最旧。">拒绝策略 <span class="key">rejectionPolicy</span></label>
              <select class="input select" :value="conf.rejectionPolicy" @change="setFields({ rejectionPolicy: $event.target.value })">
                <option value="AbortPolicy">AbortPolicy</option>
                <option value="CallerRunsPolicy">CallerRunsPolicy</option>
                <option value="DiscardPolicy">DiscardPolicy</option>
                <option value="DiscardOldestPolicy">DiscardOldestPolicy</option>
              </select>
              <div class="help">Abort 抛异常；CallerRuns 回压调用线程；Discard 丢弃。</div>
            </div>
          </div>
        </section>

        <section class="card tight">
          <div class="subhead">Caffeine L1</div>
          <div class="field-row">
            <div class="field">
              <label data-tip="关闭后不再命中本地缓存，并清空条目；命中率/驱逐指标显示不适用。">L1 开关 <span class="key">caffeineEnabled</span></label>
              <select class="input select" data-testid="wizard-config-caffeine" :value="conf.caffeineEnabled" @change="setCaffeineEnabled($event.target.value)">
                <option value="on">开启</option>
                <option value="off">关闭（绕过并清空）</option>
              </select>
              <div class="help">关闭后不再命中本地缓存，并清空条目。</div>
            </div>
            <div v-for="field in CAFFEINE_FIELDS" :key="field.key" class="field">
              <label :data-tip="field.tip">{{ field.label }} <span class="key">{{ field.flag }}</span></label>
              <input
                class="input"
                :value="caffeineOff ? 0 : conf[field.key]"
                :disabled="caffeineOff"
                @input="setField(field.key, $event.target.value)"
              />
              <div class="help">{{ caffeineOff ? '不适用（L1 已关闭）。' : field.help }}</div>
            </div>
            <div class="field">
              <label data-tip="开启命中率/驱逐统计；关闭时指标显示不适用。">统计 <span class="key">recordStats</span></label>
              <select
                class="input select"
                :value="caffeineOff ? 'off' : conf.recordStats"
                :disabled="caffeineOff"
                @change="setFields({ recordStats: $event.target.value })"
              >
                <option value="on">开启</option>
                <option value="off">关闭</option>
              </select>
              <div class="help">{{ caffeineOff ? '不适用（L1 已关闭）。' : '开启命中率/驱逐统计。' }}</div>
            </div>
          </div>
          <div class="hint">
            过期/刷新参数按秒配置，0 表示不启用。关闭 L1 时清空本地条目，命中率/驱逐等指标显示「不适用」，不显示 0；
            目标条目数 vs 实际条目数分开显示，每实例独立。
          </div>
          <div v-if="caffeineOff" class="notice warn" data-testid="wizard-config-caffeine-off">
            L1 已关闭：本地条目已清空，命中率/驱逐指标显示「不适用」（不显示 0）。
          </div>
        </section>
      </div>

      <p class="hint" data-testid="wizard-config-b4-hint">
        B4 落地应用与重启：本轮「应用并重启 / 回滚」仅渲染不可用，「仅保存」写入 run 草稿；完整参数在向导第 3 步。
      </p>
    </template>
  </div>
</template>

<style scoped>
.editor-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.editor-head h4 {
  margin: 0 0 4px;
  font-size: 13px;
}
.config-table tr.disabled {
  opacity: 0.55;
}
.pair-bar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  margin: 8px 0;
}
.pair-bar .field {
  min-width: 160px;
}
.config-table .diff {
  font-family: var(--mono);
  font-size: 12px;
}
.editor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 10px;
  margin-top: 10px;
}
.card.tight {
  padding: 10px;
  background: var(--bg-soft);
}
.subhead {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--text);
}
.chip.ok {
  color: var(--accent);
  border-color: rgba(56, 211, 159, 0.5);
}
.chip.info {
  color: var(--text);
}
.chip.warn {
  color: #f3d7a1;
  border-color: rgba(217, 164, 65, 0.5);
}
</style>
