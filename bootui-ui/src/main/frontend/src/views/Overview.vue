<script setup>
import {computed, onMounted, ref} from 'vue'

const data = ref(null)
const loading = ref(true)
const error = ref(null)

const activeProfiles = computed(() => data.value?.activeProfiles ?? [])
const defaultProfiles = computed(() => data.value?.defaultProfiles ?? [])
const warningCount = computed(() => data.value?.activation?.warnings?.length ?? 0)
const githubProjectUrl = 'https://github.com/jdubois/boot-ui'
const stats = computed(() => {
  if (!data.value) return []
  return [
    {
      label: 'Application',
      value: data.value.applicationName,
      detail: data.value.webApplicationType,
      icon: 'bi-window-sidebar',
      tone: 'success'
    },
    {
      label: 'Runtime',
      value: `Java ${data.value.javaVersion}`,
      detail: data.value.javaVendor,
      icon: 'bi-cpu',
      tone: 'primary'
    },
    {
      label: 'Spring Boot',
      value: data.value.springBootVersion,
      detail: `BootUI ${data.value.bootUiVersion}`,
      icon: 'bi-leaf',
      tone: 'info'
    },
    {
      label: 'Access',
      value: data.value.activation.localhostOnly ? 'Loopback only' : 'Relaxed',
      detail: data.value.activation.reason,
      icon: data.value.activation.localhostOnly ? 'bi-shield-check' : 'bi-shield-exclamation',
      tone: data.value.activation.localhostOnly ? 'success' : 'warning'
    }
  ]
})

const quickLinks = [
  {
    to: '/beans',
    title: 'Trace bean wiring',
    detail: 'Find controllers, services, repositories, and dependencies.',
    icon: 'bi-diagram-3'
  },
  {
    to: '/config',
    title: 'Audit configuration',
    detail: 'Inspect effective values, sources, masking, and overrides.',
    icon: 'bi-sliders'
  },
  {
    to: '/mappings',
    title: 'Map HTTP routes',
    detail: 'Understand handlers and request mappings in one place.',
    icon: 'bi-signpost-2'
  },
  {
    to: '/health',
    title: 'Check app health',
    detail: 'Drill into health components without leaving the app.',
    icon: 'bi-heart-pulse'
  }
]

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

function portText(port, fallback) {
  return port ?? fallback
}

onMounted(load)
</script>

<template>
  <div>
    <div class="overview-hero mb-4">
      <div class="hero-copy">
        <span class="hero-kicker">
          <i class="bi bi-stars me-1"></i>
          Runtime command center
        </span>
        <h2>Overview</h2>
        <p class="hero-lead">Understand your Spring Boot app in minutes.</p>
        <p>
          BootUI turns the local runtime into a guided map of profiles, ports, safety status, Actuator-backed
          diagnostics, and the panels that explain how the app is wired.
        </p>
      </div>
      <div class="hero-actions">
        <a class="btn btn-outline-light" href="/">
          <i class="bi bi-house-door me-1"></i>
          Back to homepage
        </a>
        <a :href="githubProjectUrl" class="btn btn-outline-light" rel="noopener noreferrer" target="_blank">
          <i class="bi bi-github me-1"></i>
          BootUI GitHub project
        </a>
        <button :disabled="loading" class="btn btn-light hero-refresh" @click="load">
          <i :class="{spin: loading}" class="bi bi-arrow-clockwise me-1"></i>
          Refresh snapshot
        </button>
      </div>
    </div>

    <div v-if="loading" class="overview-skeleton">
      <div v-for="n in 4" :key="n" class="skeleton-card"></div>
    </div>
    <div v-else-if="error" class="alert alert-danger">{{ error }}</div>
    <template v-else-if="data">
      <div class="row g-3 mb-4">
        <div v-for="(stat, index) in stats" :key="stat.label" class="col-xl-3 col-md-6">
          <div :style="{animationDelay: `${index * 70}ms`}" class="metric-card h-100">
            <span :class="['metric-icon', `metric-${stat.tone}`]">
              <i :class="['bi', stat.icon]"></i>
            </span>
            <div>
              <div class="metric-label">{{ stat.label }}</div>
              <div class="metric-value">{{ stat.value }}</div>
              <div class="metric-detail">{{ stat.detail }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="row g-4">
        <div class="col-lg-7">
          <div class="card h-100 app-map-card">
            <div class="card-body p-4">
              <div class="d-flex justify-content-between gap-3 flex-wrap mb-4">
                <div>
                  <div class="text-uppercase text-muted fw-bold small">Application snapshot</div>
                  <h3 class="h4 fw-bold mb-1">{{ data.applicationName }}</h3>
                  <div class="text-muted">{{ data.webApplicationType }} · context {{ data.contextPath || '/' }}</div>
                </div>
                <span :class="{disabled: !data.activation.enabled}" class="activation-badge">
                  <i :class="['bi', data.activation.enabled ? 'bi-lightning-charge-fill' : 'bi-power']"></i>
                  {{ data.activation.enabled ? 'Enabled' : 'Disabled' }}
                </span>
              </div>

              <div class="runtime-grid">
                <div>
                  <span>Server port</span>
                  <strong>{{ portText(data.serverPort, '—') }}</strong>
                </div>
                <div>
                  <span>Management port</span>
                  <strong>{{ portText(data.managementPort, '(same)') }}</strong>
                </div>
                <div>
                  <span>Spring Boot</span>
                  <strong>{{ data.springBootVersion }}</strong>
                </div>
                <div>
                  <span>BootUI</span>
                  <strong>v{{ data.bootUiVersion }}</strong>
                </div>
              </div>

              <div class="profile-panel mt-4">
                <div>
                  <div class="text-uppercase text-muted fw-bold small">Active profiles</div>
                  <div v-if="activeProfiles.length" class="d-flex flex-wrap gap-2 mt-2">
                    <span v-for="p in activeProfiles" :key="p" class="profile-pill">{{ p }}</span>
                  </div>
                  <span v-else class="text-muted small">No active profiles</span>
                </div>
                <div>
                  <div class="text-uppercase text-muted fw-bold small">Default profiles</div>
                  <div v-if="defaultProfiles.length" class="d-flex flex-wrap gap-2 mt-2">
                    <span v-for="p in defaultProfiles" :key="p" class="profile-pill muted">{{ p }}</span>
                  </div>
                  <span v-else class="text-muted small">None reported</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="col-lg-5">
          <div class="card h-100">
            <div class="card-body p-4">
              <div class="d-flex align-items-center gap-3 mb-3">
                <span class="timeline-icon"><i class="bi bi-shield-lock"></i></span>
                <div>
                  <h3 class="h5 fw-bold mb-0">Safety posture</h3>
                  <div class="text-muted small">{{ data.activation.reason }}</div>
                </div>
              </div>

              <div class="safety-timeline">
                <div class="timeline-item complete">
                  <span></span>
                  <div>
                    <strong>Development activation</strong>
                    <p>
                      {{
                        data.activation.enabled
                          ? 'BootUI is available for this runtime.'
                          : 'BootUI reports disabled state for this runtime.'
                      }}
                    </p>
                  </div>
                </div>
                <div :class="{complete: data.activation.localhostOnly}" class="timeline-item">
                  <span></span>
                  <div>
                    <strong>Loopback enforcement</strong>
                    <p>
                      {{
                        data.activation.localhostOnly
                          ? 'Non-local requests are rejected by default.'
                          : 'Non-local access is explicitly allowed.'
                      }}
                    </p>
                  </div>
                </div>
                <div :class="{warning: warningCount}" class="timeline-item">
                  <span></span>
                  <div>
                    <strong>Warnings</strong>
                    <p>
                      {{
                        warningCount ? `${warningCount} warning(s) need review.` : 'No activation warnings reported.'
                      }}
                    </p>
                  </div>
                </div>
              </div>

              <div v-if="data.activation.warnings.length" class="alert alert-warning small mt-3 mb-0">
                <div v-for="w in data.activation.warnings" :key="w">{{ w }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="row g-3 mt-1">
        <div v-for="link in quickLinks" :key="link.to" class="col-xl-3 col-md-6">
          <router-link :to="link.to" class="quick-link-card text-decoration-none">
            <span><i :class="['bi', link.icon]"></i></span>
            <div>
              <strong>{{ link.title }}</strong>
              <p>{{ link.detail }}</p>
            </div>
            <i class="bi bi-arrow-right-short ms-auto"></i>
          </router-link>
        </div>
      </div>

      <div v-if="data.openApiUrl" class="openapi-card mt-4">
        <div>
          <strong><i class="bi bi-file-earmark-code me-1"></i>OpenAPI detected</strong>
          <span>Jump from BootUI diagnostics to Swagger UI.</span>
        </div>
        <a :href="data.openApiUrl" class="btn btn-outline-primary" rel="noopener" target="_blank">
          Open Swagger UI
          <i class="bi bi-box-arrow-up-right ms-1"></i>
        </a>
      </div>
    </template>
  </div>
</template>

<style scoped>
.overview-hero {
  align-items: flex-start;
  background:
    radial-gradient(circle at top right, rgba(255, 255, 255, 0.38), transparent 18rem),
    linear-gradient(135deg, #16794c, #0d6efd);
  border-radius: 1.4rem;
  box-shadow: 0 1.5rem 3.5rem rgba(13, 110, 253, 0.22);
  color: #fff;
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  overflow: hidden;
  padding: clamp(1.4rem, 3vw, 2.4rem);
  position: relative;
}

.overview-hero::after {
  animation: sweep 5s ease-in-out infinite;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.18), transparent);
  content: '';
  height: 150%;
  position: absolute;
  right: 12%;
  top: -25%;
  transform: rotate(18deg);
  width: 7rem;
}

.hero-copy {
  max-width: 48rem;
  position: relative;
  z-index: 1;
}

.hero-kicker {
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: 999px;
  display: inline-flex;
  font-size: 0.78rem;
  font-weight: 800;
  letter-spacing: 0.07em;
  margin-bottom: 1rem;
  padding: 0.4rem 0.7rem;
  text-transform: uppercase;
}

.overview-hero h2 {
  font-size: clamp(2rem, 4vw, 3.5rem);
  font-weight: 900;
  letter-spacing: -0.04em;
  line-height: 0.95;
  margin-bottom: 1rem;
}

.overview-hero p {
  color: rgba(255, 255, 255, 0.82);
  font-size: 1.05rem;
  margin-bottom: 0;
}

.overview-hero .hero-lead {
  font-size: 1.35rem;
  font-weight: 700;
  margin-bottom: 0.75rem;
}

.hero-actions {
  display: flex;
  flex-shrink: 0;
  gap: 0.75rem;
  position: relative;
  z-index: 1;
}

.hero-actions .btn {
  font-weight: 700;
}

.hero-refresh {
  box-shadow: 0 0.8rem 1.8rem rgba(0, 0, 0, 0.14);
}

.spin {
  animation: spin 900ms linear infinite;
}

.overview-skeleton {
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.skeleton-card {
  animation: pulse 1.1s ease-in-out infinite;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.72), rgba(226, 232, 240, 0.8), rgba(255, 255, 255, 0.72));
  background-size: 200% 100%;
  border-radius: 1.1rem;
  min-height: 8rem;
}

.metric-card {
  animation: fade-up 360ms ease both;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 1.15rem;
  box-shadow: 0 1rem 2.4rem rgba(15, 23, 42, 0.07);
  display: flex;
  gap: 0.85rem;
  padding: 1rem;
  transition:
    transform 180ms ease,
    box-shadow 180ms ease;
}

.metric-card > div {
  min-width: 0;
}

.metric-card:hover {
  box-shadow: 0 1.25rem 2.8rem rgba(15, 23, 42, 0.12);
  transform: translateY(-3px);
}

.metric-icon,
.timeline-icon {
  align-items: center;
  border-radius: 1rem;
  display: inline-flex;
  flex-shrink: 0;
  font-size: 1.15rem;
  height: 2.7rem;
  justify-content: center;
  width: 2.7rem;
}

.metric-success {
  background: rgba(25, 135, 84, 0.12);
  color: #198754;
}

.metric-primary {
  background: rgba(13, 110, 253, 0.12);
  color: #0d6efd;
}

.metric-info {
  background: rgba(13, 202, 240, 0.16);
  color: #087990;
}

.metric-warning {
  background: rgba(255, 193, 7, 0.18);
  color: #997404;
}

.metric-label {
  color: #64748b;
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.metric-value {
  font-size: 1.12rem;
  font-weight: 850;
  overflow-wrap: anywhere;
}

.metric-detail {
  color: #64748b;
  font-size: 0.82rem;
  overflow-wrap: anywhere;
}

.app-map-card {
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(255, 255, 255, 0.74)),
    radial-gradient(circle at top right, rgba(25, 135, 84, 0.12), transparent 18rem);
}

.activation-badge {
  align-items: center;
  background: rgba(25, 135, 84, 0.12);
  border-radius: 999px;
  color: #146c43;
  display: inline-flex;
  font-weight: 800;
  gap: 0.4rem;
  padding: 0.55rem 0.8rem;
}

.activation-badge.disabled {
  background: rgba(100, 116, 139, 0.12);
  color: #475569;
}

.runtime-grid {
  display: grid;
  gap: 0.75rem;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.runtime-grid div,
.profile-panel {
  background: rgba(248, 250, 252, 0.86);
  border: 1px solid rgba(15, 23, 42, 0.07);
  border-radius: 1rem;
  padding: 0.9rem;
}

.runtime-grid span {
  color: #64748b;
  display: block;
  font-size: 0.78rem;
}

.runtime-grid strong {
  display: block;
  font-size: 1rem;
  margin-top: 0.2rem;
}

.profile-panel {
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.profile-pill {
  background: #198754;
  border-radius: 999px;
  color: #fff;
  font-size: 0.82rem;
  font-weight: 800;
  padding: 0.35rem 0.65rem;
}

.profile-pill.muted {
  background: #e2e8f0;
  color: #334155;
}

.timeline-icon {
  background: rgba(25, 135, 84, 0.12);
  color: #198754;
}

.safety-timeline {
  display: grid;
  gap: 0.95rem;
}

.timeline-item {
  display: grid;
  gap: 0.75rem;
  grid-template-columns: 1rem 1fr;
}

.timeline-item > span {
  background: #cbd5e1;
  border-radius: 999px;
  height: 0.75rem;
  margin-top: 0.25rem;
  position: relative;
  width: 0.75rem;
}

.timeline-item.complete > span {
  background: #198754;
  box-shadow: 0 0 0 0.35rem rgba(25, 135, 84, 0.12);
}

.timeline-item.warning > span {
  background: #ffc107;
  box-shadow: 0 0 0 0.35rem rgba(255, 193, 7, 0.15);
}

.timeline-item strong {
  display: block;
}

.timeline-item p {
  color: #64748b;
  font-size: 0.88rem;
  margin: 0.15rem 0 0;
}

.quick-link-card {
  align-items: center;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 1.05rem;
  color: inherit;
  display: flex;
  gap: 0.85rem;
  height: 100%;
  padding: 1rem;
  transition:
    background 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

.quick-link-card:hover {
  background: #fff;
  box-shadow: 0 1rem 2.2rem rgba(15, 23, 42, 0.1);
  transform: translateY(-3px);
}

.quick-link-card > span {
  align-items: center;
  background: rgba(13, 110, 253, 0.1);
  border-radius: 0.9rem;
  color: #0d6efd;
  display: inline-flex;
  flex-shrink: 0;
  height: 2.5rem;
  justify-content: center;
  width: 2.5rem;
}

.quick-link-card p {
  color: #64748b;
  font-size: 0.82rem;
  margin: 0.15rem 0 0;
}

.openapi-card {
  align-items: center;
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(13, 110, 253, 0.16);
  border-radius: 1.1rem;
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  padding: 1rem;
}

.openapi-card strong,
.openapi-card span {
  display: block;
}

.openapi-card span {
  color: #64748b;
  font-size: 0.88rem;
}

@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(0.75rem);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes pulse {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes sweep {
  0% {
    opacity: 0;
    transform: translateX(-6rem) rotate(18deg);
  }
  45%,
  55% {
    opacity: 1;
  }
  100% {
    transform: translateX(16rem) rotate(18deg);
  }
}

@media (max-width: 991.98px) {
  .overview-hero,
  .openapi-card {
    flex-direction: column;
  }

  .hero-actions {
    flex-wrap: wrap;
  }

  .overview-skeleton {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 575.98px) {
  .runtime-grid,
  .profile-panel,
  .overview-skeleton {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
