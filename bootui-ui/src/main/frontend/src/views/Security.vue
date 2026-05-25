<script setup>
import { onMounted, ref } from 'vue'

const report = ref(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''

  try {
    const response = await fetch('api/security')
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    report.value = await response.json()
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Failed to load security diagnostics'
  } finally {
    loading.value = false
  }
}

function stateIcon(enabled) {
  return enabled ? 'bi-check-circle-fill text-success' : 'bi-x-circle-fill text-danger'
}

function stateLabel(enabled) {
  return enabled ? 'Enabled' : 'Disabled'
}

function sessionPolicyClass(policy) {
  if (policy === 'STATELESS') {
    return 'text-bg-primary'
  }
  if (policy === 'UNKNOWN') {
    return 'text-bg-secondary'
  }
  return 'text-bg-dark'
}

onMounted(load)
</script>

<template>
  <div>
    <h2><i class="bi bi-shield-lock me-2"></i>Security</h2>
    <p class="text-muted">Read-only Spring Security diagnostics for 401/403 troubleshooting.</p>

    <div v-if="loading" class="card">
      <div class="card-body text-muted">Loading security diagnostics…</div>
    </div>

    <div v-else-if="error" class="alert alert-danger mb-0">
      Failed to load security diagnostics: {{ error }}
    </div>

    <template v-else-if="report">
      <div v-if="!report.springSecurityPresent" class="alert alert-info">
        Spring Security is not on the classpath.
      </div>

      <template v-else>
        <div v-if="report.auth" class="card mb-3">
          <div class="card-header">Authentication</div>
          <div class="card-body">
            <div class="mb-3">
              <div class="small text-muted mb-1">Authentication providers</div>
              <div v-if="report.auth.authenticationProviderTypes.length" class="d-flex flex-wrap gap-2">
                <span
                  v-for="provider in report.auth.authenticationProviderTypes"
                  :key="provider"
                  class="badge text-bg-light"
                >
                  {{ provider }}
                </span>
              </div>
              <div v-else class="text-muted">No AuthenticationProvider beans found.</div>
            </div>

            <div>
              <div class="small text-muted mb-1">UserDetailsService</div>
              <code v-if="report.auth.userDetailsServiceType">{{ report.auth.userDetailsServiceType }}</code>
              <span v-else class="text-muted">None</span>
            </div>

            <div v-if="report.auth.hasAutoConfiguredUser" class="alert alert-warning mt-3 mb-0">
              Using Spring Boot auto-configured in-memory user.
            </div>
          </div>
        </div>

        <div v-if="!report.chains.length" class="alert alert-info">
          No SecurityFilterChain beans found.
        </div>

        <div v-else class="d-grid gap-3">
          <div v-for="chain in report.chains" :key="`${chain.order}-${chain.requestMatcherDescription}`" class="card">
            <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
              <div class="d-flex align-items-center gap-2">
                <span class="badge text-bg-dark">Order {{ chain.order }}</span>
                <code>{{ chain.requestMatcherDescription }}</code>
              </div>
              <span class="badge" :class="sessionPolicyClass(chain.sessionCreationPolicy)">
                {{ chain.sessionCreationPolicy }}
              </span>
            </div>
            <div class="card-body">
              <div class="row g-3 align-items-start">
                <div class="col-lg-4">
                  <div class="small text-muted mb-2">Chain summary</div>
                  <ul class="list-unstyled mb-0 d-grid gap-2">
                    <li class="d-flex align-items-center gap-2">
                      <i class="bi" :class="stateIcon(chain.csrfEnabled)"></i>
                      <span>CSRF {{ stateLabel(chain.csrfEnabled) }}</span>
                    </li>
                    <li class="d-flex align-items-center gap-2">
                      <i class="bi" :class="stateIcon(chain.corsEnabled)"></i>
                      <span>CORS {{ stateLabel(chain.corsEnabled) }}</span>
                    </li>
                  </ul>
                </div>
                <div class="col-lg-8">
                  <div class="small text-muted mb-2">Filter pipeline</div>
                  <div v-if="chain.filterNames.length" class="d-flex flex-row flex-nowrap gap-2 overflow-auto pb-1">
                    <span
                      v-for="(filterName, index) in chain.filterNames"
                      :key="`${filterName}-${index}`"
                      class="badge text-bg-secondary"
                    >
                      {{ filterName }}
                    </span>
                  </div>
                  <div v-else class="text-muted">No filters reported for this chain.</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>
