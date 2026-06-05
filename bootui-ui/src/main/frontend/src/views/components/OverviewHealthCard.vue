<script setup>
import {apiFetch} from '../../api.js'
import {computed, ref, onMounted} from 'vue'
import {describeLoadError} from '../../utils/loadError.js'
import {useAutoRefresh} from '../../utils/useAutoRefresh.js'

const root = ref(null)
const error = ref(null)

async function fetchHealth() {
  error.value = null
  try {
    const res = await apiFetch('api/health')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    root.value = await res.json()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load health').message
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchHealth)

const statusTone = computed(() => {
  const s = root.value?.status
  if (!s) return 'secondary'
  if (s === 'UP') return 'success'
  if (s === 'DOWN') return 'danger'
  if (s === 'OUT_OF_SERVICE') return 'warning'
  return 'secondary'
})
</script>

<template>
  <div class="scanner-card card h-100">
    <div class="card-body d-flex flex-column">
      <div class="d-flex align-items-center gap-2 mb-3">
        <span class="scanner-icon scanner-icon--success"><i class="bi bi-heart-pulse"></i></span>
        <div class="flex-grow-1 min-w-0">
          <div class="fw-bold text-truncate">Health</div>
          <span v-if="root?.status" :class="['badge', `text-bg-${statusTone}`, 'scanner-status']">
            {{ root.status }}
          </span>
        </div>
        <div v-if="loading && !initialLoading" class="spinner-border spinner-border-sm text-secondary" role="status">
          <span class="visually-hidden">Loading...</span>
        </div>
      </div>

      <div class="scanner-body flex-grow-1">
        <template v-if="initialLoading">
          <div class="d-flex align-items-center gap-2 text-muted">
            <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
            <span>Loading…</span>
          </div>
        </template>
        <template v-else-if="error">
          <div class="text-danger small"><i class="bi bi-exclamation-triangle-fill me-1"></i>{{ error }}</div>
        </template>
        <template v-else-if="root">
          <div class="d-flex align-items-baseline gap-2">
            <span :class="['scanner-score', `scanner-score--${statusTone}`]">
              {{ root.status }}
            </span>
          </div>
          <div class="text-muted small mt-2">Overall application health status.</div>
        </template>
      </div>

      <div class="d-flex gap-2 mt-3">
        <router-link to="/health" class="btn btn-sm btn-outline-secondary ms-auto">
          Open panel<i class="bi bi-arrow-right-short"></i>
        </router-link>
      </div>
    </div>
  </div>
</template>
