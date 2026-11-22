<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'

const report = ref(null)
const detail = ref(null)
const selected = ref(null)
const filter = ref('')
const storeFilter = ref('')
const error = ref(null)
const springDataPresent = ref(true)
const initialLoading = ref(true)
const detailLoading = ref(false)
const detailError = ref(null)
let detailRequestId = 0

async function load() {
  try {
    const res = await apiFetch('api/data/repositories')
    if (res.status === 404) {
      springDataPresent.value = false
      return
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    report.value = await res.json()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Spring Data repositories')
  } finally {
    initialLoading.value = false
  }
}

async function open(repo) {
  const requestId = ++detailRequestId
  selected.value = repo.beanName
  detail.value = null
  detailError.value = null
  detailLoading.value = true

  try {
    const key = encodeURIComponent(repo.beanName)
    const response = await getJson(`api/data/repositories/${key}`)
    if (requestId === detailRequestId) detail.value = response
  } catch (e) {
    if (requestId === detailRequestId) {
      detailError.value = formatLoadError(e, 'Unable to load repository details')
    }
  } finally {
    if (requestId === detailRequestId) detailLoading.value = false
  }
}

const stores = computed(() => {
  if (!report.value) return []
  const set = new Set(report.value.repositories.map((r) => r.storeModule))
  return Array.from(set).sort()
})

const filtered = computed(() => {
  if (!report.value) return []
  const f = filter.value.toLowerCase()
  return report.value.repositories.filter((r) => {
    if (storeFilter.value && r.storeModule !== storeFilter.value) return false
    if (!f) return true
    return (
      (r.repositoryInterface || '').toLowerCase().includes(f) ||
      (r.domainType || '').toLowerCase().includes(f) ||
      (r.beanName || '').toLowerCase().includes(f)
    )
  })
})

const shortName = (name) => {
  if (!name) return ''
  const i = name.lastIndexOf('.')
  return i < 0 ? name : name.substring(i + 1)
}

const duplicateNames = computed(() => {
  const seen = new Set()
  const duplicates = new Set()
  for (const repo of report.value?.repositories ?? []) {
    const name = shortName(repo.repositoryInterface)
    if (seen.has(name)) duplicates.add(name)
    seen.add(name)
  }
  return duplicates
})

const storeClass = (s) =>
  ({
    JPA: 'bg-primary',
    JDBC: 'bg-info text-dark',
    MONGO: 'bg-success',
    REDIS: 'bg-danger',
    R2DBC: 'bg-warning text-dark',
    CASSANDRA: 'bg-secondary',
    NEO4J: 'bg-dark',
    ELASTICSEARCH: 'bg-warning text-dark',
    COUCHBASE: 'bg-info text-dark',
    COMMONS: 'bg-light text-dark border',
    GENERIC: 'bg-light text-dark border'
  })[s] || 'bg-secondary'

const originClass = (o) =>
  ({
    CRUD: 'bg-light text-dark border',
    DERIVED: 'bg-success',
    QUERY: 'bg-primary',
    ANNOTATED: 'bg-primary',
    FRAGMENT: 'bg-warning text-dark',
    DEFAULT: 'bg-info text-dark'
  })[o] || 'bg-secondary'

onMounted(load)
</script>

<template>
  <div>
    <PanelHeader icon="bi-database" title="Spring Data repositories" :error="error" />

    <PanelSkeleton v-if="initialLoading" />

    <UnavailableState v-else-if="!springDataPresent" variant="info">
      Spring Data is not on the classpath of this application. Add a Spring Data starter (e.g.
      <code>spring-boot-starter-data-jpa</code>) to see repositories here.
    </UnavailableState>

    <UnavailableState v-else-if="report && report.total === 0">
      Spring Data is on the classpath, but no repository beans were detected in the application context.
    </UnavailableState>

    <template v-else-if="report">
      <div class="row g-2 mb-3">
        <div class="col-md-6">
          <input
            v-model="filter"
            aria-label="Filter repositories"
            class="form-control"
            placeholder="Filter by interface, entity, or bean name…"
          />
        </div>
        <div class="col-md-3">
          <select v-model="storeFilter" aria-label="Filter repositories by store" class="form-select">
            <option value="">All stores</option>
            <option v-for="s in stores" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="col-md-3 text-end small text-muted align-self-center">
          {{ filtered.length }} / {{ report.total }} repositories
        </div>
      </div>

      <div class="row">
        <div class="col-md-5">
          <div class="list-group">
            <button
              v-for="r in filtered"
              :key="r.beanName"
              :class="{active: selected === r.beanName}"
              :aria-current="selected === r.beanName ? 'true' : undefined"
              class="list-group-item list-group-item-action"
              type="button"
              @click="open(r)"
            >
              <div class="d-flex justify-content-between align-items-start gap-2">
                <div class="repository-summary">
                  <div>
                    <strong class="bootui-break-anywhere">{{ shortName(r.repositoryInterface) }}</strong>
                  </div>
                  <div v-if="duplicateNames.has(shortName(r.repositoryInterface))" class="small">
                    <code class="bootui-break-anywhere">{{ r.beanName }}</code>
                  </div>
                  <div class="small text-muted">
                    {{ shortName(r.domainType) }}
                    <span v-if="r.idType"> · id: {{ shortName(r.idType) }}</span>
                  </div>
                </div>
                <span :class="storeClass(r.storeModule)" class="badge">{{ r.storeModule }}</span>
              </div>
              <div class="small mt-1">
                <span class="me-2"><i class="bi bi-search me-1"></i>{{ r.queryMethodCount }} queries</span>
                <span v-if="r.fragmentCount > 0">
                  <i class="bi bi-puzzle me-1"></i>{{ r.fragmentCount }} fragments
                </span>
              </div>
            </button>
          </div>
        </div>

        <div class="col-md-7">
          <div class="text-muted small" role="status">{{ detailLoading ? 'Loading repository details…' : '' }}</div>
          <UnavailableState v-if="detailError" icon="bi-exclamation-triangle" variant="danger">
            {{ detailError }} Select the repository again to retry.
          </UnavailableState>
          <div v-else-if="detail" class="card">
            <div class="card-body">
              <h3 class="h5 card-title mb-1">{{ shortName(detail.repositoryInterface) }}</h3>
              <div class="text-muted small mb-3">
                <code class="bootui-break-anywhere">{{ detail.repositoryInterface }}</code>
              </div>
              <dl class="row mb-3 small">
                <dt class="col-sm-3">Store module</dt>
                <dd class="col-sm-9">
                  <span :class="storeClass(detail.storeModule)" class="badge">{{ detail.storeModule }}</span>
                </dd>
                <dt class="col-sm-3">Domain type</dt>
                <dd class="col-sm-9">
                  <code class="bootui-break-anywhere">{{ detail.domainType }}</code>
                </dd>
                <dt class="col-sm-3">ID type</dt>
                <dd class="col-sm-9">
                  <code class="bootui-break-anywhere">{{ detail.idType }}</code>
                </dd>
                <dt class="col-sm-3">Bean name</dt>
                <dd class="col-sm-9">
                  <code class="bootui-break-anywhere">{{ detail.beanName }}</code>
                </dd>
                <template v-if="detail.customImplementation">
                  <dt class="col-sm-3">Base class</dt>
                  <dd class="col-sm-9">
                    <code class="bootui-break-anywhere">{{ detail.customImplementation }}</code>
                  </dd>
                </template>
              </dl>

              <h4 class="fs-6 mb-2">
                Methods <span class="badge bg-secondary">{{ detail.methods.length }}</span>
              </h4>
              <div class="table-responsive bootui-table-scroll">
                <table class="table table-sm table-hover bootui-data-table data-methods-table">
                  <thead>
                    <tr>
                      <th style="width: 110px">Origin</th>
                      <th>Signature</th>
                      <th>Query</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="m in detail.methods" :key="m.signature">
                      <td>
                        <span :class="originClass(m.origin)" class="badge">{{ m.origin }}</span>
                      </td>
                      <td>
                        <code class="bootui-break-anywhere">{{ m.signature }}</code>
                      </td>
                      <td>
                        <code v-if="m.query" class="small bootui-break-anywhere">{{ m.query }}</code>
                        <span v-else-if="m.namedQuery" class="small text-muted bootui-break-anywhere"
                          >named: {{ m.namedQuery }}</span
                        >
                        <span v-else class="text-muted small">—</span>
                        <span v-if="m.nativeQuery" class="badge bg-warning text-dark ms-1">native</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
          <div v-else-if="!detailLoading" class="text-muted small">Select a repository to see its methods.</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.repository-summary {
  min-width: 0;
}

.data-methods-table {
  --bootui-table-min-width: 42rem;
}
</style>
