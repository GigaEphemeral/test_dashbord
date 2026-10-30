<script setup>
import { computed } from 'vue'
import InstanceConfigEditor from './InstanceConfigEditor.vue'

/**
 * B4-04 configuration tab: one editor per app instance plus the jdk8 control
 * shell. The jdk8 config is ledger-only, so the editor is ALWAYS available;
 * only apply/restart need the container (the rebuild reports a clear error when
 * it is absent), which is why a stopped shell still shows editable fields.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  containers: { type: Array, default: () => [] }
})

const APP_INSTANCES = ['app-1', 'app-2', 'app-3']
const JDK8_CONTAINER = 'jl-seckill-app-jdk8'

const jdk8Container = computed(() => (
  (props.containers || []).find((item) => item.name === JDK8_CONTAINER) || null
))
const jdk8Running = computed(() => jdk8Container.value?.state === 'running')
</script>

<template>
  <section class="instances-page" data-testid="instances-config-page">
    <p class="hint">
      配置组按实例保存：<strong>校验</strong>只做 dry-run 不落库，<strong>仅保存</strong>写入 DRAFT，
      只有<strong>应用并重启</strong>才会重建容器。app-1/2/3 固定 JDK21；JDK8 对照实例是独立外壳。
    </p>

    <InstanceConfigEditor
      v-for="name in APP_INSTANCES"
      :key="name"
      :token="token"
      :instance="name"
      :active="active"
    />

    <p v-if="!jdk8Running" class="hint" data-testid="instance-config-jdk8-offline-hint">
      <code>{{ JDK8_CONTAINER }}</code> 当前为 {{ jdk8Container?.state || 'absent' }}（dual-jdk profile）：
      仍可校验与保存配置；应用/重启前需先启动该容器。
    </p>
    <InstanceConfigEditor
      :token="token"
      instance="seckill-app-jdk8"
      :active="active"
      limited-observability
    />
  </section>
</template>

<style scoped>
.jdk8-offline {
  opacity: 0.62;
  filter: grayscale(0.8);
}
</style>
