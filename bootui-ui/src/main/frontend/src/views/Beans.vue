<script setup>
import { ref, computed, onMounted } from 'vue'

const data = ref(null)
const filter = ref('')
const classification = ref('')

async function load() {
  const res = await fetch('api/beans')
  data.value = await res.json()
}

const filtered = computed(() => {
  if (!data.value) return []
  return data.value.beans.filter(b => {
    if (classification.value && b.classification !== classification.value) return false
    if (filter.value) {
      const f = filter.value.toLowerCase()
      return b.name.toLowerCase().includes(f) ||
             (b.type && b.type.toLowerCase().includes(f))
    }
    return true
  })
})

onMounted(load)
</script>

<template>
  <div>
    <h2><i class="bi bi-diagram-3 me-2"></i>Beans</h2>
    <p class="text-muted">{{ data ? data.total : 0 }} beans · {{ filtered.length }} shown</p>

    <div class="row g-2 mb-3">
      <div class="col-md-8">
        <input class="form-control" v-model="filter" placeholder="Filter by name or type…" />
      </div>
      <div class="col-md-4">
        <select class="form-select" v-model="classification">
          <option value="">All classifications</option>
          <option value="APPLICATION">Application</option>
          <option value="FRAMEWORK">Spring framework</option>
          <option value="BOOTUI">BootUI</option>
          <option value="PLATFORM">Platform</option>
          <option value="OTHER">Other</option>
        </select>
      </div>
    </div>

    <div class="table-responsive">
      <table class="table table-sm table-hover">
        <thead>
          <tr>
            <th>Name</th>
            <th>Type</th>
            <th>Scope</th>
            <th>Class.</th>
            <th>Dependencies</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in filtered" :key="b.name">
            <td><code>{{ b.name }}</code></td>
            <td><small>{{ b.type }}</small></td>
            <td>{{ b.scope }}</td>
            <td><span class="badge bg-light text-dark">{{ b.classification }}</span></td>
            <td><small class="text-muted">{{ b.dependencies.join(', ') }}</small></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
