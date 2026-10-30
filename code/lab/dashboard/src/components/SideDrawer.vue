<script setup>
import { onBeforeUnmount, watch } from 'vue'

/**
 * Right-hand drawer used by the monitor page for tools that must stay usable
 * while the KPI/timeline sheet remains visible. It is a real dialog surface:
 * Esc closes it, the backdrop does not swallow clicks on the page behind it,
 * and the body scroll is released on unmount.
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  hint: { type: String, default: '' },
  testid: { type: String, default: 'side-drawer' },
  width: { type: String, default: 'min(760px, 94vw)' }
})

const emit = defineEmits(['close'])

function onKeydown(event) {
  if (event.key === 'Escape' && props.open) emit('close')
}

watch(
  () => props.open,
  (open) => {
    document.body.style.overflow = open ? 'hidden' : ''
    if (open) window.addEventListener('keydown', onKeydown)
    else window.removeEventListener('keydown', onKeydown)
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="drawer-layer" data-testid="drawer-layer">
      <div class="drawer-backdrop" aria-hidden="true" @click="emit('close')"></div>
      <aside
        class="side-drawer"
        role="dialog"
        aria-modal="true"
        :aria-label="title"
        :data-testid="testid"
        :style="{ width }"
      >
        <header class="side-drawer-head">
          <div>
            <h2>{{ title }}</h2>
            <p v-if="hint" class="hint">{{ hint }}</p>
          </div>
          <button
            type="button"
            class="btn ghost drawer-close"
            data-testid="drawer-close"
            aria-label="关闭抽屉"
            @click="emit('close')"
          >×</button>
        </header>
        <div class="side-drawer-body">
          <slot />
        </div>
      </aside>
    </div>
  </Teleport>
</template>
