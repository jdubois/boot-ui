<script setup>
import {apiFetch} from './api.js'
import {computed, nextTick, onBeforeUnmount, onMounted, provide, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {
  applyTheme,
  nextTheme,
  normalizeThemePreference,
  readThemePreference,
  resolveTheme,
  THEME_QUERY,
  THEME_STORAGE_KEY
} from './utils/theme.js'
import {describeLoadError} from './utils/loadError.js'
import {
  buildDocumentTitle,
  createPanelLookup,
  panelDisabledReason,
  resolveRouteTitle,
  routeAvailabilityLabel as routeAccessibleLabel,
  routeStatusIcon as panelStatusIcon,
  routeUnavailable as isRouteUnavailable
} from './utils/panelNavigation.js'
import {recordRecentPanel} from './utils/recentPanels.js'
import {safeLocalStorage} from './utils/safeStorage.js'
import CommandPalette from './views/components/CommandPalette.vue'
import ConfirmDialog from './views/components/ConfirmDialog.vue'

const router = useRouter()
const route = useRoute()
const overview = ref(null)
const panels = ref(null)
const shellError = ref(null)
const authenticationRequired = ref(false)
const authenticationToken = ref('')
const authenticationError = ref(null)
const authenticating = ref(false)
const bearerScheme = 'Bearer'
const SIDEBAR_COLLAPSED_STORAGE_KEY = 'bootui.sidebar.collapsed'
const sidebarCollapsed = ref(safeLocalStorage.getBoolean(SIDEBAR_COLLAPSED_STORAGE_KEY))

const NARROW_QUERY = '(max-width: 991.98px)'
const narrowMediaQuery =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(NARROW_QUERY) : null
const isNarrow = ref(narrowMediaQuery?.matches === true)
const mobileNavOpen = ref(false)
const mobileNavRef = ref(null)
const mobileNavCloseRef = ref(null)
const mobileNavToggleRef = ref(null)
const mainContentRef = ref(null)
const mobileDrawerOpen = computed(() => isNarrow.value && mobileNavOpen.value)
const mobileDrawerClosed = computed(() => isNarrow.value && !mobileNavOpen.value)
const mobileNavToggleLabel = computed(() => (mobileDrawerOpen.value ? 'Close navigation menu' : 'Open navigation menu'))
let mobileNavInvoker = null

async function onNarrowChange(e) {
  const becomingNarrow = e.matches === true
  const activeElement = document.activeElement
  const focusWasInSidebar =
    becomingNarrow && activeElement instanceof HTMLElement && mobileNavRef.value?.contains(activeElement)
  if (focusWasInSidebar) activeElement.blur()

  isNarrow.value = becomingNarrow
  if (!becomingNarrow) {
    mobileNavOpen.value = false
    mobileNavInvoker = null
  } else if (focusWasInSidebar) {
    await nextTick()
    mobileNavToggleRef.value?.focus()
  }
}

const commandPaletteOpen = ref(false)
const commandPaletteRef = ref(null)
const commandPaletteTriggerRef = ref(null)
let commandPaletteInvoker = null
const themeMediaQuery =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(THEME_QUERY) : null
const themePreference = ref(readThemePreference())
const systemPrefersDark = ref(themeMediaQuery?.matches === true)
const resolvedTheme = computed(() => resolveTheme(themePreference.value, systemPrefersDark.value))
const darkTheme = computed(() => resolvedTheme.value === 'dark')
const themeToggleLabel = computed(() => `Switch to ${darkTheme.value ? 'light' : 'dark'} mode`)
const themeToggleText = computed(() => `${darkTheme.value ? 'Light' : 'Dark'} mode`)

provide('overview', overview)
provide('panels', panels)
provide('openCommandPalette', openCommandPalette)

async function openCommandPalette(event) {
  if (commandPaletteOpen.value || mobileDrawerOpen.value) return
  const eventTarget = event?.currentTarget
  const activeElement = typeof document === 'undefined' ? null : document.activeElement
  commandPaletteInvoker =
    eventTarget instanceof HTMLElement
      ? eventTarget
      : activeElement instanceof HTMLElement
        ? activeElement
        : commandPaletteTriggerRef.value
  commandPaletteOpen.value = true
  await nextTick()
  commandPaletteRef.value?.focusInput()
}

async function closeCommandPalette(focusTarget = 'invoker') {
  if (!commandPaletteOpen.value) return
  commandPaletteOpen.value = false
  await nextTick()
  const target =
    focusTarget === 'content'
      ? mainContentRef.value
      : commandPaletteInvoker?.isConnected
        ? commandPaletteInvoker
        : commandPaletteTriggerRef.value
  target?.focus()
  commandPaletteInvoker = null
}

watch(sidebarCollapsed, (value) => safeLocalStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, value))

watch(
  () => route.name,
  async (name) => {
    if (name) recordRecentPanel(name)
    if (mobileDrawerOpen.value) {
      await closeMobileNav('content')
    }
  },
  {immediate: true}
)

watch(resolvedTheme, syncTheme, {immediate: true})

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function onSidebarToggle() {
  if (isNarrow.value) {
    dismissMobileNav()
  } else {
    toggleSidebar()
  }
}

async function openMobileNav() {
  if (!isNarrow.value || mobileNavOpen.value) return
  mobileNavInvoker = mobileNavToggleRef.value
  mobileNavOpen.value = true
  await nextTick()
  mobileNavCloseRef.value?.focus()
}

async function closeMobileNav(focusTarget = 'toggle') {
  if (!mobileDrawerOpen.value) return
  const activeElement = document.activeElement
  if (activeElement instanceof HTMLElement && mobileNavRef.value?.contains(activeElement)) {
    activeElement.blur()
  }
  mobileNavOpen.value = false
  await nextTick()
  const target =
    focusTarget === 'content'
      ? mainContentRef.value
      : mobileNavInvoker?.isConnected
        ? mobileNavInvoker
        : mobileNavToggleRef.value
  target?.focus()
  mobileNavInvoker = null
}

function dismissMobileNav() {
  closeMobileNav('toggle')
}

async function onSidebarLinkClick(navigate, event) {
  const navigatingFromMobileDrawer = mobileDrawerOpen.value
  await navigate(event)
  if (navigatingFromMobileDrawer && mobileDrawerOpen.value) {
    await closeMobileNav('content')
  }
}

function onMobileNavKeydown(event) {
  if (!mobileDrawerOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    dismissMobileNav()
    return
  }
  if (event.key !== 'Tab') return

  const focusable = mobileNavRef.value?.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )
  if (!focusable?.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || active === mobileNavRef.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

function syncTheme(theme) {
  if (typeof document !== 'undefined') {
    applyTheme(document.documentElement, theme)
  }
}

function persistThemePreference(theme) {
  return safeLocalStorage.setItem(THEME_STORAGE_KEY, theme)
}

function toggleTheme() {
  const theme = nextTheme(resolvedTheme.value)
  themePreference.value = theme
  persistThemePreference(theme)
}

function onSystemThemeChange(e) {
  systemPrefersDark.value = e.matches === true
}

function onStorageChange(event) {
  if (event.key === THEME_STORAGE_KEY || event.key === null) {
    themePreference.value = normalizeThemePreference(event.newValue)
  }
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
  const stored = safeLocalStorage.getJson(EXPANDED_GROUPS_STORAGE_KEY, null)
  if (!stored || Array.isArray(stored) || typeof stored !== 'object') {
    if (stored !== null) safeLocalStorage.removeItem(EXPANDED_GROUPS_STORAGE_KEY)
    return defaults
  }
  const validGroupKeys = new Set([
    ...semanticNavigationGroups.map((group) => group.key),
    unavailableNavigationGroup.key
  ])
  const expanded = Object.fromEntries(
    Object.entries(stored).filter(([key, value]) => validGroupKeys.has(key) && typeof value === 'boolean')
  )
  return {...defaults, ...expanded}
}

const expandedGroups = reactive(loadExpandedGroups())
const panelLookup = computed(() => createPanelLookup(panels.value))
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
const applicationTitle = computed(() => overview.value?.applicationName || 'application')
const browserTitle = computed(() => buildDocumentTitle(route, panels.value?.platform, overview.value?.applicationName))
const runtimeSummary = computed(() => {
  if (shellServerUnreachable.value) return 'The application is not responding. Restart it and retry.'
  if (shellError.value && !overview.value) return 'Unable to load BootUI runtime details.'
  if (!overview.value) return 'Loading runtime details'
  const framework = [overview.value.frameworkName, overview.value.frameworkVersion].filter(Boolean).join(' ')
  return framework ? `${framework} · Java ${overview.value.javaVersion}` : `Java ${overview.value.javaVersion}`
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
      unreachable: 'BootUI cannot reach the application. It may have been stopped.'
    })[connectionState.value]
)
const statusPillClass = computed(() => `status-pill--${connectionState.value}`)
const frameworkLabel = computed(() => {
  const platform = panels.value?.platform
  if (platform === 'quarkus') return 'Quarkus'
  if (platform === 'spring-boot') return 'Spring Boot'
  return null
})
const githubProjectUrl = 'https://github.com/jdubois/boot-ui'
function navTitle(r) {
  return resolveRouteTitle(r, panels.value?.platform)
}
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
  const res = await apiFetch('api/overview')
  if (!res.ok) throw httpError(res.status)
  overview.value = await res.json()
}

async function loadPanels() {
  const res = await apiFetch('api/panels')
  if (!res.ok) throw httpError(res.status)
  panels.value = await res.json()
}

function httpError(status) {
  return Object.assign(new Error(`HTTP ${status}`), {status})
}

function isUnauthorized(result) {
  return result.status === 'rejected' && result.reason?.status === 401
}

async function loadShellData() {
  const results = await Promise.allSettled([loadOverview(), loadPanels()])
  if (results.some(isUnauthorized)) {
    authenticationRequired.value = true
    shellError.value = null
    return
  }

  const failures = [
    {result: results[0], context: 'Unable to load overview'},
    {result: results[1], context: 'Unable to load panel availability'}
  ].filter(({result}) => result.status === 'rejected')

  if (!failures.length) {
    authenticationRequired.value = false
    shellError.value = null
    return
  }

  const descriptions = failures.map(({result, context}) =>
    describeLoadError(/** @type {PromiseRejectedResult} */ (result).reason, context)
  )
  shellError.value = descriptions.find((description) => description.serverUnreachable) || descriptions[0]
}

async function authenticate() {
  if (!authenticationToken.value || authenticating.value) return
  authenticating.value = true
  authenticationError.value = null
  try {
    const response = await apiFetch('api/auth/session', {
      method: 'POST',
      headers: {Authorization: `${bearerScheme} ${authenticationToken.value}`}
    })
    if (!response.ok) {
      authenticationError.value =
        response.status === 401
          ? 'That token was not accepted. Check the application startup log.'
          : `HTTP ${response.status}`
      return
    }
    authenticationToken.value = ''
    authenticationRequired.value = false
    await loadShellData()
  } catch {
    authenticationError.value = 'The application is not responding. Restart it and retry.'
  } finally {
    authenticating.value = false
  }
}

function routeUnavailable(r) {
  return isRouteUnavailable(r, panelLookup.value)
}

function routeStatusIcon(r) {
  return panelStatusIcon(r, panelLookup.value)
}

function routeAvailabilityLabel(r) {
  return routeAccessibleLabel(r, panelLookup.value, panels.value?.platform)
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
    safeLocalStorage.setJson(EXPANDED_GROUPS_STORAGE_KEY, groups)
  },
  {deep: true}
)

watch(
  browserTitle,
  (title) => {
    document.title = title
  },
  {immediate: true}
)

onMounted(() => {
  loadShellData()
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('storage', onStorageChange)
  themeMediaQuery?.addEventListener?.('change', onSystemThemeChange)
  narrowMediaQuery?.addEventListener?.('change', onNarrowChange)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('storage', onStorageChange)
  themeMediaQuery?.removeEventListener?.('change', onSystemThemeChange)
  narrowMediaQuery?.removeEventListener?.('change', onNarrowChange)
  clearFlyoutTimer()
})

function onGlobalKeydown(e) {
  if (mobileDrawerOpen.value) {
    if (e.key === 'Escape') {
      e.preventDefault()
      dismissMobileNav()
    }
    return
  }
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (commandPaletteOpen.value) {
      closeCommandPalette()
    } else {
      openCommandPalette()
    }
  }
}
</script>

<template>
  <div class="bootui-shell min-vh-100">
    <CommandPalette v-if="commandPaletteOpen" ref="commandPaletteRef" @close="closeCommandPalette" />
    <ConfirmDialog />
    <div class="ambient-orb ambient-orb-one"></div>
    <div class="ambient-orb ambient-orb-two"></div>

    <div v-if="mobileDrawerOpen" aria-hidden="true" class="bootui-nav-backdrop" @click="dismissMobileNav"></div>

    <section
      v-if="authenticationRequired"
      :inert="commandPaletteOpen ? true : undefined"
      class="authentication-gate"
      aria-labelledby="authentication-title"
    >
      <div class="authentication-card">
        <span class="authentication-icon"><i class="bi bi-shield-lock"></i></span>
        <div>
          <h1 id="authentication-title">Unlock BootUI</h1>
          <p>
            This API requires authentication outside localhost. Copy the bearer token from the application startup log.
          </p>
        </div>
        <form @submit.prevent="authenticate">
          <label class="form-label" for="bootui-authentication-token">Access token</label>
          <input
            id="bootui-authentication-token"
            v-model="authenticationToken"
            autocomplete="off"
            autofocus
            class="form-control"
            type="password"
          />
          <div v-if="authenticationError" class="authentication-error" role="alert">{{ authenticationError }}</div>
          <button :disabled="!authenticationToken || authenticating" class="btn authentication-submit" type="submit">
            <span v-if="authenticating" aria-hidden="true" class="spinner-border spinner-border-sm"></span>
            {{ authenticating ? 'Unlocking…' : 'Unlock console' }}
          </button>
        </form>
      </div>
    </section>

    <template v-else>
      <aside
        id="bootui-mobile-navigation"
        ref="mobileNavRef"
        :aria-hidden="mobileDrawerClosed ? 'true' : undefined"
        :aria-label="isNarrow ? 'Navigation menu' : undefined"
        :aria-modal="mobileDrawerOpen ? 'true' : undefined"
        :class="{
          'bootui-sidebar--collapsed': collapsedRail,
          'bootui-sidebar--drawer': isNarrow,
          'bootui-sidebar--open': mobileDrawerOpen
        }"
        :inert="commandPaletteOpen || mobileDrawerClosed ? true : undefined"
        :role="isNarrow ? 'dialog' : undefined"
        class="bootui-sidebar"
        @keydown="onMobileNavKeydown"
      >
        <div class="brand-area">
          <router-link v-slot="{href, navigate}" custom to="/overview">
            <a :href="href" class="brand-card text-decoration-none" @click="onSidebarLinkClick(navigate, $event)">
              <span class="brand-mark"><i class="bi bi-cup-hot-fill"></i></span>
              <span class="brand-text">
                <span class="brand-name">BootUI</span>
                <span class="brand-subtitle">Local developer console</span>
              </span>
            </a>
          </router-link>
          <button
            ref="mobileNavCloseRef"
            :aria-label="isNarrow ? 'Close navigation menu' : sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'"
            class="sidebar-toggle"
            :title="isNarrow ? 'Close menu' : sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'"
            type="button"
            @click="onSidebarToggle"
          >
            <i
              :class="isNarrow ? 'bi-x-lg' : sidebarCollapsed ? 'bi-chevron-double-right' : 'bi-chevron-double-left'"
              aria-hidden="true"
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
                  @click="onSidebarLinkClick(navigate, $event)"
                >
                  <i :class="['bi', r.meta.icon]"></i>
                  <span class="bootui-nav-link__label">{{ navTitle(r) }}</span>
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
          :inert="commandPaletteOpen ? true : undefined"
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
          <router-link
            v-for="r in railFlyout.section.routes"
            :key="r.name"
            v-slot="{href, navigate}"
            :to="r.path"
            custom
          >
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
              <span class="bootui-nav-link__label">{{ navTitle(r) }}</span>
              <i
                v-if="routeStatusIcon(r)"
                :class="['bi', routeStatusIcon(r), 'bootui-nav-link__status']"
                aria-hidden="true"
              ></i>
            </a>
          </router-link>
        </div>
      </transition>

      <div :inert="commandPaletteOpen || mobileDrawerOpen ? true : undefined" class="bootui-workspace">
        <header class="topbar">
          <div class="topbar-lead">
            <button
              ref="mobileNavToggleRef"
              :aria-expanded="mobileDrawerOpen"
              :aria-label="mobileNavToggleLabel"
              aria-controls="bootui-mobile-navigation"
              class="nav-hamburger"
              type="button"
              @click="openMobileNav"
            >
              <i aria-hidden="true" class="bi bi-list"></i>
            </button>
            <div class="topbar-heading">
              <h1 class="topbar-title">{{ applicationTitle }}</h1>
              <p class="topbar-subtitle mb-0">{{ runtimeSummary }}</p>
            </div>
          </div>
          <div class="topbar-actions">
            <button
              ref="commandPaletteTriggerRef"
              class="cp-trigger"
              title="Open command palette (⌘K)"
              type="button"
              @click="openCommandPalette"
            >
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

        <main ref="mainContentRef" class="content-stage" tabindex="-1">
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
          <div
            v-if="activePanelUnavailable"
            class="alert alert-warning panel-availability-alert shadow-sm"
            role="status"
          >
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
          <span class="bootui-footer__context">
            <i aria-hidden="true" class="bi bi-shield-check"></i>
            <span class="bootui-footer__context-copy">
              Local-only developer console
              <span v-if="frameworkLabel" aria-hidden="true" class="bootui-footer__separator">·</span>
              <span v-if="frameworkLabel">{{ frameworkLabel }}</span>
            </span>
          </span>
          <a :href="githubProjectUrl" rel="noopener noreferrer" target="_blank">
            <i aria-hidden="true" class="bi bi-github"></i>
            View BootUI on GitHub
            <i aria-hidden="true" class="bi bi-box-arrow-up-right bootui-footer__external"></i>
          </a>
        </footer>
      </div>
    </template>
  </div>
</template>

<style scoped>
:global(:root) {
  /* Brand palette */
  --bootui-green: #198754;
  --bootui-green-dark: #146c43;
  --bootui-blue: #0d6efd;
  --bootui-text: #152033;
  --bootui-text-muted: #56667b;
  --bootui-text-subtle: #5b6b80;

  /* Status / severity palette (consistent meaning across light & dark) */
  --bootui-danger: #dc3545;
  --bootui-danger-text: #b02a37;
  --bootui-warning: #ffc107;
  --bootui-warning-text: #997404;
  --bootui-warning-text-strong: #6f5300;
  --bootui-high: #fd7e14;
  --bootui-critical: #b00020;
  --bootui-info: #0dcaf0;
  --bootui-info-text: #087990;
  --bootui-secondary: #6c757d;
  --bootui-heat-low-bg: #ffe69c;
  --bootui-heat-low-text: #664d03;

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

  /* Radius scale (mirrors DESIGN.md rounded) */
  --bootui-radius-xs: 0.35rem;
  --bootui-radius-sm: 0.5rem;
  --bootui-radius-md: 0.75rem;
  --bootui-radius-lg: 1.1rem;
  --bootui-radius-xl: 1.25rem;
  --bootui-radius-pill: 999px;

  /* Nav link state */
  --bootui-nav-hover-bg: rgba(25, 135, 84, 0.08);
  --bootui-nav-hover-color: #146c43;
  --bootui-nav-active-bg: linear-gradient(135deg, #198754, #0d6efd);
  --bootui-nav-active-color: #ffffff;
  --bootui-nav-group-bg: rgba(255, 255, 255, 0.58);
  --bootui-nav-group-color: var(--bootui-text-muted);
  --bootui-nav-link-color: #334155;

  /* Data visualization */
  --bootui-chart-grid: #dee2e6;
  --bootui-chart-axis: #56667b;
  --bootui-chart-input: #0d6efd;
  --bootui-chart-output: #6610f2;
  --bootui-chart-calls: #198754;
  --bootui-chart-selection: #64748b;
  --bootui-chart-span: #dee2e6;
  --bootui-chart-tool: #0d6efd;
  --bootui-chart-vector: #fd7e14;
  --bootui-chart-tooltip-bg: #ffffff;
  --bootui-chart-tooltip-border: #cbd5e1;
  --bootui-chart-tooltip-text: #152033;

  /* Skeleton loaders */
  --bootui-skeleton-base: #e2e8f0;
  --bootui-skeleton-shine: #f1f5f9;
}

.authentication-gate {
  align-items: center;
  display: grid;
  inset: 0;
  justify-items: center;
  padding: 2rem;
  position: fixed;
  z-index: 20;
}

.authentication-card {
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
  border-radius: 1rem;
  box-shadow: var(--bootui-shadow-md);
  display: grid;
  gap: 1.25rem;
  max-width: 31rem;
  padding: 2rem;
  width: 100%;
}

.authentication-card h1 {
  color: var(--bootui-text);
  font-size: 1.75rem;
  margin: 0.25rem 0 0.75rem;
}

.authentication-card p {
  color: var(--bootui-text-muted);
  margin: 0;
}

.authentication-icon {
  align-items: center;
  background: var(--bootui-nav-hover-bg);
  border-radius: 0.85rem;
  color: var(--bootui-green);
  display: inline-flex;
  font-size: 1.5rem;
  height: 3rem;
  justify-content: center;
  width: 3rem;
}

.authentication-error {
  color: var(--bootui-danger-text);
  font-size: 0.875rem;
  margin-top: 0.5rem;
}

.authentication-submit {
  background: var(--bootui-green);
  color: #fff;
  margin-top: 1rem;
  width: 100%;
}

.authentication-submit:hover,
.authentication-submit:focus-visible {
  background: var(--bootui-green-dark);
  color: #fff;
}

:global(:root[data-bootui-theme='dark']) {
  /* Brand palette — dark mode */
  --bootui-green: #34d068;
  --bootui-green-dark: #4ade80;
  --bootui-blue: #60a5fa;
  --bootui-text: #e2e8f0;
  --bootui-text-muted: #a3b1c6;
  --bootui-text-subtle: #94a3b8;

  /* Status text re-lit for dark-surface contrast (see Semantic Status) */
  --bootui-warning-text-strong: #e0a800;

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
  --bootui-nav-group-color: var(--bootui-text-muted);
  --bootui-nav-link-color: #cbd5e1;

  /* Data visualization */
  --bootui-chart-grid: #475569;
  --bootui-chart-axis: #a3b1c6;
  --bootui-chart-input: #6ea8fe;
  --bootui-chart-output: #c084fc;
  --bootui-chart-calls: #75b798;
  --bootui-chart-selection: #94a3b8;
  --bootui-chart-span: #475569;
  --bootui-chart-tool: #6ea8fe;
  --bootui-chart-vector: #fd9843;
  --bootui-chart-tooltip-bg: #1e293b;
  --bootui-chart-tooltip-border: #64748b;
  --bootui-chart-tooltip-text: #e2e8f0;

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

:global(.form-control::placeholder) {
  color: var(--bootui-text-subtle);
  opacity: 1;
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
.theme-toggle:focus-visible,
:global(.bootui-keyboard-target:focus-visible) {
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
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
  transition: border-color 180ms ease;
}

:global(.btn),
:global(.badge),
:global(.alert),
:global(.form-control),
:global(.form-select) {
  border-radius: var(--bootui-radius-md);
}

:global(.progress) {
  border-radius: var(--bootui-radius-pill);
  overflow: hidden;
}

:global(.progress-bar) {
  /* impeccable-disable-next-line layout-transition -- progress fill animates width by design */
  transition: width 500ms ease;
}

:global(.bootui-table-scroll) {
  max-width: 100%;
  overscroll-behavior-inline: contain;
  -webkit-overflow-scrolling: touch;
}

:global(.bootui-data-table) {
  min-width: var(--bootui-table-min-width, 42rem);
}

:global(.bootui-break-anywhere) {
  overflow-wrap: anywhere;
  white-space: normal;
  word-break: break-word;
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
  overflow: hidden;
  overscroll-behavior: contain;
  padding: 1.25rem;
  /* impeccable-disable-next-line layout-transition -- collapsible sidebar/drawer animates width by design */
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
  border-radius: var(--bootui-radius-lg);
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
  border-radius: var(--bootui-radius-md);
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
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
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
  border-radius: var(--bootui-radius-lg);
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
  flex: 1;
  flex-wrap: nowrap;
  gap: 0.45rem;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0.15rem 0.25rem 0.75rem 0;
  scrollbar-color: var(--bootui-border-alt) transparent;
  scrollbar-width: thin;
}

.bootui-nav-link {
  align-items: center;
  border-radius: var(--bootui-radius-md);
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
  flex-shrink: 0;
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
  border-radius: var(--bootui-radius-md);
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
}

.bootui-nav-link--unavailable {
  color: var(--bootui-text-subtle);
}

.bootui-nav-link.active .bootui-nav-link__status {
  color: inherit;
}

.bootui-nav-link--unavailable .bootui-nav-link__label {
  font-style: italic;
}

.sidebar-bottom {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
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
  overflow-x: hidden;
  overflow-y: auto;
}

.topbar {
  align-items: center;
  backdrop-filter: blur(18px);
  background: var(--bootui-sidebar-bg);
  border-bottom: 1px solid var(--bootui-border-subtle);
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  padding: 1.25rem 2rem;
  position: sticky;
  top: 0;
  z-index: 10;
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
  color: var(--bootui-danger-text);
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
  align-items: center;
  border-top: 1px solid var(--bootui-border-subtle);
  color: var(--bootui-text-muted);
  display: flex;
  font-size: 0.82rem;
  gap: 1rem;
  justify-content: space-between;
  margin: 0 2rem;
  padding: 1rem 0 1.25rem;
}

.bootui-footer a {
  align-items: center;
  color: inherit;
  display: inline-flex;
  gap: 0.4rem;
  text-decoration: none;
}

.bootui-footer a:hover {
  color: var(--bootui-green-dark);
}

.bootui-footer__context {
  align-items: center;
  display: inline-flex;
  gap: 0.4rem;
}

.bootui-footer__context > .bi {
  color: var(--bootui-green-dark);
}

.bootui-footer__context-copy {
  min-width: 0;
}

.bootui-footer__separator {
  color: var(--bootui-text-subtle);
  margin-inline: 0.1rem;
}

.bootui-footer__external {
  font-size: 0.7rem;
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

  .bootui-footer {
    margin-left: 0;
    margin-right: 0;
  }
}

@media (max-width: 575.98px) {
  .topbar {
    align-items: stretch;
    flex-direction: column;
    padding-left: 1rem;
    padding-right: 1rem;
    position: static;
  }

  .topbar-actions {
    justify-content: flex-start;
  }

  .topbar-title,
  .topbar-subtitle {
    overflow-wrap: anywhere;
  }

  .content-stage,
  .bootui-footer {
    padding-left: 1rem;
    padding-right: 1rem;
  }

  .bootui-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 0.65rem;
  }

  :global(button),
  :global(a.btn),
  :global(summary),
  :global(.form-control-sm),
  :global(.form-select-sm) {
    min-block-size: 44px;
    min-inline-size: 44px;
  }

  :global(.form-check-input) {
    min-block-size: 44px;
    min-inline-size: 44px;
    margin-top: 0;
  }

  :global(.form-check) {
    min-block-size: 44px;
  }
}

@media (prefers-reduced-motion: reduce) {
  :global(html) {
    scroll-behavior: auto !important;
  }

  .bootui-sidebar,
  .bootui-sidebar--drawer,
  .bootui-nav-backdrop,
  .flyout-fade-enter-active,
  .flyout-fade-leave-active,
  .page-slide-enter-active,
  .page-slide-leave-active,
  .brand-card,
  .contribute-card,
  .bootui-nav-link,
  .bootui-nav-group__toggle,
  .sidebar-toggle,
  .nav-hamburger,
  .cp-trigger,
  .theme-toggle,
  :global(.card),
  :global(.btn),
  :global(.progress-bar),
  :global(.spinner-border),
  :global(.spinner-grow),
  :global(.spin) {
    animation: none !important;
    transition: none !important;
  }

  .page-slide-enter-from,
  .page-slide-leave-to,
  .flyout-fade-enter-from,
  .flyout-fade-leave-to,
  .brand-card:hover,
  .contribute-card:hover,
  .bootui-nav-link:hover,
  .bootui-nav-group__toggle:hover,
  .bootui-nav-group__toggle.active {
    transform: none;
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
  border-radius: var(--bootui-radius-xs);
  font-size: 0.7rem;
  padding: 0.1rem 0.35rem;
}
</style>
