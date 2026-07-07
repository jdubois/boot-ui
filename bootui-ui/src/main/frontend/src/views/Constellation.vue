<script setup>
import {getJson} from '../api.js'
import {computed, ref} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/constellation')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Constellation view')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchReport)

const peers = computed(() => report.value?.peers ?? [])
const reachableCount = computed(() => peers.value.filter((peer) => peer.reachable).length)

const platformIcon = (platform) =>
  ({
    'spring-boot': 'bi-flower1',
    quarkus: 'bi-lightning-charge-fill'
  })[String(platform || '').toLowerCase()] || 'bi-question-circle'

const platformLabel = (platform) => {
  if (!platform) return 'Unknown'
  if (platform.toLowerCase() === 'spring-boot') return 'Spring Boot'
  if (platform.toLowerCase() === 'quarkus') return 'Quarkus'
  return platform
}

function peerHostLabel(url) {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-share-fill"
      title="Constellation"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="initialLoading && !report" />
    <div v-else-if="report && !report.enabled" class="alert alert-info">
      <strong>Constellation is not configured.</strong>
      Set <code>bootui.constellation.enabled=true</code> and list your local peer services with
      <code>bootui.constellation.peers</code> (a comma-separated list of loopback URLs, e.g.
      <code>bootui.constellation.peers=http://localhost:8081,http://localhost:8082</code>) to see this mesh of
      locally-running BootUI instances.
    </div>
    <div v-else-if="report && peers.length === 0" class="alert alert-secondary">No peers configured.</div>
    <template v-else-if="report">
      <p class="text-muted small mb-3">
        {{ reachableCount }} / {{ peers.length }} peers reachable. Every peer is polled over loopback-only HTTP from
        this instance - no data ever leaves your machine.
      </p>

      <div class="row g-3">
        <div v-for="peer in peers" :key="peer.url" class="col-md-6 col-xl-4">
          <div class="card h-100 shadow-sm" :class="peer.reachable ? 'border-success-subtle' : 'border-danger-subtle'">
            <div class="card-body">
              <div class="d-flex align-items-center justify-content-between mb-2">
                <span class="badge" :class="peer.reachable ? 'text-bg-success' : 'text-bg-danger'">
                  <i :class="['bi', peer.reachable ? 'bi-check-circle' : 'bi-x-circle']"></i>
                  {{ peer.reachable ? 'Reachable' : 'Unreachable' }}
                </span>
                <span class="text-muted small">
                  <i :class="['bi', platformIcon(peer.platform)]"></i>
                  {{ platformLabel(peer.platform) }}
                </span>
              </div>
              <h6 class="card-title mb-1">{{ peer.applicationName || peerHostLabel(peer.url) }}</h6>
              <div class="text-muted small mb-2">
                <code>{{ peer.url }}</code>
              </div>
              <template v-if="peer.reachable">
                <div class="small">
                  <div v-if="peer.frameworkVersion"><strong>Framework:</strong> {{ peer.frameworkVersion }}</div>
                  <div v-if="peer.javaVersion"><strong>Java:</strong> {{ peer.javaVersion }}</div>
                  <div v-if="peer.activeProfiles && peer.activeProfiles.length">
                    <strong>Profiles:</strong> {{ peer.activeProfiles.join(', ') }}
                  </div>
                </div>
              </template>
              <div v-else class="small text-danger">{{ peer.errorMessage || 'Peer did not respond.' }}</div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
