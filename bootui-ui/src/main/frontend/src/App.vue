<script setup>
import {computed, onMounted, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'

const router = useRouter()
const route = useRoute()
const overview = ref(null)
const panels = ref(null)
const error = ref(null)

const semanticNavigationGroups = [
  {key: 'runtime', title: 'Runtime', icon: 'bi-activity'},
  {key: 'configuration', title: 'Configuration', icon: 'bi-sliders'},
  {key: 'services', title: 'Services', icon: 'bi-hdd-network'},
  {key: 'diagnostics', title: 'Diagnostics', icon: 'bi-search'},
  {key: 'developer-tools', title: 'Developer tools', icon: 'bi-tools'}
]
const unavailableNavigationGroup = {
  key: 'unavailable',
  title: 'Disabled / unavailable',
  icon: 'bi-slash-circle'
}
const routes = router.options.routes.filter((r) => r.name)
const EXPANDED_GROUPS_STORAGE_KEY = 'bootui.expandedGroups'

function loadExpandedGroups() {
  const defaults = {runtime: true}
  try {
    const stored = window.localStorage?.getItem(EXPANDED_GROUPS_STORAGE_KEY)
    if (stored) {
      const parsed = JSON.parse(stored)
      if (parsed && typeof parsed === 'object') {
        return {...defaults, ...parsed}
      }
    }
  } catch {
    // Ignore unavailable or malformed storage; fall back to defaults.
  }
  return defaults
}

const expandedGroups = reactive(loadExpandedGroups())
const panelLookup = computed(() => new Map((panels.value?.panels ?? []).map((panel) => [panel.id, panel])))
const activeRoute = computed(() => routes.find((r) => r.name === route.name))
const activePanel = computed(() => (route.name ? panelLookup.value.get(route.name) : null))
const activePanelDisabled = computed(() => activePanel.value?.enabled === false)
const activePanelUnavailable = computed(() => activePanelDisabled.value || activePanel.value?.available === false)
const activePanelUnavailableTitle = computed(() => (activePanelDisabled.value ? 'Panel disabled' : 'Panel unavailable'))
const activePanelUnavailableReason = computed(() => {
  if (activePanelDisabled.value) {
    return panelDisabledReason(activePanel.value)
  }
  return (
    activePanel.value?.unavailableReason ||
    'Required classpath or endpoint support is unavailable for this application.'
  )
})
const activePanelReadOnly = computed(() => activePanel.value?.readOnly === true && !activePanelUnavailable.value)
const activePanelReadOnlyReason = computed(() => activePanel.value?.readOnlyReason || 'This panel is read-only.')
const applicationTitle = computed(() => overview.value?.applicationName || 'Spring Boot app')
const runtimeSummary = computed(() => {
  if (!overview.value) return 'Loading runtime details'
  return `Spring Boot ${overview.value.springBootVersion} · Java ${overview.value.javaVersion}`
})
const activeProfiles = computed(() => overview.value?.activeProfiles ?? [])
const activationLabel = computed(() => {
  if (!overview.value?.activation) return 'Loading'
  return overview.value.activation.enabled ? 'Active' : 'Disabled'
})
const githubProjectUrl = 'https://github.com/jdubois/boot-ui'
const navigationSections = computed(() => {
  const sections = [
    {
      key: 'overview',
      title: 'Overview',
      collapsible: false,
      routes: routes.filter((r) => r.meta?.group === 'overview')
    }
  ]

  for (const group of semanticNavigationGroups) {
    const groupRoutes = routes.filter((r) => r.meta?.group === group.key && !routeUnavailable(r))
    if (groupRoutes.length) {
      sections.push({...group, collapsible: true, unavailable: false, routes: groupRoutes})
    }
  }

  const unavailableRoutes = routes.filter((r) => r.meta?.group !== 'overview' && routeUnavailable(r))
  if (unavailableRoutes.length) {
    sections.push({...unavailableNavigationGroup, collapsible: true, unavailable: true, routes: unavailableRoutes})
  }

  return sections
})
const activeNavigationGroupKey = computed(() => {
  const currentRoute = activeRoute.value
  if (!currentRoute || currentRoute.meta?.group === 'overview') return null
  return routeUnavailable(currentRoute) ? unavailableNavigationGroup.key : currentRoute.meta?.group
})

async function loadOverview() {
  try {
    const res = await fetch('api/overview')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    overview.value = await res.json()
  } catch (e) {
    error.value = 'Unable to load overview: ' + e.message
  }
}

async function loadPanels() {
  try {
    const res = await fetch('api/panels')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    panels.value = await res.json()
  } catch (e) {
    error.value = 'Unable to load panel availability: ' + e.message
  }
}

async function loadShellData() {
  await Promise.all([loadOverview(), loadPanels()])
}

function panelForRoute(r) {
  return panelLookup.value.get(r.name)
}

function routeUnavailable(r) {
  const panel = panelForRoute(r)
  return panel?.enabled === false || panel?.available === false
}

function routeReadOnly(r) {
  const panel = panelForRoute(r)
  return panel?.readOnly === true && !routeUnavailable(r)
}

function routeStatusIcon(r) {
  if (routeUnavailable(r)) {
    return 'bi-slash-circle'
  }
  if (routeReadOnly(r)) {
    return 'bi-lock'
  }
  return null
}

function panelDisabledReason(panel) {
  return `Panel is disabled via bootui.panels.${panel?.id || 'panel'}.enabled=false`
}

function routeAvailabilityLabel(r) {
  const panel = panelForRoute(r)
  if (panel?.enabled === false) {
    return `${r.meta.title} - disabled: ${panelDisabledReason(panel)}`
  }
  if (panel?.available === false) {
    return `${r.meta.title} - unavailable: ${panel.unavailableReason || 'required support is unavailable'}`
  }
  if (panel?.readOnly === true) {
    return `${r.meta.title} - read-only: ${panel.readOnlyReason || 'mutating actions are disabled'}`
  }
  return r.meta.title
}

function groupDomId(group) {
  return `bootui-nav-group-${group.key}`
}

function groupHasActiveRoute(group) {
  return group.routes.some((r) => r.name === route.name)
}

function isGroupExpanded(groupKey) {
  return expandedGroups[groupKey] === true
}

function toggleGroup(groupKey) {
  expandedGroups[groupKey] = !isGroupExpanded(groupKey)
}

watch(
  activeNavigationGroupKey,
  (groupKey) => {
    if (groupKey) {
      expandedGroups[groupKey] = true
    }
  },
  {immediate: true}
)

watch(
  expandedGroups,
  (groups) => {
    try {
      window.localStorage?.setItem(EXPANDED_GROUPS_STORAGE_KEY, JSON.stringify(groups))
    } catch {
      // Ignore storage write failures (e.g. private mode / quota).
    }
  },
  {deep: true}
)

onMounted(loadShellData)
</script>

<template>
  <div class="bootui-shell min-vh-100">
    <div class="ambient-orb ambient-orb-one"></div>
    <div class="ambient-orb ambient-orb-two"></div>

    <aside class="bootui-sidebar">
      <router-link class="brand-card text-decoration-none" to="/overview">
        <span class="brand-mark"><i class="bi bi-cup-hot-fill"></i></span>
        <span>
          <span class="brand-name">BootUI</span>
          <span class="brand-subtitle">Local developer console</span>
        </span>
      </router-link>

      <nav aria-label="BootUI panels" class="nav nav-pills flex-column sidebar-nav">
        <div
          v-for="section in navigationSections"
          :key="section.key"
          :class="{
            'bootui-nav-section--overview': !section.collapsible,
            'bootui-nav-section--unavailable': section.unavailable
          }"
          class="bootui-nav-section"
        >
          <button
            v-if="section.collapsible"
            :aria-controls="groupDomId(section)"
            :aria-expanded="isGroupExpanded(section.key)"
            :class="{active: groupHasActiveRoute(section)}"
            class="bootui-nav-group__toggle"
            type="button"
            @click="toggleGroup(section.key)"
          >
            <span class="bootui-nav-group__label">
              <i :class="['bi', section.icon]"></i>
              <span>{{ section.title }}</span>
            </span>
            <span class="bootui-nav-group__count">{{ section.routes.length }}</span>
            <i
              :class="['bi', isGroupExpanded(section.key) ? 'bi-chevron-up' : 'bi-chevron-down']"
              aria-hidden="true"
              class="bootui-nav-group__chevron"
            ></i>
          </button>

          <div
            v-show="!section.collapsible || isGroupExpanded(section.key)"
            :id="groupDomId(section)"
            :aria-label="`${section.title} panels`"
            class="bootui-nav-group__items"
            role="group"
          >
            <router-link v-for="r in section.routes" :key="r.name" v-slot="{href, navigate}" :to="r.path" custom>
              <a
                :aria-current="route.name === r.name ? 'page' : undefined"
                :aria-label="routeAvailabilityLabel(r)"
                :class="{
                  active: route.name === r.name,
                  'bootui-nav-link--unavailable': routeUnavailable(r)
                }"
                :href="href"
                :title="routeAvailabilityLabel(r)"
                class="nav-link bootui-nav-link"
                @click="navigate"
              >
                <i :class="['bi', r.meta.icon]"></i>
                <span class="bootui-nav-link__label">{{ r.meta.title }}</span>
                <i
                  v-if="routeStatusIcon(r)"
                  :class="['bi', routeStatusIcon(r), 'bootui-nav-link__status']"
                  aria-hidden="true"
                ></i>
              </a>
            </router-link>
          </div>
        </div>
      </nav>

      <div class="sidebar-bottom mt-auto">
        <a
          :href="githubProjectUrl"
          class="contribute-card text-decoration-none"
          rel="noopener noreferrer"
          target="_blank"
        >
          <span class="contribute-icon">
            <i class="bi bi-github"></i>
          </span>
          <span>
            <strong>Contribute to the project</strong>
          </span>
        </a>
        <div v-if="overview?.activation && !overview.activation.enabled" class="alert alert-warning mt-3 mb-0 small">
          BootUI is disabled: {{ overview.activation.reason }}
        </div>
      </div>
    </aside>

    <div class="bootui-workspace">
      <header class="topbar">
        <div>
          <div class="eyebrow">Inspecting</div>
          <h1 class="topbar-title">{{ applicationTitle }}</h1>
          <p class="topbar-subtitle mb-0">{{ runtimeSummary }}</p>
        </div>
        <div class="topbar-actions">
          <span class="status-pill">
            <i class="bi bi-broadcast-pin"></i>
            {{ activationLabel }}
          </span>
          <span v-if="activeProfiles.length" class="profile-stack">
            <span v-for="profile in activeProfiles" :key="profile" class="profile-chip">{{ profile }}</span>
          </span>
          <span v-else class="profile-chip muted">default</span>
        </div>
      </header>

      <main class="content-stage">
        <div v-if="error" class="alert alert-danger shadow-sm">{{ error }}</div>
        <div v-if="activePanelUnavailable" class="alert alert-warning panel-availability-alert shadow-sm" role="status">
          <div class="panel-availability-alert__title">
            <i class="bi bi-slash-circle"></i>
            <strong>{{ activePanelUnavailableTitle }}</strong>
          </div>
          <div>{{ activePanelUnavailableReason }}</div>
        </div>
        <div v-else-if="activePanelReadOnly" class="alert alert-info panel-read-only-alert shadow-sm" role="status">
          <div class="panel-availability-alert__title">
            <i class="bi bi-lock"></i>
            <strong>Panel read-only</strong>
          </div>
          <div>{{ activePanelReadOnlyReason }}</div>
        </div>

        <router-view v-slot="{Component}">
          <transition mode="out-in" name="page-slide">
            <component :is="Component" :key="route.fullPath" :panel="activePanel" class="page-panel" />
          </transition>
        </router-view>
      </main>

      <footer class="bootui-footer">BootUI · embedded in your Spring Boot app · no external service required</footer>
    </div>
  </div>
</template>

<style scoped>
:global(body) {
  background:
    radial-gradient(circle at top left, rgba(25, 135, 84, 0.18), transparent 34rem),
    linear-gradient(135deg, #f6fbf8 0%, #eef6ff 46%, #f7f4ff 100%);
}

:global(.card) {
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 1.1rem;
  box-shadow: 0 1rem 2.5rem rgba(15, 23, 42, 0.07);
  transition:
    transform 180ms ease,
    box-shadow 180ms ease,
    border-color 180ms ease;
}

:global(.card:hover) {
  border-color: rgba(25, 135, 84, 0.25);
  box-shadow: 0 1.2rem 3rem rgba(15, 23, 42, 0.11);
  transform: translateY(-2px);
}

:global(.btn),
:global(.badge),
:global(.alert),
:global(.form-control),
:global(.form-select) {
  border-radius: 0.75rem;
}

:global(.progress) {
  border-radius: 999px;
  overflow: hidden;
}

:global(.progress-bar) {
  transition: width 500ms ease;
}

.bootui-shell {
  color: #152033;
  display: flex;
  isolation: isolate;
  overflow-x: hidden;
  position: relative;
}

.ambient-orb {
  border-radius: 999px;
  filter: blur(4px);
  opacity: 0.45;
  pointer-events: none;
  position: fixed;
  z-index: -1;
}

.ambient-orb-one {
  animation: float-orb 13s ease-in-out infinite;
  background: rgba(25, 135, 84, 0.22);
  height: 18rem;
  left: -5rem;
  top: 7rem;
  width: 18rem;
}

.ambient-orb-two {
  animation: float-orb 16s ease-in-out infinite reverse;
  background: rgba(13, 110, 253, 0.16);
  bottom: 4rem;
  height: 22rem;
  right: -8rem;
  width: 22rem;
}

.bootui-sidebar {
  backdrop-filter: blur(22px);
  background: rgba(255, 255, 255, 0.76);
  border-right: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 0.75rem 0 2rem rgba(15, 23, 42, 0.06);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  gap: 1.4rem;
  min-height: 100vh;
  padding: 1.25rem;
  position: sticky;
  top: 0;
  width: 18rem;
}

.brand-card {
  align-items: center;
  background: linear-gradient(135deg, rgba(25, 135, 84, 0.12), rgba(13, 110, 253, 0.1));
  border: 1px solid rgba(25, 135, 84, 0.14);
  border-radius: 1.25rem;
  color: inherit;
  display: flex;
  gap: 0.85rem;
  padding: 0.85rem;
  transition:
    transform 180ms ease,
    box-shadow 180ms ease;
}

.brand-card:hover {
  box-shadow: 0 1rem 2rem rgba(25, 135, 84, 0.12);
  transform: translateY(-2px);
}

.brand-mark,
.page-icon,
.contribute-icon {
  align-items: center;
  border-radius: 1rem;
  display: inline-flex;
  justify-content: center;
}

.brand-mark {
  background: #198754;
  box-shadow: 0 0.6rem 1.2rem rgba(25, 135, 84, 0.28);
  color: #fff;
  height: 2.75rem;
  width: 2.75rem;
}

.brand-name,
.brand-subtitle {
  display: block;
}

.brand-name {
  font-size: 1.1rem;
  font-weight: 800;
}

.brand-subtitle,
.topbar-subtitle {
  color: #64748b;
  font-size: 0.85rem;
}

.sidebar-nav {
  animation: fade-up 420ms ease both;
  gap: 0.45rem;
}

.eyebrow {
  color: #64748b;
  font-size: 0.7rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  margin-bottom: 0.55rem;
  text-transform: uppercase;
}

.bootui-nav-link {
  align-items: center;
  color: #334155;
  display: flex;
  gap: 0.75rem;
  padding: 0.62rem 0.75rem;
  position: relative;
  transition:
    background 160ms ease,
    color 160ms ease,
    transform 160ms ease;
}

.bootui-nav-section {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.bootui-nav-section:not(.bootui-nav-section--overview) .bootui-nav-group__items {
  border-left: 1px solid rgba(100, 116, 139, 0.2);
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  margin-left: 0.85rem;
  padding-left: 0.5rem;
}

.bootui-nav-group__items {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.bootui-nav-group__toggle {
  align-items: center;
  background: rgba(255, 255, 255, 0.58);
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 0.9rem;
  color: #64748b;
  display: flex;
  font-size: 0.72rem;
  font-weight: 800;
  gap: 0.45rem;
  letter-spacing: 0.06em;
  padding: 0.56rem 0.7rem;
  text-align: left;
  text-transform: uppercase;
  transition:
    background 160ms ease,
    border-color 160ms ease,
    color 160ms ease,
    transform 160ms ease;
  width: 100%;
}

.bootui-nav-group__toggle:hover,
.bootui-nav-group__toggle.active {
  background: rgba(25, 135, 84, 0.08);
  border-color: rgba(25, 135, 84, 0.18);
  color: #146c43;
  transform: translateX(2px);
}

.bootui-nav-group__label {
  align-items: center;
  display: flex;
  flex: 1;
  gap: 0.5rem;
  min-width: 0;
}

.bootui-nav-group__count {
  background: rgba(100, 116, 139, 0.1);
  border-radius: 999px;
  color: #64748b;
  font-size: 0.68rem;
  line-height: 1;
  padding: 0.22rem 0.42rem;
}

.bootui-nav-group__chevron {
  font-size: 0.8rem;
}

.bootui-nav-section--unavailable .bootui-nav-group__toggle {
  background: rgba(148, 163, 184, 0.08);
  border-color: rgba(100, 116, 139, 0.12);
  color: #94a3b8;
  opacity: 0.72;
}

.bootui-nav-section--unavailable .bootui-nav-group__count {
  background: rgba(148, 163, 184, 0.12);
  color: #94a3b8;
}

.bootui-nav-link:hover {
  background: rgba(25, 135, 84, 0.08);
  color: #146c43;
  transform: translateX(3px);
}

.bootui-nav-link.active {
  background: linear-gradient(135deg, #198754, #0d6efd);
  box-shadow: 0 0.8rem 1.4rem rgba(25, 135, 84, 0.2);
  color: #fff;
}

.bootui-nav-link i {
  font-size: 1.05rem;
}

.bootui-nav-link__label {
  flex: 1;
}

.bootui-nav-link__status {
  color: #94a3b8;
  font-size: 0.95rem;
  opacity: 0.65;
}

.bootui-nav-link--unavailable {
  opacity: 0.55;
}

.bootui-nav-link--unavailable .bootui-nav-link__label {
  font-style: italic;
}

.sidebar-bottom {
  display: flex;
  flex-direction: column;
}

.contribute-card {
  align-items: center;
  background: rgba(255, 255, 255, 0.84);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 1.1rem;
  color: inherit;
  display: flex;
  gap: 0.75rem;
  padding: 0.9rem;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

.contribute-card:hover {
  border-color: rgba(13, 110, 253, 0.25);
  box-shadow: 0 0.9rem 1.8rem rgba(15, 23, 42, 0.09);
  transform: translateY(-2px);
}

.contribute-card strong {
  display: block;
}

.contribute-icon {
  background: #24292f;
  color: #fff;
  height: 2.25rem;
  width: 2.25rem;
}

.bootui-workspace {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  align-items: center;
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  padding: 1.5rem 2rem 1rem;
}

.topbar-title {
  font-size: clamp(1.45rem, 2vw, 2.1rem);
  font-weight: 800;
  margin: 0;
}

.topbar-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 0.55rem;
  justify-content: flex-end;
}

.status-pill,
.profile-chip {
  align-items: center;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 999px;
  box-shadow: 0 0.5rem 1.2rem rgba(15, 23, 42, 0.06);
  display: inline-flex;
  font-size: 0.82rem;
  font-weight: 700;
  gap: 0.35rem;
  padding: 0.45rem 0.75rem;
}

.profile-stack {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.profile-chip {
  background: rgba(25, 135, 84, 0.1);
  color: #146c43;
}

.profile-chip.muted {
  background: rgba(100, 116, 139, 0.1);
  color: #64748b;
}

.content-stage {
  flex: 1;
  padding: 0 2rem 1.5rem;
}

.page-panel {
  animation: fade-up 360ms ease both;
}

.panel-alert {
  border: 0;
  box-shadow: 0 0.75rem 1.75rem rgba(180, 83, 9, 0.12);
}

.panel-availability-alert {
  border: 1px solid rgba(245, 158, 11, 0.28);
}

.panel-read-only-alert {
  border: 1px solid rgba(13, 110, 253, 0.22);
}

.panel-availability-alert__title {
  align-items: center;
  display: flex;
  gap: 0.45rem;
  margin-bottom: 0.25rem;
}

.bootui-footer {
  color: #64748b;
  font-size: 0.82rem;
  padding: 0 2rem 1.25rem;
  text-align: center;
}

.page-slide-enter-active,
.page-slide-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.page-slide-enter-from {
  opacity: 0;
  transform: translateY(0.75rem) scale(0.99);
}

.page-slide-leave-to {
  opacity: 0;
  transform: translateY(-0.35rem) scale(0.99);
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

@keyframes float-orb {
  0%,
  100% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  50% {
    transform: translate3d(1.5rem, -1rem, 0) scale(1.06);
  }
}

@media (max-width: 991.98px) {
  .bootui-shell {
    flex-direction: column;
  }

  .bootui-sidebar {
    min-height: auto;
    position: relative;
    width: 100%;
  }

  .topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .content-stage,
  .topbar,
  .bootui-footer {
    padding-left: 1rem;
    padding-right: 1rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
