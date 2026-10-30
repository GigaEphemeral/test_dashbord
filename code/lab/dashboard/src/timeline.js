/**
 * Shared timeline helpers for the monitor page and the run detail timelines.
 * Prometheus matrices are transformed here; rendering lives in TimelineChart.
 */

export const COLORS = ['#38d39f', '#5aa9e6', '#e6b45a', '#e06b6b']
export const INSTANCE_COLORS = ['#38d39f', '#5aa9e6', '#e6b45a', '#e06b6b', '#a78bfa', '#5eead4']

/** "instance" is host:port (app-1:8080); charts only need the host. */
export function hostOf(instance) {
  const raw = String(instance || '').split(':')[0]
  return raw || 'unknown'
}

export function compact(value) {
  if (!Number.isFinite(value)) return '—'
  if (Math.abs(value) >= 100) return value.toFixed(0)
  if (Math.abs(value) >= 10) return value.toFixed(1)
  return value.toFixed(2)
}

export function niceCeil(value) {
  if (!Number.isFinite(value) || value <= 0) return 1
  const exponent = Math.floor(Math.log10(value))
  const base = 10 ** exponent
  const normalized = value / base
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
  return step * base
}

/** Matrix -> one summed [{ t, v }] per timestamp. */
export function seriesPoints(result, factor = 1) {
  const byTime = new Map()
  for (const series of result || []) {
    for (const [rawStamp, rawValue] of series.values || []) {
      const stamp = Number(rawStamp)
      const value = Number(rawValue)
      if (!Number.isFinite(stamp) || !Number.isFinite(value)) continue
      byTime.set(stamp, (byTime.get(stamp) || 0) + value * factor)
    }
  }
  return [...byTime.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([t, v]) => ({ t, v }))
}

/**
 * Matrix -> one entry per label combination. `groupBy` decides the line
 * identity (default: instance); `nameFor`/`dashFor` make the legend and the
 * stroke style (e.g. minor vs major GC).
 *
 * @returns {Array<{key:string,name:string,instance:string,dash:string,points:Array}>}
 */
export function splitSeriesPoints(result, factor = 1, options = {}) {
  const { groupBy = ['instance'], nameFor, dashFor } = options
  return (result || [])
    .map((series) => {
      const labels = series.metric || {}
      const host = hostOf(labels.instance)
      // Redis/MySQL exporters do not expose an `instance` label; their node
      // identity lives in `node` (Redis) or `member` (MySQL). Fall back to those
      // so those charts get one colour and legend entry per real node.
      const instance = host === 'unknown'
        ? (labels.node || labels.member || labels.job || 'unknown')
        : host
      const points = (series.values || [])
        .map(([rawStamp, rawValue]) => ({ t: Number(rawStamp), v: Number(rawValue) * factor }))
        .filter((point) => Number.isFinite(point.t) && Number.isFinite(point.v))
        .sort((a, b) => a.t - b.t)
      const key = groupBy.map((label) => labels[label] || '').join('|') || instance
      return {
        key,
        instance,
        name: nameFor ? nameFor(labels, instance) : instance,
        dash: dashFor ? (dashFor(labels) || '') : '',
        points
      }
    })
    .filter((entry) => entry.points.length > 0)
    .sort((a, b) => a.key.localeCompare(b.key))
}

export function instanceColor(instance, instances) {
  const index = instances.indexOf(instance)
  return INSTANCE_COLORS[(index < 0 ? 0 : index) % INSTANCE_COLORS.length]
}

/** GC action label -> short Chinese name used by legends and tooltips. */
export function gcActionName(action) {
  if (action === 'end of major GC') return 'major'
  if (action === 'end of minor GC') return 'minor'
  return action || 'gc'
}
