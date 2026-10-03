<script setup>
import {computed} from 'vue'

import {formatBytes, formatDuration, formatNumber} from '../../utils/format.js'
import RouteWhySlow from './RouteWhySlow.vue'

// The runtime journal's view of one request (docs/PLAN-v2.md §5.3, §5.11): its work on one timeline, the collections
// that completed while it ran, the CPU time and memory it used, how it compares with its route, and what it touched.
// Purely presentational: the drawer fetches the profile when the developer opens a request. Its handoffs are the tasks
// the BootUI agent propagated from this request to an executor (M5-2).
const props = defineProps({
  profile: {type: Object, required: true}
})

const UNMEASURED_REASONS = {
  VIRTUAL_THREAD: 'it ran on a virtual thread, which the JVM does not measure',
  THREAD_ENDED: 'a thread it ran on ended before it could be read',
  UNSUPPORTED: 'this JVM does not measure per-thread CPU time and allocation'
}

const STANDINGS = {
  AT_OR_BELOW_P50: 'At or below the route median',
  ABOVE_P50: 'Slower than the route median',
  ABOVE_P95: 'Slower than 95% of the route'
}

const TOUCHED = [
  ['tables', 'Tables'],
  ['dataSources', 'Data sources'],
  ['transactions', 'Transactions'],
  ['caches', 'Caches'],
  ['messages', 'Messages sent'],
  ['restCalls', 'Hosts called'],
  ['logTemplates', 'Log events'],
  ['models', 'AI models']
]

const timeline = computed(() => props.profile.timeline ?? [])
const handoffs = computed(() => (props.profile.handoffs ?? []).map(handoffRow))
const lateHandoffs = computed(() => props.profile.lateHandoffs ?? 0)
const gcPauses = computed(() => props.profile.gcPauses ?? [])
const notes = computed(() => props.profile.notes ?? [])

const durationMillis = computed(() => Math.max(1, (props.profile.durationMicros ?? 0) / 1000))

const resourcesSummary = computed(() => {
  const r = props.profile.resources
  if (!r) return null
  const pauses = `${formatNumber(r.gcPauses)} GC ${r.gcPauses === 1 ? 'pause' : 'pauses'}${r.gcPausesTruncated ? ' or more' : ''}`
  if (r.availability === 'UNAVAILABLE') {
    return `CPU time and memory unavailable: ${UNMEASURED_REASONS[r.unmeasuredReason] || 'not measured'}. ${pauses}.`
  }
  const measured = `${formatDuration(r.cpuNanos)} CPU · ${formatBytes(r.allocatedBytes)} allocated · ${pauses}`
  if (r.availability === 'PARTIAL') {
    return `${measured}, at least: ${r.unmeasuredSegments} of ${r.segments} segments were not measured because ${
      UNMEASURED_REASONS[r.unmeasuredReason] || 'they could not be read'
    }.`
  }
  return measured
})

const comparison = computed(() => {
  const c = props.profile.routeComparison
  if (!c) return null
  const figures = `p50 ${formatDuration(micros(c.p50Micros))} · p95 ${formatDuration(micros(c.p95Micros))} over ${formatNumber(
    c.requests
  )} ${c.requests === 1 ? 'request' : 'requests'}`
  return {
    standing: c.standing
      ? STANDINGS[c.standing]
      : `Too few requests to compare (${formatNumber(c.requests)} of ${c.minimumRequests})`,
    figures,
    slow: c.standing === 'ABOVE_P95'
  }
})

// The request's Hibernate sessions (docs/PLAN-v2.md §5.18, M4-9): statements, flushes, and the persistence context.
const ormSummary = computed(() => {
  const o = props.profile.orm
  if (!o) return null
  const parts = [
    `${formatNumber(o.statements)} ${o.statements === 1 ? 'statement' : 'statements'} in ${formatDuration(
      micros(o.statementMicros)
    )}`,
    `${formatNumber(o.flushes)} ${o.flushes === 1 ? 'flush' : 'flushes'}, ${formatNumber(o.autoFlushes)} ${
      o.autoFlushes === 1 ? 'auto-flush' : 'auto-flushes'
    } before a query (${formatDuration(micros(o.flushMicros + o.autoFlushMicros))} flushing)`
  ]
  if (o.entitiesInContext >= 0) parts.push(`up to ${formatNumber(o.entitiesInContext)} entities in context`)
  if (o.l2Hits + o.l2Misses + o.l2Puts > 0) {
    parts.push(`second-level cache ${formatNumber(o.l2Hits)} hits, ${formatNumber(o.l2Misses)} misses`)
  }
  return {
    sessions: `${formatNumber(o.sessions)} ${o.sessions === 1 ? 'session' : 'sessions'}`,
    details: parts.join(' · '),
    flushing: o.autoFlushes >= 3
  }
})

const touched = computed(() =>
  TOUCHED.map(([key, label]) => ({key, label, values: props.profile.touched?.[key] ?? []})).filter(
    (group) => group.values.length
  )
)

function micros(value) {
  return value == null ? null : value * 1000
}

// Position on the request's own axis, clamped so work that ends after the response still shows.
function barStyle(offsetMillis, durationMicros) {
  const left = Math.min(100, Math.max(0, (offsetMillis / durationMillis.value) * 100))
  const width = durationMicros == null ? 0 : Math.max(0.6, (durationMicros / 1000 / durationMillis.value) * 100)
  return {left: `${left}%`, width: durationMicros == null ? undefined : `${Math.min(width, 100 - left)}%`}
}

function plural(count, noun) {
  return `${formatNumber(count)} ${noun}${count === 1 ? '' : 's'}`
}

function simpleName(type) {
  if (!type) return 'task'
  return type.slice(type.lastIndexOf('.') + 1)
}

// A task the BootUI agent propagated from this request to an executor (docs/PLAN-v2.md M5-2): where it ran, what it
// did under its own execution, and how it ended.
function handoffRow(handoff) {
  const work = [
    handoff.sqlCount ? plural(handoff.sqlCount, 'SQL statement') : null,
    handoff.restClientCount ? plural(handoff.restClientCount, 'REST call') : null,
    handoff.messagingCount ? plural(handoff.messagingCount, 'message') : null
  ].filter(Boolean)
  return {
    key: handoff.executionId,
    task: simpleName(handoff.taskClass),
    taskClass: handoff.taskClass,
    hook: handoff.hook,
    thread: handoff.thread,
    timing: `${formatDuration(micros(handoff.durationMicros))} after ${formatDuration(
      micros(handoff.queuedMicros)
    )} queued`,
    did: work.length ? work.join(', ') : 'nothing recorded',
    failed: handoff.failed,
    outcome: handoff.failed ? `failed: ${simpleName(handoff.exceptionClass)}` : 'completed',
    afterResponse: handoff.afterResponse
      ? `after response${
          handoff.afterResponseMicros ? ` by ${formatDuration(micros(handoff.afterResponseMicros))}` : ''
        }`
      : null,
    capped: handoff.capped,
    allocated: handoff.allocatedBytes == null ? null : formatBytes(handoff.allocatedBytes)
  }
}

function itemTitle(item) {
  const when = `at ${formatNumber(item.offsetMillis)} ms`
  return item.durationMicros == null ? when : `${when} for ${formatDuration(micros(item.durationMicros))}`
}
</script>

<template>
  <section class="mb-3 request-journal" aria-labelledby="request-journal-title">
    <h3 id="request-journal-title" class="h6">Recorded by the runtime journal</h3>
    <p v-if="!profile.available" class="small text-muted mb-0">{{ profile.unavailableReason }}</p>
    <template v-else>
      <dl class="row small mb-2">
        <dt class="col-4">Route</dt>
        <dd class="col-8">
          <code>{{ profile.route }}</code>
        </dd>
        <template v-if="comparison">
          <dt class="col-4">Against its route</dt>
          <dd class="col-8">
            <span :class="{'request-journal__slow': comparison.slow}">{{ comparison.standing }}</span>
            <span class="text-muted"> · {{ comparison.figures }}</span>
          </dd>
        </template>
        <template v-if="ormSummary">
          <dt class="col-4">Hibernate</dt>
          <dd class="col-8">
            <span :class="{'request-journal__slow': ormSummary.flushing}">{{ ormSummary.sessions }}</span>
            <span class="text-muted"> · {{ ormSummary.details }}</span>
          </dd>
        </template>
        <template v-if="resourcesSummary">
          <dt class="col-4">Resources</dt>
          <dd class="col-8">{{ resourcesSummary }}</dd>
        </template>
      </dl>
      <RouteWhySlow
        v-if="profile.route && !profile.route.startsWith('Scheduled:') && !profile.route.startsWith('Message:')"
        :route="profile.route"
      />

      <h4 class="h6 small text-muted mb-1">Timeline</h4>
      <p v-if="!timeline.length && !gcPauses.length" class="small text-muted">No work of this request was recorded.</p>
      <ol v-else class="list-unstyled small mb-2 request-journal__timeline">
        <li v-for="(item, index) in timeline" :key="index" class="request-journal__row">
          <span class="request-journal__source">{{ item.source }}</span>
          <span class="request-journal__label" :title="item.label">
            <code>{{ item.label }}</code>
            <span v-if="item.detail" class="text-muted"> · {{ item.detail }}</span>
          </span>
          <span class="request-journal__track" aria-hidden="true">
            <span
              class="request-journal__bar"
              :class="[
                `request-journal__bar--${(item.severity || 'OK').toLowerCase()}`,
                {'request-journal__bar--instant': item.durationMicros == null}
              ]"
              :style="barStyle(item.offsetMillis, item.durationMicros)"
            ></span>
          </span>
          <span class="request-journal__when">{{ itemTitle(item) }}</span>
        </li>
        <li v-for="pause in gcPauses" :key="`${pause.collector}-${pause.gcId}`" class="request-journal__row">
          <span class="request-journal__source">gc</span>
          <span class="request-journal__label">
            {{ pause.collector }} #{{ pause.gcId }} completed during this request
            <span v-if="pause.cause" class="text-muted"> · {{ pause.cause }}</span>
          </span>
          <span class="request-journal__track" aria-hidden="true">
            <span
              v-if="pause.retained"
              class="request-journal__bar request-journal__bar--gc"
              :style="barStyle(pause.offsetMillis, (pause.pauseMillis ?? 0) * 1000)"
            ></span>
          </span>
          <span class="request-journal__when">
            {{ pause.retained ? `${formatNumber(pause.pauseMillis)} ms pause` : 'no longer retained' }}
          </span>
        </li>
      </ol>

      <template v-if="handoffs.length || lateHandoffs">
        <h4 class="h6 small text-muted mb-1">Handoffs</h4>
        <ul class="list-unstyled small mb-2 request-journal__handoffs">
          <li v-for="handoff in handoffs" :key="handoff.key" class="request-journal__handoff">
            <div>
              <code :title="handoff.taskClass">{{ handoff.task }}</code>
              <span class="text-muted"> on </span>
              <code>{{ handoff.thread }}</code>
              <span v-if="handoff.afterResponse" class="badge rounded-pill text-bg-warning ms-2">{{
                handoff.afterResponse
              }}</span>
              <span
                v-if="handoff.capped"
                class="badge rounded-pill text-bg-secondary ms-2"
                title="Ended more than bootui.agent.executors.max-handoff after it started: its later work is not attributed to this request"
                >past deadline</span
              >
            </div>
            <div class="text-muted">
              {{ handoff.timing }} · {{ handoff.did }} ·
              <span :class="{'request-journal__failed': handoff.failed}">{{ handoff.outcome }}</span>
              <template v-if="handoff.allocated"> · {{ handoff.allocated }} allocated</template>
              <template v-if="handoff.hook"> · via {{ handoff.hook }}</template>
            </div>
          </li>
        </ul>
        <p v-if="lateHandoffs" class="small text-muted request-journal__late-handoffs">
          {{ plural(lateHandoffs, 'later task') }} started more than bootui.agent.executors.max-handoff after this
          request ended: counted, not shown.
        </p>
      </template>

      <template v-if="touched.length">
        <h4 class="h6 small text-muted mb-1">Touched</h4>
        <dl class="row small mb-2">
          <template v-for="group in touched" :key="group.key">
            <dt class="col-4">{{ group.label }}</dt>
            <dd class="col-8">
              <code v-for="(value, index) in group.values" :key="index" class="request-journal__value">{{
                value
              }}</code>
            </dd>
          </template>
        </dl>
      </template>

      <ul v-if="notes.length" class="small text-muted mb-0">
        <li v-for="(note, index) in notes" :key="index">{{ note }}</li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.request-journal__row {
  display: grid;
  grid-template-columns: 6rem minmax(0, 1fr) 30% 9rem;
  gap: 0.5rem;
  align-items: center;
  padding: 0.15rem 0;
  border-bottom: 1px solid var(--bootui-border);
}

.request-journal__source {
  font-family: var(--bs-font-monospace);
  color: var(--bootui-text-muted);
}

.request-journal__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.request-journal__track {
  position: relative;
  height: 0.6rem;
  background: var(--bootui-surface-alt);
  border-radius: var(--bootui-radius-xs);
}

.request-journal__bar {
  position: absolute;
  top: 0;
  bottom: 0;
  background: var(--bootui-green-dark);
  border-radius: var(--bootui-radius-xs);
}

.request-journal__bar--instant {
  width: 2px;
}

.request-journal__bar--slow,
.request-journal__bar--warn {
  background: var(--bootui-warning);
}

.request-journal__bar--error {
  background: var(--bootui-danger);
}

.request-journal__bar--gc {
  min-width: 2px;
  background: var(--bootui-text-muted);
}

.request-journal__when {
  color: var(--bootui-text-muted);
  text-align: end;
}

.request-journal__slow {
  color: var(--bootui-warning-text-strong);
  font-weight: 600;
}

.request-journal__handoff {
  padding: 0.25rem 0;
  border-bottom: 1px solid var(--bootui-border);
}

.request-journal__failed {
  color: var(--bootui-danger-text);
  font-weight: 600;
}

.request-journal__value {
  display: inline-block;
  margin-inline-end: 0.5rem;
}
</style>
