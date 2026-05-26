<script setup>
import { ref, computed, onMounted } from 'vue'

const data = ref(null)
const filter = ref('')
const message = ref(null)

async function load() {
  const res = await fetch('api/loggers')
  data.value = await res.json()
}

const filtered = computed(() => {
  if (!data.value) return []
  if (!filter.value) return data.value.loggers
  const f = filter.value.toLowerCase()
  return data.value.loggers.filter(l => l.name.toLowerCase().includes(f))
})

async function changeLevel(logger, level) {
  const body = level ? { level } : {}
  const res = await fetch('api/loggers/' + encodeURIComponent(logger.name), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (res.ok) {
    const updated = await res.json()
    const i = data.value.loggers.findIndex(l => l.name === logger.name)
    if (i >= 0) data.value.loggers[i] = updated
    message.value = 'Level updated for ' + logger.name
    setTimeout(() => { message.value = null }, 3000)
  }
}

const levelClass = l => ({
  TRACE: 'text-secondary',
  DEBUG: 'text-info',
  INFO: 'text-success',
  WARN: 'text-warning',
  ERROR: 'text-danger',
  FATAL: 'text-danger fw-bold',
  OFF: 'text-muted'
}[l] || 'text-secondary')

onMounted(load)
</script>

<template>
  <div>
    <h2><i class="bi bi-journal-text me-2"></i>Loggers</h2>
    <div v-if="message" class="alert alert-success">{{ message }}</div>
    <input class="form-control mb-3" v-model="filter" placeholder="Filter loggers by name…" />
    <div class="table-responsive">
      <table class="table table-sm table-hover loggers-table">
        <colgroup>
          <col class="loggers-table-name" />
          <col class="loggers-table-level" />
          <col class="loggers-table-level" />
          <col class="loggers-table-actions" />
        </colgroup>
        <thead>
          <tr>
            <th>Logger</th>
            <th>Configured</th>
            <th>Effective</th>
            <th>Set level</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in filtered" :key="l.name">
            <td><code class="text-truncate d-block" :title="l.name">{{ l.name }}</code></td>
            <td :class="levelClass(l.configuredLevel)">{{ l.configuredLevel || '—' }}</td>
            <td :class="levelClass(l.effectiveLevel)">{{ l.effectiveLevel || '—' }}</td>
            <td>
              <div class="btn-group btn-group-sm">
                <button v-for="lvl in data.availableLevels" :key="lvl"
                        class="btn btn-outline-secondary"
                        :class="{ active: l.configuredLevel === lvl }"
                        @click="changeLevel(l, lvl)">{{ lvl }}</button>
                <button class="btn btn-outline-secondary" @click="changeLevel(l, null)" title="Reset">↺</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.loggers-table {
  table-layout: fixed;
  min-width: 760px;
}

.loggers-table-name {
  width: 34%;
}

.loggers-table-level {
  width: 15%;
}

.loggers-table-actions {
  width: 36%;
}
</style>
