<script setup>
import { computed } from 'vue'

/**
 * B3-00c run-scoped fault timeline (design §16.8).
 *
 * "开始偏移" is relative to k6 start; 0 injects immediately. Manual recovery
 * carries ttlSeconds=-1 on the wire (the controller normalises it to a manual
 * hold) and disables the TTL input. Instance-fault rows only warn when fewer
 * than two Java nodes are enabled: the backend pre-check, not the form, is the
 * authority that blocks.
 */
const props = defineProps({
  /** [{ value: 'jl-app-2', label: 'app-2' }] */
  appTargets: { type: Array, default: () => [] },
  /** [{ value: 'jl-redis-1', label: 'redis-1' }] */
  storageTargets: { type: Array, default: () => [] },
  enabledNodeCount: { type: Number, default: 0 },
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const ACTIONS = [
  { value: 'kill', label: '停止容器 (kill)' },
  { value: 'start', label: '启动容器 (start)' },
  { value: 'restart', label: '重启容器 (restart)' },
  { value: 'freeze', label: '冻结进程 (SIGSTOP)' },
  { value: 'resume', label: '恢复进程 (SIGCONT)' }
]

const TARGETS = computed(() => {
  const base = [...props.appTargets, ...props.storageTargets]
  for (const row of props.modelValue || []) {
    if (row.target && !base.some((item) => item.value === row.target)) {
      base.push({ value: row.target, label: String(row.target).replace(/^jl-/, '') })
    }
  }
  return base
})
const rows = computed(() => props.modelValue || [])

function defaultTarget() {
  return props.appTargets[0]?.value || props.storageTargets[0]?.value || ''
}

function addRow() {
  emit('update:modelValue', [
    ...rows.value,
    { target: defaultTarget(), action: 'kill', atSeconds: 0, recovery: 'auto', ttlSeconds: 120 }
  ])
}

function removeRow(index) {
  emit('update:modelValue', rows.value.filter((_, position) => position !== index))
}

function patch(index, part) {
  emit('update:modelValue', rows.value.map((row, position) => (position === index ? { ...row, ...part } : row)))
}

function setRecovery(index, value) {
  if (value === 'manual') {
    patch(index, { recovery: 'manual', ttlSeconds: -1 })
    return
  }
  const current = Number(rows.value[index]?.ttlSeconds)
  patch(index, { recovery: 'auto', ttlSeconds: Number.isFinite(current) && current > 0 ? current : 120 })
}

function setAtSeconds(index, raw) {
  const parsed = Number.parseInt(raw, 10)
  patch(index, { atSeconds: Number.isFinite(parsed) && parsed >= 0 ? parsed : 0 })
}

function setTtl(index, raw) {
  const parsed = Number.parseInt(raw, 10)
  patch(index, { ttlSeconds: Number.isFinite(parsed) && parsed > 0 ? parsed : 120 })
}

function isAppTarget(target) {
  return /(?:^|-)app-\d+$/.test(String(target || ''))
}

const instanceWarning = computed(() =>
  rows.value.some((row) => isAppTarget(row.target)) && props.enabledNodeCount < 2
)

const span = computed(() => {
  const maxOffset = rows.value.reduce((max, row) => Math.max(max, Number(row.atSeconds) || 0), 0)
  return Math.max(60, maxOffset)
})

const ticks = computed(() =>
  [0, 30, 60].filter((second) => second <= span.value).map((second) => ({
    second,
    left: Math.min(100, (second / span.value) * 100)
  }))
)

const markers = computed(() =>
  rows.value.map((row, index) => ({
    index,
    left: Math.min(100, ((Number(row.atSeconds) || 0) / span.value) * 100),
    text: `${Number(row.atSeconds) || 0}s · ${labelOf(row.target)} · ${row.action}`
  }))
)

function labelOf(target) {
  const hit = TARGETS.value.find((item) => item.value === target)
  return hit?.label || String(target || '未选择').replace(/^jl-/, '')
}
</script>

<template>
  <div class="fault-editor" data-testid="wizard-fault-editor">
    <p class="hint">
      「开始偏移」相对 k6 启动；0 表示一开始就注入。恢复方式选「永不恢复」时 TTL 置灰（提交 -1），stop-all 仍可清理；
      成功注入的故障会写入 run 时间线。
    </p>

    <table class="data-table fault-table">
      <thead>
        <tr>
          <th>目标</th>
          <th>动作</th>
          <th>开始偏移</th>
          <th>恢复方式</th>
          <th>TTL（秒）</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rows" :key="index" :data-testid="`wizard-fault-row-${index}`">
          <td>
            <select class="input select" :value="row.target" @change="patch(index, { target: $event.target.value })">
              <option v-for="target in TARGETS" :key="target.value" :value="target.value">{{ target.label }}</option>
              <option v-if="!TARGETS.length" value="">无可用目标</option>
            </select>
          </td>
          <td>
            <select class="input select" :value="row.action" @change="patch(index, { action: $event.target.value })">
              <option v-for="action in ACTIONS" :key="action.value" :value="action.value">{{ action.label }}</option>
            </select>
          </td>
          <td>
            <div class="inline-field">
              <input
                class="input short"
                type="number"
                min="0"
                step="1"
                :value="row.atSeconds"
                @input="setAtSeconds(index, $event.target.value)"
              />
              <span class="hint">秒</span>
            </div>
          </td>
          <td>
            <select class="input select" :value="row.recovery" @change="setRecovery(index, $event.target.value)">
              <option value="auto">自动恢复（TTL）</option>
              <option value="manual">永不恢复（手动）</option>
            </select>
          </td>
          <td>
            <input
              class="input short"
              type="number"
              min="1"
              step="1"
              :value="row.recovery === 'manual' ? '' : row.ttlSeconds"
              :disabled="row.recovery === 'manual'"
              :placeholder="row.recovery === 'manual' ? '手动' : ''"
              @input="setTtl(index, $event.target.value)"
            />
          </td>
          <td>
            <button type="button" class="btn ghost" :data-testid="`wizard-fault-remove-${index}`" @click="removeRow(index)">删除</button>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td colspan="6" class="empty">还没有计划内故障；这个步骤可留空。</td>
        </tr>
      </tbody>
    </table>

    <div class="rowline" style="margin-top: 8px">
      <button type="button" class="btn ghost" data-testid="wizard-fault-add" @click="addRow">添加故障</button>
      <span class="chip info" data-tip="目标/动作受基础配置与白名单约束；TTL 上限由控制器校验。">白名单</span>
      <span class="hint">手动恢复提交 ttlSeconds=-1；自动恢复必须 1..max-ttl。</span>
    </div>

    <div v-if="instanceWarning" class="notice warn" style="margin-top: 8px" data-testid="wizard-fault-gate">
      实例故障行需要 ≥2 个已启用 Java 节点；当前只有 {{ enabledNodeCount }} 个。这里只提示，不阻断创建，
      后端预检查会给出最终判定。
    </div>

    <div class="timeline" data-testid="wizard-fault-preview">
      <div class="timeline-track">
        <span v-for="tick in ticks" :key="tick.second" class="tick" :style="{ left: `${tick.left}%` }">
          <b></b><em>{{ tick.second }}s</em>
        </span>
        <span
          v-for="marker in markers"
          :key="marker.index"
          class="marker"
          :style="{ left: `${marker.left}%` }"
          :title="marker.text"
        >{{ marker.text }}</span>
      </div>
      <div class="timeline-labels">
        <span>0s 启动</span>
        <span>30s</span>
        <span>60s 预览</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fault-table select,
.fault-table .input {
  min-width: 110px;
}
.inline-field {
  display: flex;
  align-items: center;
  gap: 6px;
}
.short {
  width: 90px;
}
.timeline {
  margin-top: 12px;
}
.timeline-track {
  position: relative;
  height: 34px;
  border-radius: 8px;
  background: #15202c;
  border: 1px solid var(--panel-border);
}
.tick {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: rgba(207, 227, 255, 0.18);
}
.tick b {
  position: absolute;
  top: 0;
  left: -2px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--text-muted);
}
.tick em {
  position: absolute;
  top: 6px;
  left: 4px;
  font-style: normal;
  font-size: 10px;
  color: var(--text-muted);
}
.marker {
  position: absolute;
  top: 18px;
  transform: translateX(-50%);
  border-radius: 999px;
  background: rgba(56, 211, 159, 0.18);
  border: 1px solid rgba(56, 211, 159, 0.5);
  color: var(--text);
  font-size: 10px;
  padding: 1px 6px;
  white-space: nowrap;
}
.timeline-labels {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 4px;
}
.chip.info {
  color: var(--text);
}
</style>
