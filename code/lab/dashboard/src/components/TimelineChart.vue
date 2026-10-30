<script setup>
import { computed } from 'vue'
import { compact, niceCeil } from '../timeline'

/**
 * One SVG timeline. The caller owns fetching and series construction; this
 * component owns scales, ticks, paths and the legend so every chart on the
 * console looks and behaves the same.
 *
 * props.chart: { id, testid, title, note?, axis?, series: [{key,label,color,
 *               dash,points,format}] }
 */
const props = defineProps({
  chart: { type: Object, required: true },
  windowStart: { type: Number, default: null },
  windowEnd: { type: Number, default: null },
  height: { type: Number, default: 200 }
})

const SVG_W = 600
const PAD_L = 54
const PAD_R = 10
const PAD_T = 12
const PAD_B = 22
const SVG_H = props.height

function clockLabel(stamp) {
  return new Date(stamp * 1000).toLocaleTimeString('zh-CN', { hour12: false })
}

const view = computed(() => {
  const axis = props.chart.axis || compact
  const series = (props.chart.series || []).map((item) => ({
    ...item,
    lastText: item.points?.length ? item.format(item.points[item.points.length - 1].v) : '无数据'
  }))
  const all = series.flatMap((item) => (item.points || []).map((point) => point.v))
  const hasData = all.length > 0
  const allZero = hasData && all.every((value) => value === 0)
  const yMax = niceCeil(Math.max(...(all.length ? all : [0]), 0))
  const start = props.windowStart
  const end = props.windowEnd
  const span = start !== null && end !== null && end > start ? end - start : 1
  const innerW = SVG_W - PAD_L - PAD_R
  const innerH = SVG_H - PAD_T - PAD_B
  const x = (stamp) => PAD_L + ((stamp - start) / span) * innerW
  const y = (value) => PAD_T + innerH - (Math.max(0, value) / yMax) * innerH

  const rendered = series.map((item) => ({
    ...item,
    path: (item.points || [])
      .map((point) => `${x(point.t).toFixed(1)},${y(point.v).toFixed(1)}`)
      .join(' '),
    dots: (item.points || []).map((point) => ({
      cx: x(point.t).toFixed(1),
      cy: y(point.v).toFixed(1),
      title: `${item.label} · ${clockLabel(point.t)} · ${item.format(point.v)}`
    }))
  }))
  const yTicks = [0, yMax / 2, yMax].map((value) => ({
    value,
    label: axis(value),
    y: y(value).toFixed(1)
  }))
  const xTicks = start === null || end === null
    ? []
    : [0, 1, 2, 3].map((index) => {
      const stamp = start + (span * index) / 3
      return {
        x: x(stamp).toFixed(1),
        label: clockLabel(stamp),
        anchor: index === 0 ? 'start' : index === 3 ? 'end' : 'middle'
      }
    })
  return { series: rendered, hasData, allZero, yMax, yTicks, xTicks, axis }
})
</script>

<template>
  <div class="chart-box" :data-testid="chart.testid">
    <div class="chart-head">
      <strong>{{ chart.title }}</strong>
      <span class="hint">
        <template v-if="view.hasData && view.allZero">当前均为 0（空闲） · </template>
        {{ chart.note ? `${chart.note} · ` : '' }}峰值 {{ view.axis(view.yMax) }}
      </span>
    </div>
    <svg v-if="view.hasData" :viewBox="`0 0 ${SVG_W} ${SVG_H}`" :style="{ height: `${height}px` }" role="img" :aria-label="chart.title">
      <line
        v-for="tick in view.yTicks"
        :key="`y-${tick.value}`"
        :x1="PAD_L"
        :x2="SVG_W - PAD_R"
        :y1="tick.y"
        :y2="tick.y"
        class="chart-gridline"
      />
      <text v-for="tick in view.yTicks" :key="`yl-${tick.value}`" :x="PAD_L - 6" :y="Number(tick.y) + 3" text-anchor="end" class="chart-axis">{{ tick.label }}</text>
      <text v-for="tick in view.xTicks" :key="`xl-${tick.x}`" :x="tick.x" :y="SVG_H - 6" :text-anchor="tick.anchor" class="chart-axis">{{ tick.label }}</text>
      <g v-for="item in view.series" :key="item.key">
        <polyline
          v-if="item.points.length > 1"
          :points="item.path"
          fill="none"
          :stroke="item.color"
          :stroke-dasharray="item.dash || null"
          stroke-width="1.5"
          stroke-linejoin="round"
          stroke-linecap="round"
        />
        <circle v-for="(dot, index) in item.dots" :key="`${item.key}-${index}`" :cx="dot.cx" :cy="dot.cy" r="2" :fill="item.color">
          <title>{{ dot.title }}</title>
        </circle>
      </g>
    </svg>
    <p v-else class="empty chart-empty">无数据</p>
    <div class="legend-inline">
      <span v-for="item in view.series" :key="`legend-${item.key}`">
        <i class="dot" :style="{ background: item.color }"></i>{{ item.label }}：{{ item.lastText }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.chart-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 4px;
}

.chart-box svg {
  width: 100%;
}

.chart-gridline {
  stroke: rgba(148, 163, 184, 0.18);
  stroke-width: 1;
}

.chart-axis {
  fill: var(--text-muted);
  font-size: 9px;
}

.chart-empty {
  margin: 24px 0;
  text-align: center;
}
</style>
