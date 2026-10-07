<script setup>
import {computed, onBeforeUnmount, ref} from 'vue'
import {getJson} from '../../api.js'
import {
  impactLists,
  isImpact,
  isSymbols,
  methodStatusText,
  nodeParts,
  routeTraffic,
  symbolOption
} from '../../utils/changeImpact.js'
import {formatNumber} from '../../utils/format.js'
import {formatLoadError} from '../../utils/loadError.js'
import InsightText from './InsightText.vue'

// An agent or a link can open the panel on a symbol; nothing is read until a symbol is asked for or typed. The methods
// the run comparison found changed are offered as the first things to check, and the panel's "See its impact" calls
// check() directly.
const props = defineProps({
  initialSymbol: {type: String, default: ''},
  changed: {type: /** @type {import('vue').PropType<{symbol: string, name: string}[]>} */ (Array), default: () => []},
  // Every method changed or added since the previous run, including those not offered or not listed by the comparison.
  changedTotal: {type: Number, default: 0}
})

const CHANGED_SHOWN = 6

const SUGGEST_DELAY_MS = 150
const listboxId = 'insight-impact-suggestions'
const hintId = 'insight-impact-hint'

const symbol = ref(props.initialSymbol)
const impact = ref(null)
const error = ref(null)
const loading = ref(false)

const suggestions = ref(null)
const suggestionError = ref(null)
const suggestionsOpen = ref(false)
const activeIndex = ref(-1)
let suggestTimer = null
let suggestSequence = 0
// A check started from the comparison can overlap one still in flight; only the latest may write its answer.
let checkSequence = 0

const options = computed(() => (suggestions.value?.symbols ?? []).map(symbolOption))
const hiddenMatches = computed(() => Math.max(0, (suggestions.value?.total ?? 0) - options.value.length))
const popupVisible = computed(
  () => suggestionsOpen.value && Boolean(suggestionError.value || suggestions.value) && symbol.value.trim() !== ''
)
const listVisible = computed(() => popupVisible.value && options.value.length > 0)
const activeOptionId = computed(() =>
  listVisible.value && activeIndex.value >= 0 ? `insight-impact-option-${activeIndex.value}` : undefined
)
const suggestionStatus = computed(() => {
  if (!popupVisible.value) return ''
  if (suggestionError.value) return suggestionError.value
  if (!suggestions.value.available) return suggestions.value.unavailableReason ?? 'Suggestions are unavailable.'
  if (options.value.length === 0) return `Nothing in this run's model matches “${suggestions.value.query}”.`
  const shown = `${formatNumber(options.value.length)} ${options.value.length === 1 ? 'suggestion' : 'suggestions'}`
  return hiddenMatches.value > 0 ? `${shown}, ${formatNumber(hiddenMatches.value)} more: keep typing` : shown
})

async function check(value = symbol.value, shown = value) {
  const asked = (value ?? '').trim()
  if (!asked) return
  closeSuggestions()
  symbol.value = (shown ?? asked).trim()
  const sequence = ++checkSequence
  loading.value = true
  error.value = null
  try {
    const result = await getJson(`api/runtime-insights/impact?symbol=${encodeURIComponent(asked)}`)
    if (sequence !== checkSequence) return
    impact.value = isImpact(result) ? result : null
  } catch (e) {
    if (sequence !== checkSequence) return
    impact.value = null
    error.value = formatLoadError(e, 'Unable to read the change impact')
  } finally {
    if (sequence === checkSequence) loading.value = false
  }
}

function onInput() {
  clearTimeout(suggestTimer)
  const query = symbol.value.trim()
  if (!query) {
    closeSuggestions()
    return
  }
  suggestTimer = setTimeout(() => suggest(query), SUGGEST_DELAY_MS)
}

async function suggest(query) {
  const sequence = ++suggestSequence
  try {
    const result = await getJson(`api/runtime-insights/impact/symbols?query=${encodeURIComponent(query)}`)
    if (sequence !== suggestSequence) return
    suggestions.value = isSymbols(result) ? result : null
    suggestionError.value = isSymbols(result) ? null : 'Suggestions are unavailable.'
  } catch (e) {
    if (sequence !== suggestSequence) return
    suggestions.value = null
    suggestionError.value = formatLoadError(e, 'Unable to read the suggestions')
  }
  activeIndex.value = -1
  suggestionsOpen.value = true
}

function closeSuggestions() {
  clearTimeout(suggestTimer)
  suggestSequence++
  suggestionsOpen.value = false
  activeIndex.value = -1
}

function choose(option) {
  check(option.symbol, option.name)
}

function onKeydown(event) {
  const count = options.value.length
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    if (!listVisible.value) {
      if (suggestions.value && count && symbol.value.trim()) {
        event.preventDefault()
        suggestionsOpen.value = true
        activeIndex.value = event.key === 'ArrowDown' ? 0 : count - 1
      }
      return
    }
    event.preventDefault()
    const step = event.key === 'ArrowDown' ? 1 : -1
    activeIndex.value = activeIndex.value < 0 ? (step > 0 ? 0 : count - 1) : (activeIndex.value + step + count) % count
  } else if (event.key === 'Enter' && listVisible.value && activeIndex.value >= 0) {
    event.preventDefault()
    choose(options.value[activeIndex.value])
  } else if (event.key === 'Escape' && popupVisible.value) {
    event.preventDefault()
    closeSuggestions()
  }
}

onBeforeUnmount(closeSuggestions)

if (props.initialSymbol) {
  check(props.initialSymbol)
}

// The router uses hash history, so a "#id" href would be read as a route; scroll to the list instead.
function showList(id) {
  const section = document.getElementById(`insight-impact-${id}`)
  section?.scrollIntoView?.({block: 'start', behavior: 'smooth'})
  section?.focus({preventScroll: true})
}

defineExpose({check})

const changedShown = computed(() => props.changed.slice(0, CHANGED_SHOWN))
const changedMore = computed(() =>
  Math.max(0, Math.max(props.changedTotal, props.changed.length) - changedShown.value.length)
)
const idle = computed(() => !impact.value && !error.value && !loading.value)

const lists = computed(() => impactLists(impact.value))
const node = computed(() => nodeParts(impact.value?.node))
const methodStatus = computed(() => methodStatusText(impact.value?.methodStatus))
</script>

<template>
  <section class="card insight-impact" aria-labelledby="insight-impact-title">
    <div class="card-body">
      <h2 id="insight-impact-title" class="h6 mb-1">Change impact</h2>
      <p class="small text-muted mb-2 insight-impact-intro">
        Which routes this run exercised through the code you are changing, and which it did not.
      </p>
      <form
        class="d-flex flex-wrap gap-2 align-items-center insight-impact-form"
        role="search"
        aria-label="Change impact search"
        @submit.prevent="check()"
      >
        <div class="insight-impact-combobox">
          <input
            v-model="symbol"
            type="search"
            role="combobox"
            class="form-control form-control-sm insight-impact-input"
            aria-label="Symbol to check, such as a route, bean, class, method, repository, table, cache, or host"
            :aria-describedby="hintId"
            aria-autocomplete="list"
            :aria-expanded="listVisible ? 'true' : 'false'"
            :aria-controls="listboxId"
            :aria-activedescendant="activeOptionId"
            placeholder="Type a route, bean, Class#method…"
            autocomplete="off"
            spellcheck="false"
            @input="onInput"
            @keydown="onKeydown"
            @blur="closeSuggestions"
          />
          <div v-show="popupVisible" class="insight-impact-popup">
            <ul
              v-if="listVisible"
              :id="listboxId"
              class="list-unstyled mb-0 insight-impact-options"
              role="listbox"
              aria-label="Symbols in this run"
            >
              <li
                v-for="(option, index) in options"
                :id="`insight-impact-option-${index}`"
                :key="option.symbol"
                role="option"
                class="insight-impact-option"
                :class="{active: index === activeIndex}"
                :aria-selected="index === activeIndex ? 'true' : 'false'"
                @mousedown.prevent
                @mousemove="activeIndex = Number(index)"
                @click="choose(option)"
              >
                <code class="insight-impact-option-name bootui-break-anywhere">{{ option.name }}</code>
                <span v-if="option.type" class="small text-muted insight-impact-option-type">{{ option.type }}</span>
                <span class="badge text-bg-light border fw-normal insight-impact-option-kind">{{ option.kind }}</span>
              </li>
            </ul>
            <p class="small text-muted mb-0 insight-impact-popup-status" aria-hidden="true">{{ suggestionStatus }}</p>
          </div>
          <span class="visually-hidden" role="status">{{ suggestionStatus }}</span>
        </div>
        <button type="submit" class="btn btn-sm btn-primary" :disabled="loading || !symbol.trim()">Check impact</button>
      </form>
      <p :id="hintId" class="small text-muted mt-2 mb-0 insight-impact-hint">
        Name a route, a bean, a class, a method such as <code>OrderService#total</code>, a repository, a table, a cache,
        or a host. Start typing to pick one from this run.
      </p>
      <div v-if="idle && changedShown.length" class="insight-impact-changed" data-testid="impact-changed">
        <h3 id="insight-impact-changed-title" class="small fw-semibold mb-1">Changed since the previous run</h3>
        <ul class="list-unstyled d-flex flex-wrap gap-1 mb-0" aria-labelledby="insight-impact-changed-title">
          <li v-for="method in changedShown" :key="method.symbol">
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary insight-impact-candidate"
              :title="method.symbol"
              @click="check(method.symbol, method.name)"
            >
              <code>{{ method.name }}</code>
            </button>
          </li>
          <li v-if="changedMore > 0" class="small text-muted align-self-center">
            and {{ formatNumber(changedMore) }} more in Changes
          </li>
        </ul>
      </div>

      <div aria-live="polite" :aria-busy="loading">
        <div v-if="error" class="alert alert-warning small mt-3 mb-0" role="alert">{{ error }}</div>
        <template v-else-if="impact">
          <template v-if="impact.status === 'RESOLVED'">
            <p class="mt-3 mb-1 insight-impact-node">
              <span v-if="node.kind" class="text-muted small">{{ node.kind }}&nbsp;</span>
              <code>{{ node.name }}</code>
            </p>
            <p v-if="methodStatus || impact.methods?.length > 1" class="small mb-1 insight-impact-method">
              <template v-if="methodStatus">{{ methodStatus }}</template>
              <template v-if="methodStatus && impact.methods.length > 1"> · </template>
              <template v-if="impact.methods?.length > 1"
                >{{ impact.methods.length }} overloads checked as one</template
              >
            </p>
            <p v-if="impact.observedFrom === 'ROUTE_TREES'" class="small text-muted mb-0">
              Reached by {{ formatNumber(impact.structuralReach) }}
              {{ impact.structuralReach === 1 ? 'node' : 'nodes' }} through its bean. A route ran it only when one of
              its requests' own call trees did; a route that ran without showing it is never proof that it did not.
            </p>
            <p v-else class="small text-muted mb-0">
              Reached by {{ formatNumber(impact.structuralReach) }}
              {{ impact.structuralReach === 1 ? 'node' : 'nodes' }} within five steps. What ran is listed apart: a
              route's traffic does not prove that a request went through this code.
            </p>
            <ul class="list-inline small mb-0 mt-2 insight-impact-summary" aria-label="Jump to a list below">
              <li v-for="list in lists" :key="list.id" class="list-inline-item">
                <button
                  type="button"
                  class="btn btn-link btn-sm p-0 align-baseline insight-impact-summary-link"
                  @click="showList(list.id)"
                >
                  {{ formatNumber(list.total) }} {{ list.total === 1 ? 'route' : 'routes' }} · {{ list.title }}
                </button>
              </li>
            </ul>
            <div
              v-for="list in lists"
              :id="`insight-impact-${list.id}`"
              :key="list.id"
              tabindex="-1"
              class="insight-impact-list"
              :data-list="list.id"
            >
              <h3 class="h6 mb-1">
                {{ list.title }}
                <span class="text-muted fw-normal small">· {{ formatNumber(list.total) }}</span>
              </h3>
              <p v-if="list.rows.length === 0" class="small text-muted mb-0">{{ list.empty }}</p>
              <ul v-else class="list-unstyled mb-0 insight-impact-rows">
                <li v-for="route in list.rows" :key="route.route" class="insight-impact-row">
                  <div class="d-flex flex-wrap justify-content-between gap-2">
                    <code class="bootui-break-anywhere">{{ route.route }}</code>
                    <span v-if="list.id !== 'not-exercised'" class="small text-muted">{{ routeTraffic(route) }}</span>
                  </div>
                  <p v-if="route.check" class="small mb-0 mt-1"><InsightText :text="route.check" /></p>
                  <p v-if="route.shared.length" class="small mb-0 mt-1">
                    Shares <code>{{ route.shared.join(', ') }}</code>
                  </p>
                  <p v-if="route.reads.length || route.writes.length" class="small text-muted mb-0 mt-1">
                    <template v-if="route.writes.length">Writes {{ route.writes.join(', ') }}</template>
                    <template v-if="route.reads.length && route.writes.length"> · </template>
                    <template v-if="route.reads.length">Reads {{ route.reads.join(', ') }}</template>
                  </p>
                  <ul v-if="route.exemplarRequestIds.length" class="list-inline small mb-0 mt-1">
                    <li v-for="requestId in route.exemplarRequestIds" :key="requestId" class="list-inline-item">
                      <router-link :to="{path: '/activity', query: {request: requestId}}">
                        <code>{{ requestId }}</code>
                      </router-link>
                    </li>
                  </ul>
                </li>
              </ul>
              <p v-if="list.total > list.rows.length" class="small text-muted mb-0 mt-1">
                {{ formatNumber(list.total - list.rows.length) }} more not listed.
              </p>
            </div>
            <details v-if="impact.limitations.length" class="small text-muted mt-3">
              <summary>Limits · {{ impact.limitations.length }}</summary>
              <ul class="mb-0 mt-1">
                <li v-for="limitation in impact.limitations" :key="limitation">
                  <InsightText :text="limitation" />
                </li>
              </ul>
            </details>
          </template>
          <template v-else>
            <p class="mt-3 mb-2 insight-impact-reason"><InsightText :text="impact.reason ?? ''" /></p>
            <div v-if="impact.candidates.length" class="d-flex flex-wrap gap-1" aria-label="Candidates">
              <button
                v-for="candidate in impact.candidates"
                :key="candidate"
                type="button"
                class="btn btn-sm btn-outline-secondary insight-impact-candidate"
                @click="check(candidate, candidate.slice(candidate.indexOf(' ') + 1))"
              >
                <code>{{ candidate }}</code>
              </button>
            </div>
          </template>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.insight-impact-intro,
.insight-impact-hint,
.insight-impact-reason,
.insight-impact-list {
  max-width: 80ch;
}

.insight-impact-combobox {
  flex: 1 1 18rem;
  max-width: 36rem;
  position: relative;
}

.insight-impact-changed {
  margin-top: 1rem;
}

/* The suggestions float over the panel's later cards and Bootstrap's raised list items (z-index 2 and 3), and stay
   under the sticky topbar (10). */
.insight-impact {
  position: relative;
  z-index: 5;
}

/* Solid, like the theme menu: the translucent glass surface would let the content below read through. */
.insight-impact-popup {
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  box-shadow: var(--bootui-shadow-md);
  left: 0;
  margin-top: 0.25rem;
  max-width: min(36rem, calc(100vw - 2rem));
  min-width: 100%;
  overflow: hidden;
  position: absolute;
  top: 100%;
  width: max-content;
  z-index: 20;
}

.insight-impact-options {
  max-height: 18rem;
  overflow-y: auto;
  padding: 0.25rem;
}

.insight-impact-option {
  align-items: center;
  border-radius: 0.5rem;
  cursor: pointer;
  display: flex;
  gap: 0.5rem;
  padding: 0.35rem 0.5rem;
}

.insight-impact-option.active {
  background: var(--bootui-nav-hover-bg, rgba(25, 135, 84, 0.08));
  box-shadow: inset 0 0 0 1px var(--bootui-blue, #0d6efd);
}

.insight-impact-option-name {
  flex: 1 1 auto;
  min-width: 0;
}

.insight-impact-option-type,
.insight-impact-option-kind {
  flex-shrink: 0;
}

.insight-impact-popup-status {
  padding: 0.35rem 0.75rem;
}

.insight-impact-options + .insight-impact-popup-status {
  border-top: 1px solid var(--bootui-border);
}

.insight-impact-list {
  margin-top: 1.25rem;
}

.insight-impact-rows {
  border-top: 1px solid var(--bootui-border);
}

.insight-impact-row {
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--bootui-border);
}
</style>
