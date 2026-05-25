<script setup>
import { ref, onMounted } from 'vue'
import HealthNode from './HealthNode.vue'

const root = ref(null)

async function load() {
  const res = await fetch('api/health')
  root.value = await res.json()
}

onMounted(load)
</script>

<template>
  <div>
    <h2><i class="bi bi-heart-pulse me-2"></i>Health</h2>
    <HealthNode v-if="root" :node="root" />
    <p v-else class="text-muted">Loading…</p>
  </div>
</template>
