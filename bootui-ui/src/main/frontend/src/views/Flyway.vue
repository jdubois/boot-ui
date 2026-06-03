<script setup>
import {apiFetch} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'

const report = ref(null)
const error = ref(null)
const flywayPresent = ref(true)
const filter = ref('')

async function load() {
  try {
    const res = await apiFetch('api/flyway/migrations')
    if (res.status === 404) {
      flywayPresent.value = false
      return
    }
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Flyway migrations')
  }
}

const databases = computed(() => {
  if (!report.value) return []
  const f = filter.value.toLowerCase()
  if (!f) return report.value.databases
  return report.value.databases
    .map((db) => ({
      ...db,
      migrations: db.migrations.filter(
        (m) =>
          (m.version || '').toLowerCase().includes(f) ||
          (m.description || '').toLowerCase().includes(f) ||
          (m.script || '').toLowerCase().includes(f)
      )
    }))
    .filter((db) => db.migrations.length > 0)
})

const stateClass = (state) => {
  const s = (state || '').toLowerCase()
  if (s.includes('success') || s === 'applied') return 'bg-success'
  if (s.includes('pending')) return 'bg-warning text-dark'
  if (s.includes('fail') || s.includes('error')) return 'bg-danger'
  if (s.includes('out of order') || s.includes('missing') || s.includes('ignored')) return 'bg-info text-dark'
  return 'bg-secondary'
}

onMounted(load)
</script>

<template>
  <div>
    <PanelHeader icon="bi-arrow-up-right-circle" title="Flyway migrations" :error="error" />

    <div v-if="!flywayPresent" class="alert alert-info">
      Flyway is not on the classpath of this application. Add the <code>flyway-core</code> dependency to see schema
      migrations here.
    </div>

    <div v-else-if="report && report.databases.length === 0" class="alert alert-secondary">
      Flyway is on the classpath, but no Flyway beans were detected in the application context.
    </div>

    <template v-else-if="report">
      <div class="row g-2 mb-3">
        <div class="col-md-6">
          <input v-model="filter" class="form-control" placeholder="Filter by version, description, or script…" />
        </div>
        <div class="col-md-6 text-end small text-muted align-self-center">
          {{ report.total }} migration(s) across {{ report.databases.length }} database(s)
        </div>
      </div>

      <div v-for="db in databases" :key="db.name" class="card mb-3">
        <div class="card-header d-flex justify-content-between align-items-center">
          <span>
            <i class="bi bi-database me-1"></i><code>{{ db.name }}</code>
          </span>
          <span class="small">
            <span class="me-2"
              >Current: <strong>{{ db.currentVersion || '—' }}</strong></span
            >
            <span class="badge bg-success me-1">{{ db.applied }} applied</span>
            <span v-if="db.pending > 0" class="badge bg-warning text-dark">{{ db.pending }} pending</span>
          </span>
        </div>
        <div class="card-body p-0">
          <table class="table table-sm table-hover mb-0">
            <thead>
              <tr>
                <th style="width: 110px">Version</th>
                <th>Description</th>
                <th>Type</th>
                <th>State</th>
                <th>Installed on</th>
                <th class="text-end">Exec (ms)</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in db.migrations" :key="(m.version || '') + m.script">
                <td>
                  <code>{{ m.version || '—' }}</code>
                </td>
                <td>
                  {{ m.description }}
                  <div class="small text-muted">
                    <code>{{ m.script }}</code>
                  </div>
                </td>
                <td>{{ m.type }}</td>
                <td>
                  <span :class="stateClass(m.state)" class="badge">{{ m.state }}</span>
                </td>
                <td class="small">{{ m.installedOn || '—' }}</td>
                <td class="text-end small">{{ m.executionTime != null ? m.executionTime : '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>
