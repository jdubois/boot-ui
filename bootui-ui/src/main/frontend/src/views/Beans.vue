<script setup>
import {onMounted, ref, watch} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'

const filter = ref('')
const classification = ref('')

const {
  data,
  error,
  items: visibleBeans,
  load,
  loadMore,
  loading,
  loadingMore,
  matchedCount,
  pageSize,
  scheduleReload,
  shownCount,
  totalCount
} = useServerPagedList('api/beans', 'beans', () => {
  return {
    q: filter.value.trim(),
    classification: classification.value
  }
})

onMounted(load)
watch([filter, classification], scheduleReload)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-diagram-3"
      title="Beans"
      :subtitle="`${totalCount} beans · ${matchedCount} matched`"
      :error="error ? `Could not load beans: ${error}` : null"
    />

    <div class="row g-2 mb-3">
      <div class="col-md-8">
        <input v-model="filter" class="form-control" placeholder="Filter by name or type…" />
      </div>
      <div class="col-md-4">
        <select v-model="classification" class="form-select">
          <option value="">All classifications</option>
          <option value="APPLICATION">Application</option>
          <option value="FRAMEWORK">Spring framework</option>
          <option value="BOOTUI">BootUI</option>
          <option value="PLATFORM">Platform</option>
          <option value="OTHER">Other</option>
        </select>
      </div>
    </div>

    <div class="table-responsive">
      <table class="table table-sm table-hover beans-table">
        <colgroup>
          <col class="beans-table-name" />
          <col class="beans-table-type" />
          <col class="beans-table-scope" />
          <col class="beans-table-classification" />
          <col class="beans-table-dependencies" />
        </colgroup>
        <thead>
          <tr>
            <th>Name</th>
            <th>Type</th>
            <th>Scope</th>
            <th>Class.</th>
            <th>Dependencies</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in visibleBeans" :key="b.name">
            <td>
              <code :title="b.name" class="text-truncate d-block">{{ b.name }}</code>
            </td>
            <td>
              <small :title="b.type" class="text-truncate d-block">{{ b.type }}</small>
            </td>
            <td>{{ b.scope }}</td>
            <td>
              <span class="badge bg-light text-dark">{{ b.classification }}</span>
            </td>
            <td>
              <small :title="b.dependencies.join(', ')" class="text-muted text-truncate d-block">{{
                b.dependencies.join(', ')
              }}</small>
            </td>
          </tr>
          <tr v-if="!loading && matchedCount === 0">
            <td class="text-center text-muted py-4" colspan="5">No beans match your filters.</td>
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
      item-label="beans"
      @load-more="loadMore"
    />
  </div>
</template>

<style scoped>
.beans-table {
  table-layout: fixed;
}

.beans-table-name {
  width: 22%;
}

.beans-table-type {
  width: 34%;
}

.beans-table-scope {
  width: 10%;
}

.beans-table-classification {
  width: 14%;
}

.beans-table-dependencies {
  width: 20%;
}
</style>
