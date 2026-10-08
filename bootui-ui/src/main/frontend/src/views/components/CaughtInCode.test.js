import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import CaughtInCode from './CaughtInCode.vue'

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="JSON.stringify(to)"><slot /></a>'
}

function row(overrides = {}) {
  return {
    id: 'caught-1',
    route: 'GET /orders',
    ownerKind: 'request',
    siteClass: 'com.example.OrderService',
    method: 'load',
    line: 42,
    declaredTypes: ['java.sql.SQLException'],
    exceptionClass: 'java.sql.SQLTimeoutException',
    family: 'sql',
    occurrences: 3,
    requests: 2,
    rethrown: 0,
    replaced: 0,
    reported: 0,
    logged: 0,
    handedOn: 0,
    reinterrupted: 0,
    retried: 0,
    notRethrownOrLogged: 0,
    unknown: 0,
    pending: 0,
    counted: 0,
    unknownReason: null,
    shapes: ['discards', 'prints-stack-trace'],
    finding: false,
    exemplarRequestId: null,
    firstSeen: 1700000000000,
    lastSeen: 1700000005000,
    ...overrides
  }
}

function report(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    limitations: [],
    settling: 0,
    occurrences: 3,
    findings: 0,
    rows: [row()],
    ...overrides
  }
}

function mountCaught(props = {}) {
  return mount(CaughtInCode, {
    props: {report: report(), ...props},
    global: {stubs: {RouterLink: RouterLinkStub}}
  })
}

function labelledRegion(wrapper) {
  const region = wrapper.get('section[role="region"][aria-labelledby]')
  const heading = wrapper.get(`#${region.attributes('aria-labelledby')}`)
  return {region, heading}
}

describe('CaughtInCode', () => {
  it('exposes the section as a labelled region', () => {
    const wrapper = mountCaught()
    const {region, heading} = labelledRegion(wrapper)

    expect(region.element.tagName).toBe('SECTION')
    expect(heading.text()).toBe('Caught in application code')
  })

  it('shows an unavailable reason without rendering a table', () => {
    const wrapper = mountCaught({
      report: report({
        available: false,
        unavailableReason: 'The caught-exceptions sensor is not recording.',
        rows: []
      })
    })

    expect(wrapper.text()).toContain('The caught-exceptions sensor is not recording.')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('renders a finding as a fact without forbidden wording', () => {
    const wrapper = mountCaught({
      report: report({
        findings: 1,
        rows: [row({finding: true, notRethrownOrLogged: 3})]
      })
    })
    const {region} = labelledRegion(wrapper)

    expect(region.text()).toContain('Not seen rethrown or logged at WARN or above')
    expect(region.text()).toContain('Finding')
    expect(wrapper.html()).not.toContain('swallowed')
  })

  it('renders unknown reasons and the settling count', () => {
    const wrapper = mountCaught({
      report: report({
        settling: 2,
        rows: [row({unknown: 1, pending: 2, unknownReason: 'log coverage changed during the request'})]
      })
    })

    expect(wrapper.text()).toContain(
      '2 occurrences settling: their request ended less than 5 s ago or is still running'
    )
    expect(wrapper.text()).toContain('Unknown')
    expect(wrapper.text()).toContain('Unknown: log coverage changed during the request')
    expect(wrapper.text()).toContain('Settling')
  })

  it('links to Live Activity only for rows with an exemplar request id', () => {
    const wrapper = mountCaught({
      report: report({
        rows: [
          row({id: 'with-link', exemplarRequestId: '00000000000000ab'}),
          row({id: 'without-link', exemplarRequestId: null})
        ]
      })
    })

    const links = wrapper.findAll('a')
    expect(links).toHaveLength(1)
    expect(JSON.parse(links[0].attributes('href'))).toEqual({
      path: '/activity',
      query: {request: '00000000000000ab'}
    })
    expect(wrapper.text()).toContain('00000000000000ab')
  })
})
