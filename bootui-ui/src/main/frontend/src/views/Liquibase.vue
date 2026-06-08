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
const liquibasePresent = ref(true)
const filter = ref('')
const {message: banner, flash, clear} = useFlashMessage()
const busy = ref(null)

async function load() {
  try {
    const res = await apiFetch('api/liquibase/changesets')
    if (res.status === 404) {
      liquibasePresent.value = false
      return
    }
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Liquibase change sets')
  }
}

function actionKey(db, action) {
  return `${db.name}:${action}`
}

async function runUpdate(db) {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (!confirm(`Apply pending Liquibase change sets for "${db.name}"?`)) return

  const key = actionKey(db, 'update')
  busy.value = key
  clear()
  try {
    const res = await apiFetch('api/liquibase/update', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({beanName: db.name, confirm: true})
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
      return
    }
    flash(result.message || 'Liquibase action completed.', result.status === 'success' ? 'success' : 'warning')
    await load()
  } catch (e) {
    flash(formatLoadError(e, 'Could not run Liquibase update'), 'danger')
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
      changeSets: db.changeSets.filter(
        (c) =>
          (c.id || '').toLowerCase().includes(f) ||
          (c.author || '').toLowerCase().includes(f) ||
          (c.changeLog || '').toLowerCase().includes(f) ||
          (c.description || '').toLowerCase().includes(f)
      )
    }))
    .filter((db) => db.changeSets.length > 0)
})

const execClass = (execType) => {
  const s = (execType || '').toUpperCase()
  if (s === 'EXECUTED' || s === 'RERAN') return 'bg-success'
  if (s === 'PENDING') return 'bg-warning text-dark'
  if (s === 'FAILED') return 'bg-danger'
  if (s === 'SKIPPED') return 'bg-secondary'
  if (s === 'MARK_RAN') return 'bg-info text-dark'
  return 'bg-secondary'
}

onMounted(load)
</script>

<template>
  <div>
    <PanelHeader icon="bi-droplet" title="Liquibase change sets" :error="error" />

    <FlashBanner :message="banner" @dismiss="clear" />

    <UnavailableState v-if="!liquibasePresent" variant="info">
      Liquibase is not on the classpath of this application. Add the <code>liquibase-core</code> dependency to see
      change sets here.
    </UnavailableState>

    <UnavailableState v-else-if="report && report.databases.length === 0">
      Liquibase is on the classpath, but no Liquibase beans were detected in the application context.
    </UnavailableState>

    <template v-else-if="report">
      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Liquibase actions are read-only.</ReadOnlyNotice>

      <div class="row g-2 mb-3">
        <div class="col-md-6">
          <input
            v-model="filter"
            class="form-control"
            placeholder="Filter by id, author, change-log, or description…"
          />
        </div>
        <div class="col-md-6 text-end small text-muted align-self-center">
          {{ report.total }} change set(s) across {{ report.databases.length }} database(s)
        </div>
      </div>

      <div v-for="db in databases" :key="db.name" class="card mb-3">
        <div class="card-header d-flex justify-content-between align-items-center">
          <span>
            <i class="bi bi-database me-1"></i><code>{{ db.name }}</code>
          </span>
          <span>
            <span class="badge bg-success me-1">{{ db.applied }} applied</span>
            <span v-if="db.pending > 0" class="badge bg-warning text-dark">{{ db.pending }} pending</span>
          </span>
        </div>
        <div class="card-body border-bottom">
          <div class="d-flex flex-wrap gap-2">
            <SpinnerButton
              :loading="busy === actionKey(db, 'update')"
              :disabled="readOnly || busy || !db.updateEnabled"
              :title="db.updateDisabledReason || 'Apply pending Liquibase change sets'"
              class="btn btn-sm btn-outline-primary"
              icon="bi-play-circle"
              label="Update"
              @click="runUpdate(db)"
            />
          </div>
          <div class="small text-muted mt-2">
            <div v-if="db.updateDisabledReason"><strong>Update:</strong> {{ db.updateDisabledReason }}</div>
          </div>
        </div>
        <div class="card-body p-0">
          <table class="table table-sm table-hover mb-0">
            <thead>
              <tr>
                <th style="width: 60px">#</th>
                <th>Id</th>
                <th>Author</th>
                <th>Change log</th>
                <th>Exec type</th>
                <th>Executed</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in db.changeSets" :key="c.changeLog + '-' + c.id + '-' + c.author + '-' + c.execType">
                <td class="small text-muted">{{ c.orderExecuted != null ? c.orderExecuted : '—' }}</td>
                <td>
                  <code>{{ c.id }}</code>
                  <span v-if="c.tag" class="badge bg-primary ms-1">{{ c.tag }}</span>
                  <div v-if="c.description" class="small text-muted">{{ c.description }}</div>
                </td>
                <td class="small">{{ c.author }}</td>
                <td class="small">
                  <code>{{ c.changeLog }}</code>
                </td>
                <td>
                  <span :class="execClass(c.execType)" class="badge">{{ c.execType }}</span>
                </td>
                <td class="small">{{ c.dateExecuted || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>
