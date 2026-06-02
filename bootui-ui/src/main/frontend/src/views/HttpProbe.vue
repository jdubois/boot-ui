<script setup>
import {computed, ref} from 'vue'
import {apiFetch} from '../api.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {formatLoadError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const method = ref('GET')
const path = ref('')
const requestBody = ref('')
const loading = ref(false)
const response = ref(null)

const methodsWithBody = ['POST', 'PUT', 'PATCH']

const showRequestBody = computed(() => methodsWithBody.includes(method.value))

const requestHeaders = computed(() => {
  if (!showRequestBody.value) return {}
  if (!requestBody.value.trim()) return {}
  return {'Content-Type': 'application/json'}
})

const formattedResponseBody = computed(() => {
  const body = response.value?.body
  if (!body) return ''
  const trimmed = body.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return body
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch {
    return body
  }
})

const statusBadgeClass = computed(() => {
  if (response.value?.error) return 'bg-danger'
  const status = response.value?.status ?? 0
  if (status >= 200 && status < 300) return 'bg-success'
  if (status >= 300 && status < 400) return 'bg-warning text-dark'
  if (status >= 400) return 'bg-danger'
  return 'bg-secondary'
})

async function sendProbe() {
  if (readOnly.value) {
    response.value = {
      status: 0,
      statusText: 'Read-only',
      headers: {},
      body: null,
      durationMs: 0,
      error: readOnlyReason.value
    }
    return
  }
  loading.value = true
  try {
    const res = await apiFetch('api/probe', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        method: method.value,
        path: normalizePath(path.value),
        body: showRequestBody.value && requestBody.value ? requestBody.value : null,
        headers: requestHeaders.value
      })
    })
    response.value = await res.json()
  } catch (error) {
    response.value = {
      status: 0,
      statusText: 'Error',
      headers: {},
      body: null,
      durationMs: 0,
      error: formatLoadError(error, 'Unable to send HTTP probe')
    }
  } finally {
    loading.value = false
  }
}

function normalizePath(value) {
  const trimmed = (value || '').trim()
  if (!trimmed) return '/'
  return trimmed.startsWith('/') ? trimmed : '/' + trimmed
}

function clearForm() {
  method.value = 'GET'
  path.value = ''
  requestBody.value = ''
  response.value = null
  loading.value = false
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-send"
      title="HTTP Probe"
      subtitle="Send local HTTP requests against your running Spring Boot app without leaving BootUI."
    >
      <template #actions>
        <button :disabled="loading" class="btn btn-outline-secondary" @click="clearForm">
          <i class="bi bi-x-circle me-1"></i>Clear
        </button>
        <button :disabled="loading || readOnly" class="btn btn-primary" @click="sendProbe">
          <span v-if="loading" aria-hidden="true" class="spinner-border spinner-border-sm me-2"></span>
          <i v-else class="bi bi-send me-1"></i>
          {{ loading ? 'Sending…' : 'Send' }}
        </button>
      </template>
    </PanelHeader>

    <div v-if="readOnly" class="alert alert-warning small">
      <i class="bi bi-lock me-1"></i>
      HTTP probes are read-only. {{ readOnlyReason }}
    </div>

    <div class="card mb-4">
      <div class="card-body">
        <div class="row g-3 align-items-start">
          <div class="col-md-3 col-lg-2">
            <label class="form-label">Method</label>
            <select v-model="method" class="form-select">
              <option value="GET">GET</option>
              <option value="POST">POST</option>
              <option value="PUT">PUT</option>
              <option value="DELETE">DELETE</option>
              <option value="PATCH">PATCH</option>
            </select>
          </div>
          <div class="col-md-9 col-lg-10">
            <label class="form-label">Path</label>
            <input
              v-model="path"
              class="form-control font-monospace"
              placeholder="/api/sample/hello"
              @keyup.enter="sendProbe"
            />
            <div class="form-text">Relative to the application root. Requests are always sent to localhost.</div>
          </div>
        </div>

        <div v-if="showRequestBody" class="mt-3">
          <label class="form-label">Request body</label>
          <textarea
            v-model="requestBody"
            class="form-control font-monospace"
            placeholder='{
  "message": "hello"
}'
            rows="10"
          ></textarea>
          <div v-if="Object.keys(requestHeaders).length" class="form-text">
            Content-Type: <code>application/json</code>
          </div>
        </div>
      </div>
    </div>

    <div v-if="response" class="card">
      <div class="card-header d-flex flex-wrap justify-content-between gap-2 align-items-center">
        <div class="d-flex align-items-center gap-2">
          <strong>Response</strong>
          <span :class="statusBadgeClass" class="badge">
            {{ response.status || 0 }} {{ response.statusText || 'Error' }}
          </span>
        </div>
        <small class="text-muted">{{ response.durationMs ?? 0 }} ms</small>
      </div>
      <div class="card-body">
        <div v-if="response.error" class="alert alert-danger mb-3">
          <i class="bi bi-exclamation-octagon me-2"></i>{{ response.error }}
        </div>

        <div v-if="response.headers && Object.keys(response.headers).length" class="mb-3">
          <h6>Headers</h6>
          <ul class="list-unstyled small mb-0">
            <li v-for="(value, name) in response.headers" :key="name">
              <code>{{ name }}</code
              >: {{ value }}
            </li>
          </ul>
        </div>

        <div>
          <h6>Body</h6>
          <pre class="bg-body-tertiary border rounded p-3 mb-0"><code>{{
              formattedResponseBody || '(empty response body)'
            }}</code></pre>
        </div>
      </div>
    </div>
  </div>
</template>
