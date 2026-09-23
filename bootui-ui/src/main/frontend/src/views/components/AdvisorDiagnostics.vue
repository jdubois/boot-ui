<script setup>
import {ref, useId} from 'vue'

defineProps({
  diagnostics: {
    type: /** @type {import('vue').PropType<{source: string, unit?: string, level: string, message: string}[]>} */ (
      Array
    ),
    default: () => []
  }
})

const LEVEL_CLASSES = {
  ERROR: 'text-bg-danger',
  WARNING: 'text-bg-warning',
  INFO: 'text-bg-secondary'
}

const expanded = ref(false)
const listId = useId()

function levelClass(level) {
  return LEVEL_CLASSES[level] || 'text-bg-light border text-dark'
}
</script>

<template>
  <div v-if="diagnostics.length > 0" class="card mb-3">
    <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
      <div>
        <div class="fw-semibold">Scan diagnostics</div>
        <div class="text-muted small">
          {{ diagnostics.length }} {{ diagnostics.length === 1 ? 'note' : 'notes' }} — not counted as findings
        </div>
      </div>
      <button
        class="btn btn-sm btn-outline-secondary"
        type="button"
        :aria-controls="listId"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        {{ expanded ? 'Hide' : 'Show' }} diagnostics
      </button>
    </div>
    <ul v-if="expanded" :id="listId" class="list-group list-group-flush">
      <li v-for="(diagnostic, index) in diagnostics" :key="index" class="list-group-item small">
        <span :class="levelClass(diagnostic.level)" class="badge me-2">{{ diagnostic.level }}</span>
        <span class="font-monospace">{{ diagnostic.source }}</span>
        <span v-if="diagnostic.unit" class="font-monospace text-muted ms-1">[{{ diagnostic.unit }}]</span>
        <span class="ms-2">{{ diagnostic.message }}</span>
      </li>
    </ul>
  </div>
</template>
