<script setup>
/**
 * One navigation entry per workspace section. The order is the operator's
 * workflow: observe the environment, load a scenario, tune instances, run
 * benchmarks, watch metrics, inject faults, then read the ledger.
 */
defineProps({
  sections: { type: Array, required: true },
  active: { type: String, required: true }
})

defineEmits(['select'])
</script>

<template>
  <aside class="workspace-sidebar" aria-label="工作区导航">
    <p class="sidebar-title">工作区</p>
    <nav class="sidebar-nav">
      <button
        v-for="item in sections"
        :key="item.id"
        type="button"
        class="nav-btn sidebar-btn"
        :class="{ active: active === item.id }"
        :data-testid="`nav-${item.id}`"
        :aria-current="active === item.id ? 'page' : undefined"
        :title="item.hint"
        @click="$emit('select', item.id)"
      >
        <span class="sidebar-label">{{ item.label }}</span>
        <span v-if="item.hint" class="sidebar-hint">{{ item.hint }}</span>
      </button>
    </nav>
  </aside>
</template>
