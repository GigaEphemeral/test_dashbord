<script setup>
import { computed } from 'vue'
import InstanceConfigEditor from './InstanceConfigEditor.vue'

/**
 * B4-04 configuration tab: one editor per app instance plus the jdk8 control
 * shell. The jdk8 shell is only editable while its container runs; an absent or
 * stopped jl-seckill-app-jdk8 stays a greyed row with no apply button.
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

    <InstanceConfigEditor
      v-if="jdk8Running"
      :token="token"
      instance="seckill-app-jdk8"
      :active="active"
      limited-observability
    />

    <article
      v-else
      class="card instance-card off jdk8-offline"
      data-testid="instance-config-jdk8-unavailable"
    >
      <header class="instance-head">
        <div>
          <h3><code>{{ JDK8_CONTAINER }}</code></h3>
          <p class="instance-sub">seckill-app-jdk8 · JDK8 对照外壳</p>
        </div>
        <div class="instance-badges">
          <span class="badge muted">{{ jdk8Container?.state || 'absent' }}</span>
          <span class="badge warn">LIMITED-OBSERVABILITY</span>
        </div>
      </header>
      <p class="hint">
        容器未运行（dual-jdk profile）：启动 <code>jl-seckill-app-jdk8</code> 后才可编辑；
        当前不显示应用/回滚按钮，也不会写入任何配置。
      </p>
      <p class="hint">
        该外壳只提供有限观测：app 级热回读为 <code>READBACK_UNAVAILABLE</code>，
        池/GC 日志回读不可用时保持「无数据」。
      </p>
    </article>
  </section>
</template>

<style scoped>
.jdk8-offline {
  opacity: 0.62;
  filter: grayscale(0.8);
}
</style>
