<script setup>
import {ApiError, apiFetch} from '../api.js'
import {computed, inject, onBeforeUnmount, onMounted, ref} from 'vue'
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
const panels = inject('panels', ref(null))
const platform = computed(() => panels.value?.platform ?? 'spring-boot')
const report = ref(null)
const error = ref(null)
const liquibasePresent = ref(true)
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
    const res = await apiFetch('api/liquibase/changesets')
    if (!current()) return
    if (res.status === 404) {
      liquibasePresent.value = false
      return
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const result = await res.json()
    if (!current()) return
    if (!Array.isArray(result?.databases) || !Number.isFinite(result.total))
      throw new Error('Invalid Liquibase change sets response.')
    report.value = result
    error.value = null
    liquibasePresent.value = true
  } catch (e) {
    if (current()) error.value = describeLoadError(e, 'Unable to load Liquibase change sets')
  } finally {
    if (current()) initialLoading.value = false
  }
}

function actionKey(db, action) {
  return `${db.name}:${action}`
}

async function runUpdate(db) {
  if (disposed || busy.value) return
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (
    !(await confirm({
      title: 'Apply Liquibase changes?',
      message: `Apply all pending Liquibase change sets for "${db.name}" against the live database.`,
      resource: db.name,
      confirmLabel: 'Apply',
      danger: true
    }))
  )
    return
  if (disposed || busy.value || readOnly.value) return

  const key = actionKey(db, 'update')
  busy.value = key
  ++readEpoch
  clear()
  let reconcile = true
  try {
    const res = await apiFetch('api/liquibase/update', {
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
      if (res.ok) throw new Error('Invalid Liquibase action response.')
    }
    if (disposed) return
    if (!res.ok) {
      show(diagnosticActionError(new ApiError(res.status, result), 'Could not run Liquibase update'), 'warning')
      return
    }
    if (
      !['success', 'failed'].includes(result?.status) ||
      typeof result.message !== 'string' ||
      !result.message.trim() ||
      result.beanName !== db.name ||
      !Array.isArray(result.warnings) ||
      !result.warnings.every((value) => typeof value === 'string') ||
      !['pendingBefore', 'pendingAfter', 'changeSetsApplied'].every(
        (field) => result[field] == null || (Number.isFinite(result[field]) && result[field] >= 0)
      )
    )
      throw new Error('Invalid Liquibase action response.')
    if (result.status === 'success') flash(result.message, 'success')
    else show(result.message, 'warning')
  } catch (e) {
    if (!disposed)
      show(
        `${formatLoadError(e, 'Could not run Liquibase update')} The action outcome is unknown; reading the current change sets without retrying the action.`,
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
onBeforeUnmount(() => {
  disposed = true
  ++readEpoch
})
</script>

<template>
  <div>
    <PanelHeader icon="bi-droplet" title="Liquibase change sets" :error="error" />

    <FlashBanner :message="banner" @dismiss="clear" />

    <PanelSkeleton v-if="initialLoading" />

    <UnavailableState v-else-if="!liquibasePresent" variant="info">
      <template v-if="platform === 'quarkus'">
        Liquibase is not configured on this application. Add the <code>quarkus-liquibase</code> extension and a change
        log to see change sets here.
      </template>
      <template v-else>
        Liquibase is not on the classpath of this application. Add the <code>liquibase-core</code> dependency to see
        change sets here.
      </template>
    </UnavailableState>

    <UnavailableState v-else-if="report && report.databases.length === 0">
      <template v-if="platform === 'quarkus'">
        Liquibase is on the classpath, but no Liquibase datasource was detected on this application.
      </template>
      <template v-else>
        Liquibase is on the classpath, but no Liquibase beans were detected in the application context.
      </template>
    </UnavailableState>

    <template v-else-if="report">
      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Liquibase actions are read-only.</ReadOnlyNotice>

      <div class="row g-2 mb-3">
        <div class="col-md-6">
          <input
            v-model="filter"
            aria-label="Filter change sets"
            class="form-control"
            placeholder="Filter by id, author, change-log, or description…"
          />
        </div>
        <div class="col-md-6 text-end small text-muted align-self-center">
          {{ report.total }} change set(s) across {{ report.databases.length }} database(s)
        </div>
      </div>

      <div v-for="db in databases" :key="db.name" class="card mb-3">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <h3 class="fs-6 fw-semibold mb-0">
            <i class="bi bi-database me-1"></i><code class="bootui-break-anywhere">{{ db.name }}</code>
          </h3>
          <span class="d-flex flex-wrap align-items-center gap-1">
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
          <div class="table-responsive bootui-table-scroll">
            <table class="table table-sm table-hover mb-0 bootui-data-table liquibase-changesets-table">
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
                    <code class="bootui-break-anywhere">{{ c.id }}</code>
                    <span v-if="c.tag" class="badge bg-primary ms-1">{{ c.tag }}</span>
                    <div v-if="c.description" class="small text-muted">{{ c.description }}</div>
                  </td>
                  <td class="small">{{ c.author }}</td>
                  <td class="small">
                    <code class="bootui-break-anywhere">{{ c.changeLog }}</code>
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
      </div>
    </template>
  </div>
</template>

<style scoped>
.liquibase-changesets-table {
  --bootui-table-min-width: 48rem;
}
</style>
