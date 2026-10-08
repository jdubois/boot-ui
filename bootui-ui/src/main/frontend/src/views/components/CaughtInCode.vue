<script setup>
import {computed} from 'vue'
import {formatNumber, shortName} from '../../utils/format.js'
import UnavailableState from './UnavailableState.vue'

const props = defineProps({
  report: {type: Object, required: true}
})

const SHAPES = {
  discards: 'discards it',
  'prints-stack-trace': 'prints stack trace',
  reinterrupts: 'restores interrupt',
  'passes-as-value': 'passes it on',
  'throws-new': 'throws another'
}

const OUTCOMES = [
  ['rethrown', 'Rethrown'],
  ['replaced', 'Replaced'],
  ['reported', 'Reported'],
  ['logged', 'Logged'],
  ['handedOn', 'Handed on'],
  ['reinterrupted', 'Re-interrupted'],
  ['retried', 'Retried'],
  ['notRethrownOrLogged', 'Not seen rethrown or logged at WARN or above'],
  ['unknown', 'Unknown'],
  ['pending', 'Settling'],
  ['counted', 'Counted only']
]

const rows = computed(() => props.report?.rows ?? [])
const limitations = computed(() => props.report?.limitations ?? [])
const settling = computed(() => Number(props.report?.settling || 0))
const hasRows = computed(() => rows.value.length > 0)

const summary = computed(() => {
  const occurrences = formatNumber(props.report?.occurrences || 0)
  const findings = formatNumber(props.report?.findings || 0)
  return `${occurrences} caught occurrence${props.report?.occurrences === 1 ? '' : 's'} · ${findings} finding${
    props.report?.findings === 1 ? '' : 's'
  }`
})

const settlingText = computed(
  () =>
    `${formatNumber(settling.value)} occurrence${
      settling.value === 1 ? '' : 's'
    } settling: their request ended less than 5 s ago or is still running`
)

function handlerLabel(row) {
  const owner = shortName(row.siteClass)
  const method = row.method || '—'
  const line = Number(row.line || 0) > 0 ? `:${row.line}` : ''
  return `${owner}.${method}${line}`
}

function caughtLabel(row) {
  if (row.exceptionClass) return shortName(row.exceptionClass)
  if (row.declaredTypes?.length) return row.declaredTypes.map(shortName).join(' or ')
  return 'Exception'
}

function caughtTitle(row) {
  if (row.exceptionClass) return row.exceptionClass
  if (row.declaredTypes?.length) return row.declaredTypes.join(' or ')
  return 'Exception class not identified'
}

function familyLabel(family) {
  return String(family || '').replace('-', ' ')
}

function routeLabel(row) {
  if (row.route) return row.route
  return (
    {
      request: 'Request',
      task: 'Task',
      execution: 'Execution',
      none: 'No owner'
    }[row.ownerKind] ||
    row.ownerKind ||
    'No owner'
  )
}

function outcomeChips(row) {
  return OUTCOMES.map(([key, label]) => ({key, label, count: Number(row[key] || 0)})).filter((chip) => chip.count > 0)
}

function shapeLabel(shape) {
  return SHAPES[shape] || shape
}
</script>

<template>
  <section class="caught-in-code card" role="region" aria-labelledby="caught-in-code-title">
    <div class="card-body">
      <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
        <div>
          <h2 id="caught-in-code-title" class="h5 mb-1">Caught in application code</h2>
          <p class="text-body-secondary small mb-0">
            Outcomes for exceptions application handlers caught, from the BootUI agent.
          </p>
        </div>
        <span v-if="report.available" class="badge text-bg-light border caught-summary">{{ summary }}</span>
      </div>

      <UnavailableState
        v-if="!report.available"
        icon="bi-info-circle"
        variant="secondary"
        class="caught-unavailable small"
      >
        {{ report.unavailableReason || 'Caught exception outcomes are not available.' }}
        <div class="mt-2">
          <router-link to="/java-agent" class="caught-agent-link">Open the Java Agent panel</router-link>
          to attach the BootUI agent.
        </div>
      </UnavailableState>

      <template v-else>
        <ul v-if="limitations.length" class="small text-body-secondary caught-limitations mb-3">
          <li v-for="limitation in limitations" :key="limitation">{{ limitation }}</li>
        </ul>

        <div v-if="settling > 0" class="alert alert-info py-2 small caught-settling" role="status">
          {{ settlingText }}
        </div>

        <div v-if="!hasRows" class="alert alert-secondary small mb-0">No caught exception handlers recorded yet.</div>

        <div v-else class="table-responsive">
          <table class="table table-sm align-middle caught-table mb-0">
            <thead>
              <tr>
                <th>Handler</th>
                <th>Caught</th>
                <th>Route</th>
                <th>Occurrences</th>
                <th>Outcome</th>
                <th>Shapes</th>
                <th>Activity</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id" :class="{'caught-row-finding': row.finding}">
                <td>
                  <div class="d-flex flex-column gap-1">
                    <span class="fw-semibold" :title="row.siteClass">
                      <code>{{ handlerLabel(row) }}</code>
                    </span>
                    <span v-if="row.finding" class="badge rounded-pill text-bg-light border caught-finding">
                      <i class="bi bi-search me-1" aria-hidden="true"></i>Finding
                    </span>
                  </div>
                </td>
                <td>
                  <span class="fw-semibold" :title="caughtTitle(row)">{{ caughtLabel(row) }}</span>
                  <span v-if="row.family" class="badge text-bg-light border ms-1">{{ familyLabel(row.family) }}</span>
                </td>
                <td class="small">
                  <span v-if="row.route">{{ row.route }}</span>
                  <span v-else class="text-body-secondary">{{ routeLabel(row) }}</span>
                </td>
                <td class="small">
                  <span class="fw-semibold">{{ formatNumber(row.occurrences) }}</span>
                  <div class="text-body-secondary">
                    {{ formatNumber(row.requests) }} request{{ row.requests === 1 ? '' : 's' }}
                  </div>
                </td>
                <td>
                  <div v-if="outcomeChips(row).length" class="d-flex flex-wrap gap-1 caught-chip-group">
                    <span
                      v-for="chip in outcomeChips(row)"
                      :key="chip.key"
                      class="badge text-bg-light border caught-chip"
                      :title="chip.key === 'unknown' ? row.unknownReason || undefined : undefined"
                    >
                      {{ chip.label }}
                      <span class="caught-chip-count">{{ formatNumber(chip.count) }}</span>
                    </span>
                  </div>
                  <span v-else class="text-body-secondary small">—</span>
                  <div v-if="row.unknown > 0 && row.unknownReason" class="small text-body-secondary mt-1">
                    Unknown: {{ row.unknownReason }}
                  </div>
                </td>
                <td>
                  <div v-if="row.shapes?.length" class="d-flex flex-wrap gap-1">
                    <span v-for="shape in row.shapes" :key="shape" class="badge text-bg-light border">{{
                      shapeLabel(shape)
                    }}</span>
                  </div>
                  <span v-else class="text-body-secondary small">—</span>
                </td>
                <td class="small">
                  <router-link
                    v-if="row.exemplarRequestId"
                    :to="{path: '/activity', query: {request: row.exemplarRequestId}}"
                  >
                    <code>{{ row.exemplarRequestId }}</code>
                  </router-link>
                  <span v-else class="text-body-secondary">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.caught-in-code {
  border-color: var(--bootui-border);
}

.caught-summary {
  color: var(--bootui-muted) !important;
  font-weight: 600;
}

.caught-limitations {
  padding-left: 1.15rem;
}

.caught-settling {
  border-color: color-mix(in srgb, var(--bootui-info-text) 18%, transparent);
}

.caught-table :is(th, td) {
  vertical-align: top;
}

.caught-row-finding {
  --bs-table-bg: color-mix(in srgb, var(--bootui-warning-text-strong) 7%, transparent);
}

.caught-finding {
  align-self: flex-start;
  color: var(--bootui-warning-text-strong) !important;
}

.caught-chip {
  color: var(--bootui-ink) !important;
  font-weight: 600;
  white-space: normal;
}

.caught-chip-count {
  color: var(--bootui-muted);
  font-weight: 700;
  margin-left: 0.2rem;
}

code {
  overflow-wrap: anywhere;
}
</style>
