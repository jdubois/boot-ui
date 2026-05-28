<script setup>
import {computed, onMounted, ref} from 'vue'
import {useVisibleItems} from '../utils/useVisibleItems.js'
import ProgressiveListFooter from './components/ProgressiveListFooter.vue'

const data = ref(null)
const filter = ref('')
const classification = ref('')

async function load() {
  const res = await fetch('api/beans')
  data.value = await res.json()
}

const filtered = computed(() => {
  if (!data.value) return []
  return data.value.beans.filter((b) => {
    if (classification.value && b.classification !== classification.value) return false
    if (filter.value) {
      const f = filter.value.toLowerCase()
      return b.name.toLowerCase().includes(f) || (b.type && b.type.toLowerCase().includes(f))
    }
    return true
  })
})

const {chunkSize, visibleItems: visibleBeans, shownCount, hiddenCount, showMore, showAll} = useVisibleItems(filtered)

onMounted(load)
</script>

<template>
  <div>
    <h2><i class="bi bi-diagram-3 me-2"></i>Beans</h2>
    <p class="text-muted">{{ data ? data.total : 0 }} beans · {{ filtered.length }} matched</p>

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
        </tbody>
      </table>
    </div>
    <ProgressiveListFooter
      :chunk-size="chunkSize"
      :hidden="hiddenCount"
      :shown="shownCount"
      :total="filtered.length"
      item-label="beans"
      @show-all="showAll"
      @show-more="showMore"
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
