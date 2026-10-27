<script setup>
import { computed } from 'vue'
import { classifyContainer, containerTone, sortContainers } from '../labModel'

const props = defineProps({
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] },
  toxiproxy: { type: Object, default: null }
})

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
  // The ha profile keeps the dev container running next to the cluster, so the
  // caption counts cluster members by name instead of trusting the total.
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

// Only the tiers that actually have containers are rendered; a stopped
// one-shot job still appears because it exists in Docker.
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

function healthClass(health) {
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
    <div class="topo-summary">
      <span class="topo-stat"><strong>{{ nodes.length }}</strong> 个容器</span>
      <span class="topo-stat"><strong>{{ running }}</strong> 运行中</span>
      <span class="topo-stat" :class="{ warn: faults.length }"><strong>{{ faults.length }}</strong> 个活动故障</span>
      <span class="topo-legend">
        <span class="legend-item"><i class="legend-dot ok"></i>运行且健康</span>
        <span class="legend-item"><i class="legend-dot warn"></i>启动中</span>
        <span class="legend-item"><i class="legend-dot down"></i>不健康</span>
        <span class="legend-item"><i class="legend-dot idle"></i>已停止 / 按需</span>
        <span class="legend-item"><span class="badge warn">action</span>故障徽标</span>
      </span>
    </div>

    <p class="hint topo-source">
      节点全部来自 <code>GET /lab-api/containers</code>，故障徽标来自 <code>GET /lab-api/chaos/actions</code>；
      没有出现的组件就是没有运行，不画占位节点。<code>health=none</code> 表示该容器没有 healthcheck，
      不代表故障。
    </p>

    <div v-if="!nodes.length" class="empty" data-testid="topology-empty">
      没有容器数据：先确认访问令牌（在「实验操作台」页填写）与 jl-chaos-controller 是否在线。
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
                :class="[`tone-${tone(node)}`, { 'has-fault': faultsOf(node.name).length }]"
                :data-testid="`topology-node-${node.name}`"
              >
                <div class="topo-node-head">
                  <span class="topo-dot" aria-hidden="true"></span>
                  <code class="topo-name">{{ node.name }}</code>
                </div>
                <div class="topo-node-meta">
                  <span class="topo-role">{{ node.label }}</span>
                  <span class="badge" :class="stateClass(node.state)">{{ node.state }}</span>
                  <span class="badge" :class="healthClass(node.health)">{{ node.health }}</span>
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
                :class="[`tone-${tone(node)}`, { 'has-fault': faultsOf(node.name).length }]"
                :data-testid="`topology-node-${node.name}`"
              >
                <div class="topo-node-head">
                  <span class="topo-dot" aria-hidden="true"></span>
                  <code class="topo-name">{{ node.name }}</code>
                </div>
                <div class="topo-node-meta">
                  <span class="topo-role">{{ node.label }}</span>
                  <span class="badge" :class="stateClass(node.state)">{{ node.state }}</span>
                  <span class="badge" :class="healthClass(node.health)">{{ node.health }}</span>
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
  </section>
</template>
