import {mount} from '@vue/test-utils'
import {defineComponent, h} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'

const route = vi.hoisted(() => ({query: {}}))
const push = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({push})
}))

import {CORRELATION_PANELS, shortTraceId, useTraceCorrelation} from './correlation.js'

function harness(current) {
  let api
  const Comp = defineComponent({
    setup() {
      api = useTraceCorrelation(current)
      return () => h('div')
    }
  })
  const wrapper = mount(Comp)
  return {api, wrapper}
}

describe('correlation utils', () => {
  afterEach(() => {
    route.query = {}
    push.mockReset()
  })

  it('shortens long trace ids and leaves short ones intact', () => {
    expect(shortTraceId('4bf92f3577b34da6a3ce929d0e0e4736')).toBe('4bf92f3577b3…')
    expect(shortTraceId('abc123')).toBe('abc123')
    expect(shortTraceId(null)).toBe('')
  })

  it('exposes the three correlated diagnostics panels', () => {
    expect(CORRELATION_PANELS.map((p) => p.name)).toEqual(['traces', 'log-tail', 'http-exchanges'])
  })

  it('reads the active trace id from the route query and excludes the current panel from pivots', () => {
    route.query = {trace: 'trace-123'}
    const {api} = harness('traces')
    expect(api.traceFilter.value).toBe('trace-123')
    expect(api.pivotTargets.value.map((p) => p.name)).toEqual(['log-tail', 'http-exchanges'])
  })

  it('focuses a trace by navigating with the trace query on the current panel', () => {
    const {api} = harness('http-exchanges')
    api.focusTrace('trace-xyz')
    expect(push).toHaveBeenCalledWith({name: 'http-exchanges', query: {trace: 'trace-xyz'}})
  })

  it('pivots to another panel preserving the active trace', () => {
    route.query = {trace: 'trace-abc'}
    const {api} = harness('log-tail')
    api.pivotTo('traces')
    expect(push).toHaveBeenCalledWith({name: 'traces', query: {trace: 'trace-abc'}})
  })

  it('clears the trace by dropping the query parameter', () => {
    route.query = {trace: 'trace-abc', other: 'keep'}
    const {api} = harness('traces')
    api.clearTrace()
    expect(push).toHaveBeenCalledWith({name: 'traces', query: {other: 'keep'}})
  })
})
