<script setup>
import {computed, onMounted, ref} from 'vue'

const report = ref(null)
const detail = ref(null)
const selected = ref(null)
const filter = ref('')
const storeFilter = ref('')
const error = ref(null)
const springDataPresent = ref(true)

async function load() {
  try {
    const res = await fetch('api/data/repositories')
    if (res.status === 404) {
      springDataPresent.value = false
      return
    }
    if (!res.ok) throw new Error('HTTP ' + res.status)
    report.value = await res.json()
  } catch (e) {
    error.value = e.message
  }
}

async function open(repo) {
  selected.value = repo.repositoryInterface
  detail.value = null
  const key = encodeURIComponent(repo.repositoryInterface || repo.beanName)
  const res = await fetch(`api/data/repositories/${key}`)
  if (res.ok) {
    detail.value = await res.json()
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
    <h2><i class="bi bi-database me-2"></i>Spring Data repositories</h2>

    <div v-if="!springDataPresent" class="alert alert-info">
      Spring Data is not on the classpath of this application. Add a Spring Data starter (e.g.
      <code>spring-boot-starter-data-jpa</code>) to see repositories here.
    </div>

    <div v-else-if="error" class="alert alert-danger">{{ error }}</div>

    <div v-else-if="report && report.total === 0" class="alert alert-secondary">
      Spring Data is on the classpath, but no repository beans were detected in the application context.
    </div>

    <template v-else-if="report">
      <div class="row g-2 mb-3">
        <div class="col-md-6">
          <input v-model="filter" class="form-control" placeholder="Filter by interface, entity, or bean name…" />
        </div>
        <div class="col-md-3">
          <select v-model="storeFilter" class="form-select">
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
              :class="{active: selected === r.repositoryInterface}"
              class="list-group-item list-group-item-action"
              type="button"
              @click="open(r)"
            >
              <div class="d-flex justify-content-between align-items-start">
                <div>
                  <div>
                    <strong>{{ shortName(r.repositoryInterface) }}</strong>
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
          <div v-if="!detail" class="text-muted small">Select a repository to see its methods.</div>
          <div v-else class="card">
            <div class="card-body">
              <h5 class="card-title mb-1">{{ shortName(detail.repositoryInterface) }}</h5>
              <div class="text-muted small mb-3">
                <code>{{ detail.repositoryInterface }}</code>
              </div>
              <dl class="row mb-3 small">
                <dt class="col-sm-3">Store module</dt>
                <dd class="col-sm-9">
                  <span :class="storeClass(detail.storeModule)" class="badge">{{ detail.storeModule }}</span>
                </dd>
                <dt class="col-sm-3">Domain type</dt>
                <dd class="col-sm-9">
                  <code>{{ detail.domainType }}</code>
                </dd>
                <dt class="col-sm-3">ID type</dt>
                <dd class="col-sm-9">
                  <code>{{ detail.idType }}</code>
                </dd>
                <dt class="col-sm-3">Bean name</dt>
                <dd class="col-sm-9">
                  <code>{{ detail.beanName }}</code>
                </dd>
                <template v-if="detail.customImplementation">
                  <dt class="col-sm-3">Base class</dt>
                  <dd class="col-sm-9">
                    <code>{{ detail.customImplementation }}</code>
                  </dd>
                </template>
              </dl>

              <h6 class="mb-2">
                Methods <span class="badge bg-secondary">{{ detail.methods.length }}</span>
              </h6>
              <table class="table table-sm table-hover">
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
                      <code>{{ m.signature }}</code>
                    </td>
                    <td>
                      <code v-if="m.query" class="small">{{ m.query }}</code>
                      <span v-else-if="m.namedQuery" class="small text-muted">named: {{ m.namedQuery }}</span>
                      <span v-else class="text-muted small">—</span>
                      <span v-if="m.nativeQuery" class="badge bg-warning text-dark ms-1">native</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
