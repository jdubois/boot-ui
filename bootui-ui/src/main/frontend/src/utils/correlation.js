import {computed, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'

/**
 * The diagnostics panels that participate in trace ↔ log ↔ request correlation.
 * Order is shared by the correlation banner so pivot links are stable.
 */
export const CORRELATION_PANELS = [
  {name: 'traces', label: 'Traces', icon: 'bi-bezier2'},
  {name: 'log-tail', label: 'Logs', icon: 'bi-terminal'},
  {name: 'http-exchanges', label: 'HTTP Exchanges', icon: 'bi-arrow-left-right'}
]

/**
 * Returns a shortened, display-friendly form of a (possibly long) trace id.
 */
export function shortTraceId(traceId) {
  if (!traceId) return ''
  return traceId.length > 12 ? `${traceId.slice(0, 12)}…` : traceId
}

/**
 * Shared correlation state for a diagnostics panel.
 *
 * Reads the active trace id from the `trace` route query (when a router is available) so panels
 * can be cross-linked by navigating with `?trace=<id>`. It also exposes helpers to focus a trace
 * within the current panel and to pivot to the other correlated panels.
 *
 * The router is optional: when a panel is mounted without a router (for example in unit tests),
 * the trace filter is kept locally and navigation helpers become no-ops, so panels degrade
 * gracefully instead of throwing.
 *
 * @param {string} current the route name of the panel using this composable
 */
export function useTraceCorrelation(current) {
  const route = useRoute()
  const router = useRouter()

  const queryTrace = () => {
    const value = route?.query?.trace
    if (Array.isArray(value)) return value[0] || null
    return value || null
  }

  const traceFilter = ref(queryTrace())

  if (route) {
    watch(
      () => route.query.trace,
      () => {
        traceFilter.value = queryTrace()
      }
    )
  }

  const pivotTargets = computed(() => CORRELATION_PANELS.filter((panel) => panel.name !== current))

  function focusTrace(traceId) {
    if (!traceId) return
    if (router) {
      router.push({name: current, query: {...route?.query, trace: traceId}})
    } else {
      traceFilter.value = traceId
    }
  }

  function clearTrace() {
    if (router && route) {
      const query = {...route.query}
      delete query.trace
      router.push({name: current, query})
    } else {
      traceFilter.value = null
    }
  }

  function pivotTo(name) {
    if (!traceFilter.value) return
    if (router) {
      router.push({name, query: {trace: traceFilter.value}})
    }
  }

  return {traceFilter, pivotTargets, focusTrace, clearTrace, pivotTo}
}
