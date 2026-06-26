<script setup>
import {apiFetch} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const report = ref(null)
const error = ref(null)
const flywayPresent = ref(true)
const filter = ref('')
const {message: banner, flash, clear} = useFlashMessage()
const busy = ref(null)

async function load() {
  try {
    const res = await apiFetch('api/flyway/migrations')
    if (res.status === 404) {
      flywayPresent.value = false
      return
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    report.value = await res.json()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Flyway migrations')
  }
}

function actionKey(db, action) {
  return `${db.name}:${action}`
}

async function runAction(db, action) {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  const confirmation =
    action === 'clean'
      ? `Clean Flyway-managed schema(s) for "${db.name}"? This can delete all objects in those schemas.`
      : `Run pending Flyway migrations for "${db.name}"?`
  if (!confirm(confirmation)) return

  const key = actionKey(db, action)
  busy.value = key
  clear()
  try {
    const res = await apiFetch(`api/flyway/${action}`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({beanName: db.name, confirm: true})
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    flash(result.message || 'Flyway action completed.', result.status === 'success' ? 'success' : 'warning')
    await load()
  } catch (e) {
    flash(formatLoadError(e, 'Could not run Flyway action'), 'danger')
  } finally {
    busy.value = null
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
    <PanelHeader icon="bi-database-up" title="Flyway migrations" :error="error" />

    <FlashBanner :message="banner" @dismiss="clear" />

    <UnavailableState v-if="!flywayPresent" variant="info">
      Flyway is not on the classpath of this application. Add the <code>flyway-core</code> dependency to see schema
      migrations here.
    </UnavailableState>

    <UnavailableState v-else-if="report && report.databases.length === 0">
      Flyway is on the classpath, but no Flyway beans were detected in the application context.
    </UnavailableState>

    <template v-else-if="report">
      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Flyway actions are read-only.</ReadOnlyNotice>

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
        <div class="card-body border-bottom">
          <div class="d-flex flex-wrap gap-2">
            <SpinnerButton
              :loading="busy === actionKey(db, 'migrate')"
              :disabled="readOnly || busy || !db.migrateEnabled"
              :title="db.migrateDisabledReason || 'Run pending Flyway migrations'"
              class="btn btn-sm btn-outline-primary"
              icon="bi-play-circle"
              label="Migrate"
              @click="runAction(db, 'migrate')"
            />
            <SpinnerButton
              :loading="busy === actionKey(db, 'clean')"
              :disabled="readOnly || busy || !db.cleanEnabled"
              :title="db.cleanDisabledReason || 'Clean Flyway-managed schemas'"
              class="btn btn-sm btn-outline-danger"
              icon="bi-trash"
              label="Clean"
              @click="runAction(db, 'clean')"
            />
          </div>
          <div class="small text-muted mt-2">
            <div v-if="db.migrateDisabledReason"><strong>Migrate:</strong> {{ db.migrateDisabledReason }}</div>
            <div v-if="db.cleanDisabledReason"><strong>Clean:</strong> {{ db.cleanDisabledReason }}</div>
          </div>
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
