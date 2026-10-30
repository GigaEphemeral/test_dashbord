<script setup>
import { computed } from 'vue'

/**
 * B3-00c product-id sampling block (design §16.9).
 *
 * The k6 load scenario samples PRODUCT_ID_MIN..PRODUCT_ID_MAX; the seeded
 * database only has 1..10000. Sampling past the database range is deliberate
 * (cache penetration): absent ids bypass L1/L2 and hit the empty-cache path.
 * The histogram is a pure-JS normal PDF preview; it never calls the API.
 */
const props = defineProps({
  /** { min, max, distribution, concentration } — concentration is 0..100 here. */
  modelValue: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue'])

const DB_MIN = 1
const DB_MAX = 10000
const BAR_COUNT = 28
const PRESETS = [
  { value: 'penetration', label: '含越界（5000–20000）', min: 5000, max: 20000 },
  { value: 'hot', label: '库内热点（1–10000）', min: 1, max: 10000 },
  { value: 'custom', label: '自定义' }
]

const min = computed(() => Number(props.modelValue?.min) || 0)
const max = computed(() => Number(props.modelValue?.max) || 0)
const distribution = computed(() => (props.modelValue?.distribution === 'normal' ? 'normal' : 'uniform'))
const concentration = computed(() => {
  const value = Number(props.modelValue?.concentration)
  if (!Number.isFinite(value)) return 0
  return Math.min(100, Math.max(0, Math.round(value)))
})

const preset = computed(() => {
  if (min.value === 5000 && max.value === 20000) return 'penetration'
  if (min.value === 1 && max.value === 10000) return 'hot'
  return 'custom'
})

const span = computed(() => Math.max(1, max.value - min.value))
const mu = computed(() => (min.value + max.value) / 2)
const sigma = computed(() => Math.max(1, span.value * (0.18 - 0.13 * (concentration.value / 100))))

function erf(x) {
  const sign = Math.sign(x)
  const value = Math.abs(x)
  const a1 = 0.254829592
  const a2 = -0.284496736
  const a3 = 1.421413741
  const a4 = -1.453152027
  const a5 = 1.061405429
  const p = 0.3275911
  const t = 1 / (1 + p * value)
  const y = 1 - ((((a5 * t + a4) * t + a3) * t + a2) * t + a1) * t * Math.exp(-value * value)
  return sign * y
}

function normalPdf(x) {
  return Math.exp(-0.5 * ((x - mu.value) / sigma.value) ** 2) / (sigma.value * Math.sqrt(2 * Math.PI))
}

function normalCdf(x) {
  return 0.5 * (1 + erf((x - mu.value) / (sigma.value * Math.sqrt(2))))
}

const bars = computed(() => {
  const peak = normalPdf(mu.value) || 1
  return Array.from({ length: BAR_COUNT }, (_, index) => {
    if (distribution.value === 'uniform') return 55
    const x = min.value + (span.value * (index + 0.5)) / BAR_COUNT
    return Math.max(4, Math.min(100, (normalPdf(x) / peak) * 100))
  })
})

const inFraction = computed(() => {
  if (distribution.value === 'uniform') {
    const lo = Math.max(min.value, DB_MIN)
    const hi = Math.min(max.value, DB_MAX)
    return Math.min(1, Math.max(0, hi - lo) / span.value)
  }
  return Math.min(1, Math.max(0, normalCdf(DB_MAX) - normalCdf(DB_MIN)))
})

const inPercent = computed(() => Math.round(inFraction.value * 100))
const outPercent = computed(() => 100 - inPercent.value)

const summary = computed(() => {
  const base = `DB 范围 ${DB_MIN}–${DB_MAX}；k6 采样 ${min.value}–${max.value}：库内约 ${inPercent.value}%，越界约 ${outPercent.value}%。`
  const shape = distribution.value === 'normal'
    ? ' 正态分布下中间 ID 更热，集中度越高越集中。'
    : ' 平均分布下每个 ID 等概率。'
  const penetration = outPercent.value > 1
    ? ' 越界 ID 会触发缓存穿透路径。'
    : ' 本次没有越界 ID；如需穿透实验，选择「含越界」范围。'
  return base + shape + penetration
})

function patch(part) {
  emit('update:modelValue', { ...(props.modelValue || {}), ...part })
}

function applyPreset(value) {
  const hit = PRESETS.find((item) => item.value === value)
  if (!hit || hit.value === 'custom') return
  patch({ min: hit.min, max: hit.max })
}

function updateNumber(key, raw) {
  const parsed = Number.parseInt(raw, 10)
  patch({ [key]: Number.isFinite(parsed) ? parsed : 0 })
}
</script>

<template>
  <div class="sampling-block" data-testid="wizard-product-sampling">
    <div class="field-row">
      <div class="field">
        <label data-tip="含越界用于缓存穿透；库内热点用于正态热度对照；改动 ID 后自动切到自定义。">
          采样范围 <span class="key">preset</span>
        </label>
        <select
          class="input select"
          data-testid="wizard-product-preset"
          :value="preset"
          @change="applyPreset($event.target.value)"
        >
          <option v-for="item in PRESETS" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>
        <div class="help">含越界用于穿透；库内热点用于正态热度对照。</div>
      </div>
      <div class="field">
        <label data-tip="k6 采样下界；可以小于数据库最小值，越界 ID 走空值缓存/布隆过滤路径。">
          采样起始 ID <span class="key">PRODUCT_ID_MIN</span>
        </label>
        <input
          class="input"
          type="number"
          data-testid="wizard-product-min"
          :value="min"
          @input="updateNumber('min', $event.target.value)"
        />
        <div class="help">本次 load 场景的最小商品 ID。</div>
      </div>
      <div class="field">
        <label data-tip="k6 采样上界；大于 10000 的部分不在 seed 数据库中，用于穿透。">
          采样结束 ID <span class="key">PRODUCT_ID_MAX</span>
        </label>
        <input
          class="input"
          type="number"
          data-testid="wizard-product-max"
          :value="max"
          @input="updateNumber('max', $event.target.value)"
        />
        <div class="help">本次 load 场景的最大商品 ID。</div>
      </div>
      <div class="field">
        <label data-tip="固定 seed 10,000 个商品（1–10000）；只读，用于计算库内/越界占比。">数据库范围</label>
        <input class="input" :value="`${DB_MIN} – ${DB_MAX}`" disabled />
        <div class="help">固定 seed 10,000 商品。</div>
      </div>
    </div>

    <div class="field-row" style="margin-top: 8px">
      <div class="field">
        <label data-tip="平均分布每个 ID 等概率，适合作基线；正态分布让中间 ID 更热。">
          分布 <span class="key">PRODUCT_ID_DISTRIBUTION</span>
        </label>
        <select
          class="input select"
          data-testid="wizard-product-distribution"
          :value="distribution"
          @change="patch({ distribution: $event.target.value })"
        >
          <option value="uniform">平均分布（每个 ID 等概率）</option>
          <option value="normal">正态分布（中间最多）</option>
        </select>
        <div class="help">正态分布下中间 ID 更热；平均分布用于基线。</div>
      </div>
      <div class="field">
        <label data-tip="仅正态分布生效：0 接近平均，100 高度集中在中间 ID。">
          热度集中度 <span class="key">PRODUCT_ID_CONCENTRATION</span>
        </label>
        <div class="slider-row">
          <input
            class="range"
            type="range"
            min="0"
            max="100"
            step="1"
            data-testid="wizard-product-concentration"
            :value="concentration"
            @input="patch({ concentration: Number($event.target.value) })"
          />
          <span class="chip">{{ concentration }}%</span>
        </div>
        <div class="help">按 0–100 配置；提交时归一化为 0–1。</div>
      </div>
    </div>

    <div class="histogram" data-testid="wizard-product-histogram" aria-label="商品 ID 分布预览">
      <i v-for="(height, index) in bars" :key="index" :style="{ height: `${height}%` }"></i>
    </div>
    <div class="share-bar" aria-label="库内与越界占比">
      <span class="in" :style="{ width: `${inPercent}%` }"></span>
      <span class="out" :style="{ width: `${outPercent}%` }"></span>
    </div>
    <p class="hint" data-testid="wizard-product-summary">{{ summary }}</p>
    <p v-if="outPercent > 0" class="notice info" data-testid="wizard-product-penetration-warning">
      越界 ID 约 {{ outPercent }}%：这些请求会返回 404，k6 视为**缓存穿透命中**并计入
      <code>product_missing</code>，不计入 <code>flow_errors</code>。
      只做纯库内基线时把上界调到 ≤ {{ DB_MAX }}；穿透实验可保留。
    </p>
  </div>
</template>

<style scoped>
.sampling-block {
  margin-top: 8px;
}
.slider-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.slider-row .range {
  flex: 1;
  accent-color: var(--accent);
}
.histogram {
  height: 76px;
  margin-top: 10px;
  display: flex;
  align-items: flex-end;
  gap: 2px;
  padding: 6px 8px;
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  background: #111824;
}
.histogram i {
  flex: 1;
  min-height: 4px;
  border-radius: 2px 2px 0 0;
  background: linear-gradient(180deg, rgba(56, 211, 159, 0.9), rgba(56, 211, 159, 0.25));
}
.share-bar {
  display: flex;
  height: 10px;
  margin-top: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: #1b2634;
}
.share-bar .in {
  background: var(--accent);
}
.share-bar .out {
  background: #d9a441;
}
</style>
