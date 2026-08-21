<script setup>
import {apiFetch} from '../api.js'
import {computed, inject, ref} from 'vue'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {formatLoadError} from '../utils/loadError.js'
import {useConfirm} from '../utils/useConfirm.js'
import PanelHeader from './components/PanelHeader.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const {confirm} = useConfirm()
const panels = inject('panels', ref(null))
const frameworkLabel = computed(() => (panels.value?.platform === 'quarkus' ? 'Quarkus' : 'Spring Boot'))
const probeSubtitle = computed(
  () => `Send local HTTP requests against your running ${frameworkLabel.value} app without leaving BootUI.`
)
const method = ref('GET')
const path = ref('')
const requestBody = ref('')
const loading = ref(false)
const confirming = ref(false)
const response = ref(null)

const methodsWithBody = ['POST', 'PUT', 'PATCH']
const safeMethods = new Set(['GET', 'HEAD'])

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
  if (readOnly.value || loading.value || confirming.value) {
    if (!readOnly.value) return
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
  const request = {
    method: method.value,
    path: normalizePath(path.value),
    body: showRequestBody.value && requestBody.value ? requestBody.value : null,
    headers: requestHeaders.value
  }
  if (!safeMethods.has(request.method)) {
    confirming.value = true
    let confirmed
    try {
      confirmed = await confirm({
        title: `Send ${request.method} request?`,
        message: 'This request may change state in your running app. Review the method and path before continuing.',
        resource: `${request.method} ${request.path}`,
        confirmLabel: `Send ${request.method}`,
        danger: true
      })
    } finally {
      confirming.value = false
    }
    if (!confirmed) return
  }
  loading.value = true
  try {
    const res = await apiFetch('api/http-probe', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(request)
    })
    const payload = await res.json().catch(() => null)
    if (!res.ok) {
      // BootUI bounds probe input (body, path, headers): an over-limit request is rejected with a
      // canonical {"error": ...} body before anything is sent to the application.
      response.value = {
        status: 0,
        statusText: 'Rejected',
        headers: {},
        body: null,
        durationMs: 0,
        error: payload?.error || `Probe request rejected (HTTP ${res.status})`
      }
    } else {
      response.value = payload
    }
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
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`
}

function clearForm() {
  method.value = 'GET'
  path.value = ''
  requestBody.value = ''
  response.value = null
  loading.value = false
  confirming.value = false
}
</script>

<template>
  <div>
    <PanelHeader icon="bi-send" title="HTTP Probe" :subtitle="probeSubtitle">
      <template #actions>
        <button :disabled="loading || confirming" class="btn btn-outline-secondary" @click="clearForm">
          <i class="bi bi-x-circle me-1"></i>Clear
        </button>
        <SpinnerButton
          :loading="loading"
          :disabled="loading || confirming || readOnly"
          class="btn btn-primary"
          icon="bi-send"
          label="Send"
          loading-label="Sending…"
          spinner-class="me-2"
          @click="sendProbe"
        />
      </template>
    </PanelHeader>

    <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">HTTP probes are read-only.</ReadOnlyNotice>

    <div class="card mb-4">
      <div class="card-body">
        <div class="row g-3 align-items-start">
          <div class="col-md-3 col-lg-2">
            <label class="form-label" for="http-probe-method">Method</label>
            <select id="http-probe-method" v-model="method" class="form-select">
              <option value="GET">GET</option>
              <option value="HEAD">HEAD</option>
              <option value="POST">POST</option>
              <option value="PUT">PUT</option>
              <option value="DELETE">DELETE</option>
              <option value="PATCH">PATCH</option>
            </select>
          </div>
          <div class="col-md-9 col-lg-10">
            <label class="form-label" for="http-probe-path">Path</label>
            <input
              id="http-probe-path"
              v-model="path"
              class="form-control font-monospace"
              placeholder="/api/sample/hello"
              @keyup.enter="sendProbe"
            />
            <div class="form-text">Relative to the application root. Requests are always sent to localhost.</div>
          </div>
        </div>

        <div v-if="showRequestBody" class="mt-3">
          <label class="form-label" for="http-probe-body">Request body</label>
          <textarea
            id="http-probe-body"
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
          <h4 class="fs-6">Headers</h4>
          <ul class="list-unstyled small mb-0">
            <li v-for="(value, name) in response.headers" :key="name">
              <code>{{ name }}</code
              >: {{ value }}
            </li>
          </ul>
        </div>

        <div>
          <h4 class="fs-6">Body</h4>
          <div v-if="response.truncated" class="alert alert-warning mb-2 py-1 small">
            <i class="bi bi-scissors me-1"></i>Response body was truncated at the configured byte limit.
          </div>
          <pre class="bg-body-tertiary border rounded p-3 mb-0"><code>{{
              formattedResponseBody || '(empty response body)'
            }}</code></pre>
        </div>
      </div>
    </div>
  </div>
</template>
