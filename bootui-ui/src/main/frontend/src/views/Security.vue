<script setup>
import { ref, computed, onMounted } from 'vue'

const report = ref(null)
const error = ref(null)
const springSecurityPresent = ref(true)

const explainMethod = ref('GET')
const explainPath = ref('/')
const explainResult = ref(null)
const explainLoading = ref(false)

const endpoints = ref(null)
const endpointsError = ref(null)
const endpointsLoading = ref(false)
const endpointFilter = ref('')

async function load() {
  try {
    const res = await fetch('api/security')
    if (res.status === 404) {
      springSecurityPresent.value = false
      return
    }
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
    if (!report.value.springSecurityPresent) {
      springSecurityPresent.value = false
    }
  } catch (e) {
    error.value = e.message
  }
}

async function loadEndpoints() {
  endpointsLoading.value = true
  endpointsError.value = null
  try {
    const res = await fetch('api/security/endpoints')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    endpoints.value = await res.json()
  } catch (e) {
    endpointsError.value = e.message
  } finally {
    endpointsLoading.value = false
  }
}

async function explain() {
  explainLoading.value = true
  explainResult.value = null
  try {
    const params = new URLSearchParams({ method: explainMethod.value, path: explainPath.value })
    const res = await fetch('api/security/explain?' + params)
    if (res.ok) {
      explainResult.value = await res.json()
    } else {
      explainResult.value = { matched: false, bestEffort: false, matcherDescription: 'Error: HTTP ' + res.status, filters: [] }
    }
  } finally {
    explainLoading.value = false
  }
}

const httpMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']

const filterBadgeClass = name => {
  if (name.includes('Csrf')) return 'bg-warning text-dark'
  if (name.includes('Cors')) return 'bg-info text-dark'
  if (name.includes('Session')) return 'bg-secondary'
  if (name.includes('Authentication') || name.includes('Login') || name.includes('Logout')) return 'bg-primary'
  if (name.includes('Authorization') || name.includes('Access')) return 'bg-danger'
  if (name.includes('Security') || name.includes('Exception')) return 'bg-dark'
  return 'bg-light text-dark border'
}

const ruleBadgeClass = rule => {
  switch (rule) {
    case 'permitAll': return 'bg-success'
    case 'authenticated': return 'bg-primary'
    case 'hasRole':
    case 'hasAuthority': return 'bg-warning text-dark'
    case 'denyAll': return 'bg-danger'
    case 'unsecured': return 'bg-secondary'
    case 'custom':
    case 'unknown':
    default: return 'bg-dark'
  }
}

const methodBadgeClass = method => {
  switch (method) {
    case 'GET': return 'bg-info text-dark'
    case 'POST': return 'bg-success'
    case 'PUT':
    case 'PATCH': return 'bg-warning text-dark'
    case 'DELETE': return 'bg-danger'
    default: return 'bg-light text-dark border'
  }
}

const shortName = name => {
  if (!name) return ''
  const i = name.lastIndexOf('.')
  return i < 0 ? name : name.substring(i + 1)
}

const filteredEndpoints = computed(() => {
  if (!endpoints.value || !endpoints.value.endpoints) return []
  const needle = endpointFilter.value.trim().toLowerCase()
  if (!needle) return endpoints.value.endpoints
  return endpoints.value.endpoints.filter(e =>
    (e.pattern || '').toLowerCase().includes(needle)
    || (e.method || '').toLowerCase().includes(needle)
    || (e.handler || '').toLowerCase().includes(needle)
    || (e.rule || '').toLowerCase().includes(needle)
  )
})

onMounted(() => {
  load()
  loadEndpoints()
})
</script>

<template>
  <div>
    <h2><i class="bi bi-person-lock me-2"></i>Spring Security</h2>

    <div v-if="!springSecurityPresent" class="alert alert-info">
      Spring Security is not on the classpath of this application. Add
      <code>spring-boot-starter-security</code> to see security configuration here.
    </div>

    <div v-else-if="error" class="alert alert-danger">{{ error }}</div>

    <template v-else-if="report">

      <!-- Filter chains -->
      <h5 class="mt-3 mb-2">Filter chains <span class="badge bg-secondary">{{ report.chains.length }}</span></h5>

      <div v-if="report.chains.length === 0" class="alert alert-secondary">
        No filter chains detected. Spring Security may be present but not configured.
      </div>

      <div v-else class="accordion mb-4" id="chains-accordion">
        <div
          v-for="chain in report.chains"
          :key="chain.order"
          class="accordion-item">
          <h2 class="accordion-header">
            <button
              class="accordion-button collapsed"
              type="button"
              data-bs-toggle="collapse"
              :data-bs-target="'#chain-' + chain.order">
              <div class="d-flex align-items-center gap-2 flex-wrap">
                <span class="badge bg-secondary">#{{ chain.order }}</span>
                <code class="small">{{ chain.requestMatcher }}</code>
                <span class="badge bg-light text-dark border small">{{ chain.requestMatcherType }}</span>
                <span v-if="chain.csrfEnabled" class="badge bg-warning text-dark">CSRF</span>
                <span v-if="chain.corsEnabled" class="badge bg-info text-dark">CORS</span>
                <span v-if="chain.sessionManagementPresent" class="badge bg-secondary">Session</span>
                <span class="ms-auto text-muted small">{{ chain.filters.length }} filters</span>
              </div>
            </button>
          </h2>
          <div :id="'chain-' + chain.order" class="accordion-collapse collapse">
            <div class="accordion-body">
              <div class="mb-2 small text-muted">
                <strong>Request matcher:</strong> {{ chain.requestMatcher }}
                <span class="ms-2 badge bg-light text-dark border">{{ chain.requestMatcherType }}</span>
              </div>
              <div class="mb-1 small"><strong>Filters ({{ chain.filters.length }}):</strong></div>
              <div class="d-flex flex-wrap gap-1">
                <span
                  v-for="filter in chain.filters"
                  :key="filter"
                  class="badge"
                  :class="filterBadgeClass(filter)">
                  {{ filter }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Authentication -->
      <h5 class="mt-4 mb-2">Authentication</h5>
      <div v-if="report.auth">
        <dl class="row small">
          <template v-if="report.auth.authenticationProviderTypes.length">
            <dt class="col-sm-3">Authentication providers</dt>
            <dd class="col-sm-9">
              <div v-for="t in report.auth.authenticationProviderTypes" :key="t">
                <code>{{ shortName(t) }}</code>
                <span class="text-muted ms-1">({{ t }})</span>
              </div>
            </dd>
          </template>

          <template v-if="report.auth.userDetailsServiceTypes.length">
            <dt class="col-sm-3">UserDetailsService</dt>
            <dd class="col-sm-9">
              <div v-for="t in report.auth.userDetailsServiceTypes" :key="t">
                <code>{{ shortName(t) }}</code>
                <span class="text-muted ms-1">({{ t }})</span>
              </div>
            </dd>
          </template>

          <template v-if="report.auth.configuredUsername">
            <dt class="col-sm-3">Configured username</dt>
            <dd class="col-sm-9">
              <code>{{ report.auth.configuredUsername }}</code>
              <span class="text-muted ms-2 small">(spring.security.user.name)</span>
            </dd>
          </template>
        </dl>

        <div
          v-if="!report.auth.authenticationProviderTypes.length && !report.auth.userDetailsServiceTypes.length"
          class="alert alert-secondary small">
          No <code>AuthenticationProvider</code> or <code>UserDetailsService</code> beans found in the
          application context. These may be configured internally by a parent context.
        </div>
      </div>

      <!-- Endpoints -->
      <h5 class="mt-4 mb-2">
        Endpoints
        <span v-if="endpoints" class="badge bg-secondary">{{ endpoints.total }}</span>
        <button class="btn btn-sm btn-outline-secondary ms-2" @click="loadEndpoints" :disabled="endpointsLoading">
          <span v-if="endpointsLoading" class="spinner-border spinner-border-sm me-1"></span>
          Reload
        </button>
      </h5>
      <p class="text-muted small">
        Per-endpoint authorization rule resolved by matching each Spring MVC mapping against the
        configured filter chains. Resolution is best-effort: header- or session-based matchers may
        not be evaluated accurately.
      </p>

      <div v-if="endpointsError" class="alert alert-danger small">{{ endpointsError }}</div>
      <div v-else-if="endpoints && !endpoints.handlerMappingAvailable" class="alert alert-secondary small">
        No <code>RequestMappingHandlerMapping</code> bean is available — endpoints cannot be
        listed for this application.
      </div>
      <template v-else-if="endpoints">
        <input
          class="form-control form-control-sm mb-2"
          v-model="endpointFilter"
          placeholder="Filter by pattern, method, handler, or rule…" />

        <div class="table-responsive">
          <table class="table table-sm table-hover small align-middle">
            <thead>
              <tr>
                <th style="width:5rem">Method</th>
                <th>Pattern</th>
                <th>Handler</th>
                <th>Chain</th>
                <th>Rule</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(ep, idx) in filteredEndpoints" :key="idx">
                <td>
                  <span class="badge" :class="methodBadgeClass(ep.method)">{{ ep.method }}</span>
                </td>
                <td><code>{{ ep.pattern }}</code></td>
                <td class="text-muted">{{ ep.handler }}</td>
                <td>
                  <span v-if="ep.chainIndex != null" class="badge bg-light text-dark border">#{{ ep.chainIndex }}</span>
                  <span v-else class="text-muted">—</span>
                </td>
                <td>
                  <span class="badge me-1" :class="ruleBadgeClass(ep.rule)">{{ ep.rule }}</span>
                  <span
                    v-for="role in (ep.roles || [])"
                    :key="role"
                    class="badge bg-light text-dark border me-1">{{ role }}</span>
                  <span v-if="ep.bestEffort" class="badge bg-warning text-dark ms-1" title="Best effort: header- or session-based matchers may not be accurate">
                    <i class="bi bi-exclamation-triangle"></i>
                  </span>
                  <div v-if="ep.description" class="text-muted small">{{ ep.description }}</div>
                </td>
              </tr>
              <tr v-if="filteredEndpoints.length === 0">
                <td colspan="5" class="text-muted text-center">No endpoints match the filter.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <div v-else class="text-muted small">Loading endpoints…</div>

      <!-- Explain tool -->
      <h5 class="mt-4 mb-2">Explain a request</h5>
      <p class="text-muted small">
        Enter a method and path to see which filter chain would handle that request.
        Matching is best-effort: header- or session-based matchers may not be evaluated accurately.
      </p>
      <div class="row g-2 mb-3">
        <div class="col-auto">
          <select class="form-select form-select-sm" v-model="explainMethod" style="width:auto">
            <option v-for="m in httpMethods" :key="m" :value="m">{{ m }}</option>
          </select>
        </div>
        <div class="col">
          <input
            class="form-control form-control-sm"
            v-model="explainPath"
            placeholder="/api/example"
            @keyup.enter="explain" />
        </div>
        <div class="col-auto">
          <button class="btn btn-sm btn-primary" @click="explain" :disabled="explainLoading">
            <span v-if="explainLoading" class="spinner-border spinner-border-sm me-1"></span>
            Explain
          </button>
        </div>
      </div>

      <div v-if="explainResult" class="card">
        <div class="card-body small">
          <div class="mb-2">
            <span v-if="explainResult.matched" class="badge bg-success me-2">Matched</span>
            <span v-else class="badge bg-danger me-2">No match</span>
            <span v-if="explainResult.bestEffort" class="badge bg-warning text-dark me-2">Best effort</span>
            <span v-if="explainResult.chainIndex != null" class="text-muted">Chain #{{ explainResult.chainIndex }}</span>
          </div>
          <div v-if="explainResult.matcherDescription" class="mb-2">
            <strong>Matcher:</strong> <code>{{ explainResult.matcherDescription }}</code>
          </div>
          <div v-if="explainResult.filters && explainResult.filters.length">
            <strong>Filter pipeline:</strong>
            <div class="d-flex flex-wrap gap-1 mt-1">
              <span
                v-for="filter in explainResult.filters"
                :key="filter"
                class="badge"
                :class="filterBadgeClass(filter)">
                {{ filter }}
              </span>
            </div>
          </div>
          <div v-if="explainResult.bestEffort" class="alert alert-warning mt-2 mb-0 py-1 px-2 small">
            <i class="bi bi-exclamation-triangle me-1"></i>
            Result may be inaccurate for chains that match on headers, cookies, or session state.
          </div>
        </div>
      </div>

    </template>

    <div v-else class="text-muted small">Loading…</div>
  </div>
</template>
