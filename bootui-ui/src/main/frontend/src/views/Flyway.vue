<script setup>
import {ApiError, apiFetch} from '../api.js'
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {diagnosticActionError} from '../utils/diagnosticAcknowledgement.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const flywayPresent = ref(true)
const initialLoading = ref(true)
const filter = ref('')
const {message: banner, flash, show, clear} = useFlashMessage()
const busy = ref(null)
let disposed = false
let readEpoch = 0

async function load() {
  if (disposed) return
  const read = ++readEpoch
  const current = () => !disposed && read === readEpoch
  try {
    const res = await apiFetch('api/flyway/migrations')
    if (!current()) return
    if (res.status === 404) {
      flywayPresent.value = false
      return
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const result = await res.json()
    if (!current()) return
    if (!Array.isArray(result?.databases) || !Number.isFinite(result.total))
      throw new Error('Invalid Flyway migrations response.')
    report.value = result
    error.value = null
    flywayPresent.value = true
  } catch (e) {
    if (current()) error.value = describeLoadError(e, 'Unable to load Flyway migrations')
  } finally {
    if (current()) initialLoading.value = false
  }
}

function actionKey(db, action) {
  return `${db.name}:${action}`
}

async function runAction(db, action) {
  if (disposed || busy.value) return
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  const confirmation =
    action === 'clean'
      ? {
          title: 'Clean Flyway schema?',
          message: `Clean Flyway-managed schema(s) for "${db.name}"? Every table, view, and object in those schemas is dropped.`,
          resource: db.name,
          confirmLabel: 'Clean schema',
          danger: true,
          irreversible: true
        }
      : {
          title: 'Run Flyway migrations?',
          message: `Run all pending Flyway migrations for "${db.name}" against the live database.`,
          resource: db.name,
          confirmLabel: 'Migrate',
          danger: true
        }
  if (!(await confirm(confirmation))) return
  if (disposed || busy.value || readOnly.value) return

  const key = actionKey(db, action)
  busy.value = key
  ++readEpoch
  clear()
  let reconcile = true
  try {
    const res = await apiFetch(`api/flyway/${action}`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({beanName: db.name, confirm: true})
    })
    if (disposed) return
    reconcile = res.ok || res.status >= 500
    let result
    try {
      result = await res.json()
    } catch {
      if (res.ok) throw new Error('Invalid Flyway action response.')
    }
    if (disposed) return
    if (!res.ok) {
      show(diagnosticActionError(new ApiError(res.status, result), 'Could not run Flyway action'), 'warning')
      return
    }
    if (
      !['success', 'failed'].includes(result?.status) ||
      typeof result.message !== 'string' ||
      !result.message.trim() ||
      result.beanName !== db.name ||
      !['schemasCleaned', 'schemasDropped', 'warnings'].every(
        (field) => Array.isArray(result[field]) && result[field].every((value) => typeof value === 'string')
      )
    )
      throw new Error('Invalid Flyway action response.')
    if (result.status === 'success') flash(result.message, 'success')
    else show(result.message, 'warning')
  } catch (e) {
    if (!disposed)
      show(
        `${formatLoadError(e, 'Could not run Flyway action')} The action outcome is unknown; reading the current migrations without retrying the action.`,
        'danger'
      )
  } finally {
    if (!disposed) {
      if (reconcile) await load()
      if (!disposed) busy.value = null
    }
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
onBeforeUnmount(() => {
  disposed = true
  ++readEpoch
})
</script>

<template>
  <div>
    <PanelHeader icon="bi-database-up" title="Flyway migrations" :error="error" />

    <FlashBanner :message="banner" @dismiss="clear" />

    <PanelSkeleton v-if="initialLoading" />

    <UnavailableState v-else-if="!flywayPresent" variant="info">
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
          <input
            v-model="filter"
            aria-label="Filter migrations"
            class="form-control"
            placeholder="Filter by version, description, or script…"
          />
        </div>
        <div class="col-md-6 text-end small text-muted align-self-center">
          {{ report.total }} migration(s) across {{ report.databases.length }} database(s)
        </div>
      </div>

      <div v-for="db in databases" :key="db.name" class="card mb-3">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <h3 class="fs-6 fw-semibold mb-0">
            <i class="bi bi-database me-1"></i><code class="bootui-break-anywhere">{{ db.name }}</code>
          </h3>
          <span class="small d-flex flex-wrap align-items-center gap-1">
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
          <div class="table-responsive bootui-table-scroll">
            <table class="table table-sm table-hover mb-0 bootui-data-table flyway-migrations-table">
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
                      <code class="bootui-break-anywhere">{{ m.script }}</code>
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
      </div>
    </template>
  </div>
</template>

<style scoped>
.flyway-migrations-table {
  --bootui-table-min-width: 44rem;
}
</style>
