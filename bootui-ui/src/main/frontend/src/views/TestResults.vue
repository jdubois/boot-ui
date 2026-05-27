<script setup>
import { computed, onMounted, ref } from 'vue'

const data = ref(null)
const error = ref(null)
const expandedSuites = ref(new Set())

const overallStatus = computed(() => {
  if (!data.value) return 'secondary'
  if (data.value.totalFailed > 0) return 'danger'
  if (data.value.totalSkipped > 0 && data.value.totalPassed === 0) return 'warning'
  if (data.value.totalPassed > 0) return 'success'
  return 'secondary'
})

const statusLabel = computed(() => {
  if (!data.value) return ''
  if (data.value.totalFailed > 0) return `${data.value.totalFailed} failed`
  if (data.value.totalPassed > 0) return 'All passing'
  return 'No tests found'
})

function toggleSuite(suiteName) {
  if (expandedSuites.value.has(suiteName)) {
    expandedSuites.value.delete(suiteName)
  } else {
    expandedSuites.value.add(suiteName)
  }
}

function expandAll() {
  data.value?.suites?.forEach(s => expandedSuites.value.add(s.name))
}

function collapseAll() {
  expandedSuites.value.clear()
}

function formatDuration(ms) {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function statusClass(status) {
  return {
    PASSED: 'text-bg-success',
    FAILED: 'text-bg-danger',
    ERROR: 'text-bg-danger',
    SKIPPED: 'text-bg-secondary'
  }[status] || 'text-bg-light'
}

function suiteStatusClass(suite) {
  if (suite.failed > 0) return 'border-danger-subtle'
  if (suite.skipped > 0 && suite.passed === 0) return 'border-warning-subtle'
  if (suite.passed > 0) return 'border-success-subtle'
  return ''
}

async function load() {
  try {
    const res = await fetch('api/test-results')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
    error.value = null
    // Auto-expand suites with failures
    data.value?.suites?.forEach(s => {
      if (s.failed > 0) expandedSuites.value.add(s.name)
    })
  } catch (e) {
    error.value = e.message
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
      <div>
        <h2 class="h4 mb-1"><i class="bi bi-check2-all me-2"></i>Test Results</h2>
        <p class="text-muted mb-0">
          Local Maven Surefire / Failsafe results from the last build run.
          Reload after running <code>./mvnw test</code> to refresh results.
        </p>
      </div>
      <button class="btn btn-outline-secondary" @click="load" title="Refresh">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    <div v-if="error" class="alert alert-danger">{{ error }}</div>

    <template v-if="data">
      <div v-if="!data.surefirePresent" class="card">
        <div class="card-body text-center py-5 text-muted">
          <i class="bi bi-folder-x fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">No Surefire reports found</div>
          <div class="small">Run <code>./mvnw test</code> to generate test reports, then refresh this panel.</div>
        </div>
      </div>

      <template v-else>
        <div class="row g-3 mb-3">
          <div class="col-sm-6 col-lg-3">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small">Total</div>
                <div class="display-6">{{ data.totalTests }}</div>
              </div>
            </div>
          </div>
          <div class="col-sm-6 col-lg-3">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small">Passed</div>
                <div class="display-6 text-success">{{ data.totalPassed }}</div>
              </div>
            </div>
          </div>
          <div class="col-sm-6 col-lg-3">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small">Failed</div>
                <div class="display-6" :class="data.totalFailed > 0 ? 'text-danger' : 'text-muted'">{{ data.totalFailed }}</div>
              </div>
            </div>
          </div>
          <div class="col-sm-6 col-lg-3">
            <div class="card h-100">
              <div class="card-body">
                <div class="text-muted small">Skipped</div>
                <div class="display-6 text-secondary">{{ data.totalSkipped }}</div>
              </div>
            </div>
          </div>
        </div>

        <div class="card mb-3">
          <div class="card-body d-flex align-items-center gap-3">
            <span class="badge fs-6" :class="'text-bg-' + overallStatus">{{ statusLabel }}</span>
            <span class="small text-muted">from <code>{{ data.reportsDir }}</code></span>
            <div class="ms-auto d-flex gap-2">
              <button class="btn btn-sm btn-outline-secondary" @click="expandAll">Expand all</button>
              <button class="btn btn-sm btn-outline-secondary" @click="collapseAll">Collapse all</button>
            </div>
          </div>
        </div>

        <div v-if="data.suites.length === 0" class="card">
          <div class="card-body text-center text-muted py-4">
            No TEST-*.xml files found in <code>{{ data.reportsDir }}</code>.
          </div>
        </div>

        <div v-for="suite in data.suites" :key="suite.name" class="card mb-2" :class="suiteStatusClass(suite)">
          <div
            class="card-header d-flex align-items-center gap-3 cursor-pointer"
            role="button"
            @click="toggleSuite(suite.name)">
            <i :class="['bi', expandedSuites.has(suite.name) ? 'bi-chevron-down' : 'bi-chevron-right']"></i>
            <span class="fw-semibold flex-grow-1 font-monospace small">{{ suite.name }}</span>
            <span class="badge text-bg-success">{{ suite.passed }} passed</span>
            <span v-if="suite.failed > 0" class="badge text-bg-danger">{{ suite.failed }} failed</span>
            <span v-if="suite.skipped > 0" class="badge text-bg-secondary">{{ suite.skipped }} skipped</span>
            <span class="text-muted small">{{ formatDuration(suite.durationMs) }}</span>
          </div>

          <div v-if="expandedSuites.has(suite.name)" class="table-responsive">
            <table class="table table-sm align-middle mb-0">
              <tbody>
                <tr v-for="tc in suite.testCases" :key="`${tc.className}.${tc.testName}`">
                  <td class="ps-4" style="width: 1%">
                    <span class="badge" :class="statusClass(tc.status)">{{ tc.status }}</span>
                  </td>
                  <td class="font-monospace small">{{ tc.testName }}</td>
                  <td class="text-muted small text-end pe-3">{{ formatDuration(tc.durationMs) }}</td>
                  <td v-if="tc.failureMessage" colspan="0" class="pb-2">
                    <div class="small text-danger font-monospace ms-4">
                      <strong>{{ tc.failureType }}</strong>
                      <pre class="mb-0 mt-1 failure-message">{{ tc.failureMessage }}</pre>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.cursor-pointer {
  cursor: pointer;
  user-select: none;
}

.failure-message {
  color: inherit;
  font-size: 0.8rem;
  max-height: 8rem;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
