<script setup>
import {computed, ref} from 'vue'
import {getJson} from '../../api.js'
import {impactLists, isImpact, nodeParts, routeTraffic} from '../../utils/changeImpact.js'
import {formatNumber} from '../../utils/format.js'
import {describeLoadError} from '../../utils/loadError.js'
import InsightText from './InsightText.vue'

// An agent or a link can open the panel on a symbol; nothing is read until a symbol is asked for.
const props = defineProps({initialSymbol: {type: String, default: ''}})

const symbol = ref(props.initialSymbol)
const impact = ref(null)
const error = ref(null)
const loading = ref(false)

async function check(value = symbol.value) {
  const asked = (value ?? '').trim()
  if (!asked) return
  symbol.value = asked
  loading.value = true
  error.value = null
  try {
    const result = await getJson(`api/runtime-insights/impact?symbol=${encodeURIComponent(asked)}`)
    impact.value = isImpact(result) ? result : null
  } catch (e) {
    impact.value = null
    error.value = describeLoadError(e, 'Unable to read the change impact')
  } finally {
    loading.value = false
  }
}

if (props.initialSymbol) {
  check(props.initialSymbol)
}

const lists = computed(() => impactLists(impact.value))
const node = computed(() => nodeParts(impact.value?.node))
</script>

<template>
  <section class="card insight-impact" aria-labelledby="insight-impact-title">
    <div class="card-body">
      <h2 id="insight-impact-title" class="h6 mb-1">Change impact</h2>
      <p class="small text-muted mb-2 insight-impact-intro">
        Name what you are changing, a bean, a class, a repository, a table, a cache, or a host, to see which routes this
        run exercised through it and which it did not.
      </p>
      <form class="d-flex flex-wrap gap-2 align-items-center" role="search" @submit.prevent="check()">
        <input
          v-model="symbol"
          type="search"
          class="form-control form-control-sm insight-impact-input"
          aria-label="Symbol to check, such as a bean, class, repository, table, cache, or host"
          placeholder="ProductRepository, sample_products…"
          autocomplete="off"
          spellcheck="false"
        />
        <button type="submit" class="btn btn-sm btn-primary" :disabled="loading || !symbol.trim()">Check impact</button>
      </form>

      <div aria-live="polite" :aria-busy="loading">
        <div v-if="error" class="alert alert-warning small mt-3 mb-0" role="alert">{{ error }}</div>
        <template v-else-if="impact">
          <template v-if="impact.status === 'RESOLVED'">
            <p class="mt-3 mb-1 insight-impact-node">
              <span v-if="node.kind" class="text-muted small">{{ node.kind }}&nbsp;</span>
              <code>{{ node.name }}</code>
            </p>
            <p class="small text-muted mb-0">
              Reached by {{ formatNumber(impact.structuralReach) }}
              {{ impact.structuralReach === 1 ? 'node' : 'nodes' }} within five steps. What ran is listed apart: a
              route's traffic does not prove that a request went through this code.
            </p>
            <div v-for="list in lists" :key="list.id" class="insight-impact-list" :data-list="list.id">
              <h3 class="h6 mb-1">
                {{ list.title }}
                <span class="text-muted fw-normal small">· {{ formatNumber(list.total) }}</span>
              </h3>
              <p v-if="list.rows.length === 0" class="small text-muted mb-0">{{ list.empty }}</p>
              <ul v-else class="list-unstyled mb-0 insight-impact-rows">
                <li v-for="route in list.rows" :key="route.route" class="insight-impact-row">
                  <div class="d-flex flex-wrap justify-content-between gap-2">
                    <code class="bootui-break-anywhere">{{ route.route }}</code>
                    <span v-if="list.id !== 'not-exercised'" class="small text-muted">{{ routeTraffic(route) }}</span>
                  </div>
                  <p v-if="route.check" class="small mb-0 mt-1"><InsightText :text="route.check" /></p>
                  <p v-if="route.shared.length" class="small mb-0 mt-1">
                    Shares <code>{{ route.shared.join(', ') }}</code>
                  </p>
                  <p v-if="route.reads.length || route.writes.length" class="small text-muted mb-0 mt-1">
                    <template v-if="route.writes.length">Writes {{ route.writes.join(', ') }}</template>
                    <template v-if="route.reads.length && route.writes.length"> · </template>
                    <template v-if="route.reads.length">Reads {{ route.reads.join(', ') }}</template>
                  </p>
                  <ul v-if="route.exemplarRequestIds.length" class="list-inline small mb-0 mt-1">
                    <li v-for="requestId in route.exemplarRequestIds" :key="requestId" class="list-inline-item">
                      <router-link :to="{path: '/activity', query: {request: requestId}}">
                        <code>{{ requestId }}</code>
                      </router-link>
                    </li>
                  </ul>
                </li>
              </ul>
              <p v-if="list.total > list.rows.length" class="small text-muted mb-0 mt-1">
                {{ formatNumber(list.total - list.rows.length) }} more not listed.
              </p>
            </div>
            <details v-if="impact.limitations.length" class="small text-muted mt-3">
              <summary>Limits · {{ impact.limitations.length }}</summary>
              <ul class="mb-0 mt-1">
                <li v-for="limitation in impact.limitations" :key="limitation">
                  <InsightText :text="limitation" />
                </li>
              </ul>
            </details>
          </template>
          <template v-else>
            <p class="mt-3 mb-2 insight-impact-reason"><InsightText :text="impact.reason ?? ''" /></p>
            <div v-if="impact.candidates.length" class="d-flex flex-wrap gap-1" aria-label="Candidates">
              <button
                v-for="candidate in impact.candidates"
                :key="candidate"
                type="button"
                class="btn btn-sm btn-outline-secondary insight-impact-candidate"
                @click="check(candidate.slice(candidate.indexOf(' ') + 1))"
              >
                <code>{{ candidate }}</code>
              </button>
            </div>
          </template>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.insight-impact-intro,
.insight-impact-reason,
.insight-impact-list {
  max-width: 80ch;
}

.insight-impact-input {
  max-width: 22rem;
}

.insight-impact-list {
  margin-top: 1.25rem;
}

.insight-impact-rows {
  border-top: 1px solid var(--bootui-border);
}

.insight-impact-row {
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--bootui-border);
}
</style>
