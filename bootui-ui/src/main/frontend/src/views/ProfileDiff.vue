<script setup>
import {getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const data = ref(null)
const loading = ref(true)
const error = ref(null)
const filter = ref('')
const lastFetched = ref(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await getJson('api/profile-diff')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load profile diff')
  } finally {
    loading.value = false
  }
}

const filteredSources = computed(() => {
  if (!data.value) return []
  const f = filter.value.toLowerCase()
  if (!f) return data.value.profileSources
  return data.value.profileSources
    .map((source) => ({
      ...source,
      properties: source.properties.filter(
        (p) => p.name.toLowerCase().includes(f) || (p.value != null && String(p.value).toLowerCase().includes(f))
      )
    }))
    .filter((source) => source.properties.length > 0)
})

onMounted(load)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-layers"
      title="Profile Diff"
      subtitle="View configuration properties that differ across active profiles."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      @refresh="load"
    />

    <PanelSkeleton v-if="loading && !data" />
    <div v-else-if="data">
      <div class="mb-3">
        <span class="me-2 text-muted small">Active profiles:</span>
        <span v-if="data.activeProfiles.length">
          <span v-for="p in data.activeProfiles" :key="p" class="badge bg-success me-1">{{ p }}</span>
        </span>
        <span v-else class="text-muted small">(none)</span>
      </div>

      <input v-model="filter" class="form-control mb-3" placeholder="Filter by property name or value…" />

      <div v-if="filteredSources.length === 0" class="alert alert-info">
        <i class="bi bi-info-circle me-1"></i>
        No profile-specific configuration sources found. Properties that belong to
        <code>application-{profile}.properties</code> or <code>application-{profile}.yml</code>
        files will appear here when a profile is active.
      </div>

      <div v-for="source in filteredSources" :key="source.sourceName" class="mb-4">
        <div class="d-flex align-items-center gap-2 mb-2">
          <span class="badge bg-success fs-6">{{ source.profile }}</span>
          <small class="text-muted font-monospace">{{ source.sourceName }}</small>
        </div>
        <div class="table-responsive">
          <table class="table table-sm table-hover mb-0">
            <thead class="table-light">
              <tr>
                <th style="width: 40%">Property</th>
                <th>Value</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="prop in source.properties" :key="prop.name">
                <td>
                  <code>{{ prop.name }}</code>
                </td>
                <td>
                  <span v-if="prop.masked" class="text-muted font-monospace">••••••••</span>
                  <span v-else class="font-monospace">{{ prop.value ?? '(null)' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>
