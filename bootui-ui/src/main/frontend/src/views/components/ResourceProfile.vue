<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {ApiError, getJson} from '../../api.js'
import {formatBytes, formatNumber} from '../../utils/format.js'
import {diagnosticActionError} from '../../utils/diagnosticAcknowledgement.js'
import {formatLoadError} from '../../utils/loadError.js'
import {
  durationLabel,
  elapsedPercent,
  frameLabel,
  isResourceProfile,
  profileRows,
  remainingSeconds,
  requestShare,
  samplerLabel
} from '../../utils/resourceProfile.js'

const props = defineProps({
  readOnly: {type: Boolean, default: false},
  readOnlyReason: {type: String, default: 'This panel is read-only.'}
})

const PATH = 'api/runtime-insights/resource-profile'
const POLL_MILLIS = 2000

const profile = ref(null)
const error = ref(null)
const busy = ref(false)
const now = ref(Date.now())
let poll = null
let tick = null
let disposed = false
let actionEpoch = 0
let readEpoch = 0

const state = computed(() => profile.value?.state ?? null)
const running = computed(() => state.value === 'RUNNING')
const rows = computed(() => profileRows(profile.value))
const share = computed(() => requestShare(profile.value))
const sampler = computed(() => samplerLabel(profile.value?.sampler))
const length = computed(() => durationLabel(profile.value?.maxDurationSeconds ?? 30))
const remaining = computed(() => remainingSeconds(profile.value, now.value))
const progress = computed(() => elapsedPercent(profile.value, now.value))
const routesHidden = computed(() =>
  profile.value?.limitations.some((limitation) =>
    limitation.startsWith('The http-exchanges panel is disabled, so the samples are not listed by route;')
  )
)
const summary = computed(() => {
  const p = profile.value
  if (!p) return ''
  const parts = [`${formatNumber(p.cpuSamples)} CPU samples`]
  if (share.value != null) parts.push(`${share.value} % of them while a request ran`)
  let text = `${parts.join(', ')}, joined to ${formatNumber(p.requests)} ${p.requests === 1 ? 'request' : 'requests'}`
  if (sampler.value) text += ` by the ${sampler.value}`
  if (finished.value) text += ` · ended ${finished.value}`
  return `${text}.`
})
const finished = computed(() =>
  profile.value?.finishedAt ? new Date(profile.value.finishedAt).toLocaleTimeString() : null
)

function show(result) {
  if (
    !isResourceProfile(result) ||
    !['IDLE', 'RUNNING', 'COMPLETED', 'FAILED', 'UNAVAILABLE'].includes(result.state) ||
    !['reason', 'sampler'].every((field) => result[field] === null || typeof result[field] === 'string') ||
    !['startedAt', 'endsAt', 'finishedAt'].every((field) => result[field] === null || Number.isFinite(result[field])) ||
    !Array.isArray(result.limitations) ||
    !result.limitations.every((limitation) => typeof limitation === 'string') ||
    !['maxDurationSeconds', 'cpuSamples', 'outsideSamples', 'requests', 'routesOmitted'].every(
      (field) => Number.isFinite(result[field]) && result[field] >= 0
    ) ||
    !result.routes.every(
      (route) =>
        typeof route?.route === 'string' &&
        ['requests', 'cpuSamples', 'allocatedBytes'].every(
          (field) => Number.isFinite(route[field]) && route[field] >= 0
        ) &&
        typeof route.virtualThreads === 'boolean' &&
        Array.isArray(route.hotFrames) &&
        route.hotFrames.every(
          (frame) => typeof frame?.frame === 'string' && Number.isFinite(frame.samples) && frame.samples >= 0
        )
    )
  )
    throw new Error('Invalid resource profile response.')
  if (!disposed) profile.value = result
}

function cancelSchedule() {
  clearTimeout(poll)
  clearInterval(tick)
  poll = null
  tick = null
}

function schedule() {
  cancelSchedule()
  if (disposed || busy.value || !running.value) return
  // While a session records, the countdown moves every second and the state is read every two.
  now.value = Date.now()
  tick = setInterval(() => {
    if (!disposed && !busy.value) now.value = Date.now()
  }, 1000)
  poll = setTimeout(load, POLL_MILLIS)
}

async function load({preserveError = false} = {}) {
  if (disposed || (busy.value && !preserveError)) return
  const action = actionEpoch
  const read = ++readEpoch
  const current = () => !disposed && action === actionEpoch && read === readEpoch
  try {
    const result = await getJson(PATH)
    if (!current()) return
    show(result)
    if (!preserveError) error.value = null
  } catch (e) {
    if (!current()) return
    const failure = formatLoadError(e, 'Unable to read the resource profile')
    error.value = preserveError && error.value ? `${error.value} ${failure}` : failure
  } finally {
    if (current()) schedule()
  }
}

async function act(path, message) {
  if (disposed || busy.value || (path === PATH && props.readOnly)) return
  const action = ++actionEpoch
  ++readEpoch
  cancelSchedule()
  busy.value = true
  error.value = null
  try {
    const result = await getJson(path, {method: 'POST'})
    if (disposed || action !== actionEpoch) return
    show(result)
  } catch (e) {
    if (disposed || action !== actionEpoch) return
    error.value = diagnosticActionError(e, message)
    if (!(e instanceof ApiError) || e.status >= 500) {
      error.value += ' The action outcome is unknown; reading the current profile without retrying the action.'
      await load({preserveError: true})
    }
  } finally {
    if (!disposed && action === actionEpoch) {
      busy.value = false
      schedule()
    }
  }
}

const start = () => act(PATH, 'Unable to start the resource profile')
const stop = () => act(`${PATH}/stop`, 'Unable to stop the resource profile')

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  ++actionEpoch
  ++readEpoch
  cancelSchedule()
})
</script>

<template>
  <section class="card insight-profile" aria-labelledby="insight-profile-title" :aria-busy="busy">
    <div class="card-body">
      <div class="d-flex flex-wrap justify-content-between align-items-start gap-2">
        <div class="insight-profile-intro">
          <h2 id="insight-profile-title" class="h6 mb-1">Profile resources</h2>
          <p class="small text-muted mb-2">
            Records CPU and allocation samples with JDK Flight Recorder for {{ length }}, then splits them by route,
            virtual threads included. Nothing records until you start it; starting JFR takes about a third of a second
            and some 40 MB.
          </p>
          <ol class="insight-profile-steps small text-muted mb-0" :class="`insight-profile-steps-${state ?? 'idle'}`">
            <li :class="{'insight-profile-step-done': running || state === 'COMPLETED'}">Start the session</li>
            <li :class="{'insight-profile-step-active': running, 'insight-profile-step-done': state === 'COMPLETED'}">
              Use the app for {{ length }} — only requests you make now are sampled
            </li>
            <li :class="{'insight-profile-step-active': state === 'COMPLETED'}">See which routes took the most CPU</li>
          </ol>
        </div>
        <button
          v-if="running"
          type="button"
          class="btn btn-sm btn-outline-secondary insight-profile-stop"
          :disabled="busy"
          @click="stop"
        >
          Stop now
        </button>
        <button
          v-else
          type="button"
          class="btn btn-sm btn-primary insight-profile-start"
          :disabled="busy || props.readOnly || state === 'UNAVAILABLE' || !profile"
          :title="props.readOnly ? props.readOnlyReason : null"
          @click="start"
        >
          {{ state === 'COMPLETED' ? 'Profile again' : 'Profile resources' }}
        </button>
      </div>

      <div aria-live="polite">
        <div v-if="error" class="alert alert-warning small mt-3 mb-0" role="alert">{{ error }}</div>
        <p v-if="props.readOnly && !running" class="small text-muted mt-2 mb-0">{{ props.readOnlyReason }}</p>
        <p v-if="state === 'UNAVAILABLE'" class="small text-muted mt-3 mb-0 insight-profile-reason">
          {{ profile.reason }}
        </p>
        <div v-else-if="state === 'FAILED'" class="alert alert-warning small mt-3 mb-0" role="alert">
          {{ profile.reason }}
        </div>
        <div v-else-if="running" class="mt-3 insight-profile-running">
          <div
            class="progress insight-profile-progress"
            role="progressbar"
            aria-label="Profile resources session"
            :aria-valuenow="progress"
            aria-valuemin="0"
            aria-valuemax="100"
          >
            <div class="progress-bar" :style="{width: `${progress}%`}"></div>
          </div>
          <p class="small mt-2 mb-0">
            Recording · {{ remaining }} s left. Use the application now: only requests that run during the session are
            sampled.
          </p>
        </div>
        <template v-else-if="state === 'COMPLETED'">
          <p class="small mt-3 mb-2 insight-profile-summary">{{ summary }}</p>
          <p v-if="!rows.length && !routesHidden" class="small text-muted mb-0">
            No request samples were attributed to listed routes during the session. Click "Profile again" and use the
            application — click through the pages you want measured — while it records.
          </p>
          <div v-if="rows.length" class="table-responsive">
            <table class="table table-sm align-middle mb-0 insight-profile-table">
              <thead>
                <tr>
                  <th scope="col">Route</th>
                  <th scope="col" class="text-end">Requests</th>
                  <th scope="col">CPU samples</th>
                  <th scope="col" class="text-end">Allocated</th>
                  <th scope="col">Hottest sampled frame</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.route" :class="{'insight-profile-top': row.top}">
                  <td>
                    <code class="insight-profile-route">{{ row.route }}</code>
                    <span
                      v-if="row.virtualThreads"
                      class="badge text-bg-light border fw-normal ms-1"
                      title="Sampled on a virtual thread, whose CPU scope readings cannot measure"
                      >virtual threads</span
                    >
                  </td>
                  <td class="text-end insight-profile-number">{{ formatNumber(row.requests) }}</td>
                  <td class="insight-profile-cpu">
                    <span class="insight-share">
                      <span class="insight-share-track" aria-hidden="true">
                        <span
                          class="insight-share-bar"
                          :class="{'insight-share-bar-top': row.top}"
                          :style="{width: `${row.share}%`}"
                        ></span>
                      </span>
                      <span class="insight-profile-number insight-profile-samples"
                        >{{ formatNumber(row.cpuSamples) }} · {{ row.shareLabel }}</span
                      >
                    </span>
                  </td>
                  <td class="text-end insight-profile-number">{{ formatBytes(row.allocatedBytes) }}</td>
                  <td class="insight-profile-frame">
                    <template v-if="row.hotFrames.length">
                      <code :title="row.hotFrames[0].frame">{{ frameLabel(row.hotFrames[0].frame) }}</code>
                      <details v-if="row.hotFrames.length > 1" class="small">
                        <summary class="text-muted">{{ row.hotFrames.length - 1 }} more</summary>
                        <ol class="list-unstyled mb-0">
                          <li v-for="frame in row.hotFrames.slice(1)" :key="frame.frame">
                            <code :title="frame.frame">{{ frameLabel(frame.frame) }}</code>
                            <span class="text-muted"> · {{ formatNumber(frame.samples) }}</span>
                          </li>
                        </ol>
                      </details>
                    </template>
                    <span v-else class="text-muted small">No frame sampled</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-if="profile.routesOmitted" class="small text-muted mt-2 mb-0">
            {{ formatNumber(profile.routesOmitted) }} more routes not listed.
          </p>
          <ul v-if="profile.limitations.length" class="small text-muted mt-2 mb-0 insight-profile-limitations">
            <li v-for="limitation in profile.limitations" :key="limitation">{{ limitation }}</li>
          </ul>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.insight-profile-intro,
.insight-profile-limitations,
.insight-profile-reason {
  max-width: 80ch;
}

.insight-profile-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1rem;
  padding-left: 0;
  list-style: none;
  counter-reset: insight-profile-step;
}

.insight-profile-steps li {
  counter-increment: insight-profile-step;
}

.insight-profile-steps li::before {
  content: counter(insight-profile-step) '. ';
  font-weight: 600;
}

.insight-profile-step-active {
  color: var(--bs-body-color);
  font-weight: 600;
}

.insight-profile-step-done {
  text-decoration: line-through;
}

.insight-profile-progress {
  height: 0.5rem;
  max-width: 32rem;
}

.insight-profile-number {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.insight-profile-cpu {
  min-width: 12rem;
  width: 30%;
}

.insight-profile-frame {
  min-width: 12rem;
}

.insight-profile-route {
  white-space: nowrap;
}

.insight-profile-top td:first-child code,
.insight-profile-top .insight-profile-samples {
  font-weight: 700;
}

.insight-share {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.insight-share-track {
  position: relative;
  flex: 1 1 auto;
  height: 0.6rem;
  background: var(--bs-secondary-bg);
  border-radius: var(--bootui-radius-xs);
  overflow: hidden;
}

.insight-share-bar {
  position: absolute;
  inset: 0 auto 0 0;
  min-width: 2px;
  background: var(--bootui-text-muted);
  border-radius: var(--bootui-radius-xs);
}

.insight-share-bar-top {
  background: var(--bootui-green-dark);
}

.insight-profile-samples {
  flex: 0 0 auto;
  min-width: 5.5rem;
  text-align: end;
}

.insight-profile-limitations {
  padding-left: 1.1rem;
}
</style>
