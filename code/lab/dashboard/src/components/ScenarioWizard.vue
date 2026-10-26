<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  scenarios: { type: Array, default: () => [] },
  state: { type: Object, default: null },
  busy: { type: String, default: '' }
})
defineEmits(['start', 'step', 'skip', 'pause', 'resume', 'reset', 'refresh'])

// The controller owns the scenario schema; the console only mirrors the status
// it reports. A step the operator does not want to inject is skipped, not reset.
const status = computed(() => props.state?.status || 'idle')
const steps = computed(() => props.state?.steps || [])
const currentStep = computed(() => props.state?.currentStep ?? 0)
const current = computed(() => (status.value === 'running' ? steps.value[currentStep.value] || null : null))
const progress = computed(() => {
  const total = props.state?.totalSteps || 0
  return total ? Math.round((currentStep.value / total) * 100) : 0
})

const canStart = computed(() => ['idle', 'completed', 'failed'].includes(status.value))
const canRun = computed(() => status.value === 'running')

const picked = ref('')
watch(
  () => props.scenarios,
  (list) => {
    if (!list.some((item) => item.id === picked.value)) {
      picked.value = list[0]?.id || ''
    }
  },
  { immediate: true }
)

const pickedScenario = computed(() => props.scenarios.find((item) => item.id === picked.value) || null)

function stepState(index) {
  if (status.value === 'completed') return 'done'
  if (status.value === 'idle') return 'pending'
  if (index < currentStep.value) return 'done'
  if (index === currentStep.value && canRun.value) return 'current'
  return 'pending'
}

function actionLabel(action) {
  return {
    start: '启动容器',
    kill: '停止容器',
    restart: '重启容器',
    freeze: '冻结进程 (SIGSTOP)',
    resume: '恢复进程 (SIGCONT)'
  }[action] || action
}

function durationHint(hint) {
  if (!hint) return '时长未标注'
  return hint < 60 ? `约 ${Math.round(hint)}s` : `约 ${Math.round(hint / 60)}min`
}
</script>

<template>
  <section class="card wizard-card">
    <div class="card-head">
      <h2>实验向导</h2>
      <div class="wizard-head-actions">
        <span class="pill" :class="status" data-testid="wizard-status">{{ status }}</span>
        <button type="button" class="btn ghost" data-testid="wizard-refresh" :disabled="Boolean(busy)" @click="$emit('refresh')">
          {{ busy === 'refresh' ? '同步中…' : '同步状态' }}
        </button>
      </div>
    </div>

    <div class="wizard-pick">
      <label class="pick-label">
        场景
        <select v-model="picked" class="input select" data-testid="scenario-select" :disabled="!canStart || !scenarios.length">
          <option v-for="item in scenarios" :key="item.id" :value="item.id">
            {{ item.name }}（{{ item.stepCount }} 步 · {{ durationHint(item.durationHintSeconds) }}）
          </option>
        </select>
      </label>
      <button
        type="button"
        class="btn"
        data-testid="scenario-start"
        :disabled="!canStart || !picked || Boolean(busy)"
        @click="$emit('start', picked)"
      >
        {{ busy === 'start' ? '载入中…' : '载入场景' }}
      </button>
      <p v-if="pickedScenario" class="scenario-desc">{{ pickedScenario.description }}</p>
      <p v-if="!canStart" class="hint">当前场景进行中；「复位」后才能在下面的列表里换场景。</p>
    </div>

    <div class="wizard-progress">
      <div class="progress-track">
        <div class="progress-bar" :style="{ width: `${progress}%` }"></div>
      </div>
      <span class="progress-label" data-testid="progress-label">
        {{ currentStep }} / {{ state?.totalSteps || 0 }} 步 ·
        活动故障 {{ state?.activeActions || 0 }} 个 ·
        {{ state?.resetMode === 'auto' ? '结束后自动还原' : '结束后手动还原' }}
      </span>
    </div>

    <p v-if="state?.lastError" class="error-banner" role="alert" data-testid="step-error">上一步失败：{{ state.lastError }}</p>

    <ol v-if="steps.length" class="step-list" data-testid="step-list">
      <li v-for="(step, index) in steps" :key="index" class="step" :class="stepState(index)" :data-testid="`step-item-${index}`">
        <span class="step-marker">{{ index + 1 }}</span>
        <div class="step-body">
          <div class="step-title">
            <strong>{{ step.title || actionLabel(step.action) }}</strong>
            <code>{{ actionLabel(step.action) }} · {{ step.target }}{{ step.ttlSeconds ? ` · TTL ${step.ttlSeconds}s` : '' }}</code>
          </div>
          <p v-if="step.text" class="step-text">{{ step.text }}</p>
          <dl v-if="index === currentStep && step.explain && canRun" class="step-explain" data-testid="step-explain">
            <template v-if="step.explain.symptom">
              <dt>现象</dt>
              <dd>{{ step.explain.symptom }}</dd>
            </template>
            <template v-if="step.explain.rootCause">
              <dt>根因</dt>
              <dd>{{ step.explain.rootCause }}</dd>
            </template>
            <template v-if="step.explain.fix">
              <dt>处方</dt>
              <dd>{{ step.explain.fix }}</dd>
            </template>
            <template v-if="step.explain.expectation">
              <dt>预期曲线</dt>
              <dd>{{ step.explain.expectation }}</dd>
            </template>
          </dl>
        </div>
      </li>
    </ol>
    <p v-else class="empty">选择场景后点「载入场景」，控制器会校验 JSON 并列出时间线。</p>

    <div class="wizard-actions">
      <button type="button" class="btn" data-testid="action-step" :disabled="!canRun || Boolean(busy)" @click="$emit('step')">
        {{ busy === 'step' ? '执行中…' : '执行下一步' }}
      </button>
      <button type="button" class="btn" data-testid="action-skip" :disabled="!canRun || Boolean(busy)" @click="$emit('skip')">
        {{ busy === 'skip' ? '跳过中…' : '跳过此步' }}
      </button>
      <button type="button" class="btn" data-testid="action-pause" :disabled="!canRun || Boolean(busy)" @click="$emit('pause')">
        {{ busy === 'pause' ? '暂停中…' : '暂停（冻结 TTL）' }}
      </button>
      <button
        type="button"
        class="btn"
        data-testid="action-resume"
        :disabled="status !== 'paused' || Boolean(busy)"
        @click="$emit('resume')"
      >
        {{ busy === 'resume' ? '继续中…' : '继续（剩余 TTL）' }}
      </button>
      <button type="button" class="btn danger" data-testid="action-reset" :disabled="Boolean(busy)" @click="$emit('reset')">
        {{ busy === 'reset' ? '复位中…' : '复位（stop-all）' }}
      </button>
    </div>
    <p class="hint">
      step / skip / pause 仅在 running 可用，resume 仅在 paused 可用，reset 在任意状态可用。
      暂停期间 TTL 倒计时冻结，容器不会自动恢复，可以随时查看日志与面板。
    </p>
  </section>
</template>
