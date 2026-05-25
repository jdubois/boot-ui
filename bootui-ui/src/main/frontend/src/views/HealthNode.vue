<script>
export default { name: 'HealthNode' }
</script>

<script setup>
const props = defineProps({ node: { type: Object, required: true } })

const statusClass = s => ({
  UP: 'bg-success',
  DOWN: 'bg-danger',
  OUT_OF_SERVICE: 'bg-warning text-dark',
  UNKNOWN: 'bg-secondary'
}[s] || 'bg-secondary')
</script>

<template>
  <div class="card mb-2">
    <div class="card-header d-flex justify-content-between">
      <strong>{{ node.name }}</strong>
      <span class="badge" :class="statusClass(node.status)">{{ node.status }}</span>
    </div>
    <div v-if="(node.components && node.components.length) || node.details" class="card-body">
      <pre v-if="node.details" class="small mb-0">{{ JSON.stringify(node.details, null, 2) }}</pre>
      <HealthNode v-for="c in (node.components || [])" :key="c.name" :node="c" />
    </div>
  </div>
</template>
