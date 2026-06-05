<script setup>
import {apiFetch} from '../api.js'
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import PanelHeader from './components/PanelHeader.vue'

const status = ref(null)
const error = ref(null)
const loading = ref(false)
const lastFetched = ref(null)

const descriptor = ref(null)
const run = ref(null)
const events = ref([])
const running = ref(false)
let source = null

const available = computed(() => status.value?.available === true)
const enabled = computed(() => status.value?.enabled === true)
const unavailableReason = computed(() => status.value?.unavailableReason)
const diff = computed(() => run.value?.diff || '')
const branch = computed(() => run.value?.branch)
const runStatus = computed(() => run.value?.status)

async function loadStatus() {
  loading.value = true
  error.value = null
  try {
    const res = await apiFetch('api/copilot-fix/status', {cache: 'no-store'})
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    status.value = await res.json()
    lastFetched.value = Date.now()
  } catch (err) {
    error.value = err
  } finally {
    loading.value = false
  }
}

function readPendingDescriptor() {
  try {
    const raw = sessionStorage.getItem('bootui.copilotFix.pending')
    if (raw) {
      sessionStorage.removeItem('bootui.copilotFix.pending')
      return JSON.parse(raw)
    }
  } catch (err) {
    // Ignore malformed or unavailable storage; the panel still works without a seeded finding.
  }
  return null
}

function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

async function startFix(target) {
  if (!target || running.value) return
  descriptor.value = target
  events.value = []
  run.value = null
  running.value = true
  error.value = null
  closeStream()
  try {
    const res = await apiFetch('api/copilot-fix/run', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({descriptor: target})
    })
    if (res.status === 409) throw new Error('The Fix it with Copilot capability is disabled.')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    run.value = await res.json()
    streamRun(run.value.id)
  } catch (err) {
    error.value = err
    running.value = false
  }
}

function streamRun(id) {
  source = new EventSource(`api/copilot-fix/runs/${encodeURIComponent(id)}/stream`)
  source.addEventListener('event', (message) => {
    try {
      const event = JSON.parse(message.data)
      events.value = [...events.value, event]
      if (event.type === 'done') {
        finishRun(id)
      }
    } catch (err) {
      // Ignore unparseable frames; the run snapshot is fetched on completion.
    }
  })
  source.onerror = () => {
    closeStream()
    finishRun(id)
  }
}

async function finishRun(id) {
  closeStream()
  running.value = false
  try {
    const res = await apiFetch(`api/copilot-fix/runs/${encodeURIComponent(id)}`, {cache: 'no-store'})
    if (res.ok) {
      run.value = await res.json()
    }
  } catch (err) {
    // Keep the streamed state if the final fetch fails.
  }
}

function retry() {
  if (descriptor.value) startFix(descriptor.value)
}

onMounted(async () => {
  await loadStatus()
  const pending = readPendingDescriptor()
  if (pending && available.value) {
    startFix(pending)
  } else if (pending) {
    descriptor.value = pending
  }
})

onBeforeUnmount(closeStream)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-magic"
      title="Fix with Copilot"
      subtitle="Draft a remediation for a scanner finding on an isolated branch using the GitHub Copilot SDK."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      @refresh="loadStatus"
    />

    <div v-if="!enabled" class="alert alert-secondary" role="alert">
      The Fix it with Copilot capability is disabled. Set
      <code>bootui.copilot-fix.enabled=ON</code> to allow automated fixes. It is local-only and
      opt-in by design.
    </div>
    <div v-else-if="!available" class="alert alert-warning" role="alert">
      {{ unavailableReason || 'This capability is currently unavailable.' }}
    </div>

    <div v-if="!descriptor && enabled" class="text-secondary">
      Open a finding in the <strong>Vulnerabilities</strong> panel and choose
      <em>Fix it with Copilot</em> to start a run here.
    </div>

    <div v-if="descriptor" class="card mb-3">
      <div class="card-body">
        <h2 class="h6 mb-2">{{ descriptor.title || descriptor.findingId }}</h2>
        <p class="mb-1 small text-secondary">
          <span class="badge text-bg-secondary me-1">{{ descriptor.source || 'scanner' }}</span>
          <span v-if="descriptor.severity" class="badge text-bg-dark me-1">{{ descriptor.severity }}</span>
          <code>{{ descriptor.findingId }}</code>
        </p>
        <p v-if="descriptor.targets && descriptor.targets.length" class="mb-0 small">
          Affected: <code>{{ descriptor.targets.join(', ') }}</code>
        </p>
        <button
          v-if="available && !running"
          type="button"
          class="btn btn-sm btn-primary mt-3"
          @click="retry"
        >
          <i class="bi bi-magic me-1" aria-hidden="true"></i>{{ run ? 'Run again' : 'Fix it with Copilot' }}
        </button>
      </div>
    </div>

    <div v-if="run" class="card mb-3">
      <div class="card-header d-flex justify-content-between align-items-center">
        <span>
          Run <code>{{ run.id }}</code>
          <span class="badge ms-2" :class="running ? 'text-bg-info' : 'text-bg-secondary'">{{ runStatus }}</span>
        </span>
        <span v-if="branch" class="small">branch <code>{{ branch }}</code></span>
      </div>
      <div class="card-body">
        <ul class="list-unstyled small mb-3 bootui-copilot-fix-events">
          <li v-for="event in events" :key="event.sequence" :class="{'text-danger': event.type === 'error'}">
            <span class="text-secondary me-2">{{ event.type }}</span>{{ event.message }}
          </li>
        </ul>

        <div v-if="diff">
          <h3 class="h6">Proposed diff ({{ run.filesChanged }} file(s))</h3>
          <pre class="bg-body-tertiary p-2 rounded small bootui-copilot-fix-diff"><code>{{ diff }}</code></pre>
          <p class="small text-secondary mb-0">
            Review the diff above. The changes are isolated on branch <code>{{ branch }}</code> and
            were not applied to your current branch. Open a draft pull request from that branch when
            you are satisfied.
          </p>
        </div>
        <p v-else-if="!running" class="small text-secondary mb-0">{{ run.message }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bootui-copilot-fix-diff {
  max-height: 28rem;
  overflow: auto;
  white-space: pre;
}
.bootui-copilot-fix-events {
  max-height: 16rem;
  overflow: auto;
}
</style>
