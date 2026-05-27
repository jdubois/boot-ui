<script setup>
import {computed, onMounted, ref} from 'vue'

const data = ref(null)
const filter = ref('')

async function load() {
  const res = await fetch('api/mappings')
  if (res.status === 204) return
  data.value = await res.json()
}

const flat = computed(() => {
  if (!data.value) return []
  const rows = []
  const contexts = data.value.contexts || {}
  for (const ctxName of Object.keys(contexts)) {
    const ctx = contexts[ctxName]
    const dispatchers = ctx.mappings?.dispatcherServlets || ctx.mappings?.dispatcherHandlers || {}
    for (const dispatcherName of Object.keys(dispatchers)) {
      const handlers = dispatchers[dispatcherName] || []
      for (const h of handlers) {
        const conds = h.details?.requestMappingConditions || {}
        const patterns = conds.patterns || []
        const methods = conds.methods || ['ANY']
        for (const pattern of (patterns.length ? patterns : ['(any)'])) {
          for (const method of (methods.length ? methods : ['ANY'])) {
            rows.push({method, pattern, handler: h.handler, predicate: h.predicate})
          }
        }
      }
    }
  }
  if (!filter.value) return rows
  const f = filter.value.toLowerCase()
  return rows.filter(r =>
    (r.pattern || '').toLowerCase().includes(f) ||
    (r.handler || '').toLowerCase().includes(f) ||
    (r.method || '').toLowerCase().includes(f))
})

const methodClass = m => ({
  GET: 'bg-success', POST: 'bg-primary', PUT: 'bg-warning text-dark',
  DELETE: 'bg-danger', PATCH: 'bg-info text-dark', ANY: 'bg-secondary'
}[m] || 'bg-secondary')

onMounted(load)
</script>

<template>
  <div>
    <h2><i class="bi bi-signpost-2 me-2"></i>HTTP mappings</h2>
    <input v-model="filter" class="form-control mb-3" placeholder="Filter…"/>
    <div class="table-responsive">
      <table class="table table-sm table-hover">
        <thead>
        <tr>
          <th style="width:90px">Method</th>
          <th>Pattern</th>
          <th>Handler</th>
        </tr>
        </thead>
        <tbody>
        <tr v-for="(r, i) in flat" :key="i">
          <td><span :class="methodClass(r.method)" class="badge">{{ r.method }}</span></td>
          <td><code>{{ r.pattern }}</code></td>
          <td><small>{{ r.handler }}</small></td>
        </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
