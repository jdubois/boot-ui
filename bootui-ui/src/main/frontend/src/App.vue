<script setup>
import {computed, onBeforeUnmount, onMounted, provide, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {
  applyTheme,
  nextTheme,
  readThemePreference,
  resolveTheme,
  THEME_QUERY,
  THEME_STORAGE_KEY
} from './utils/theme.js'
import {describeLoadError} from './utils/loadError.js'
import {recordRecentPanel} from './utils/recentPanels.js'
import CommandPalette from './views/components/CommandPalette.vue'

const router = useRouter()
const route = useRoute()
const overview = ref(null)
const panels = ref(null)
const shellError = ref(null)
const savedCollapsed = localStorage.getItem('bootui.sidebar.collapsed')
const sidebarCollapsed = ref(savedCollapsed === 'true')

const NARROW_QUERY = '(max-width: 991.98px)'
const narrowMediaQuery =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(NARROW_QUERY) : null
const isNarrow = ref(narrowMediaQuery?.matches === true)
const mobileNavOpen = ref(false)

function onNarrowChange(e) {
  isNarrow.value = e.matches === true
  if (!isNarrow.value) {
    mobileNavOpen.value = false
  }
}

const commandPaletteOpen = ref(false)
const commandPaletteRef = ref(null)
const themeMediaQuery =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(THEME_QUERY) : null
const themePreference = ref(readThemePreference(typeof window === 'undefined' ? null : window.localStorage))
const systemPrefersDark = ref(themeMediaQuery?.matches === true)
const resolvedTheme = computed(() => resolveTheme(themePreference.value, systemPrefersDark.value))
const darkTheme = computed(() => resolvedTheme.value === 'dark')
const themeToggleLabel = computed(() => `Switch to ${darkTheme.value ? 'light' : 'dark'} mode`)
const themeToggleText = computed(() => `${darkTheme.value ? 'Light' : 'Dark'} mode`)

provide('overview', overview)
provide('panels', panels)

function openCommandPalette() {
  commandPaletteOpen.value = true
  commandPaletteRef.value?.focusInput()
}

watch(sidebarCollapsed, (v) => localStorage.setItem('bootui.sidebar.collapsed', String(v)))

watch(
  () => route.name,
  (name) => {
    if (name) recordRecentPanel(name)
    mobileNavOpen.value = false
  },
  {immediate: true}
)

watch(resolvedTheme, syncTheme, {immediate: true})

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function onSidebarToggle() {
  if (isNarrow.value) {
    mobileNavOpen.value = false
  } else {
    toggleSidebar()
  }
}

function openMobileNav() {
  mobileNavOpen.value = true
}

function closeMobileNav() {
  mobileNavOpen.value = false
}

function syncTheme(theme) {
  if (typeof document !== 'undefined') {
    applyTheme(document.documentElement, theme)
  }
}

function persistThemePreference(theme) {
  try {
    window.localStorage?.setItem(THEME_STORAGE_KEY, theme)
  } catch {
    // Ignore unavailable storage; the in-memory theme still applies for this session.
  }
}

function toggleTheme() {
  const theme = nextTheme(resolvedTheme.value)
  themePreference.value = theme
  persistThemePreference(theme)
}

function onSystemThemeChange(e) {
  systemPrefersDark.value = e.matches === true
}

const semanticNavigationGroups = [
  {key: 'advisors', title: 'Advisors', icon: 'bi-clipboard2-check'},
  {key: 'runtime', title: 'Runtime', icon: 'bi-activity'},
  {key: 'configuration', title: 'Configuration', icon: 'bi-sliders'},
  {key: 'database', title: 'Database', icon: 'bi-database'},
  {key: 'security', title: 'Security', icon: 'bi-shield-lock'},
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
  const defaults = {advisors: true}
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
  if (shellServerUnreachable.value) return 'Spring Boot app is not responding. Restart it and retry.'
  if (shellError.value && !overview.value) return 'Unable to load BootUI runtime details.'
  if (!overview.value) return 'Loading runtime details'
  return `Spring Boot ${overview.value.springBootVersion} · Java ${overview.value.javaVersion}`
})
const activeProfiles = computed(() => overview.value?.activeProfiles ?? [])
const shellErrorMessage = computed(() => shellError.value?.message ?? null)
const shellErrorTitle = computed(() => shellError.value?.title ?? 'Load failed')
const shellServerUnreachable = computed(() => shellError.value?.serverUnreachable === true)
const connectionState = computed(() => {
  if (shellServerUnreachable.value) return 'unreachable'
  if (shellError.value && !overview.value?.activation) return 'error'
  if (!overview.value?.activation) return 'checking'
  return overview.value.activation.enabled ? 'active' : 'disabled'
})
const activationLabel = computed(() => {
  if (connectionState.value === 'unreachable') return 'Server unreachable'
  if (connectionState.value === 'error') return 'API load failed'
  if (connectionState.value === 'checking') return 'Checking server'
  return connectionState.value === 'active' ? 'BootUI active' : 'BootUI disabled'
})
const activationIcon = computed(
  () =>
    ({
      active: 'bi-broadcast-pin',
      disabled: 'bi-slash-circle',
      error: 'bi-exclamation-triangle',
      checking: 'bi-hourglass-split',
      unreachable: 'bi-wifi-off'
    })[connectionState.value]
)
const activationTitle = computed(
  () =>
    ({
      active: 'BootUI is active and the local API is reachable.',
      disabled: 'BootUI answered the local API but is disabled for this application.',
      error: 'BootUI reached the local API but could not load the shell data.',
      checking: 'Checking the BootUI API connection.',
      unreachable: 'BootUI cannot reach the Spring Boot app. It may have been stopped.'
    })[connectionState.value]
)
const statusPillClass = computed(() => `status-pill--${connectionState.value}`)
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
  const res = await fetch('api/overview')
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  overview.value = await res.json()
}

async function loadPanels() {
  const res = await fetch('api/panels')
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  panels.value = await res.json()
}

async function loadShellData() {
  const results = await Promise.allSettled([loadOverview(), loadPanels()])
  const failures = [
    {result: results[0], context: 'Unable to load overview'},
    {result: results[1], context: 'Unable to load panel availability'}
  ].filter(({result}) => result.status === 'rejected')

  if (!failures.length) {
    shellError.value = null
    return
  }

  const descriptions = failures.map(({result, context}) =>
    describeLoadError(/** @type {PromiseRejectedResult} */ (result).reason, context)
  )
  shellError.value = descriptions.find((description) => description.serverUnreachable) || descriptions[0]
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

function toggleGroup(groupKey, event) {
  expandedGroups[groupKey] = !isGroupExpanded(groupKey)
  if (event?.detail > 0 && event.currentTarget instanceof HTMLElement) {
    event.currentTarget.blur()
  }
}

const collapsedRail = computed(() => !isNarrow.value && sidebarCollapsed.value)
const railFlyout = ref(null)
let flyoutCloseTimer = null

function clearFlyoutTimer() {
  if (flyoutCloseTimer) {
    clearTimeout(flyoutCloseTimer)
    flyoutCloseTimer = null
  }
}

function openRailFlyout(section, event) {
  if (!collapsedRail.value || !section.collapsible) return
  clearFlyoutTimer()
  const rect = event.currentTarget.getBoundingClientRect()
  const estimatedHeight = 52 + section.routes.length * 40
  const viewportHeight = typeof window === 'undefined' ? 0 : window.innerHeight
  const top = Math.max(8, Math.min(rect.top, viewportHeight - estimatedHeight - 8))
  railFlyout.value = {section, top, left: rect.right + 10}
}

function scheduleRailFlyoutClose() {
  clearFlyoutTimer()
  flyoutCloseTimer = setTimeout(() => {
    railFlyout.value = null
  }, 140)
}

function cancelRailFlyoutClose() {
  clearFlyoutTimer()
}

function closeRailFlyout() {
  clearFlyoutTimer()
  railFlyout.value = null
}

function onFlyoutLinkClick(navigate, event) {
  navigate(event)
  closeRailFlyout()
}

watch(collapsedRail, (value) => {
  if (!value) closeRailFlyout()
})

watch(
  activeNavigationGroupKey,
  (groupKey) => {
    if (groupKey) {
      expandedGroups[/** @type {string} */ (groupKey)] = true
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

onMounted(() => {
  loadShellData()
  window.addEventListener('keydown', onGlobalKeydown)
  themeMediaQuery?.addEventListener?.('change', onSystemThemeChange)
  narrowMediaQuery?.addEventListener?.('change', onNarrowChange)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  themeMediaQuery?.removeEventListener?.('change', onSystemThemeChange)
  narrowMediaQuery?.removeEventListener?.('change', onNarrowChange)
  clearFlyoutTimer()
})

function onGlobalKeydown(e) {
  if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
    e.preventDefault()
    commandPaletteOpen.value = !commandPaletteOpen.value
    if (commandPaletteOpen.value) {
      commandPaletteRef.value?.focusInput()
    }
  }
}
</script>

<template>
  <div class="bootui-shell min-vh-100">
    <CommandPalette v-if="commandPaletteOpen" ref="commandPaletteRef" @close="commandPaletteOpen = false" />
    <div class="ambient-orb ambient-orb-one"></div>
    <div class="ambient-orb ambient-orb-two"></div>

    <div v-if="isNarrow && mobileNavOpen" class="bootui-nav-backdrop" @click="closeMobileNav"></div>

    <aside
      :class="{
        'bootui-sidebar--collapsed': collapsedRail,
        'bootui-sidebar--drawer': isNarrow,
        'bootui-sidebar--open': isNarrow && mobileNavOpen
      }"
      class="bootui-sidebar"
    >
      <div class="brand-area">
        <router-link class="brand-card text-decoration-none" to="/overview">
          <span class="brand-mark"><i class="bi bi-cup-hot-fill"></i></span>
          <span class="brand-text">
            <span class="brand-name">BootUI</span>
            <span class="brand-subtitle">Local developer console</span>
          </span>
        </router-link>
        <button
          class="sidebar-toggle"
          :title="isNarrow ? 'Close menu' : sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'"
          @click="onSidebarToggle"
        >
          <i
            :class="isNarrow ? 'bi-x-lg' : sidebarCollapsed ? 'bi-chevron-double-right' : 'bi-chevron-double-left'"
            class="bi"
          ></i>
        </button>
      </div>

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
            :aria-label="
              sidebarCollapsed
                ? `${isGroupExpanded(section.key) ? 'Collapse' : 'Expand'} ${section.title} panels`
                : undefined
            "
            :aria-controls="groupDomId(section)"
            :aria-expanded="isGroupExpanded(section.key)"
            :class="{active: groupHasActiveRoute(section)}"
            :title="section.title"
            class="bootui-nav-group__toggle"
            type="button"
            @click="toggleGroup(section.key, $event)"
            @mouseenter="openRailFlyout(section, $event)"
            @mouseleave="scheduleRailFlyoutClose"
            @focusin="openRailFlyout(section, $event)"
            @focusout="scheduleRailFlyoutClose"
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

    <transition name="flyout-fade">
      <div
        v-if="railFlyout"
        class="bootui-nav-flyout"
        :style="{top: railFlyout.top + 'px', left: railFlyout.left + 'px'}"
        role="group"
        :aria-label="`${railFlyout.section.title} panels`"
        @mouseenter="cancelRailFlyoutClose"
        @mouseleave="scheduleRailFlyoutClose"
      >
        <div class="bootui-nav-flyout__title">
          <i :class="['bi', railFlyout.section.icon]"></i>
          <span>{{ railFlyout.section.title }}</span>
        </div>
        <router-link v-for="r in railFlyout.section.routes" :key="r.name" v-slot="{href, navigate}" :to="r.path" custom>
          <a
            :aria-current="route.name === r.name ? 'page' : undefined"
            :aria-label="routeAvailabilityLabel(r)"
            :class="{
              active: route.name === r.name,
              'bootui-nav-link--unavailable': routeUnavailable(r)
            }"
            :href="href"
            :title="routeAvailabilityLabel(r)"
            class="nav-link bootui-nav-link bootui-nav-flyout__link"
            @click="onFlyoutLinkClick(navigate, $event)"
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
    </transition>

    <div class="bootui-workspace">
      <header class="topbar">
        <div class="topbar-lead">
          <button class="nav-hamburger" type="button" aria-label="Open navigation menu" @click="openMobileNav">
            <i class="bi bi-list"></i>
          </button>
          <div class="topbar-heading">
            <div class="eyebrow">Inspecting</div>
            <h1 class="topbar-title">{{ applicationTitle }}</h1>
            <p class="topbar-subtitle mb-0">{{ runtimeSummary }}</p>
          </div>
        </div>
        <div class="topbar-actions">
          <button class="cp-trigger" title="Open command palette (⌘K)" @click="openCommandPalette">
            <i class="bi bi-search me-1"></i>
            <span class="cp-trigger-label">Go to panel</span>
            <kbd class="cp-trigger-hint">⌘K</kbd>
          </button>
          <button
            class="theme-toggle"
            type="button"
            :title="themeToggleLabel"
            :aria-label="themeToggleLabel"
            @click="toggleTheme"
          >
            <i :class="['bi', darkTheme ? 'bi-sun' : 'bi-moon-stars']"></i>
            <span class="theme-toggle__label">{{ themeToggleText }}</span>
          </button>
          <span :class="['status-pill', statusPillClass]" :title="activationTitle">
            <i :class="['bi', activationIcon]"></i>
            {{ activationLabel }}
          </span>
          <span v-if="activeProfiles.length" class="profile-stack">
            <span v-for="profile in activeProfiles" :key="profile" class="profile-chip">{{ profile }}</span>
          </span>
          <span v-else class="profile-chip muted">default</span>
        </div>
      </header>

      <main class="content-stage">
        <div
          v-if="shellErrorMessage"
          :class="['alert', shellServerUnreachable ? 'alert-warning' : 'alert-danger']"
          class="shell-error shadow-sm"
          role="alert"
        >
          <div class="shell-error__title">
            <i :class="['bi', shellServerUnreachable ? 'bi-wifi-off' : 'bi-exclamation-triangle-fill']"></i>
            <strong>{{ shellErrorTitle }}</strong>
          </div>
          <div>{{ shellErrorMessage }}</div>
        </div>
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
            <keep-alive include="Overview">
              <component :is="Component" :key="route.fullPath" :panel="activePanel" class="page-panel" />
            </keep-alive>
          </transition>
        </router-view>
      </main>

      <footer class="bootui-footer">
        <a :href="githubProjectUrl" rel="noopener noreferrer" target="_blank">
          BootUI - The missing developer UI for Spring Boot!
        </a>
      </footer>
    </div>
  </div>
</template>

<style scoped>
:global(:root) {
  /* Brand palette */
  --bootui-green: #198754;
  --bootui-green-dark: #146c43;
  --bootui-blue: #0d6efd;
  --bootui-text: #152033;
  --bootui-text-muted: #64748b;
  --bootui-text-subtle: #94a3b8;

  /* Surfaces */
  --bootui-bg-body: linear-gradient(135deg, #f6fbf8 0%, #eef6ff 46%, #f7f4ff 100%);
  --bootui-bg-body-orb: rgba(25, 135, 84, 0.18);
  --bootui-surface: rgba(255, 255, 255, 0.82);
  --bootui-surface-solid: #ffffff;
  --bootui-surface-alt: rgba(248, 250, 252, 0.86);
  --bootui-sidebar-bg: rgba(255, 255, 255, 0.76);

  /* Borders */
  --bootui-border: rgba(15, 23, 42, 0.08);
  --bootui-border-subtle: rgba(15, 23, 42, 0.06);
  --bootui-border-alt: rgba(100, 116, 139, 0.2);

  /* Shadows */
  --bootui-shadow-sm: 0 0.25rem 0.75rem rgba(15, 23, 42, 0.05);
  --bootui-shadow-md: 0 1.2rem 3rem rgba(15, 23, 42, 0.11);
  --bootui-shadow-sidebar: 0.75rem 0 2rem rgba(15, 23, 42, 0.06);

  /* Nav link state */
  --bootui-nav-hover-bg: rgba(25, 135, 84, 0.08);
  --bootui-nav-hover-color: #146c43;
  --bootui-nav-active-bg: linear-gradient(135deg, #198754, #0d6efd);
  --bootui-nav-active-color: #ffffff;
  --bootui-nav-group-bg: rgba(255, 255, 255, 0.58);
  --bootui-nav-group-color: #64748b;
  --bootui-nav-link-color: #334155;

  /* Chart legend */
  --bootui-chart-input: #0d6efd;
  --bootui-chart-output: #6610f2;
  --bootui-chart-calls: #198754;

  /* Skeleton loaders */
  --bootui-skeleton-base: #e2e8f0;
  --bootui-skeleton-shine: #f1f5f9;
}

:global(:root[data-bootui-theme='dark']) {
  /* Brand palette — dark mode */
  --bootui-green: #34d068;
  --bootui-green-dark: #4ade80;
  --bootui-blue: #60a5fa;
  --bootui-text: #e2e8f0;
  --bootui-text-muted: #94a3b8;
  --bootui-text-subtle: #64748b;

  /* Surfaces */
  --bootui-bg-body: linear-gradient(135deg, #0d1a12 0%, #0f1929 46%, #100f1a 100%);
  --bootui-bg-body-orb: rgba(52, 208, 104, 0.12);
  --bootui-surface: rgba(30, 41, 59, 0.9);
  --bootui-surface-solid: #1e293b;
  --bootui-surface-alt: rgba(15, 23, 42, 0.86);
  --bootui-sidebar-bg: rgba(15, 23, 42, 0.88);

  /* Borders */
  --bootui-border: rgba(226, 232, 240, 0.1);
  --bootui-border-subtle: rgba(226, 232, 240, 0.07);
  --bootui-border-alt: rgba(100, 116, 139, 0.25);

  /* Shadows */
  --bootui-shadow-sm: 0 0.25rem 0.75rem rgba(0, 0, 0, 0.22);
  --bootui-shadow-md: 0 1.2rem 3rem rgba(0, 0, 0, 0.4);
  --bootui-shadow-sidebar: 0.75rem 0 2rem rgba(0, 0, 0, 0.25);

  /* Nav link state */
  --bootui-nav-hover-bg: rgba(52, 208, 104, 0.1);
  --bootui-nav-hover-color: #4ade80;
  --bootui-nav-active-bg: linear-gradient(135deg, #198754, #2563eb);
  --bootui-nav-active-color: #ffffff;
  --bootui-nav-group-bg: rgba(30, 41, 59, 0.7);
  --bootui-nav-group-color: #94a3b8;
  --bootui-nav-link-color: #cbd5e1;

  /* Chart legend */
  --bootui-chart-input: #6ea8fe;
  --bootui-chart-output: #c084fc;
  --bootui-chart-calls: #75b798;

  /* Skeleton loaders */
  --bootui-skeleton-base: #334155;
  --bootui-skeleton-shine: #475569;
}

:global(body) {
  background:
    radial-gradient(circle at top left, rgba(25, 135, 84, 0.18), transparent 34rem),
    linear-gradient(135deg, #f6fbf8 0%, #eef6ff 46%, #f7f4ff 100%);
}

:global(:root[data-bootui-theme='dark'] body) {
  background:
    radial-gradient(circle at top left, rgba(52, 208, 104, 0.12), transparent 34rem),
    linear-gradient(135deg, #0d1a12 0%, #0f1929 46%, #100f1a 100%);
}

:global(:root[data-bootui-theme='dark'] .card) {
  background: var(--bootui-surface);
  color: var(--bootui-text);
}

:global(:root[data-bootui-theme='dark'] .table) {
  --bs-table-bg: transparent;
  --bs-table-color: var(--bootui-text);
  --bs-table-border-color: var(--bootui-border-alt);
  --bs-table-hover-bg: rgba(226, 232, 240, 0.04);
  --bs-table-striped-bg: rgba(226, 232, 240, 0.03);
}

:global(:root[data-bootui-theme='dark'] .form-control),
:global(:root[data-bootui-theme='dark'] .form-select) {
  background-color: var(--bootui-surface-alt);
  border-color: var(--bootui-border-alt);
  color: var(--bootui-text);
}

:global(:root[data-bootui-theme='dark'] .form-control::placeholder) {
  color: var(--bootui-text-subtle);
}

:global(:root[data-bootui-theme='dark'] .text-muted) {
  color: var(--bootui-text-muted) !important;
}

:global(:root[data-bootui-theme='dark'] .alert-danger) {
  --bs-alert-bg: rgba(220, 38, 38, 0.15);
  --bs-alert-border-color: rgba(220, 38, 38, 0.3);
  --bs-alert-color: #fca5a5;
}

:global(:root[data-bootui-theme='dark'] .alert-warning) {
  --bs-alert-bg: rgba(245, 158, 11, 0.12);
  --bs-alert-border-color: rgba(245, 158, 11, 0.25);
  --bs-alert-color: #fcd34d;
}

:global(:root[data-bootui-theme='dark'] .alert-info) {
  --bs-alert-bg: rgba(96, 165, 250, 0.1);
  --bs-alert-border-color: rgba(96, 165, 250, 0.2);
  --bs-alert-color: #93c5fd;
}

:global(:root[data-bootui-theme='dark'] .btn-outline-secondary) {
  --bs-btn-color: var(--bootui-text-muted);
  --bs-btn-border-color: var(--bootui-border-alt);
  --bs-btn-hover-bg: rgba(226, 232, 240, 0.08);
  --bs-btn-hover-color: var(--bootui-text);
  --bs-btn-active-bg: rgba(226, 232, 240, 0.15);
}

:global(:root[data-bootui-theme='dark'] .badge.bg-light) {
  background-color: rgba(226, 232, 240, 0.12) !important;
  color: var(--bootui-text-muted) !important;
}

/* The text-bg-light badge variant is a fixed light color; keep it muted in dark mode. */
:global(:root[data-bootui-theme='dark'] .text-bg-light) {
  background-color: rgba(226, 232, 240, 0.12) !important;
  color: var(--bootui-text-muted) !important;
}

/* Bootstrap contextual table variants are not theme-aware; remap them for dark mode. */
:global(:root[data-bootui-theme='dark'] .table-light) {
  --bs-table-bg: var(--bootui-surface-alt);
  --bs-table-color: var(--bootui-text);
  --bs-table-border-color: var(--bootui-border-alt);
}

:global(:root[data-bootui-theme='dark'] .table-warning) {
  --bs-table-color: var(--bootui-text);
  --bs-table-bg: rgba(245, 158, 11, 0.16);
  --bs-table-border-color: rgba(245, 158, 11, 0.28);
  --bs-table-striped-bg: rgba(245, 158, 11, 0.2);
  --bs-table-striped-color: var(--bootui-text);
  --bs-table-active-bg: rgba(245, 158, 11, 0.24);
  --bs-table-active-color: var(--bootui-text);
  --bs-table-hover-bg: rgba(245, 158, 11, 0.22);
  --bs-table-hover-color: var(--bootui-text);
}

:global(:root[data-bootui-theme='dark'] .table-danger) {
  --bs-table-color: var(--bootui-text);
  --bs-table-bg: rgba(220, 38, 38, 0.18);
  --bs-table-border-color: rgba(220, 38, 38, 0.3);
  --bs-table-striped-bg: rgba(220, 38, 38, 0.22);
  --bs-table-striped-color: var(--bootui-text);
  --bs-table-active-bg: rgba(220, 38, 38, 0.26);
  --bs-table-active-color: var(--bootui-text);
  --bs-table-hover-bg: rgba(220, 38, 38, 0.24);
  --bs-table-hover-color: var(--bootui-text);
}

:global(:root[data-bootui-theme='dark'] .table-active) {
  --bs-table-active-bg: rgba(226, 232, 240, 0.1);
  --bs-table-active-color: var(--bootui-text);
}

/* Emphasis text utilities keep their saturated light-mode colors in Bootstrap's
   dark theme; brighten them to the matching dark emphasis tones for contrast. */
:global(:root[data-bootui-theme='dark'] .text-primary) {
  color: rgba(110, 168, 254, var(--bs-text-opacity, 1)) !important;
}

:global(:root[data-bootui-theme='dark'] .text-success) {
  color: rgba(117, 183, 152, var(--bs-text-opacity, 1)) !important;
}

:global(:root[data-bootui-theme='dark'] .text-danger) {
  color: rgba(234, 134, 143, var(--bs-text-opacity, 1)) !important;
}

:global(:root[data-bootui-theme='dark'] .text-info) {
  color: rgba(110, 223, 246, var(--bs-text-opacity, 1)) !important;
}

:global(:root[data-bootui-theme='dark'] .text-warning) {
  color: rgba(255, 218, 106, var(--bs-text-opacity, 1)) !important;
}

/* Bootstrap's saturated semantic text colors (info and warning especially) fail
   WCAG AA as body text on BootUI's light surfaces; darken each toward its own hue
   so themed text clears 4.5:1 in light mode too. Dark mode is handled above. */
:global(:root:not([data-bootui-theme='dark']) .text-primary) {
  color: rgba(10, 83, 190, var(--bs-text-opacity, 1)) !important;
}

:global(:root:not([data-bootui-theme='dark']) .text-success) {
  color: rgba(20, 108, 67, var(--bs-text-opacity, 1)) !important;
}

:global(:root:not([data-bootui-theme='dark']) .text-danger) {
  color: rgba(176, 42, 55, var(--bs-text-opacity, 1)) !important;
}

:global(:root:not([data-bootui-theme='dark']) .text-info) {
  color: rgba(11, 110, 133, var(--bs-text-opacity, 1)) !important;
}

:global(:root:not([data-bootui-theme='dark']) .text-warning) {
  color: rgba(138, 109, 0, var(--bs-text-opacity, 1)) !important;
}

/* Consistent, branded keyboard-focus ring for the custom controls that would
   otherwise fall back to the UA default outline. Visible in both themes via
   --bootui-blue (#0d6efd light / #60a5fa dark). */
.brand-card:focus-visible,
.contribute-card:focus-visible,
.sidebar-toggle:focus-visible,
.bootui-nav-group__toggle:focus-visible,
.nav-hamburger:focus-visible,
.cp-trigger:focus-visible,
.theme-toggle:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: 2px;
}

/* Fixed light surfaces (code snippets, popovers) must darken in dark mode. */
:global(:root[data-bootui-theme='dark'] .bg-light:not(.badge)) {
  background-color: var(--bootui-surface-alt) !important;
  color: var(--bootui-text) !important;
}

:global(:root[data-bootui-theme='dark'] .bg-white) {
  background-color: var(--bootui-surface-solid) !important;
  color: var(--bootui-text) !important;
}

:global(.card) {
  border: 1px solid var(--bootui-border);
  border-radius: 1.1rem;
  box-shadow: var(--bootui-shadow-sm);
  transition: border-color 180ms ease;
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
  color: var(--bootui-text);
  display: flex;
  height: 100vh;
  isolation: isolate;
  overflow: hidden;
  position: relative;
}

.ambient-orb {
  border-radius: 999px;
  filter: blur(55px);
  opacity: 0.4;
  pointer-events: none;
  position: fixed;
  z-index: -1;
}

.ambient-orb-one {
  background: rgba(25, 135, 84, 0.22);
  height: 18rem;
  left: -5rem;
  top: 7rem;
  width: 18rem;
}

.ambient-orb-two {
  background: rgba(13, 110, 253, 0.16);
  bottom: 4rem;
  height: 22rem;
  right: -8rem;
  width: 22rem;
}

.bootui-sidebar {
  backdrop-filter: blur(22px);
  background: var(--bootui-sidebar-bg);
  border-right: 1px solid var(--bootui-border);
  box-shadow: var(--bootui-shadow-sidebar);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  gap: 1.4rem;
  height: 100vh;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 1.25rem;
  transition: width 220ms ease;
  width: 18rem;
}

.bootui-sidebar--drawer {
  position: fixed;
  top: 0;
  left: 0;
  z-index: 1045;
  height: 100vh;
  width: min(20rem, 86vw);
  transform: translateX(-100%);
  transition:
    transform 240ms ease,
    box-shadow 240ms ease;
}

.bootui-sidebar--drawer.bootui-sidebar--open {
  transform: translateX(0);
  box-shadow: 1rem 0 3rem rgba(15, 23, 42, 0.35);
}

.bootui-nav-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1044;
  background: rgba(15, 23, 42, 0.5);
  backdrop-filter: blur(2px);
  animation: fade-in 160ms ease both;
}

.bootui-nav-flyout {
  position: fixed;
  z-index: 1046;
  width: 14rem;
  max-height: calc(100vh - 1rem);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  padding: 0.6rem;
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border);
  border-radius: 1rem;
  box-shadow: var(--bootui-shadow-md);
}

.bootui-nav-flyout__title {
  align-items: center;
  color: var(--bootui-nav-group-color);
  display: flex;
  font-size: 0.72rem;
  font-weight: 800;
  gap: 0.5rem;
  letter-spacing: 0.06em;
  padding: 0.35rem 0.6rem 0.5rem;
  text-transform: uppercase;
}

.bootui-nav-flyout__link {
  border-radius: 0.7rem;
}

.flyout-fade-enter-active,
.flyout-fade-leave-active {
  transition:
    opacity 140ms ease,
    transform 140ms ease;
}

.flyout-fade-enter-from,
.flyout-fade-leave-to {
  opacity: 0;
  transform: translateX(-0.4rem);
}

@keyframes fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.bootui-sidebar--collapsed {
  gap: 1rem;
  padding: 1rem 0.75rem;
  width: 5.25rem;
}

.brand-area {
  align-items: center;
  display: flex;
  gap: 0.5rem;
  justify-content: space-between;
}

.sidebar-toggle {
  background: none;
  border: 1px solid var(--bootui-border);
  border-radius: 0.5rem;
  color: var(--bootui-text-muted);
  cursor: pointer;
  flex-shrink: 0;
  font-size: 0.75rem;
  line-height: 1;
  padding: 0.35rem 0.45rem;
  transition:
    background 150ms ease,
    color 150ms ease;
}

.sidebar-toggle:hover {
  background: var(--bootui-nav-hover-bg);
  color: var(--bootui-green);
}

.bootui-sidebar--collapsed .brand-text,
.bootui-sidebar--collapsed .bootui-nav-link__label,
.bootui-sidebar--collapsed .bootui-nav-group__label span,
.bootui-sidebar--collapsed .bootui-nav-group__count,
.bootui-sidebar--collapsed .bootui-nav-group__chevron,
.bootui-sidebar--collapsed .bootui-nav-link__status,
.bootui-sidebar--collapsed .contribute-card > span:last-child,
.bootui-sidebar--collapsed .sidebar-bottom .alert {
  display: none;
}

.bootui-sidebar--collapsed .brand-area {
  align-items: stretch;
  flex-direction: column;
}

.bootui-sidebar--collapsed .brand-card {
  justify-content: center;
  padding: 0.85rem 0.5rem;
}

.bootui-sidebar--collapsed .sidebar-toggle {
  align-items: center;
  display: inline-flex;
  justify-content: center;
  width: 100%;
}

.bootui-sidebar--collapsed .bootui-nav-group__toggle {
  justify-content: center;
  padding: 0.6rem 0.5rem;
}

.bootui-sidebar--collapsed .bootui-nav-group__label {
  flex: 0;
  justify-content: center;
}

.bootui-sidebar--collapsed .bootui-nav-group__label i {
  font-size: 1.05rem;
}

.bootui-sidebar--collapsed .bootui-nav-section:not(.bootui-nav-section--overview) .bootui-nav-group__items {
  display: none;
}

.bootui-sidebar--collapsed .bootui-nav-link {
  justify-content: center;
  padding: 0.6rem 0.5rem;
}

.bootui-sidebar--collapsed .contribute-card {
  justify-content: center;
  padding: 0.7rem 0.5rem;
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
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
}

.sidebar-nav {
  gap: 0.45rem;
}

.eyebrow {
  color: var(--bootui-text-muted);
  font-size: 0.7rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  margin-bottom: 0.55rem;
  text-transform: uppercase;
}

.bootui-nav-link {
  align-items: center;
  color: var(--bootui-nav-link-color);
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
  border-left: 1px solid var(--bootui-border-alt);
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
  background: var(--bootui-nav-group-bg);
  border: 1px solid var(--bootui-border-subtle);
  border-radius: 0.9rem;
  color: var(--bootui-nav-group-color);
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
  background: var(--bootui-nav-hover-bg);
  border-color: rgba(25, 135, 84, 0.18);
  color: var(--bootui-nav-hover-color);
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
  color: var(--bootui-text-muted);
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
  color: var(--bootui-text-subtle);
  opacity: 0.72;
}

.bootui-nav-section--unavailable .bootui-nav-group__count {
  background: rgba(148, 163, 184, 0.12);
  color: var(--bootui-text-subtle);
}

.bootui-nav-link:hover {
  background: var(--bootui-nav-hover-bg);
  color: var(--bootui-nav-hover-color);
  transform: translateX(3px);
}

.bootui-nav-link.active {
  background: var(--bootui-nav-active-bg);
  box-shadow: 0 0.8rem 1.4rem rgba(25, 135, 84, 0.2);
  color: var(--bootui-nav-active-color);
}

.bootui-nav-link i {
  font-size: 1.05rem;
}

.bootui-nav-link__label {
  flex: 1;
}

.bootui-nav-link__status {
  color: var(--bootui-text-subtle);
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
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
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
  height: 100vh;
  min-width: 0;
  overflow-y: auto;
}

.topbar {
  align-items: center;
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  padding: 1.5rem 2rem 1rem;
}

.topbar-lead {
  align-items: center;
  display: flex;
  gap: 0.85rem;
  min-width: 0;
}

.topbar-heading {
  min-width: 0;
}

.nav-hamburger {
  align-items: center;
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
  border-radius: 0.75rem;
  color: var(--bootui-text);
  cursor: pointer;
  display: none;
  flex-shrink: 0;
  font-size: 1.2rem;
  height: 2.6rem;
  justify-content: center;
  transition: background 150ms ease;
  width: 2.6rem;
}

.nav-hamburger:hover {
  background: var(--bootui-nav-hover-bg);
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
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
  border-radius: 999px;
  box-shadow: 0 0.5rem 1.2rem rgba(15, 23, 42, 0.06);
  display: inline-flex;
  font-size: 0.82rem;
  font-weight: 700;
  gap: 0.35rem;
  padding: 0.45rem 0.75rem;
}

.status-pill--active {
  background: rgba(25, 135, 84, 0.12);
  border-color: rgba(25, 135, 84, 0.22);
  color: var(--bootui-green-dark);
}

.status-pill--disabled,
.status-pill--checking {
  background: rgba(100, 116, 139, 0.1);
  color: var(--bootui-text-muted);
}

.status-pill--error,
.status-pill--unreachable {
  background: rgba(220, 53, 69, 0.1);
  border-color: rgba(220, 53, 69, 0.25);
  color: #b02a37;
}

.shell-error {
  display: grid;
  gap: 0.35rem;
}

.shell-error__title {
  align-items: center;
  display: flex;
  gap: 0.4rem;
}

.profile-stack {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.profile-chip {
  background: rgba(25, 135, 84, 0.1);
  color: var(--bootui-green-dark);
}

.profile-chip.muted {
  background: rgba(100, 116, 139, 0.1);
  color: var(--bootui-text-muted);
}

.content-stage {
  flex: 1;
  padding: 0 2rem 1.5rem;
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
  color: var(--bootui-text-muted);
  font-size: 0.82rem;
  padding: 0 2rem 1.25rem;
  text-align: center;
}

.bootui-footer a {
  color: inherit;
}

.bootui-footer a:hover {
  color: var(--bootui-green-dark);
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

@media (max-width: 991.98px) {
  .nav-hamburger {
    display: inline-flex;
  }

  .cp-trigger-label,
  .cp-trigger-hint,
  .theme-toggle__label {
    display: none;
  }

  .topbar {
    padding: 1.1rem 1.25rem 0.85rem;
  }

  .content-stage,
  .bootui-footer {
    padding-left: 1.25rem;
    padding-right: 1.25rem;
  }
}

@media (max-width: 575.98px) {
  .topbar {
    padding-left: 1rem;
    padding-right: 1rem;
  }

  .content-stage,
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

.cp-trigger,
.theme-toggle {
  align-items: center;
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
  border-radius: 999px;
  color: var(--bootui-text-muted);
  cursor: pointer;
  display: inline-flex;
  font-size: 0.82rem;
  font-weight: 600;
  gap: 0.35rem;
  padding: 0.45rem 0.75rem;
  transition:
    background 150ms ease,
    color 150ms ease;
}

.cp-trigger:hover,
.theme-toggle:hover {
  background: var(--bootui-nav-hover-bg);
  color: var(--bootui-text);
}

.cp-trigger-hint {
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border-alt);
  border-radius: 0.3rem;
  font-size: 0.7rem;
  padding: 0.1rem 0.35rem;
}
</style>
