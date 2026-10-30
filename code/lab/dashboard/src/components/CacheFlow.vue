<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { formatPercent, formatRate } from '../labModel'

const props = defineProps({
  /** { l1:{total,hit,miss,hitRatio,present}, l2:{…}, fallbacks:{total,reasons,present}, available } */
  cache: { type: Object, default: () => ({}) },
  paused: { type: Boolean, default: false },
  reducedMotion: { type: Boolean, default: false },
  /** false = Caffeine bypassed and cleared; null = unknown (state not loaded). */
  l1Enabled: { type: Boolean, default: null },
  mode: { type: String, default: '' },
  instance: { type: String, default: '' }
})

const emit = defineEmits(['toggle-pause'])

const PARTICLES = 10

const l1Track = ref(null)
const l2Track = ref(null)
const dbTrack = ref(null)

const cache = computed(() => props.cache || {})
const l1 = computed(() => cache.value.l1 || {})
const l2 = computed(() => cache.value.l2 || {})
const fallbacks = computed(() => cache.value.fallbacks || {})
const reasons = computed(() => fallbacks.value.reasons || [])
const available = computed(() => Boolean(cache.value.available))

const fallbackShare = computed(() => {
  const total = l1.value.total
  const db = fallbacks.value.total
  return Number.isFinite(total) && total > 0 && Number.isFinite(db) ? db / total : null
})

const l1Off = computed(() => props.l1Enabled === false)
const entryRate = computed(() => (l1Off.value ? l2.value.total : l1.value.total))

const lanes = computed(() => [
  {
    id: 'l1',
    title: '入口 → L1',
    tone: 'accent',
    rate: l1Off.value ? null : l1.value.total,
    note: l1Off.value ? 'L1 已关闭：本段旁路，不计数' : '全部缓存查询',
    hint: l1Off.value ? 'L1 关闭时请求直接进入 L2' : '进入 Caffeine 的每秒查询数'
  },
  {
    id: 'l2',
    title: 'L1 → L2',
    tone: 'amber',
    rate: l1Off.value ? l2.value.total : l1.value.miss,
    note: l1Off.value ? 'L1 关闭：全部查询直达 Redis' : '仅 L1 未命中',
    hint: '只有 L1 未命中的请求下探到 Redis；L1 关闭时全部下探'
  },
  {
    id: 'db',
    title: 'L2 → DB',
    tone: 'red',
    rate: fallbacks.value.total,
    note: '全部回源 reason',
    hint: 'cache_db_fallbacks_total{reason} 的合计'
  }
])

function laneIdle(lane) {
  return !Number.isFinite(lane.rate) || lane.rate <= 0
}

function toneClass(tone) {
  return `lane-${tone}`
}

// ------------------------------------------------------------------ motion
// Hand-written rAF animation: positions live in a plain Map and are applied as
// compositor-friendly transforms, so a 5s data refresh never re-mounts the
// particles or triggers layout. Speed eases toward the new rate instead of
// jumping, which is the "前端插值" the design asks for.
let rafId = 0
let lastTs = 0
let observer = null
const frames = new Map()

function trackElements() {
  return { l1: l1Track.value, l2: l2Track.value, db: dbTrack.value }
}

function measure() {
  for (const [key, el] of Object.entries(trackElements())) {
    if (!el) continue
    const elements = Array.from(el.querySelectorAll('.flow-particle'))
    const existing = frames.get(key)
    // The v-if layer is mounted only once `available` is true; a fresh element
    // list means the old frames are detached and must be rebuilt.
    if (existing && existing.elements.length === elements.length
      && existing.elements[0] === elements[0]) {
      existing.width = el.clientWidth
      continue
    }
    frames.set(key, {
      elements,
      positions: elements.map((_, index) => index / Math.max(1, elements.length)),
      speed: 0,
      width: el.clientWidth
    })
  }
}

function observeTracks() {
  if (typeof ResizeObserver === 'undefined') return
  if (!observer) observer = new ResizeObserver(() => measure())
  observer.disconnect()
  for (const el of Object.values(trackElements())) {
    if (el) observer.observe(el)
  }
}

// On first visit the cache query is still in flight, so the flow board (and its
// tracks) renders after mount. Measure again when the data arrives, otherwise
// the tracked element list stays empty and the particles never get a transform.
watch(available, async (value) => {
  if (!value) {
    frames.clear()
    return
  }
  await nextTick()
  measure()
  observeTracks()
})

function targetSpeed(rate) {
  if (!Number.isFinite(rate) || rate <= 0) return 0
  // Fraction of the lane per second: small rates stay visible, big bursts cap.
  return Math.min(2.2, Math.max(0.05, rate * 0.06))
}

function tick(ts) {
  const dt = lastTs ? Math.min(0.1, (ts - lastTs) / 1000) : 0
  lastTs = ts
  const rates = {
    l1: props.cache?.l1?.total,
    l2: props.cache?.l1?.miss,
    db: props.cache?.fallbacks?.total
  }
  for (const [key, frame] of frames) {
    const target = props.paused ? 0 : targetSpeed(rates[key])
    frame.speed += (target - frame.speed) * Math.min(1, dt * 4)
    if (frame.speed > 0.0005) {
      for (let index = 0; index < frame.positions.length; index += 1) {
        frame.positions[index] = (frame.positions[index] + frame.speed * dt) % 1
      }
    }
    const travel = Math.max(0, frame.width - 10)
    for (let index = 0; index < frame.elements.length; index += 1) {
      frame.elements[index].style.transform = `translate3d(${(frame.positions[index] * travel).toFixed(1)}px, 0, 0)`
    }
  }
  rafId = requestAnimationFrame(tick)
}

onMounted(async () => {
  await nextTick()
  measure()
  observeTracks()
  rafId = requestAnimationFrame(tick)
})

onBeforeUnmount(() => {
  if (rafId) cancelAnimationFrame(rafId)
  rafId = 0
  observer?.disconnect()
  observer = null
  frames.clear()
})
</script>

<template>
  <section class="card cache-flow-card" data-testid="cache-flow">
    <div class="card-head">
      <h2>缓存流动 <span class="count">L1 → L2 → DB</span></h2>
      <div class="cache-head-actions">
        <span v-if="instance" class="badge muted" data-testid="cache-flow-instance">{{ instance }}</span>
        <span v-if="mode" class="badge" :class="mode === 'off' ? 'ok' : 'warn'" data-testid="cache-flow-mode">{{ mode }}</span>
        <span
          v-if="l1Enabled !== null"
          class="badge"
          :class="l1Enabled ? 'ok' : 'muted'"
          data-testid="cache-flow-l1"
        >L1 {{ l1Enabled ? '启用' : '已关闭' }}</span>
        <span class="pill" :class="paused ? 'paused' : 'running'" data-testid="cache-motion-state">
          {{ paused ? '动画暂停' : '动画播放中' }}
        </span>
        <button
          type="button"
          class="btn ghost"
          data-testid="animation-toggle"
          :aria-pressed="paused"
          @click="emit('toggle-pause')"
        >{{ paused ? '播放动画' : '暂停动画' }}</button>
      </div>
    </div>

    <p class="hint">
      粒子速度与该段每秒速率成正比（Prometheus <code>rate(...[1m])</code>）；数据每 5s 更新，速度平滑过渡。
      没有粒子 = 该段当前没有流量，不是渲染失败。指标按当前实例的 <code>instance</code> 标签过滤。
    </p>

    <p v-if="l1Off" class="notice info" data-testid="cache-flow-l1-off">
      L1 已关闭并清空本地条目：入口流量直接打到 Redis（L2），L1 命中率显示“不适用”。
      对比性能差异时看 L2 查询速率与 DB 回源速率，而不是 L1 曲线。
    </p>

    <div v-if="!available" class="empty" data-testid="cache-empty">
      无数据：Prometheus 没有返回 <code>cache_l1_requests_total</code> / <code>cache_l2_requests_total</code> /
      <code>cache_db_fallbacks_total</code>，请确认缓存实验已开启且商品读接口被访问过。
    </div>

    <template v-else>
      <div class="flow-board" :class="{ 'is-paused': paused }">
        <div class="flow-stage">
          <span class="stage-kicker">入口</span>
          <strong class="stage-title">GET /api/products/{id}</strong>
          <span class="stage-sub">{{ l1Off ? '只读商品查询 · L1 旁路' : '只读商品查询' }}</span>
          <span class="stage-metric" data-testid="cache-total-l1">{{ formatRate(entryRate) }}</span>
          <span class="stage-note">{{ l1Off ? '缓存查询速率（直达 L2）' : 'L1 查询速率' }}</span>
        </div>

        <div class="flow-lane" :class="[toneClass('accent'), { idle: laneIdle(lanes[0]) }]" data-testid="cache-lane-l1">
          <div class="lane-head">
            <span class="lane-title">{{ lanes[0].title }}</span>
            <span class="lane-rate">{{ formatRate(lanes[0].rate) }}</span>
          </div>
          <div ref="l1Track" class="flow-track" aria-hidden="true">
            <span v-for="n in PARTICLES" :key="n" class="flow-particle"></span>
          </div>
          <span class="lane-note">{{ lanes[0].note }}</span>
        </div>

        <div class="flow-stage">
          <span class="stage-kicker">L1</span>
          <strong class="stage-title">Caffeine</strong>
          <span class="stage-sub">{{ l1Off ? '已关闭 · 本地条目已清空' : '进程内 · 60s 过期' }}</span>
          <div class="ratio">
            <span class="ratio-label">命中率</span>
            <span class="ratio-value" data-testid="cache-hit-ratio-l1">{{ l1Off ? '不适用' : formatPercent(l1.hitRatio) }}</span>
          </div>
          <div class="ratio-bar" aria-hidden="true">
            <span :style="{ width: `${!l1Off && Number.isFinite(l1.hitRatio) ? Math.min(100, l1.hitRatio * 100) : 0}%` }"></span>
          </div>
          <span class="stage-note">{{ l1Off ? 'L1 旁路：未命中统计不适用' : `命中 ${formatRate(l1.hit)} · 未命中 ${formatRate(l1.miss)}` }}</span>
        </div>

        <div class="flow-lane" :class="[toneClass('amber'), { idle: laneIdle(lanes[1]) }]" data-testid="cache-lane-l2">
          <div class="lane-head">
            <span class="lane-title">{{ lanes[1].title }}</span>
            <span class="lane-rate">{{ formatRate(lanes[1].rate) }}</span>
          </div>
          <div ref="l2Track" class="flow-track" aria-hidden="true">
            <span v-for="n in PARTICLES" :key="n" class="flow-particle"></span>
          </div>
          <span class="lane-note">{{ lanes[1].note }}</span>
        </div>

        <div class="flow-stage">
          <span class="stage-kicker">L2</span>
          <strong class="stage-title">Redis</strong>
          <span class="stage-sub">仅承接 L1 未命中</span>
          <div class="ratio">
            <span class="ratio-label">命中率</span>
            <span class="ratio-value" data-testid="cache-hit-ratio-l2">{{ formatPercent(l2.hitRatio) }}</span>
          </div>
          <div class="ratio-bar amber" aria-hidden="true">
            <span :style="{ width: `${Number.isFinite(l2.hitRatio) ? Math.min(100, l2.hitRatio * 100) : 0}%` }"></span>
          </div>
          <span class="stage-note">查询 {{ formatRate(l2.total) }} · 未命中 {{ formatRate(l2.miss) }}</span>
        </div>

        <div class="flow-lane" :class="[toneClass('red'), { idle: laneIdle(lanes[2]) }]" data-testid="cache-lane-db">
          <div class="lane-head">
            <span class="lane-title">{{ lanes[2].title }}</span>
            <span class="lane-rate">{{ formatRate(lanes[2].rate) }}</span>
          </div>
          <div ref="dbTrack" class="flow-track" aria-hidden="true">
            <span v-for="n in PARTICLES" :key="n" class="flow-particle"></span>
          </div>
          <span class="lane-note">{{ lanes[2].note }}</span>
        </div>

        <div class="flow-stage">
          <span class="stage-kicker">DB</span>
          <strong class="stage-title">MySQL</strong>
          <span class="stage-sub">回源查询</span>
          <span class="stage-metric" data-testid="cache-fallbacks">{{ formatRate(fallbacks.total) }}</span>
          <span class="stage-note">占 L1 查询 {{ formatPercent(fallbackShare) }}</span>
        </div>
      </div>

      <div class="cache-detail">
        <div class="cache-legend">
          <h3>图例 · 运动代表什么</h3>
          <ul>
            <li><i class="legend-dot accent"></i>出口 → L1 的点：全部缓存查询，点数速度 ∝ <code>rate(cache_l1_requests_total[1m])</code>。</li>
            <li><i class="legend-dot amber"></i>L1 → L2 的点：只有未命中才继续下探，速度 ∝ <code>result="miss"</code> 的速率。</li>
            <li><i class="legend-dot red"></i>L2 → DB 的点：回源，速度 ∝ <code>rate(cache_db_fallbacks_total[1m])</code>。</li>
            <li>命中率条 = 该层 hit / 该层查询，代表被缓存挡下的比例；命中不会产生下一段粒子。</li>
            <li>点全部停住 = 该段 0 rps、Prometheus 无数据，或动画被暂停。数值轮询不受暂停影响。</li>
          </ul>
        </div>

        <div class="cache-reasons">
          <h3>回源 reason 拆分</h3>
          <ul v-if="reasons.length" data-testid="cache-reasons">
            <li v-for="reason in reasons" :key="reason.reason">
              <code>{{ reason.reason }}</code>
              <span>{{ formatRate(reason.value) }}</span>
            </li>
          </ul>
          <p v-else class="hint">当前没有回源（或 reason 序列尚未出现）。</p>
          <p class="hint">
            reason 是有界标签族：<code>miss</code>（两级都没有）、<code>penetration</code>、
            <code>breakdown</code>、<code>avalanche</code>。
          </p>
        </div>
      </div>

      <p v-if="reducedMotion" class="hint" data-testid="cache-reduced-motion">
        系统偏好「减少动态效果」：动画默认暂停，可手动播放；数值照常每 5s 更新。
      </p>
    </template>
  </section>
</template>
