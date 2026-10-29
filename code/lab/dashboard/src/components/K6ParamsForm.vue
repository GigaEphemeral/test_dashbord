<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { fetchBenchScenarios } from '../api'
import ProductSamplingField from './ProductSamplingField.vue'

/**
 * B3-00c k6 execution form (design §16.2/§16.9, plan §7.1).
 *
 * Only smoke/load/spike are offered: the manifest's views.ui is the whitelist,
 * with a static fallback when the catalog is unavailable (stress/soak stay
 * CLI-only). The JSON preview is the exact `params` object createRun receives
 * (traffic selectors included), so an operator can verify before the draft.
 */
const props = defineProps({
  token: { type: String, default: '' },
  scenario: { type: String, default: 'load' },
  params: { type: Object, default: () => ({}) },
  /** Effective params (traffic selectors merged in) rendered by the preview. */
  previewParams: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:scenario', 'update:params'])

const SCENARIO_LABELS = {
  smoke: 'smoke · 冒烟（最小完整流程）',
  load: 'load · 爬坡 + 保持',
  spike: 'spike · 短时尖峰'
}

const FIELD_DEFS = {
  smoke: [
    { key: 'SMOKE_VUS', label: '冒烟 VU', help: '并发虚拟用户数；VU 不是服务端线程数。', tip: 'VU=虚拟并发用户，每个 VU 按脚本反复执行。', type: 'number' },
    { key: 'SMOKE_ITERATIONS', label: '迭代次数', help: '每 VU 完整流程次数。', type: 'number' },
    { key: 'SMOKE_DURATION', label: '最长时长', help: 'k6 duration，例如 30s。', type: 'text' }
  ],
  load: [
    { key: 'LOAD_VUS', label: '峰值 VU', help: '保持阶段并发；上限 1000。', tip: 'VU=虚拟并发用户数，不是服务端线程数；每个 VU 按脚本反复执行。', type: 'number' },
    { key: 'LOAD_START_VUS', label: '起始 VU', help: '爬升起点并发。', type: 'number' },
    { key: 'LOAD_DURATION', label: '总时长', help: 'k6 duration；上限 3600s。', type: 'text' },
    { key: 'LOAD_RAMP_UP_FRACTION', label: '爬升占比', help: '总时长中爬升到起始 VU 的比例（0–1）。', type: 'number' },
    { key: 'LOAD_RAMP_PEAK_FRACTION', label: '峰值爬升占比', help: '继续升到峰值 VU 的比例（0–1）。', type: 'number' }
  ],
  spike: [
    { key: 'SPIKE_BASELINE_VUS', label: '基线 VU', help: '尖峰前的稳定并发。', type: 'number' },
    { key: 'SPIKE_BASELINE_DURATION', label: '基线时长', help: '尖峰前保持时长，例如 30s。', type: 'text' },
    { key: 'SPIKE_RAMP_UP', label: '爬升时长', help: '从基线升到峰值的时长。', type: 'text' },
    { key: 'SPIKE_VUS', label: '峰值 VU', help: '尖峰阶段并发。', type: 'number' },
    { key: 'SPIKE_DURATION', label: '峰值时长', help: '峰值保持时长。', type: 'text' },
    { key: 'SPIKE_RAMP_DOWN', label: '回落时长', help: '从峰值回落到基线的时长。', type: 'text' }
  ]
}

const THRESHOLD_DEFS = [
  { key: 'P95_MS', label: 'P95 阈值', help: 'P95 超过该毫秒数视为不达标。', tip: 'k6 阈值：P95 响应时间上限（毫秒）。', type: 'number' },
  { key: 'P99_MS', label: 'P99 阈值', help: 'P99 超过该毫秒数视为不达标。', type: 'number' },
  { key: 'ERROR_RATE', label: '错误率阈值', help: '0.01 = 1%；超过即不达标。', type: 'number' },
  { key: 'CHECKS_RATE', label: '校验通过率', help: '0.99 = 99%；低于即不达标。', type: 'number' }
]

const DEFAULTS = {
  smoke: { SMOKE_VUS: 1, SMOKE_ITERATIONS: 1, SMOKE_DURATION: '30s' },
  load: {
    LOAD_VUS: 200,
    LOAD_START_VUS: 50,
    LOAD_DURATION: '10m',
    LOAD_RAMP_UP_FRACTION: 0.2,
    LOAD_RAMP_PEAK_FRACTION: 0.2,
    PRODUCT_ID_MIN: 5000,
    PRODUCT_ID_MAX: 20000,
    PRODUCT_ID_DISTRIBUTION: 'uniform',
    PRODUCT_ID_CONCENTRATION: 0.55
  },
  spike: {
    SPIKE_BASELINE_VUS: 50,
    SPIKE_BASELINE_DURATION: '30s',
    SPIKE_RAMP_UP: '10s',
    SPIKE_VUS: 800,
    SPIKE_DURATION: '60s',
    SPIKE_RAMP_DOWN: '10s'
  }
}

const THRESHOLD_DEFAULTS = { P95_MS: 300, P99_MS: 800, ERROR_RATE: 0.01, CHECKS_RATE: 0.99 }

const scenarioOptions = ref(['smoke', 'load', 'spike'])
const catalogNote = ref('场景列表按内置 smoke/load/spike 渲染。')
// Raw text of numeric inputs so a half-typed value ("0.", "1e") is not
// canonicalised away on every keystroke; the parsed number still reaches
// params as soon as it is finite.
const rawNumbers = ref({})

function rawValue(field) {
  if (Object.prototype.hasOwnProperty.call(rawNumbers.value, field.key)) {
    return rawNumbers.value[field.key]
  }
  return props.params?.[field.key] ?? ''
}

function onNumberInput(field, raw) {
  rawNumbers.value = { ...rawNumbers.value, [field.key]: raw }
  if (raw === '') {
    emit('update:params', { ...(props.params || {}), [field.key]: '' })
    return
  }
  const parsed = Number(raw)
  if (!Number.isFinite(parsed)) return
  emit('update:params', { ...(props.params || {}), [field.key]: parsed })
}

function defaultParams(scenario) {
  const base = { ...(DEFAULTS[scenario] || DEFAULTS.load), ...THRESHOLD_DEFAULTS }
  return base
}

onMounted(async () => {
  if (!Object.keys(props.params || {}).length) {
    emit('update:params', defaultParams(props.scenario))
  }
  if (!props.token) return
  try {
    const catalog = await fetchBenchScenarios(props.token)
    const ui = Array.isArray(catalog?.views?.ui) ? catalog.views.ui : []
    const known = ['smoke', 'load', 'spike'].filter((name) => ui.includes(name))
    if (known.length) {
      scenarioOptions.value = known
      catalogNote.value = `场景列表来自 manifest views.ui（${known.join('/')}）。`
      if (!known.includes(props.scenario)) emit('update:scenario', known[0])
    } else {
      catalogNote.value = 'manifest 未列出 UI 场景，使用内置 smoke/load/spike。'
    }
  } catch {
    catalogNote.value = '场景目录不可用（503/50303），使用内置 smoke/load/spike。'
  }
})

watch(
  () => props.scenario,
  (next, previous) => {
    if (!next || next === previous || !DEFAULTS[next]) return
    rawNumbers.value = {}
    emit('update:params', defaultParams(next))
  }
)

const fields = computed(() => FIELD_DEFS[props.scenario] || FIELD_DEFS.load)
const productModel = computed(() => ({
  min: props.params?.PRODUCT_ID_MIN ?? 5000,
  max: props.params?.PRODUCT_ID_MAX ?? 20000,
  distribution: props.params?.PRODUCT_ID_DISTRIBUTION || 'uniform',
  concentration: Math.round((Number(props.params?.PRODUCT_ID_CONCENTRATION) || 0) * 100)
}))

function setScenario(value) {
  emit('update:scenario', value)
}

function setParam(key, raw, type) {
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
  emit('update:params', { ...(props.params || {}), [key]: value })
}
function applyProduct(model) {
  emit('update:params', {
    ...(props.params || {}),
    PRODUCT_ID_MIN: Number(model.min) || 0,
    PRODUCT_ID_MAX: Number(model.max) || 0,
    PRODUCT_ID_DISTRIBUTION: model.distribution,
    PRODUCT_ID_CONCENTRATION: Math.min(1, Math.max(0, (Number(model.concentration) || 0) / 100))
  })
}

const jsonPreview = computed(() => JSON.stringify({ scenario: props.scenario, params: props.previewParams }, null, 2))
</script>

<template>
  <div class="k6-form" data-testid="wizard-k6-form">
    <div class="notice info">
      k6 是本次实验的统一流量触发层；以下配置对所有已选实验目标生效。
    </div>

    <div class="field-row" style="margin-top: 9px">
      <div class="field">
        <label data-tip="smoke=最小完整流程；load=爬坡+保持；spike=短时尖峰；stress/soak 仅 CLI。">
          场景 <span class="key">scenario</span>
        </label>
        <select
          class="input select"
          data-testid="wizard-scenario"
          :value="scenario"
          @change="setScenario($event.target.value)"
        >
          <option v-for="name in scenarioOptions" :key="name" :value="name">{{ SCENARIO_LABELS[name] || name }}</option>
        </select>
        <div class="help">只开放 smoke/load/spike；stress/soak 仅 CLI。</div>
        <div class="help">{{ catalogNote }}</div>
      </div>
      <div v-for="field in fields" :key="field.key" class="field">
        <label :data-tip="field.tip || field.help">
          {{ field.label }} <span class="key">{{ field.key }}</span>
        </label>
        <input
          v-if="field.type === 'number'"
          class="input"
          type="text"
          inputmode="decimal"
          :data-testid="`wizard-param-${field.key}`"
          :value="rawValue(field)"
          @input="onNumberInput(field, $event.target.value)"
        />
        <input
          v-else
          class="input"
          type="text"
          :data-testid="`wizard-param-${field.key}`"
          :value="params?.[field.key]"
          @input="setParam(field.key, $event.target.value, field.type)"
        />
        <div class="help">{{ field.help }}</div>
      </div>
    </div>

    <template v-if="scenario === 'load'">
      <div class="divider"></div>
      <div class="section-head">
        <h4>商品 ID 范围与热度分布</h4>
        <span class="chip" data-tip="k6 采样范围可大于数据库范围，用于缓存穿透；正态分布让中间 ID 更热。">load 专属</span>
      </div>
      <ProductSamplingField :model-value="productModel" @update:model-value="applyProduct" />
    </template>

    <div class="divider"></div>
    <div class="field-row">
      <div v-for="field in THRESHOLD_DEFS" :key="field.key" class="field">
        <label :data-tip="field.tip || field.help">
          {{ field.label }} <span class="key">{{ field.key }}</span>
        </label>
        <input
          v-if="field.type === 'number'"
          class="input"
          type="text"
          inputmode="decimal"
          :data-testid="`wizard-param-${field.key}`"
          :value="rawValue(field)"
          @input="onNumberInput(field, $event.target.value)"
        />
        <input
          v-else
          class="input"
          type="text"
          :data-testid="`wizard-param-${field.key}`"
          :value="params?.[field.key]"
          @input="setParam(field.key, $event.target.value, field.type)"
        />
        <div class="help">{{ field.help }}</div>
      </div>
    </div>

    <div class="field" style="margin-top: 8px">
      <label data-tip="高级用户核对；服务端仍按场景白名单校验参数类型与范围。">
        等效 JSON 预览 <span class="key">{ scenario, params }</span>
      </label>
      <textarea class="input json-preview" rows="8" readonly data-testid="wizard-params-json">{{ jsonPreview }}</textarea>
      <div class="help">这是 createRun 实际收到的 params（含 TRAFFIC_MODE 等流量选择器）。</div>
    </div>
  </div>
</template>

<style scoped>
.k6-form {
  display: flex;
  flex-direction: column;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}
.section-head h4 {
  margin: 0;
  font-size: 13px;
}
.json-preview {
  font-family: var(--mono);
  font-size: 12px;
  line-height: 1.5;
  resize: vertical;
}
</style>
