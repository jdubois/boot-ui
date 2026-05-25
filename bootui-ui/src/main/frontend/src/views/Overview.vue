<script setup>
import { ref, onMounted } from 'vue'

const data = ref(null)
const loading = ref(true)
const error = ref(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await fetch('api/overview')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h2><i class="bi bi-speedometer2 me-2"></i>Overview</h2>
      <button class="btn btn-sm btn-outline-secondary" @click="load">
        <i class="bi bi-arrow-clockwise"></i> Refresh
      </button>
    </div>

    <div v-if="loading" class="text-muted">Loading…</div>
    <div v-else-if="error" class="alert alert-danger">{{ error }}</div>
    <div v-else-if="data" class="row g-3">
      <div class="col-md-6">
        <div class="card">
          <div class="card-header">Application</div>
          <ul class="list-group list-group-flush">
            <li class="list-group-item"><strong>Name</strong>: {{ data.applicationName }}</li>
            <li class="list-group-item"><strong>Active profiles</strong>:
              <span v-if="data.activeProfiles.length">
                <span v-for="p in data.activeProfiles" :key="p" class="badge bg-success me-1">{{ p }}</span>
              </span>
              <span v-else class="text-muted">(none)</span>
            </li>
            <li class="list-group-item"><strong>Default profiles</strong>:
              <span v-for="p in data.defaultProfiles" :key="p" class="badge bg-secondary me-1">{{ p }}</span>
            </li>
            <li class="list-group-item"><strong>Server port</strong>: {{ data.serverPort ?? '—' }}</li>
            <li class="list-group-item"><strong>Management port</strong>: {{ data.managementPort ?? '(same)' }}</li>
            <li class="list-group-item"><strong>Context path</strong>: {{ data.contextPath || '/' }}</li>
            <li class="list-group-item"><strong>Web type</strong>: {{ data.webApplicationType }}</li>
          </ul>
        </div>
      </div>

      <div class="col-md-6">
        <div class="card">
          <div class="card-header">Runtime</div>
          <ul class="list-group list-group-flush">
            <li class="list-group-item"><strong>BootUI</strong>: v{{ data.bootUiVersion }}</li>
            <li class="list-group-item"><strong>Spring Boot</strong>: {{ data.springBootVersion }}</li>
            <li class="list-group-item"><strong>Java</strong>: {{ data.javaVersion }} ({{ data.javaVendor }})</li>
          </ul>
        </div>

        <div v-if="data.openApiUrl" class="card mt-3">
          <div class="card-header"><i class="bi bi-file-earmark-code me-1"></i>API Docs</div>
          <div class="card-body">
            <a :href="data.openApiUrl" target="_blank" rel="noopener" class="btn btn-sm btn-outline-primary">
              <i class="bi bi-box-arrow-up-right me-1"></i>Open Swagger UI
            </a>
          </div>
        </div>

        <div class="card mt-3">
          <div class="card-header">Activation</div>
          <div class="card-body">
            <p class="mb-2">
              <span class="badge" :class="data.activation.enabled ? 'bg-success' : 'bg-secondary'">
                {{ data.activation.enabled ? 'Enabled' : 'Disabled' }}
              </span>
              <span class="ms-2">{{ data.activation.reason }}</span>
            </p>
            <p class="mb-0 small text-muted" v-if="data.activation.localhostOnly">
              <i class="bi bi-shield-check"></i> Loopback-only access enforced
            </p>
            <div v-if="data.activation.warnings.length" class="alert alert-warning small mt-2">
              <div v-for="w in data.activation.warnings" :key="w">{{ w }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
