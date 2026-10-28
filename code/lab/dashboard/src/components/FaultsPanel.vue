<script setup>
import { computed } from 'vue'
import AnimationLab from './AnimationLab.vue'
import CacheLab from './CacheLab.vue'
import ChaosActions from './ChaosActions.vue'

/**
 * Fault and cache experiments: the unchanged CacheLab and AnimationLab mount
 * side by side with the container-fault card. AnimationLab still owns its
 * topology / instances / cache-flow tabs, so CacheFlow stays reachable without
 * duplicating it here.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: false },
  syncTick: { type: Number, default: 0 },
  containers: { type: Array, default: () => [] },
  faults: { type: Array, default: () => [] }
})

defineEmits(['open-thread'])

const instances = computed(() => props.containers
  .filter((item) => /^jl-app-\d+$/.test(item.name))
  .map((item) => item.name.replace(/^jl-/, ''))
  .sort())
</script>

<template>
  <div class="faults-panel">
    <ChaosActions
      :token="token"
      :active="active"
      :instances="instances.length ? instances : ['app-1', 'app-2', 'app-3']"
      :faults="faults"
      @changed="$emit('changed')"
    />
    <CacheLab :token="token" :active="active" />
    <AnimationLab :token="token" :active="active" @open-thread="$emit('open-thread', $event)" />
  </div>
</template>
