/**
 * Pure helpers shared by the animation views.
 *
 * Every role is classified from the container name returned by
 * `GET /lab-api/containers`. Nothing is assumed to exist: a tier whose
 * containers are absent is simply not rendered, so the page never draws an
 * invented component (the dev stack has no sentinel nodes, the ha stack has no
 * standalone `jl-redis`, and so on).
 */

/** The three JDK 21 app containers the instance view charts. */
export const APP_CONTAINER_NAME = /^jl-app-\d+$/

// Order matters: the exporters are matched before the plain nodes they observe.
const ROLE_RULES = [
  { role: 'gateway', test: /^jl-nginx$/ },
  { role: 'app', test: /^jl-app-\d+$/ },
  { role: 'app-jdk8', test: /^jl-seckill-app-jdk8$/ },
  { role: 'redis', test: /^jl-redis(-\d+)?$/ },
  { role: 'sentinel', test: /^jl-sentinel-\d+$/ },
  { role: 'redis-exporter', test: /^jl-redis(-\d+)?-exporter$/ },
  { role: 'mysql', test: /^jl-mysql(-\d+)?$/ },
  { role: 'mysql-router', test: /^jl-mysql-router$/ },
  { role: 'mysql-init', test: /^jl-mysql-(cluster|router)-init$/ },
  { role: 'mysql-exporter', test: /^jl-(mysql(-\d+)?-exporter|mysqld-exporter)$/ },
  { role: 'rabbitmq', test: /^jl-rabbitmq$/ },
  { role: 'prometheus', test: /^jl-prometheus$/ },
  { role: 'grafana', test: /^jl-grafana$/ },
  { role: 'tempo', test: /^jl-tempo$/ },
  { role: 'otel', test: /^jl-otel-collector$/ },
  { role: 'cadvisor', test: /^jl-cadvisor$/ },
  { role: 'nginx-exporter', test: /^jl-nginx-exporter$/ },
  { role: 'chaos', test: /^jl-chaos-controller$/ },
  { role: 'toxiproxy', test: /^jl-toxiproxy$/ },
  { role: 'toxiproxy-init', test: /^jl-toxiproxy-init$/ },
  { role: 'traffic', test: /^jl-traffic$/ },
  // One container per run started from the ledger: jl-k6-<runId>.
  { role: 'k6', test: /^jl-k6-/ },
]

const ROLE_META = {
  gateway: { label: 'Nginx 入口', group: 'edge' },
  app: { label: '应用实例', group: 'app' },
  'app-jdk8': { label: 'JDK 8 对照', group: 'app' },
  redis: { label: 'Redis 节点', group: 'cache' },
  sentinel: { label: 'Sentinel', group: 'cache' },
  'redis-exporter': { label: 'Redis Exporter', group: 'cache' },
  mysql: { label: 'MySQL 节点', group: 'db' },
  'mysql-router': { label: 'MySQL Router', group: 'db' },
  'mysql-init': { label: '一次性初始化任务', group: 'db' },
  'mysql-exporter': { label: 'MySQL Exporter', group: 'db' },
  rabbitmq: { label: 'RabbitMQ', group: 'mq' },
  prometheus: { label: '指标存储', group: 'obs' },
  grafana: { label: '仪表盘', group: 'obs' },
  tempo: { label: '链路存储', group: 'obs' },
  otel: { label: 'OTLP 接收', group: 'obs' },
  cadvisor: { label: '容器资源指标', group: 'obs' },
  'nginx-exporter': { label: 'Nginx 指标', group: 'obs' },
  chaos: { label: '实验控制 API', group: 'control' },
  toxiproxy: { label: '网络故障代理', group: 'control' },
  'toxiproxy-init': { label: '一次性初始化任务', group: 'control' },
  traffic: { label: 'k6 背景流量', group: 'load' },
  k6: { label: '按需压测', group: 'load' },
}

/** Role + group + display label for one container name. */
export function classifyContainer(name) {
  const rule = ROLE_RULES.find((item) => item.test.test(name))
  if (!rule) return { role: 'other', group: 'other', label: '未分类容器' }
  const meta = ROLE_META[rule.role]
  const label = rule.role === 'redis' && name === 'jl-redis' ? 'Redis 单机'
    : rule.role === 'mysql' && name === 'jl-mysql' ? 'MySQL 单机'
      : meta.label
  return { role: rule.role, group: meta.group, label }
}

/** `jl-app-1` -> `app-1`, the instance name Prometheus and the thread proxy use. */
export function instanceName(containerName) {
  return containerName.replace(/^jl-/, '')
}

/** Stable ordering keeps the topology from reshuffling on every poll. */
export function sortContainers(list) {
  return [...(list || [])].sort((a, b) => String(a.name).localeCompare(String(b.name)))
}

export function formatRate(value) {
  if (!Number.isFinite(value)) return '无数据'
  if (value === 0) return '0 /s'
  if (value >= 100) return `${value.toFixed(0)} /s`
  if (value >= 10) return `${value.toFixed(1)} /s`
  return `${value.toFixed(2)} /s`
}

export function formatPercent(ratio) {
  if (!Number.isFinite(ratio)) return '无数据'
  return `${(ratio * 100).toFixed(1)}%`
}

export function formatBytes(bytes) {
  if (!Number.isFinite(bytes)) return '无数据'
  const mb = bytes / (1024 * 1024)
  return mb >= 1024 ? `${(mb / 1024).toFixed(2)} GB` : `${mb.toFixed(1)} MB`
}

export function formatMillis(seconds) {
  if (!Number.isFinite(seconds)) return '无数据'
  const ms = seconds * 1000
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`
  return `${ms.toFixed(1)} ms`
}

export function formatCpu(ratio) {
  if (!Number.isFinite(ratio)) return '无数据'
  return `${(ratio * 100).toFixed(1)}%`
}

/** Severity of one container row; drives the node border and dot. */
export function containerTone(container) {
  if (!container) return 'idle'
  if (container.state !== 'running') return 'idle'
  if (container.health === 'unhealthy') return 'down'
  if (container.health === 'starting') return 'warn'
  if (container.health === 'healthy') return 'ok'
  return 'plain'
}
