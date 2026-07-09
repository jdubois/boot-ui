import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import Beans from './Beans.vue'

const mountOptions = {global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function bean(name, dependencies = [], resource = null) {
  return {
    name,
    type: `com.example.${name}`,
    scope: 'singleton',
    resource,
    dependencies,
    aliases: [],
    classification: 'APPLICATION'
  }
}

function beanList(beans) {
  return {
    total: beans.length,
    beans,
    page: {total: beans.length, matched: beans.length, offset: 0, limit: 100, returned: beans.length, hasMore: false}
  }
}

function graphReport(overrides = {}) {
  return {
    available: true,
    focus: bean('focusBean', ['dependencyBean'], 'com/example/SampleAutoConfiguration.class'),
    dependencies: [bean('dependencyBean')],
    dependents: [bean('dependentBean', ['focusBean'])],
    edges: [
      {source: 'dependencyBean', target: 'focusBean'},
      {source: 'focusBean', target: 'dependentBean'}
    ],
    unresolvedDependencies: ['missingBean'],
    hiddenDependencies: 1,
    hiddenDependents: 2,
    hiddenUnresolvedDependencies: 0,
    ...overrides
  }
}

describe('Beans', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('switches from the list to a bounded graph and enriches exact Spring conditions', async () => {
    const fetchMock = vi.fn((url) => {
      if (String(url).startsWith('api/beans/graph')) return Promise.resolve(jsonResponse(graphReport()))
      if (String(url).startsWith('api/conditions')) {
        return Promise.resolve(
          jsonResponse({
            positiveMatches: [
              {
                autoConfigurationClass: 'com.example.SampleAutoConfiguration',
                condition: 'OnClassCondition',
                message: 'required class found',
                outcome: 'MATCH'
              },
              {
                autoConfigurationClass: 'com.example.SimilarAutoConfiguration',
                condition: 'Other',
                message: 'must not be shown',
                outcome: 'MATCH'
              }
            ]
          })
        )
      }
      return Promise.resolve(jsonResponse(beanList([bean('focusBean')])))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Beans, mountOptions)
    await flushPromises()
    await wrapper.get('.bean-link').trigger('click')
    await flushPromises()

    expect(wrapper.get('svg').attributes('aria-label')).toBe('Dependency graph for focusBean')
    expect(wrapper.text()).toContain('dependencyBean')
    expect(wrapper.text()).toContain('dependentBean')
    expect(wrapper.text()).toContain('missingBean')
    expect(wrapper.text()).toContain('1 more dependencies, 2 more dependents')
    expect(wrapper.text()).toContain('required class found')
    expect(wrapper.text()).not.toContain('must not be shown')
  })

  it('refocuses graph nodes with the keyboard', async () => {
    const fetchMock = vi.fn((url) => {
      if (String(url).startsWith('api/beans/graph')) return Promise.resolve(jsonResponse(graphReport()))
      if (String(url).startsWith('api/conditions')) return Promise.resolve(jsonResponse({positiveMatches: []}))
      return Promise.resolve(jsonResponse(beanList([bean('focusBean')])))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Beans, mountOptions)
    await flushPromises()
    await wrapper.get('.bean-link').trigger('click')
    await flushPromises()
    await wrapper.get('.graph-node[role="button"]').trigger('keydown', {key: 'Enter'})
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('focus=dependencyBean'), {})
  })

  it('shows an empty prompt before a focus is selected and a clear API error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (String(url).startsWith('api/beans/graph')) return Promise.resolve(jsonResponse({}, false, 500))
        return Promise.resolve(jsonResponse(beanList([])))
      })
    )

    wrapper = mount(Beans, mountOptions)
    await flushPromises()
    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    expect(wrapper.text()).toContain('Search for a bean')

    await wrapper.get('#bean-graph-focus').setValue('brokenBean')
    await wrapper.get('.graph-search').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Could not load bean graph')
  })

  it('offers server-backed search suggestions', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (String(url).includes('q=target')) return Promise.resolve(jsonResponse(beanList([bean('targetBean')])))
        return Promise.resolve(jsonResponse(beanList([])))
      })
    )

    wrapper = mount(Beans, mountOptions)
    await flushPromises()
    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await wrapper.get('#bean-graph-focus').setValue('target')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(wrapper.get('.graph-suggestion').text()).toContain('targetBean')
  })
})
