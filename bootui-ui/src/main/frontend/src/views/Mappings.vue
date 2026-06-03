<script setup>
import {onMounted, ref, watch} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'

const filter = ref('')

const {
  error,
  items: visibleMappings,
  load,
  loadMore,
  loading,
  loadingMore,
  matchedCount,
  pageSize,
  scheduleReload,
  shownCount,
  totalCount
} = useServerPagedList(
  'api/mappings/flat',
  'mappings',
  () => {
    return {q: filter.value.trim()}
  },
  {errorContext: 'Could not load mappings'}
)

const methodClass = (m) =>
  ({
    GET: 'bg-success',
    POST: 'bg-primary',
    PUT: 'bg-warning text-dark',
    DELETE: 'bg-danger',
    PATCH: 'bg-info text-dark',
    ANY: 'bg-secondary'
  })[m] || 'bg-secondary'

onMounted(load)
watch(filter, scheduleReload)
</script>

<template>
  <div>
    <PanelHeader icon="bi-signpost-2" title="HTTP mappings" :error="error" />
    <input v-model="filter" class="form-control mb-3" placeholder="Filter…" />
    <p class="small text-muted">{{ matchedCount }} of {{ totalCount }} mappings matched</p>
    <div class="table-responsive">
      <table class="table table-sm table-hover">
        <thead>
          <tr>
            <th style="width: 90px">Method</th>
            <th>Pattern</th>
            <th>Handler</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in visibleMappings" :key="`${r.method}:${r.pattern}:${r.handler}:${i}`">
            <td>
              <span :class="methodClass(r.method)" class="badge">{{ r.method }}</span>
            </td>
            <td>
              <code>{{ r.pattern }}</code>
            </td>
            <td>
              <small>{{ r.handler }}</small>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <ServerListFooter
      v-if="!loading"
      :loading="loadingMore"
      :matched="matchedCount"
      :page-size="pageSize"
      :shown="shownCount"
      :total="totalCount"
      item-label="mappings"
      @load-more="loadMore"
    />
  </div>
</template>
