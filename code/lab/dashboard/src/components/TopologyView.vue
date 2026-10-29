<script setup>
import { computed, ref } from 'vue'
import { classifyContainer, containerTone, instanceName, sortContainers } from '../labModel'

/**
 * B5-01b/c live topology.
 *
 * Primary source: GET /lab/topology (TopologyView receives the snapshot as
 * `topology`). Every contract node is rendered, including NOT_RUNNING and
 * disabled ones, and edges are kept complete but greyed when either side is
 * disabled/absent. Null metric values are always "无数据", never 0.
 *
 * When MonitorPage cannot reach the endpoint it passes no `topology`; the view
 * then falls back to the legacy /containers-based structure and marks itself
 * "拓扑数据降级" instead of rendering a blank page.
 */
const props = defineProps({
  topology: { type: Object, default: null },
  includeSidecars: { type: Boolean, default: false },
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  toxiproxy: { type: Object, default: null },
  /**
   * Service names enabled by the newest run draft (B3-00a). An empty list is
   * the pre-B3 "all configured" behaviour, so every node keeps its normal look.
   */
  enabledNodes: { type: Array, default: () => [] }
})

const contract = computed(() => (
  props.topology && Array.isArray(props.topology.nodes) ? props.topology : null
))

// ---------------------------------------------------------------- heat model
// Defaults are documented here and overridable per build:
//   VITE_LAB_TOPOLOGY_CPU_WARN=60   VITE_LAB_TOPOLOGY_CPU_HIGH=85
//   VITE_LAB_TOPOLOGY_MEM_WARN=70   VITE_LAB_TOPOLOGY_MEM_HIGH=90
//   VITE_LAB_TOPOLOGY_POOL_QUEUE_WARN=60
//   VITE_LAB_TOPOLOGY_POOL_QUEUE_HIGH=90
function envThreshold(key, fallback) {
  const value = Number(import.meta.env[key])
  return Number.isFinite(value) && value > 0 ? value : fallback
}

const THRESHOLDS = {
  cpu: {
    warn: envThreshold('VITE_LAB_TOPOLOGY_CPU_WARN', 60),
    high: envThreshold('VITE_LAB_TOPOLOGY_CPU_HIGH', 85)
  },
  memory: {
    warn: envThreshold('VITE_LAB_TOPOLOGY_MEM_WARN', 70),
    high: envThreshold('VITE_LAB_TOPOLOGY_MEM_HIGH', 90)
  },
  poolQueue: {
    warn: envThreshold('VITE_LAB_TOPOLOGY_POOL_QUEUE_WARN', 60),
    high: envThreshold('VITE_LAB_TOPOLOGY_POOL_QUEUE_HIGH', 90)
  }
}

const HEAT_DIMENSIONS = [
  { id: 'all', label: '综合' },
  { id: 'cpu', label: 'CPU' },
  { id: 'memory', label: '内存' },
  { id: 'threads', label: '线程池' },
  { id: 'network', label: '网络 I/O' }
]
const heatDimension = ref('all')
const SEVERITY_RANK = { ok: 0, warn: 1, high: 2 }

const CONTRACT_TIERS = [
  { id: 'edge', title: '入口', caption: 'Nginx 反向代理 · 宿主 8080' },
  { id: 'java', title: '应用实例', caption: 'app-1..3 · JDK 8 为按需对照' },
  { id: 'redis', title: 'Redis 缓存', caption: '三主三从 · 真实节点' },
  { id: 'sentinel', title: 'Sentinel 哨兵', caption: 'RESP · 无 Prometheus exporter' },
  { id: 'mysql', title: 'MySQL 集群', caption: 'InnoDB Cluster · 节点指标待补采' },
  { id: 'router', title: 'MySQL Router', caption: '读写路由 · 指标未接线' },
  { id: 'mq', title: '消息队列', caption: 'RabbitMQ · 秒杀异步下单' },
  { id: 'proxy', title: '故障注入代理', caption: 'Toxiproxy · 按需启动' }
]

const TIER_LABELS = Object.fromEntries(CONTRACT_TIERS.map((tier) => [tier.id, tier.title]))

// ------------------------------------------------------------- formatting
function isFiniteNumber(value) {
  return value !== null && value !== undefined && Number.isFinite(Number(value))
}

function fmtNumber(value, digits = 0) {
  return isFiniteNumber(value) ? Number(value).toFixed(digits) : '无数据'
}

function fmtPercent(value) {
  return isFiniteNumber(value) ? `${Number(value).toFixed(2)}%` : '无数据'
}

function fmtBytes(value) {
  if (!isFiniteNumber(value)) return '无数据'
  const bytes = Number(value)
  const mb = bytes / (1024 * 1024)
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`
  if (mb >= 1) return `${mb.toFixed(1)} MB`
  return `${(bytes / 1024).toFixed(1)} KB`
}

function fmtUnit(value, unit, digits = 1) {
  return isFiniteNumber(value) ? `${Number(value).toFixed(digits)} ${unit}` : '无数据'
}

function fmtEpochSeconds(value) {
  if (!isFiniteNumber(value)) return '无数据'
  const date = new Date(Number(value) * 1000)
  return Number.isNaN(date.getTime())
    ? '无数据'
    : date.toLocaleString('zh-CN', { hour12: false })
}

function memoryPercent(node) {
  const metrics = node?.metrics || {}
  if (isFiniteNumber(metrics.heapUsedBytes) && Number(metrics.heapLimitBytes) > 0) {
    return (Number(metrics.heapUsedBytes) / Number(metrics.heapLimitBytes)) * 100
  }
  if (isFiniteNumber(metrics.memUsedBytes) && Number(metrics.memLimitBytes) > 0) {
    return (Number(metrics.memUsedBytes) / Number(metrics.memLimitBytes)) * 100
  }
  return null
}

function severityOf(value, threshold) {
  if (!isFiniteNumber(value)) return null
  if (Number(value) >= threshold.high) return 'high'
  if (Number(value) >= threshold.warn) return 'warn'
  return 'ok'
}

function contractHeat(node) {
  if (!contract.value) return 'unwired'
  if (heatDimension.value === 'network') return 'unwired'
  const metrics = node.metrics || {}
  let values
  if (heatDimension.value === 'cpu') {
    values = [severityOf(metrics.cpuPercent, THRESHOLDS.cpu)]
  } else if (heatDimension.value === 'memory') {
    values = [severityOf(memoryPercent(node), THRESHOLDS.memory)]
  } else if (heatDimension.value === 'threads') {
    values = [severityOf(metrics.poolQueue, THRESHOLDS.poolQueue)]
  } else {
    values = [
      severityOf(metrics.cpuPercent, THRESHOLDS.cpu),
      severityOf(memoryPercent(node), THRESHOLDS.memory),
      severityOf(metrics.poolQueue, THRESHOLDS.poolQueue)
    ]
  }
  const present = values.filter(Boolean)
  if (!present.length) return 'unwired'
  return present.sort((a, b) => SEVERITY_RANK[b] - SEVERITY_RANK[a])[0]
}

function heatLabel(node) {
  const metrics = node.metrics || {}
  if (heatDimension.value === 'network') return '网络 I/O 无数据/未接线'
  if (heatDimension.value === 'cpu') return `CPU ${fmtPercent(metrics.cpuPercent)}`
  if (heatDimension.value === 'memory') {
    const percent = memoryPercent(node)
    return `内存 ${percent === null ? '无数据' : `${percent.toFixed(1)}%`}`
  }
  if (heatDimension.value === 'threads') {
    return `线程池队列 ${fmtNumber(metrics.poolQueue, 0)}`
  }
  return `CPU ${fmtPercent(metrics.cpuPercent)} · 内存 ${memoryPercent(node) === null ? '无数据' : `${memoryPercent(node).toFixed(1)}%`} · 队列 ${fmtNumber(metrics.poolQueue, 0)}`
}

// -------------------------------------------------------------- contract state
const enabledSet = computed(() => new Set(
  (props.enabledNodes || [])
    .map((value) => String(value || '').trim().replace(/^jl-/, ''))
    .filter(Boolean)
))

const visibleNodes = computed(() => {
  if (!contract.value) return []
  return contract.value.nodes.filter((node) => !node.hidden || props.includeSidecars)
})

const nodeById = computed(() => new Map(
  (contract.value?.nodes || []).map((node) => [node.id, node])
))

/** A contract node is disabled when `enabled=false`; an empty enabled set keeps apps on. */
function isNodeDisabled(node) {
  if (!node) return true
  if (node.enabled) return false
  if (node.tier === 'java' && enabledSet.value.size === 0) return false
  return true
}

const contractUp = computed(() => visibleNodes.value.filter((node) => node.health === 'UP').length)
const contractDisabled = computed(() => visibleNodes.value.filter((node) => isNodeDisabled(node)).length)
const contractTiers = computed(() => CONTRACT_TIERS
  .map((tier) => ({
    ...tier,
    nodes: visibleNodes.value.filter((node) => node.tier === tier.id)
  }))
  .filter((tier) => tier.nodes.length))

const contractEdges = computed(() => (contract.value?.edges || []).map((edge, index) => ({
  ...edge,
  key: `${edge.from}-${edge.to}-${edge.kind}-${index}`
})))

function edgeMuted(edge) {
  const from = nodeById.value.get(edge.from)
  const to = nodeById.value.get(edge.to)
  if (!from || !to) return true
  if (!props.includeSidecars && (from.hidden || to.hidden)) return true
  return isNodeDisabled(from) || isNodeDisabled(to)
}

const activeEdgeCount = computed(() => contractEdges.value.filter((edge) => !edgeMuted(edge)).length)

function healthClass(health) {
  if (health === 'UP') return 'ok'
  if (health === 'DOWN') return 'down'
  if (health === 'UNKNOWN') return 'warn'
  return 'muted'
}

function qualityClass(quality) {
  return `dq-${String(quality || 'unknown').toLowerCase().replace(/_/g, '-')}`
}

// ------------------------------------------------------------------ detail panel
const detailId = ref('')
const detailNode = computed(() => visibleNodes.value.find((node) => node.id === detailId.value) || null)

function openDetail(node) {
  detailId.value = node.id
}

function closeDetail() {
  detailId.value = ''
}

const detailFields = computed(() => {
  const node = detailNode.value
  if (!node) return []
  const metrics = node.metrics || {}
  return [
    { label: '实例', value: node.id },
    { label: '服务', value: node.service || '无数据' },
    { label: '层级', value: TIER_LABELS[node.tier] || node.tier || '无数据' },
    { label: '健康', value: node.health || '无数据' },
    { label: 'JDK', value: node.jdkVersion || '无数据' },
    { label: '镜像', value: node.imageTag || '无数据' },
    { label: 'CPU', value: fmtPercent(metrics.cpuPercent) },
    { label: '堆内存 used/limit', value: `${fmtBytes(metrics.heapUsedBytes)} / ${fmtBytes(metrics.heapLimitBytes)}` },
    { label: '容器内存 used/limit', value: `${fmtBytes(metrics.memUsedBytes)} / ${fmtBytes(metrics.memLimitBytes)}` },
    { label: '线程数', value: fmtNumber(metrics.threads, 0) },
    { label: '业务线程池 active/max', value: `${fmtNumber(metrics.activeThreads, 0)} / ${fmtNumber(metrics.poolMax, 0)}` },
    { label: '线程池 core/queue/rejected', value: `${fmtNumber(metrics.poolCore, 0)} / ${fmtNumber(metrics.poolQueue, 0)} / ${fmtNumber(metrics.poolRejected, 0)}` },
    { label: '请求', value: fmtUnit(metrics.requestsPerSecond, 'req/s', 2) },
    { label: '平均响应', value: fmtUnit(metrics.avgResponseMs, 'ms', 1) },
    { label: 'P95', value: fmtUnit(metrics.p95Ms, 'ms', 1) },
    { label: '网络 I/O', value: '无数据/未接线' },
    { label: '更新时间', value: fmtEpochSeconds(metrics.updatedAtEpochSeconds) },
    { label: '数据质量', value: node.dataQuality || '无数据' }
  ]
})

// ------------------------------------------------------------- legacy fallback
const nodes = computed(() =>
  sortContainers(props.containers).map((container) => ({
    ...container,
    ...classifyContainer(container.name)
  }))
)

const running = computed(() => nodes.value.filter((node) => node.state === 'running').length)

const faultsByTarget = computed(() => {
  const map = new Map()
  for (const fault of props.faults || []) {
    const list = map.get(fault.target) || []
    list.push(fault)
    map.set(fault.target, list)
  }
  return map
})

function faultsOf(name) {
  return faultsByTarget.value.get(name) || []
}

function isDisabled(node) {
  if (!enabledSet.value.size) return false
  const ENABLED_ROLES = new Set(['app', 'app-jdk8', 'redis', 'mysql'])
  if (!ENABLED_ROLES.has(node.role)) return false
  return !enabledSet.value.has(instanceName(node.name))
}

function byGroup(group) {
  return nodes.value.filter((node) => node.group === group)
}

function group(id, title, caption, groupIds) {
  const list = groupIds.flatMap((groupId) => byGroup(groupId))
  return list.length ? { id, title, caption, nodes: list } : null
}

const cacheCaption = computed(() => {
  const redis = byGroup('cache').filter((node) => node.role === 'redis')
  const sentinels = byGroup('cache').filter((node) => node.role === 'sentinel')
  if (!redis.length) return ''
  const cluster = redis.filter((node) => /^jl-redis-\d+$/.test(node.name))
  if (cluster.length) {
    const sentinelPart = sentinels.length ? ` + ${sentinels.length} 个哨兵` : ''
    const devPart = redis.length > cluster.length ? ' · dev 单机仍在运行' : ''
    return `${cluster.length} 个 Redis 节点${sentinelPart}${devPart}`
  }
  return 'dev 单机 Redis'
})

const dbCaption = computed(() => {
  const mysql = byGroup('db').filter((node) => node.role === 'mysql')
  const router = byGroup('db').filter((node) => node.role === 'mysql-router')
  if (!mysql.length) return ''
  const cluster = mysql.filter((node) => /^jl-mysql-\d+$/.test(node.name))
  if (cluster.length) {
    const routerPart = router.length ? ' + Router' : ''
    const devPart = mysql.length > cluster.length ? ' · dev 单机仍在运行' : ''
    return `${cluster.length} 个集群节点${routerPart}${devPart}`
  }
  return 'dev 单机 MySQL'
})

const mainTiers = computed(() =>
  [
    {
      id: 'edge',
      title: '入口',
      groups: [group('edge', '网关', '宿主 8080 端口', ['edge'])],
      spine: '「/api/」轮询 · max_fails=2 · fail_timeout=5s'
    },
    {
      id: 'app',
      title: '应用实例',
      groups: [group('app', '应用', 'app-1..3 轮询处理 · JDK 8 为按需对照', ['app'])],
      spine: 'L2 / 锁 / 幂等 → 缓存 · 读写池 / Router → 数据库 · 发布/消费 → MQ'
    },
    {
      id: 'data',
      title: '数据与中间件',
      groups: [
        group('cache', '缓存', cacheCaption.value, ['cache']),
        group('db', '数据库', dbCaption.value, ['db']),
        group('mq', '消息', '秒杀异步下单', ['mq'])
      ],
      spine: null
    }
  ].filter((tier) => tier.groups.some(Boolean))
)

const supportGroups = computed(() =>
  [
    group('obs', '可观测性', '抓取 /actuator/prometheus 每 5s · /prom/ 查询代理', ['obs']),
    group('control', '实验控制', 'chaos API · Docker API · Toxiproxy 代理', ['control']),
    group('load', '负载', '按需启停', ['load']),
    group('other', '未分类', '名称不在已知前缀里，原样展示', ['other'])
  ].filter(Boolean)
)

const toxiproxyInfo = computed(() => {
  const state = props.toxiproxy
  if (!state || !Array.isArray(state.proxies)) return ''
  const present = state.proxies.filter((proxy) => proxy.present)
  const enabled = present.filter((proxy) => proxy.enabled).length
  const toxics = present.reduce((total, proxy) => total + (proxy.toxics?.length || 0), 0)
  return `代理 ${present.length}/${state.allowedProxies?.length ?? present.length} · 启用 ${enabled} · 毒化 ${toxics}`
})

function tone(node) {
  return containerTone(node)
}

function stateClass(state) {
  return state === 'running' ? 'ok' : 'muted'
}

function containerHealthClass(health) {
  if (health === 'healthy') return 'ok'
  if (health === 'unhealthy') return 'down'
  if (health === 'starting') return 'warn'
  return 'muted'
}

function localTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <section class="topo-page" data-testid="topology-page">
    <!-- Primary contract view (B5-01b) -->
    <template v-if="contract">
      <div class="topo-summary">
        <span class="topo-stat"><strong>{{ visibleNodes.length }}</strong> 个节点</span>
        <span class="topo-stat"><strong>{{ contractUp }}</strong> 健康 UP</span>
        <span class="topo-stat"><strong>{{ contractDisabled }}</strong> 未启用</span>
        <span class="topo-stat"><strong>{{ activeEdgeCount }}/{{ contractEdges.length }}</strong> 活动依赖边</span>
        <span class="topo-stat" :class="{ warn: faults.length }"><strong>{{ faults.length }}</strong> 个活动故障</span>
        <label class="topo-controls">
          热度维度
          <select v-model="heatDimension" data-testid="topology-heat-select" aria-label="节点负载热度维度">
            <option v-for="item in HEAT_DIMENSIONS" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
        </label>
        <span class="topo-legend" data-testid="topology-legend">
          <span class="legend-item"><i class="legend-dot heat-ok"></i>正常</span>
          <span class="legend-item"><i class="legend-dot heat-warn"></i>接近阈值</span>
          <span class="legend-item"><i class="legend-dot heat-high"></i>超过阈值</span>
          <span class="legend-item"><i class="legend-dot heat-unwired"></i>无数据/未接线</span>
          <span class="legend-item legend-thresholds">
            CPU ≥ {{ THRESHOLDS.cpu.warn }}/{{ THRESHOLDS.cpu.high }}% ·
            内存 ≥ {{ THRESHOLDS.memory.warn }}/{{ THRESHOLDS.memory.high }}% ·
            队列 ≥ {{ THRESHOLDS.poolQueue.warn }}/{{ THRESHOLDS.poolQueue.high }}
          </span>
        </span>
      </div>

      <p class="hint topo-source">
        节点、连线与指标来自 <code>GET /lab-api/lab/topology</code>（5s 服务端缓存）；
        <code>null</code> 指标显示为“无数据”，不会填 0。dataQuality 徽标是数据事实：
        <code>OK / PARTIAL / NO_DATA / UNWIRED</code>。网络 I/O 当前无采集源，热度保持中性。
      </p>
      <p v-if="contract.enabledNodes?.length" class="hint" data-testid="topology-enabled-note">
        当前 run 启用节点：{{ contract.enabledNodes.join('、') }}；其余可切换节点灰显为“未启用”，但不从拓扑中删除。
      </p>

      <div v-if="heatDimension === 'network'" class="notice" data-testid="topology-network-note">
        网络 I/O 无数据/未接线：当前契约没有每节点网卡收发速率，节点保持中性，不伪造 0。
      </div>

      <div v-for="tier in contractTiers" :key="tier.id" class="topo-tier" :data-testid="`topology-tier-${tier.id}`">
        <div class="topo-tier-head">
          <h3>{{ tier.title }}</h3>
          <span class="hint">{{ tier.caption }}</span>
        </div>
        <div class="topo-node-grid">
          <article
            v-for="node in tier.nodes"
            :key="node.id"
            class="topo-node"
            :class="[`heat-${contractHeat(node)}`, { 'has-fault': faultsOf(node.id).length, disabled: isNodeDisabled(node) }]"
            :data-testid="`topology-node-${node.id}`"
            :data-heat="contractHeat(node)"
            :data-health="node.health"
            tabindex="0"
            @click="openDetail(node)"
            @mouseenter="openDetail(node)"
            @focus="openDetail(node)"
            @keydown.enter="openDetail(node)"
          >
            <div class="topo-node-head">
              <span class="topo-dot" aria-hidden="true"></span>
              <code class="topo-name">{{ node.id }}</code>
              <span v-if="node.hidden" class="badge muted">sidecar</span>
            </div>
            <div class="topo-node-meta">
              <span class="topo-role">{{ node.label }}</span>
              <span class="badge" :class="healthClass(node.health)" :data-testid="`topology-health-${node.id}`">{{ node.health }}</span>
              <span class="badge" :class="qualityClass(node.dataQuality)" :data-testid="`topology-quality-${node.id}`">{{ node.dataQuality }}</span>
              <span v-if="node.jdkVersion" class="badge muted">JDK {{ node.jdkVersion }}</span>
              <span v-if="isNodeDisabled(node)" class="badge muted" :data-testid="`topology-disabled-${node.id}`">未启用</span>
            </div>
            <div class="topo-heat-line" :class="`heat-text-${contractHeat(node)}`">{{ heatLabel(node) }}</div>
            <div v-if="faultsOf(node.id).length" class="topo-faults" :data-testid="`topology-faults-${node.id}`">
              <span
                v-for="fault in faultsOf(node.id)"
                :key="fault.id"
                class="badge warn"
                :data-action="fault.action"
                :title="`${fault.action} · ${fault.status}${fault.expiresAt ? ` · 到期 ${localTime(fault.expiresAt)}` : ''}`"
              >{{ fault.action }}</span>
            </div>
            <p
              v-if="node.notes?.length && node.dataQuality !== 'OK'"
              class="topo-extra"
              :title="node.notes.join('；')"
              :data-testid="`topology-note-${node.id}`"
            >{{ node.notes[0] }}</p>
            <p v-else-if="node.id === 'jl-toxiproxy' && toxiproxyInfo" class="topo-extra">{{ toxiproxyInfo }}</p>
          </article>
        </div>
      </div>

      <section class="topo-edges" data-testid="topology-edges">
        <div class="topo-tier-head">
          <h3>依赖边</h3>
          <span class="hint">共 {{ contractEdges.length }} 条 · 任一端未启用/缺失时灰显，结构不裁剪</span>
        </div>
        <div class="topo-edge-grid">
          <span
            v-for="edge in contractEdges"
            :key="edge.key"
            class="topo-edge"
            :class="{ muted: edgeMuted(edge) }"
            :data-testid="`topology-edge-${edge.from}-${edge.to}`"
            :data-state="edgeMuted(edge) ? 'muted' : 'active'"
          >
            <code>{{ edge.from }}</code>
            <span aria-hidden="true">→</span>
            <code>{{ edge.to }}</code>
            <span class="badge muted">{{ edge.kind }}</span>
          </span>
        </div>
      </section>

      <aside
        v-if="detailNode"
        class="topo-tooltip"
        role="dialog"
        aria-label="拓扑节点详情"
        data-testid="topology-tooltip"
      >
        <header class="topo-tooltip-head">
          <strong>{{ detailNode.id }}</strong>
          <span class="badge" :class="healthClass(detailNode.health)">{{ detailNode.health }}</span>
          <button
            type="button"
            class="btn ghost topo-tooltip-close"
            data-testid="topology-tooltip-close"
            aria-label="关闭节点详情"
            @click="closeDetail"
          >×</button>
        </header>
        <dl class="topo-tooltip-grid">
          <template v-for="field in detailFields" :key="field.label">
            <dt>{{ field.label }}</dt>
            <dd>{{ field.value }}</dd>
          </template>
        </dl>
        <p v-if="detailNode.notes?.length" class="topo-tooltip-notes">
          备注：{{ detailNode.notes.join('；') }}
        </p>
        <p v-if="faultsOf(detailNode.id).length" class="topo-tooltip-notes">
          活动故障：{{ faultsOf(detailNode.id).map((fault) => `${fault.action}(${fault.status})`).join('、') }}
        </p>
      </aside>
    </template>

    <!-- Fallback only: /lab/topology unavailable, keep the /containers view -->
    <template v-else>
      <div class="error-banner" role="alert" data-testid="topology-degraded">
        拓扑数据降级：<code>GET /lab-api/lab/topology</code> 暂不可用，节点来自
        <code>GET /lab-api/containers</code>（无热度指标，缺失容器不画占位）。
      </div>

      <div class="topo-summary">
        <span class="topo-stat"><strong>{{ nodes.length }}</strong> 个容器</span>
        <span class="topo-stat"><strong>{{ running }}</strong> 运行中</span>
        <span class="topo-stat" :class="{ warn: faults.length }"><strong>{{ faults.length }}</strong> 个活动故障</span>
        <label class="topo-controls">
          热度维度
          <select v-model="heatDimension" data-testid="topology-heat-select" aria-label="节点负载热度维度">
            <option v-for="item in HEAT_DIMENSIONS" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
        </label>
        <span class="topo-legend" data-testid="topology-legend">
          <span v-if="enabledNodes.length" class="legend-item" data-testid="topology-enabled-note">
            <span class="badge muted">未启用</span>&nbsp;不在当前 run 的启用节点内
          </span>
          <span class="legend-item"><i class="legend-dot ok"></i>运行且健康</span>
          <span class="legend-item"><i class="legend-dot warn"></i>启动中</span>
          <span class="legend-item"><i class="legend-dot down"></i>不健康</span>
          <span class="legend-item"><i class="legend-dot idle"></i>已停止 / 按需</span>
          <span class="legend-item"><span class="badge warn">action</span>故障徽标</span>
          <span class="legend-item legend-thresholds">
            热度阈值 CPU ≥ {{ THRESHOLDS.cpu.warn }}/{{ THRESHOLDS.cpu.high }}% ·
            内存 ≥ {{ THRESHOLDS.memory.warn }}/{{ THRESHOLDS.memory.high }}% ·
            队列 ≥ {{ THRESHOLDS.poolQueue.warn }}/{{ THRESHOLDS.poolQueue.high }}
          </span>
        </span>
      </div>

      <p class="hint topo-source">
        降级模式下节点来自 <code>GET /lab-api/containers</code>，没有热度指标；
        没有出现的组件就是没有运行，不画占位节点。<code>health=none</code> 表示该容器没有 healthcheck，
        不代表故障。
      </p>

      <div v-if="!nodes.length" class="empty" data-testid="topology-empty">
        没有容器数据：先确认访问令牌（在任意分区顶部的输入框填写）与 jl-chaos-controller 是否在线。
      </div>

      <template v-else>
        <div v-for="tier in mainTiers" :key="tier.id" class="topo-tier">
          <div class="topo-tier-head">
            <h3>{{ tier.title }}</h3>
          </div>
          <div class="topo-groups">
            <div v-for="groupItem in tier.groups.filter(Boolean)" :key="groupItem.id" class="topo-group">
              <div class="topo-group-head">
                <span class="topo-group-title">{{ groupItem.title }}</span>
                <span v-if="groupItem.caption" class="topo-group-caption">{{ groupItem.caption }}</span>
              </div>
              <div class="topo-node-grid">
                <article
                  v-for="node in groupItem.nodes"
                  :key="node.name"
                  class="topo-node"
                  :class="[`tone-${tone(node)}`, { 'has-fault': faultsOf(node.name).length, disabled: isDisabled(node) }]"
                  :data-testid="`topology-node-${node.name}`"
                  :data-heat="'unwired'"
                >
                  <div class="topo-node-head">
                    <span class="topo-dot" aria-hidden="true"></span>
                    <code class="topo-name">{{ node.name }}</code>
                  </div>
                  <div class="topo-node-meta">
                    <span class="topo-role">{{ node.label }}</span>
                    <span class="badge" :class="stateClass(node.state)">{{ node.state }}</span>
                    <span class="badge" :class="containerHealthClass(node.health)">{{ node.health }}</span>
                    <span v-if="isDisabled(node)" class="badge muted" :data-testid="`topology-disabled-${node.name}`">未启用</span>
                  </div>
                  <div v-if="faultsOf(node.name).length" class="topo-faults" :data-testid="`topology-faults-${node.name}`">
                    <span
                      v-for="fault in faultsOf(node.name)"
                      :key="fault.id"
                      class="badge warn"
                      :data-action="fault.action"
                      :title="`${fault.action} · ${fault.status}${fault.expiresAt ? ` · 到期 ${localTime(fault.expiresAt)}` : ''}`"
                    >{{ fault.action }}</span>
                  </div>
                  <p v-if="node.name === 'jl-toxiproxy' && toxiproxyInfo" class="topo-extra">{{ toxiproxyInfo }}</p>
                </article>
              </div>
            </div>
          </div>
          <div v-if="tier.spine" class="topo-spine">
            <span class="topo-spine-line" aria-hidden="true"></span>
            <span class="topo-spine-label">{{ tier.spine }}</span>
          </div>
        </div>

        <section v-if="supportGroups.length" class="topo-support">
          <div class="topo-tier-head">
            <h3>支撑面</h3>
            <span class="hint">按角色分组，不表示请求顺序</span>
          </div>
          <div class="topo-groups">
            <div v-for="groupItem in supportGroups" :key="groupItem.id" class="topo-group">
              <div class="topo-group-head">
                <span class="topo-group-title">{{ groupItem.title }}</span>
                <span v-if="groupItem.caption" class="topo-group-caption">{{ groupItem.caption }}</span>
              </div>
              <div class="topo-node-grid">
                <article
                  v-for="node in groupItem.nodes"
                  :key="node.name"
                  class="topo-node"
                  :class="[`tone-${tone(node)}`, { 'has-fault': faultsOf(node.name).length, disabled: isDisabled(node) }]"
                  :data-testid="`topology-node-${node.name}`"
                  :data-heat="'unwired'"
                >
                  <div class="topo-node-head">
                    <span class="topo-dot" aria-hidden="true"></span>
                    <code class="topo-name">{{ node.name }}</code>
                  </div>
                  <div class="topo-node-meta">
                    <span class="topo-role">{{ node.label }}</span>
                    <span class="badge" :class="stateClass(node.state)">{{ node.state }}</span>
                    <span class="badge" :class="containerHealthClass(node.health)">{{ node.health }}</span>
                    <span v-if="isDisabled(node)" class="badge muted" :data-testid="`topology-disabled-${node.name}`">未启用</span>
                  </div>
                  <div v-if="faultsOf(node.name).length" class="topo-faults" :data-testid="`topology-faults-${node.name}`">
                    <span
                      v-for="fault in faultsOf(node.name)"
                      :key="fault.id"
                      class="badge warn"
                      :data-action="fault.action"
                      :title="`${fault.action} · ${fault.status}`"
                    >{{ fault.action }}</span>
                  </div>
                  <p v-if="node.name === 'jl-toxiproxy' && toxiproxyInfo" class="topo-extra">{{ toxiproxyInfo }}</p>
                </article>
              </div>
            </div>
          </div>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
/* A node the newest run did not enable stays in the structure (so the topology
   is always complete) but is visibly out of the experiment. */
.topo-node.disabled {
  opacity: 0.42;
  filter: grayscale(0.35);
}

.topo-node.disabled .topo-name {
  text-decoration: line-through;
}
</style>
