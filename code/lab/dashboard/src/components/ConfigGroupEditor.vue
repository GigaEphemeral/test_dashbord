<script setup>
import { computed, ref, watch } from 'vue'
import InstanceConfigEditor from './InstanceConfigEditor.vue'
import { describeError, fetchInstanceConfig, fetchInstanceEffective } from '../api'

/**
 * Wizard step 3: per-instance JVM configuration.
 *
 * This used to be a second draft model with its own defaults and dead
 * apply/rollback buttons, which is why the wizard and the 实例与 JVM page
 * disagreed. It now reads and writes the SAME lab_instance_config ledger
 * through the InstanceConfigEditor, so there is one source of truth:
 *
 * - the table mirrors each node's stored status/revision/readback;
 * - the embedded editor owns validate/save/apply/rollback/restart;
 * - `update:config` emits the stored config snapshot per node so the run draft
 *   still records what was applied when the run is created.
 */
const props = defineProps({
  token: { type: String, default: '' },
  nodes: { type: Array, default: () => [] },
  allNodes: { type: Array, default: () => [] },
  config: { type: Object, default: () => ({}) },
  pairMode: { type: Boolean, default: false }
})

const emit = defineEmits(['update:config'])

const STATUS_LABELS = {
  NOT_CONFIGURED: '未配置',
  DRAFT: '草稿',
  PENDING_RESTART: '待重启',
  APPLYING: '应用中',
  APPLIED: '已应用',
  ROLLED_BACK: '已回滚',
  FAILED: '失败',
  DEGRADED: '降级'
}
const STATUS_TONES = {
  NOT_CONFIGURED: 'muted',
  DRAFT: 'warn',
  PENDING_RESTART: 'warn',
  APPLYING: 'warn',
  APPLIED: 'ok',
  ROLLED_BACK: 'ok',
  FAILED: 'down',
  DEGRADED: 'down'
}

const views = ref({})
const effective = ref({})
const loading = ref(false)
const error = ref('')

const rows = computed(() => (props.allNodes.length ? props.allNodes : props.nodes).map((node) => {
  const enabled = props.nodes.includes(node)
  const view = views.value[node]
  const live = effective.value[node]
  return {
    node,
    enabled,
    status: view?.status || (enabled ? 'NOT_CONFIGURED' : '—'),
    revision: view?.revision || 0,
    args: view?.jvmArgs?.length ? view.jvmArgs.join(' ') : '未配置',
    readback: live?.readback || (enabled ? '未验证' : '—')
  }
}))

function statusLabel(status) {
  return STATUS_LABELS[status] || status
}

function statusTone(status) {
  return STATUS_TONES[status] || 'muted'
}

async function loadAll() {
  if (!props.token) return
  loading.value = true
  error.value = ''
  const snapshot = {}
  try {
    await Promise.all(props.nodes.map(async (node) => {
      try {
        // Await first, then merge: spreading views inside the literal would
        // snapshot the object BEFORE the await and lose concurrent writes.
        const view = await fetchInstanceConfig(props.token, node)
        views.value = { ...views.value, [node]: view }
        if (view?.config) snapshot[node] = view.config
      } catch (err) {
        error.value = `读取 ${node} 配置失败：${describeError(err)}`
      }
      try {
        const live = await fetchInstanceEffective(props.token, node)
        effective.value = { ...effective.value, [node]: live }
      } catch {
        effective.value = { ...effective.value, [node]: null }
      }
    }))
    emit('update:config', snapshot)
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.token, props.nodes.join(',')],
  () => loadAll(),
  { immediate: true }
)
</script>

<template>
  <div class="config-editor" data-testid="wizard-config-editor">
    <p class="hint">
      这里的配置与「实例与 JVM」页是<strong>同一份实例台账</strong>：保存、应用并重启、重启 JVM、回滚都在下方编辑器完成。
      未启用节点不参与本实验，不会下发。
    </p>
    <p v-if="loading" class="hint">读取实例配置…</p>
    <p v-if="error" class="error-banner" role="alert" data-testid="wizard-config-error">{{ error }}</p>

    <table class="data-table config-table">
      <thead>
        <tr>
          <th>实例</th>
          <th>启用</th>
          <th>配置状态</th>
          <th>revision</th>
          <th>目标参数</th>
          <th>回读</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in rows"
          :key="row.node"
          :class="{ disabled: !row.enabled }"
          :data-testid="`wizard-config-row-${row.node}`"
        >
          <td><code>{{ row.node }}</code></td>
          <td>
            <span v-if="row.enabled" class="chip on">启用</span>
            <span v-else class="chip" data-testid="wizard-config-disabled">
              未启用 · 未启用节点不参与本实验
            </span>
          </td>
          <td>
            <span class="badge" :class="statusTone(row.status)">{{ statusLabel(row.status) }}</span>
          </td>
          <td>{{ row.revision || '—' }}</td>
          <td class="hint config-args">{{ row.args }}</td>
          <td>{{ row.readback }}</td>
        </tr>
      </tbody>
    </table>

    <div class="config-columns">
      <section
        v-for="node in nodes"
        :key="node"
        class="config-column"
        :data-testid="`wizard-config-column-${node}`"
      >
        <h4><code>{{ node }}</code> · JVM 配置</h4>
        <InstanceConfigEditor
          :token="token"
          :instance="node"
          :active="true"
          :limited-observability="node === 'seckill-app-jdk8'"
          @changed="loadAll"
        />
      </section>
    </div>
    <p v-if="!nodes.length" class="hint">请先在向导第 1 步选择至少一个 Java 节点。</p>
  </div>
</template>

<style scoped>
.config-args {
  max-width: 380px;
  overflow-wrap: anywhere;
}

/* Three instances side by side: parameters line up row-for-row for comparison. */
.config-columns {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(380px, 1fr));
  gap: 14px;
  align-items: start;
}

.config-column h4 {
  margin: 0 0 6px;
  font-size: 0.9rem;
  color: var(--text-muted);
}

.config-table tr.disabled {
  opacity: 0.55;
}
</style>
