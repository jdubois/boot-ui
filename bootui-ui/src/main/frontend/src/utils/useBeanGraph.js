import {computed, ref} from 'vue'
import {apiFetch} from '../api.js'
import {describeLoadError} from './loadError.js'

/** Maximum beans to fetch when building the graph index. */
const MAX_GRAPH_LOAD = 2000
/** The shared backend paging helper caps every response at 1 000 rows. */
export const GRAPH_LOAD_PAGE_SIZE = 1000
/** Maximum nodes to include in a single neighbourhood render. */
export const MAX_GRAPH_NODES = 60
/** Maximum BFS hop depth from the focused node in either direction. */
export const MAX_GRAPH_DEPTH = 3
/** Maximum matching condition entries shown for one focused bean. */
export const MAX_BEAN_CONDITIONS = 20

function compareCodeUnits(a, b) {
  if (a === b) return 0
  return a < b ? -1 : 1
}

/** Selects the Application bean with the most visible direct dependency relationships. */
export function mostConnectedApplicationBeanName(beans) {
  const {byName, definitionsByName, reverseIndex} = buildGraphIndex(beans || [])
  const applicationNames = new Set(
    [...definitionsByName]
      .filter(([, definitions]) => definitions.some((bean) => bean.classification === 'APPLICATION'))
      .map(([name]) => name)
  )

  return (
    [...applicationNames]
      .map((name) => {
        const dependencies = new Set(
          (byName.get(name)?.dependencies || []).filter(
            (dependency) => dependency !== name && applicationNames.has(dependency)
          )
        )
        const dependents = new Set(
          [...(reverseIndex.get(name) || [])].filter(
            (dependent) => dependent !== name && applicationNames.has(dependent)
          )
        )
        return {name, relationships: dependencies.size + dependents.size}
      })
      .filter(({relationships}) => relationships > 0)
      .sort(
        (first, second) => second.relationships - first.relationships || compareCodeUnits(first.name, second.name)
      )[0]?.name || null
  )
}

/**
 * Extracts a fully-qualified configuration class from the classpath resource
 * format exposed by Spring Boot's Beans endpoint.
 *
 * Unsupported or ambiguous resource formats deliberately return null: the UI
 * must not infer bean provenance from a filename alone.
 */
export function conditionClassFromResource(resource) {
  if (typeof resource !== 'string') return null
  const match = resource.trim().match(/^class path resource \[([A-Za-z0-9_$/.-]+)\.class]$/)
  if (!match) return null

  const segments = match[1].split('/')
  if (!segments.length || segments.some((segment) => !/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(segment))) {
    return null
  }
  return segments.join('.')
}

/**
 * Keeps only positive Conditions entries that belong to the exact
 * configuration class represented by a bean resource. Method-level condition
 * sources use the stable "Class#method" prefix and are included.
 */
export function matchingPositiveConditions(report, configurationClass, limit = MAX_BEAN_CONDITIONS) {
  if (!configurationClass || !Array.isArray(report?.positiveMatches)) return []
  const safeLimit = Math.max(0, limit)
  return report.positiveMatches
    .filter((entry) => {
      const source = entry?.autoConfigurationClass
      return source === configurationClass || source?.startsWith(`${configurationClass}#`)
    })
    .sort((a, b) => {
      const sourceComparison = compareCodeUnits(a.autoConfigurationClass || '', b.autoConfigurationClass || '')
      if (sourceComparison !== 0) return sourceComparison
      const conditionComparison = compareCodeUnits(a.condition || '', b.condition || '')
      if (conditionComparison !== 0) return conditionComparison
      return compareCodeUnits(a.message || '', b.message || '')
    })
    .slice(0, safeLimit)
}

/**
 * Builds a forward dependency index (name → BeanSummary) and a reverse index
 * (name → Set of dependent names) from a flat bean list.
 *
 * @param {Array} beans  Array of BeanSummary objects.
 * Duplicate names can occur across Spring application contexts or for synthetic CDI names. Their
 * dependency lists are merged under one stable graph node, while definitionsByName keeps the
 * original summaries so the UI can explain the ambiguity.
 *
 * @returns {{
 *   byName: Map<string,object>,
 *   definitionsByName: Map<string,Array<object>>,
 *   reverseIndex: Map<string,Set<string>>
 * }}
 */
export function buildGraphIndex(beans) {
  const definitionsByName = new Map()
  for (const bean of beans) {
    if (!bean?.name) continue
    if (!definitionsByName.has(bean.name)) definitionsByName.set(bean.name, [])
    definitionsByName.get(bean.name).push(bean)
  }

  const byName = new Map()
  for (const [name, definitions] of [...definitionsByName].sort(([a], [b]) => compareCodeUnits(a, b))) {
    const dependencies = [...new Set(definitions.flatMap((bean) => bean.dependencies || []))].sort(compareCodeUnits)
    byName.set(name, {...definitions[0], dependencies})
  }

  const reverseIndex = new Map()
  for (const bean of byName.values()) {
    for (const dep of bean.dependencies || []) {
      if (!reverseIndex.has(dep)) reverseIndex.set(dep, new Set())
      reverseIndex.get(dep).add(bean.name)
    }
  }
  return {byName, definitionsByName, reverseIndex}
}

/**
 * Traverses the dependency neighbourhood of a focus bean using bounded BFS in
 * both directions, guarded against cycles by a visited-set.
 *
 * Node roles:
 *   - 'focus'  — the selected bean (depth 0).
 *   - 'dep'    — direct dependency of focus (focus → node).
 *   - 'rdep'   — direct dependent of focus (node → focus).
 *   - 'both'   — direct dependency AND dependent (mutual/cycle at depth 1).
 *   - 'deep'   — reachable only at depth 2+.
 *
 * @param {string}              focusName     Name of the bean to focus on.
 * @param {Map<string,object>}  byName        Forward dependency index.
 * @param {Map<string,Set>}     reverseIndex  Reverse dependency index.
 * @param {number}              maxNodes      Node limit (default {@link MAX_GRAPH_NODES}).
 * @param {number}              maxDepth      Hop depth limit (default {@link MAX_GRAPH_DEPTH}).
 * @param {function(string): boolean} includeName Whether a bean may appear in the graph.
 * @returns {{
 *   nodes: Array,
 *   edges: Array,
 *   truncated: boolean,
 *   depthLimited: boolean,
 *   nodeLimited: boolean
 * }}
 */
export function traverseNeighborhood(
  focusName,
  byName,
  reverseIndex,
  maxNodes = MAX_GRAPH_NODES,
  maxDepth = MAX_GRAPH_DEPTH,
  includeName = () => true
) {
  if (!byName.has(focusName)) {
    return {nodes: [], edges: [], truncated: false, depthLimited: false, nodeLimited: false}
  }

  const safeMaxNodes = Math.max(1, maxNodes)
  const safeMaxDepth = Math.max(0, maxDepth)
  const nodeDetails = new Map([[focusName, {name: focusName, depth: 0, role: 'focus'}]])
  const queue = [{name: focusName, depth: 0}]
  let nodeLimited = false
  let depthLimited = false

  for (let index = 0; index < queue.length; index += 1) {
    const {name, depth} = queue[index]
    const dependencies = [...new Set(byName.get(name)?.dependencies || [])].filter(includeName).sort(compareCodeUnits)
    const dependents = [...(reverseIndex.get(name) || [])].filter(includeName).sort(compareCodeUnits)
    const neighbours = [...new Set([...dependencies, ...dependents])].sort(compareCodeUnits)

    if (depth >= safeMaxDepth) {
      if (neighbours.some((neighbour) => !nodeDetails.has(neighbour))) depthLimited = true
      continue
    }

    for (const neighbour of neighbours) {
      if (nodeDetails.has(neighbour)) continue
      if (nodeDetails.size >= safeMaxNodes) {
        nodeLimited = true
        continue
      }

      const nextDepth = depth + 1
      let role = 'deep'
      if (nextDepth === 1) {
        const dependency = dependencies.includes(neighbour)
        const dependent = dependents.includes(neighbour)
        role = dependency && dependent ? 'both' : dependency ? 'dep' : 'rdep'
      }
      nodeDetails.set(neighbour, {name: neighbour, depth: nextDepth, role})
      queue.push({name: neighbour, depth: nextDepth})
    }
  }

  const included = new Set(nodeDetails.keys())
  const edges = []
  for (const from of [...included].sort(compareCodeUnits)) {
    const dependencies = [...new Set(byName.get(from)?.dependencies || [])].filter(includeName).sort(compareCodeUnits)
    for (const to of dependencies) {
      if (included.has(to)) edges.push({from, to})
    }
  }

  return {
    nodes: Array.from(nodeDetails.values()),
    edges,
    truncated: nodeLimited || depthLimited,
    depthLimited,
    nodeLimited
  }
}

/**
 * Composable that manages bean-graph data: load-once, index, and navigation.
 *
 * Call {@link loadAll} when the user activates graph mode; it fetches up to
 * {@link MAX_GRAPH_LOAD} beans from {@code api/beans} and builds the indices.
 * Subsequent calls are no-ops once {@link loaded} is true.
 *
 * @returns {{
 *   allBeans: import('vue').Ref<Array>,
 *   beanNames: import('vue').ComputedRef<string[]>,
 *   byName: import('vue').ComputedRef<Map>,
 *   definitionsByName: import('vue').ComputedRef<Map>,
 *   reverseIndex: import('vue').ComputedRef<Map>,
 *   error: import('vue').Ref,
 *   focusName: import('vue').Ref<string|null>,
 *   graph: import('vue').ComputedRef,
 *   inventoryTotal: import('vue').Ref<number>,
 *   inventoryTruncated: import('vue').Ref<boolean>,
 *   loaded: import('vue').Ref<boolean>,
 *   loading: import('vue').Ref<boolean>,
 *   loadAll: function,
 *   setFocus: function
 * }}
 */
export function useBeanGraph(classification = null) {
  const allBeans = ref([])
  const loading = ref(false)
  const error = ref(null)
  const focusName = ref(null)
  const loaded = ref(false)
  const inventoryTotal = ref(0)
  const inventoryTruncated = ref(false)

  const index = computed(() => buildGraphIndex(allBeans.value))
  const byName = computed(() => index.value.byName)
  const definitionsByName = computed(() => index.value.definitionsByName)
  const reverseIndex = computed(() => index.value.reverseIndex)

  function matchesClassification(name) {
    if (!classification?.value || !byName.value.has(name)) return true
    return (definitionsByName.value.get(name) || []).some((bean) => bean.classification === classification.value)
  }

  /** Sorted list of selectable bean names for the active classification. */
  const beanNames = computed(() => [...byName.value.keys()].filter(matchesClassification))

  /** Neighbourhood graph for the currently focused bean, or {@code null}. */
  const graph = computed(() => {
    if (!focusName.value || !byName.value.has(focusName.value)) return null
    return traverseNeighborhood(
      focusName.value,
      byName.value,
      reverseIndex.value,
      MAX_GRAPH_NODES,
      MAX_GRAPH_DEPTH,
      matchesClassification
    )
  })

  /** Load all beans (up to {@link MAX_GRAPH_LOAD}) once. Subsequent calls are no-ops. */
  async function loadAll() {
    if (loaded.value || loading.value) return
    loading.value = true
    error.value = null
    try {
      const beans = []
      let hasMore = true
      let total = 0
      while (hasMore && beans.length < MAX_GRAPH_LOAD) {
        const params = new URLSearchParams({
          offset: String(beans.length),
          limit: String(Math.min(GRAPH_LOAD_PAGE_SIZE, MAX_GRAPH_LOAD - beans.length))
        })
        const res = await apiFetch(`api/beans?${params}`)
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const data = await res.json()
        const pageBeans = data.beans || []
        beans.push(...pageBeans)
        total = data.page?.matched ?? data.total ?? beans.length
        hasMore = data.page?.hasMore === true
        if (pageBeans.length === 0) break
      }
      allBeans.value = beans
      inventoryTotal.value = total
      inventoryTruncated.value = hasMore || total > beans.length
      loaded.value = true
    } catch (e) {
      error.value = describeLoadError(e, 'Could not load beans for graph')
    } finally {
      loading.value = false
    }
  }

  /** Navigate the graph to a different focus bean by name. */
  function setFocus(name) {
    focusName.value = name || null
  }

  return {
    allBeans,
    beanNames,
    byName,
    definitionsByName,
    error,
    focusName,
    graph,
    inventoryTotal,
    inventoryTruncated,
    loaded,
    loading,
    loadAll,
    reverseIndex,
    setFocus
  }
}
