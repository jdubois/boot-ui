<script setup>
import {isPlainObject} from '../utils/format.js'
import HealthDetails from './HealthDetails.vue'

defineProps({
  node: {type: Object, required: true},
  depth: {type: Number, default: 0}
})

const statusClass = (s) =>
  ({
    UP: 'bg-success',
    DOWN: 'bg-danger',
    OUT_OF_SERVICE: 'bg-warning text-dark',
    UNKNOWN: 'bg-secondary',
    DISABLED: 'bg-secondary'
  })[s] || 'bg-secondary'

const statusIcon = (s) =>
  ({
    UP: 'bi-check-circle-fill text-success',
    DOWN: 'bi-x-circle-fill text-danger',
    OUT_OF_SERVICE: 'bi-exclamation-triangle-fill text-warning',
    UNKNOWN: 'bi-question-circle-fill text-secondary',
    DISABLED: 'bi-slash-circle-fill text-secondary'
  })[s] || 'bi-question-circle-fill text-secondary'

const childCount = (node) => (node.components || []).length

function hasDetailValue(value) {
  if (value === null || value === undefined || value === '') return false
  if (Array.isArray(value)) return value.some(hasDetailValue)
  if (isPlainObject(value)) return Object.values(value).some(hasDetailValue)
  return true
}

const detailCount = (node) => {
  if (!hasDetailValue(node.details)) return 0
  if (Array.isArray(node.details)) return node.details.filter(hasDetailValue).length
  if (isPlainObject(node.details)) return Object.values(node.details).filter(hasDetailValue).length
  return 1
}

const hasDetails = (node) => detailCount(node) > 0
</script>

<template>
  <details :open="depth < 2 || node.status !== 'UP'" class="card mb-2">
    <summary class="card-header d-flex justify-content-between align-items-center gap-2">
      <span class="d-flex align-items-center gap-2">
        <i :class="statusIcon(node.status)" class="bi"></i>
        <strong>{{ node.name }}</strong>
        <span v-if="childCount(node)" class="text-muted small">
          {{ childCount(node) }} {{ childCount(node) === 1 ? 'component' : 'components' }}
        </span>
        <span v-if="hasDetails(node)" class="text-muted small">
          {{ detailCount(node) }} {{ detailCount(node) === 1 ? 'detail' : 'details' }}
        </span>
      </span>
      <span :class="statusClass(node.status)" class="badge">{{ node.status }}</span>
    </summary>

    <div v-if="childCount(node) || hasDetails(node)" class="card-body">
      <section v-if="hasDetails(node)" class="mb-3">
        <h6 class="text-muted text-uppercase small mb-2">Details</h6>
        <HealthDetails :value="node.details" />
      </section>

      <section v-if="childCount(node)">
        <h6 v-if="hasDetails(node)" class="text-muted text-uppercase small mb-2">Components</h6>
        <HealthNode v-for="c in node.components" :key="c.name" :depth="depth + 1" :node="c" />
      </section>
    </div>
  </details>
</template>
