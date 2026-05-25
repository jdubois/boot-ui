<script setup>
import { ref, computed, onMounted } from 'vue'

const data = ref(null)
const tab = ref('positive')
const filter = ref('')

async function load() {
  const res = await fetch('api/conditions')
  data.value = await res.json()
}

const entries = computed(() => {
  if (!data.value) return []
  const items = tab.value === 'positive' ? data.value.positiveMatches : data.value.negativeMatches
  if (!filter.value) return items
  const f = filter.value.toLowerCase()
  return items.filter(e =>
    (e.autoConfigurationClass || '').toLowerCase().includes(f) ||
    (e.message || '').toLowerCase().includes(f))
})

onMounted(load)
</script>

<template>
  <div>
    <h2><i class="bi bi-check2-circle me-2"></i>Auto-configuration conditions</h2>
    <ul class="nav nav-tabs mb-3">
      <li class="nav-item">
        <a class="nav-link" :class="{ active: tab === 'positive' }" href="#" @click.prevent="tab = 'positive'">
          Positive ({{ data ? data.positiveMatches.length : 0 }})
        </a>
      </li>
      <li class="nav-item">
        <a class="nav-link" :class="{ active: tab === 'negative' }" href="#" @click.prevent="tab = 'negative'">
          Negative ({{ data ? data.negativeMatches.length : 0 }})
        </a>
      </li>
    </ul>
    <input class="form-control mb-3" v-model="filter" placeholder="Filter…" />
    <div v-for="e in entries" :key="e.autoConfigurationClass + e.condition" class="mb-2">
      <div class="d-flex">
        <span class="badge me-2" :class="tab === 'positive' ? 'bg-success' : 'bg-secondary'">{{ e.outcome }}</span>
        <div>
          <strong>{{ e.autoConfigurationClass }}</strong>
          <div class="small text-muted">{{ e.condition }}</div>
          <div class="small">{{ e.message }}</div>
        </div>
      </div>
    </div>
  </div>
</template>
