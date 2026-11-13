<script setup>
import {ApiError, apiFetch, getJson, isPanelAccessError} from '../api.js'
import {diagnosticActionError} from '../utils/diagnosticAcknowledgement.js'
import {computed, inject, nextTick, onBeforeUnmount, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {formatLoadError} from '../utils/loadError.js'
import {useConfirm} from '../utils/useConfirm.js'
import {
  BODY_CARRYING_METHODS,
  composeProbePath,
  extractPathPlaceholders,
  hasQueryValuePlaceholders,
  isMappingsAvailable,
  normalizeBasePath,
  preferredAccept,
  preferredContentType,
  resolveProbeMethod
} from '../utils/httpProbePrefill.js'
import PanelHeader from './components/PanelHeader.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const {confirm} = useConfirm()
const panels = inject('panels', ref(null))
let route = null
try {
  route = useRoute()
} catch {
  route = null
}

const frameworkLabel = computed(() => (panels.value?.platform === 'quarkus' ? 'Quarkus' : 'Spring Boot'))
const probeSubtitle = computed(
  () => `Send local HTTP requests against your running ${frameworkLabel.value} app without leaving BootUI.`
)
const method = ref('GET')
const path = ref('')
const pathInputRef = ref(null)
const acceptHeader = ref('')
const contentTypeHeader = ref('')
const acceptEditedByUser = ref(false)
const contentTypeEditedByUser = ref(false)
const requestBody = ref('')
const prefillSource = ref('')
const omittedQueryCount = ref(0)
const loading = ref(false)
const confirming = ref(false)
const response = ref(null)
let suppressNextPathEnterKeyup = false

const safeMethods = new Set(['GET', 'HEAD'])

const showRequestBody = computed(() => BODY_CARRYING_METHODS.has(method.value))
const showContentTypeField = computed(() => showRequestBody.value || Boolean(contentTypeHeader.value.trim()))

const mappingsAvailable = computed(() => isMappingsAvailable(panels.value))

const routeListboxId = 'http-probe-route-listbox'
const routePickerHintId = 'http-probe-route-picker-hint'
const ROUTE_SUGGEST_DELAY_MS = 120
const routeQuery = ref('')
const routeReport = ref(null)
const routeError = ref(null)
const routeLoading = ref(false)
const routePickerOpen = ref(false)
const activeRouteIndex = ref(-1)
let routeSuggestTimer = null
let routeSuggestSequence = 0

const routeBasePath = computed(() => normalizeBasePath(routeReport.value?.basePath ?? ''))

const routeOptions = computed(() => {
  const mappings = routeReport.value?.mappings
  if (!Array.isArray(mappings)) return []
  return mappings
    .filter((item) => typeof item?.pattern === 'string' && item.pattern.trim().startsWith('/'))
    .map((item, idx) => {
      const resolvedMethod = resolveProbeMethod(item.method)
      const composedPath = composeProbePath(routeBasePath.value, item.pattern)
      const accept = preferredAccept(item.produces)
      const contentType = preferredContentType(item.consumes, resolvedMethod)
      const mediaParts = [accept, contentType].filter(Boolean)
      return {
        key: `${item.method || 'ANY'}:${item.pattern}:${item.handler || ''}:${idx}`,
        raw: item,
        method: item.method || 'ANY',
        resolvedMethod,
        composedPath,
        shortHandler: shortenHandler(item.handler),
        mediaSummary: [...new Set(mediaParts)].join(' · ')
      }
    })
})

const routePopupVisible = computed(
  () => routePickerOpen.value && Boolean(routeLoading.value || routeError.value || routeReport.value)
)
const routeListVisible = computed(() => routePopupVisible.value && routeOptions.value.length > 0)
const activeRouteOptionId = computed(() =>
  routeListVisible.value && activeRouteIndex.value >= 0
    ? `http-probe-route-option-${activeRouteIndex.value}`
    : undefined
)

const routePickerStatus = computed(() => {
  if (!routePopupVisible.value) return ''
  if (routeLoading.value && !routeReport.value) return 'Loading declared routes…'
  if (routeError.value) return routeError.value
  if (!routeOptions.value.length) {
    const q = routeQuery.value.trim()
    return q ? `No declared routes match “${q}”.` : 'No declared HTTP mappings were reported.'
  }
  const count = routeOptions.value.length
  const matched = routeReport.value?.page?.matched ?? count
  const shown = `${count} ${count === 1 ? 'route' : 'routes'}`
  return matched > count ? `${shown} shown of ${matched} matching routes — type to narrow` : shown
})

const pathPlaceholders = computed(() => extractPathPlaceholders(path.value))
const hasValuePlaceholders = computed(() => hasQueryValuePlaceholders(path.value))
const needsExchangeBodyNote = computed(() => prefillSource.value === 'http-exchanges' && showRequestBody.value)

const requestHeaders = computed(() => {
  const headers = {}
  const accept = acceptHeader.value.trim()
  if (accept) {
    headers.Accept = accept
  }
  const contentType = contentTypeHeader.value.trim()
  if (contentType) {
    headers['Content-Type'] = contentType
  } else if (showRequestBody.value && requestBody.value.trim()) {
    headers['Content-Type'] = 'application/json'
  }
  return headers
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

function routeMethodBadgeClass(m) {
  return (
    {
      GET: 'bg-success',
      POST: 'bg-primary',
      PUT: 'bg-warning text-dark',
      DELETE: 'bg-danger',
      PATCH: 'bg-info text-dark',
      ANY: 'bg-secondary'
    }[m] || 'bg-secondary'
  )
}

function shortenHandler(handler) {
  if (typeof handler !== 'string' || !handler.trim()) return ''
  const trimmed = handler.trim()
  const hashIndex = trimmed.indexOf('#')
  const classPart = hashIndex >= 0 ? trimmed.slice(0, hashIndex) : trimmed
  const methodPart = hashIndex >= 0 ? trimmed.slice(hashIndex) : ''
  const lastDot = classPart.lastIndexOf('.')
  const simpleClass = lastDot >= 0 ? classPart.slice(lastDot + 1) : classPart
  return `${simpleClass}${methodPart}`
}

async function fetchRoutes(query = '') {
  const sequence = ++routeSuggestSequence
  routeLoading.value = true
  try {
    const params = new URLSearchParams({limit: '50'})
    if (query) {
      params.set('q', query)
    }
    const report = await getJson(`api/mappings/flat?${params.toString()}`)
    if (sequence !== routeSuggestSequence) return
    routeReport.value = report
    routeError.value = null
  } catch (e) {
    if (sequence !== routeSuggestSequence) return
    routeReport.value = null
    routeError.value = formatLoadError(e, 'Unable to load declared routes')
  } finally {
    if (sequence === routeSuggestSequence) {
      routeLoading.value = false
    }
  }
  if (sequence !== routeSuggestSequence) return
  activeRouteIndex.value = -1
  routePickerOpen.value = true
}

function onRoutePickerFocus() {
  routePickerOpen.value = true
  if (!routeReport.value && !routeLoading.value) {
    fetchRoutes(routeQuery.value.trim())
  }
}

function onRoutePickerInput() {
  clearTimeout(routeSuggestTimer)
  const q = routeQuery.value.trim()
  routeSuggestTimer = setTimeout(() => fetchRoutes(q), ROUTE_SUGGEST_DELAY_MS)
}

function closeRoutePicker() {
  clearTimeout(routeSuggestTimer)
  routeSuggestSequence++
  routeLoading.value = false
  routePickerOpen.value = false
  activeRouteIndex.value = -1
}

function scrollActiveRouteIntoView() {
  nextTick(() => {
    if (activeRouteIndex.value < 0) return
    const el = document.getElementById(`http-probe-route-option-${activeRouteIndex.value}`)
    el?.scrollIntoView?.({block: 'nearest'})
  })
}

async function selectRoute(mapping, fromKeyboard = false) {
  if (!mapping) return
  const nextMethod = resolveProbeMethod(mapping.method)
  const composed = composeProbePath(routeBasePath.value, mapping.pattern)
  method.value = nextMethod
  path.value = composed
  response.value = null

  const nextAccept = preferredAccept(mapping.produces)
  if (!acceptEditedByUser.value || !acceptHeader.value.trim()) {
    acceptHeader.value = nextAccept
  }

  if (BODY_CARRYING_METHODS.has(nextMethod)) {
    const nextContentType = preferredContentType(mapping.consumes, nextMethod)
    if (!contentTypeEditedByUser.value || !contentTypeHeader.value.trim()) {
      contentTypeHeader.value = nextContentType
    }
    const effectiveContentType = (contentTypeHeader.value || nextContentType || 'application/json').toLowerCase()
    if (effectiveContentType.includes('json') && !requestBody.value.trim()) {
      requestBody.value = '{}'
    }
  } else if (!contentTypeEditedByUser.value) {
    contentTypeHeader.value = ''
  }

  prefillSource.value = 'mappings'
  omittedQueryCount.value = 0
  routeQuery.value = composed
  closeRoutePicker()
  if (fromKeyboard) {
    suppressNextPathEnterKeyup = true
  }
  await nextTick()
  pathInputRef.value?.focus?.()
}

function onPathKeydown(event) {
  if (event.key === 'Enter') {
    suppressNextPathEnterKeyup = false
  }
}

function onPathEnterKeyup() {
  if (suppressNextPathEnterKeyup) {
    suppressNextPathEnterKeyup = false
    return
  }
  sendProbe()
}

function onRoutePickerKeydown(event) {
  const count = routeOptions.value.length
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    if (!routeListVisible.value) {
      event.preventDefault()
      routePickerOpen.value = true
      if (!routeReport.value && !routeLoading.value) {
        fetchRoutes(routeQuery.value.trim())
      } else if (count > 0) {
        activeRouteIndex.value = event.key === 'ArrowDown' ? 0 : count - 1
        scrollActiveRouteIntoView()
      }
      return
    }
    event.preventDefault()
    const step = event.key === 'ArrowDown' ? 1 : -1
    activeRouteIndex.value =
      activeRouteIndex.value < 0 ? (step > 0 ? 0 : count - 1) : (activeRouteIndex.value + step + count) % count
    scrollActiveRouteIntoView()
  } else if (event.key === 'Enter' && routeListVisible.value && activeRouteIndex.value >= 0) {
    event.preventDefault()
    selectRoute(routeOptions.value[activeRouteIndex.value].raw, true)
  } else if (event.key === 'Escape' && routePopupVisible.value) {
    event.preventDefault()
    closeRoutePicker()
  }
}

function applyQueryPrefill(query) {
  if (!query || typeof query !== 'object') return
  const hasPath = typeof query.path === 'string' && query.path.trim() !== ''
  const hasMethod = typeof query.method === 'string' && query.method.trim() !== ''
  const hasAccept = typeof query.accept === 'string' && query.accept.trim() !== ''
  const hasContentType = typeof query.contentType === 'string' && query.contentType.trim() !== ''
  if (!hasPath && !hasMethod && !hasAccept && !hasContentType) {
    return
  }

  response.value = null
  const nextMethod = hasMethod ? resolveProbeMethod(query.method) : method.value
  method.value = nextMethod
  if (hasPath) {
    path.value = normalizePath(query.path)
  }
  acceptHeader.value = hasAccept ? query.accept.trim() : ''
  contentTypeHeader.value = hasContentType ? query.contentType.trim() : ''
  acceptEditedByUser.value = false
  contentTypeEditedByUser.value = false

  const source = typeof query.source === 'string' ? query.source.trim() : 'mappings'
  prefillSource.value = source
  const omitted = Number.parseInt(String(query.omittedQuery ?? '0'), 10)
  omittedQueryCount.value = Number.isFinite(omitted) && omitted > 0 ? omitted : 0

  if (
    source !== 'http-exchanges' &&
    BODY_CARRYING_METHODS.has(nextMethod) &&
    (contentTypeHeader.value || 'application/json').toLowerCase().includes('json') &&
    !requestBody.value.trim()
  ) {
    requestBody.value = '{}'
  }
}

watch(
  () => route?.query,
  (query) => {
    if (query) {
      applyQueryPrefill(query)
    }
  },
  {immediate: true}
)

onBeforeUnmount(closeRoutePicker)

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
    if (!confirmed || readOnly.value) return
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
      const error = new ApiError(res.status, payload)
      // BootUI bounds probe input (body, path, headers): an over-limit request is rejected with a
      // canonical {"error": ...} body before anything is sent to the application.
      response.value = {
        status: 0,
        statusText: 'Rejected',
        headers: {},
        body: null,
        durationMs: 0,
        error: isPanelAccessError(error)
          ? diagnosticActionError(error, 'Probe request rejected')
          : payload?.error || `Probe request rejected (HTTP ${res.status})`
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
  acceptHeader.value = ''
  contentTypeHeader.value = ''
  acceptEditedByUser.value = false
  contentTypeEditedByUser.value = false
  requestBody.value = ''
  routeQuery.value = ''
  prefillSource.value = ''
  omittedQueryCount.value = 0
  suppressNextPathEnterKeyup = false
  response.value = null
  loading.value = false
  confirming.value = false
  closeRoutePicker()
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

    <div class="card mb-4 http-probe-request-card">
      <div class="card-body">
        <div v-if="mappingsAvailable" class="mb-3 http-probe-route-picker">
          <label class="form-label" for="http-probe-route-picker">Pick a declared route</label>
          <div class="http-probe-combobox">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true"><i class="bi bi-signpost-2"></i></span>
              <input
                id="http-probe-route-picker"
                v-model="routeQuery"
                type="search"
                role="combobox"
                class="form-control font-monospace"
                aria-autocomplete="list"
                :aria-expanded="routePopupVisible ? 'true' : 'false'"
                :aria-controls="routeListVisible ? routeListboxId : undefined"
                :aria-activedescendant="activeRouteOptionId"
                :aria-describedby="routePickerHintId"
                placeholder="Search declared routes by path, method, handler, or media type…"
                autocomplete="off"
                spellcheck="false"
                @focus="onRoutePickerFocus"
                @input="onRoutePickerInput"
                @keydown="onRoutePickerKeydown"
                @blur="closeRoutePicker"
              />
            </div>
            <div v-show="routePopupVisible" class="http-probe-route-popup">
              <ul
                v-if="routeListVisible"
                :id="routeListboxId"
                class="list-unstyled mb-0 http-probe-route-options"
                role="listbox"
                aria-label="Declared routes"
              >
                <li
                  v-for="(option, index) in routeOptions"
                  :id="`http-probe-route-option-${index}`"
                  :key="option.key"
                  role="option"
                  class="http-probe-route-option"
                  :class="{active: index === activeRouteIndex}"
                  :aria-selected="index === activeRouteIndex ? 'true' : 'false'"
                  @mousedown.prevent
                  @mousemove="activeRouteIndex = Number(index)"
                  @click="selectRoute(option.raw)"
                >
                  <span :class="routeMethodBadgeClass(option.method)" class="badge http-probe-route-method">{{
                    option.method
                  }}</span>
                  <code class="http-probe-route-path bootui-break-anywhere">{{ option.composedPath }}</code>
                  <span
                    v-if="option.mediaSummary"
                    class="badge text-bg-light border fw-normal http-probe-route-media"
                    >{{ option.mediaSummary }}</span
                  >
                  <span v-if="option.shortHandler" class="small text-muted http-probe-route-handler">{{
                    option.shortHandler
                  }}</span>
                </li>
              </ul>
              <p class="small text-muted mb-0 http-probe-route-status" aria-hidden="true">{{ routePickerStatus }}</p>
            </div>
            <span class="visually-hidden" role="status">{{ routePickerStatus }}</span>
          </div>
          <div :id="routePickerHintId" class="form-text">
            Prefills method, path<template v-if="routeBasePath">
              (prefixed with base path <code>{{ routeBasePath }}</code
              >)</template
            >, and <code>Accept</code> / <code>Content-Type</code> headers from Mappings without sending a request.
          </div>
        </div>

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
              <option value="OPTIONS">OPTIONS</option>
            </select>
          </div>
          <div class="col-md-9 col-lg-10">
            <label class="form-label" for="http-probe-path">Path</label>
            <input
              id="http-probe-path"
              ref="pathInputRef"
              v-model="path"
              class="form-control font-monospace"
              placeholder="/api/sample/hello"
              @keydown="onPathKeydown"
              @keyup.enter="onPathEnterKeyup"
            />
            <div class="form-text">Relative to the application root. Requests are always sent to localhost.</div>
            <div
              v-if="pathPlaceholders.length || hasValuePlaceholders || needsExchangeBodyNote || omittedQueryCount > 0"
              class="alert alert-info py-2 px-3 mt-2 mb-0 small http-probe-placeholder-hint"
              role="status"
            >
              <div v-if="pathPlaceholders.length">
                <i aria-hidden="true" class="bi bi-info-circle me-1"></i>
                Replace path {{ pathPlaceholders.length === 1 ? 'placeholder' : 'placeholders' }}
                <code>{{ pathPlaceholders.join(', ') }}</code> with
                {{ pathPlaceholders.length === 1 ? 'a concrete value' : 'concrete values' }} before sending.
              </div>
              <div v-if="hasValuePlaceholders" :class="{'mt-1': pathPlaceholders.length}">
                <i aria-hidden="true" class="bi bi-info-circle me-1"></i>
                Query parameter values from HTTP Exchanges use <code>VALUE</code> placeholders—replace them with
                concrete values before sending.
              </div>
              <div v-if="omittedQueryCount > 0" :class="{'mt-1': pathPlaceholders.length || hasValuePlaceholders}">
                <i aria-hidden="true" class="bi bi-info-circle me-1"></i>
                {{ omittedQueryCount }} masked or malformed query
                {{ omittedQueryCount === 1 ? 'parameter was' : 'parameters were' }} omitted—supply
                {{ omittedQueryCount === 1 ? 'it' : 'them' }} in the path if needed.
              </div>
              <div
                v-if="needsExchangeBodyNote"
                :class="{'mt-1': pathPlaceholders.length || hasValuePlaceholders || omittedQueryCount > 0}"
              >
                <i aria-hidden="true" class="bi bi-info-circle me-1"></i>
                BootUI never records request bodies in HTTP Exchanges—supply a request body before sending.
              </div>
            </div>
          </div>
        </div>

        <div class="row g-3 align-items-start mt-1">
          <div :class="showContentTypeField ? 'col-md-6' : 'col-12'">
            <label class="form-label" for="http-probe-accept">
              Accept header <span class="text-muted fw-normal">(optional)</span>
            </label>
            <input
              id="http-probe-accept"
              v-model="acceptHeader"
              class="form-control font-monospace"
              placeholder="application/json"
              @input="acceptEditedByUser = true"
            />
          </div>
          <div v-if="showContentTypeField" class="col-md-6">
            <label class="form-label" for="http-probe-content-type">
              Content-Type header <span class="text-muted fw-normal">(optional)</span>
            </label>
            <input
              id="http-probe-content-type"
              v-model="contentTypeHeader"
              class="form-control font-monospace"
              placeholder="application/json"
              @input="contentTypeEditedByUser = true"
            />
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
          <div v-if="requestHeaders['Content-Type']" class="form-text">
            Content-Type: <code>{{ requestHeaders['Content-Type'] }}</code>
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

<style scoped>
.http-probe-request-card {
  position: relative;
  z-index: 5;
}

.http-probe-combobox {
  position: relative;
}

.http-probe-route-popup {
  background: var(--bootui-surface-solid, var(--bs-body-bg));
  border: 1px solid var(--bootui-border, var(--bs-border-color));
  border-radius: var(--bootui-radius-md, 0.5rem);
  box-shadow: var(--bootui-shadow-md, 0 0.5rem 1rem rgba(0, 0, 0, 0.15));
  left: 0;
  margin-top: 0.25rem;
  max-width: 100%;
  min-width: 100%;
  overflow: hidden;
  position: absolute;
  top: 100%;
  z-index: 20;
}

.http-probe-route-options {
  max-height: 18rem;
  overflow-y: auto;
  padding: 0.25rem;
}

.http-probe-route-option {
  align-items: center;
  border-radius: 0.5rem;
  cursor: pointer;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 0.35rem 0.5rem;
}

.http-probe-route-option.active {
  background: var(--bootui-nav-hover-bg, rgba(25, 135, 84, 0.08));
  box-shadow: inset 0 0 0 1px var(--bootui-blue, #0d6efd);
}

.http-probe-route-method {
  flex-shrink: 0;
  min-width: 3.5rem;
}

.http-probe-route-path {
  flex: 1 1 auto;
  min-width: 0;
}

.http-probe-route-media,
.http-probe-route-handler {
  flex-shrink: 0;
}

.http-probe-route-status {
  padding: 0.35rem 0.75rem;
}

.http-probe-route-options + .http-probe-route-status {
  border-top: 1px solid var(--bootui-border, var(--bs-border-color));
}
</style>
