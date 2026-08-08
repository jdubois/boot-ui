import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Transactions from './Transactions.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function transactionReport(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    capturing: true,
    bufferSize: 200,
    totalCaptured: 5,
    slowTransactionThresholdMillis: 200,
    connectionHoldThresholdMillis: 500,
    stats: {
      totalTransactions: 2,
      totalDurationMillis: 320,
      maxDurationMillis: 250,
      avgDurationMillis: 160,
      slowTransactions: 1,
      connectionHeldTransactions: 0,
      committedCount: 1,
      rolledBackCount: 1,
      unknownCount: 0,
      nestedCount: 1,
      evicted: 0
    },
    entries: [
      {
        id: 2,
        methodName: 'OrderService.placeOrder',
        propagation: 'NEW',
        isolation: 'READ_COMMITTED',
        status: 'COMMITTED',
        startTimestamp: 1700000000000,
        endTimestamp: 1700000000250,
        durationMillis: 250,
        parentId: null,
        thread: 'http-nio-1',
        traceId: 'trace-abc',
        sqlStatementCount: 3,
        connectionCount: 1,
        readOnly: false,
        slow: true,
        connectionHeld: false,
        errorMessage: null
      },
      {
        id: 1,
        methodName: 'InventoryService.reserveStock',
        propagation: 'PARTICIPATING',
        isolation: 'READ_COMMITTED',
        status: 'ROLLED_BACK',
        startTimestamp: 1700000000010,
        endTimestamp: 1700000000080,
        durationMillis: 70,
        parentId: 2,
        thread: 'http-nio-1',
        traceId: 'trace-abc',
        sqlStatementCount: 1,
        connectionCount: 1,
        readOnly: false,
        slow: false,
        connectionHeld: false,
        errorMessage: 'Insufficient stock'
      }
    ],
    warnings: [],
    ...overrides
  }
}

describe('Transactions', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows the unavailable reason when no PlatformTransactionManager is present', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          available: false,
          unavailableReason: 'No PlatformTransactionManager bean is available',
          stats: {totalTransactions: 0},
          entries: [],
          warnings: []
        })
      )
    )

    wrapper = mount(Transactions, {props: {panel: {id: 'transactions'}}})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/transactions', expect.anything())
    expect(wrapper.text()).toContain('No PlatformTransactionManager bean is available')
  })

  it('renders captured transactions, stats, and nesting', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(transactionReport())))

    wrapper = mount(Transactions, {props: {panel: {id: 'transactions'}}})
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('OrderService.placeOrder')
    expect(text).toContain('1 nested')
    expect(text).toContain('NEW')
    expect(text).toContain('READ_COMMITTED')
    expect(text).toContain('captured since startup')
  })

  it('renders the nested child transaction under its parent row', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(transactionReport())))

    wrapper = mount(Transactions, {props: {panel: {id: 'transactions'}}})
    await flushPromises()

    expect(wrapper.find('tr.tx-row-nested').exists()).toBe(true)
    expect(wrapper.get('tr.tx-row-nested').text()).toContain('InventoryService.reserveStock')
  })

  it('reveals thread and trace id when a row is expanded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(transactionReport())))

    wrapper = mount(Transactions, {props: {panel: {id: 'transactions'}}})
    await flushPromises()

    expect(wrapper.text()).not.toContain('http-nio-1')
    await wrapper.get('tr.tx-row').trigger('click')
    const text = wrapper.text()
    expect(text).toContain('http-nio-1')
    expect(text).toContain('trace-abc')
  })

  it('provides a native keyboard action without changing row pointer behavior', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(transactionReport())))

    wrapper = mount(Transactions, {props: {panel: {id: 'transactions'}}})
    await flushPromises()

    const row = wrapper.get('tr.tx-row')
    const toggle = row.get('button.tx-row-toggle')
    expect(toggle.element.tagName).toBe('BUTTON')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
  })

  it('filters transactions by method name', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(transactionReport())))

    wrapper = mount(Transactions, {props: {panel: {id: 'transactions'}}})
    await flushPromises()

    await wrapper.get('input.trace-filter').setValue('Inventory')
    const executions = wrapper.get('table.tx-table').text()
    expect(executions).toContain('InventoryService.reserveStock')
    expect(executions).not.toContain('OrderService.placeOrder')
  })

  it('toggles recording when the pause action is clicked', async () => {
    const paused = transactionReport({capturing: false})
    const fetchMock = vi.fn((url) => {
      if (url === 'api/transactions/recording') return Promise.resolve(jsonResponse(paused))
      return Promise.resolve(jsonResponse(transactionReport()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Transactions, {props: {panel: {id: 'transactions'}}})
    await flushPromises()

    await wrapper.get('button.btn-outline-warning').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/transactions/recording',
      expect.objectContaining({method: 'POST', body: JSON.stringify({enabled: false})})
    )
  })

  it('clears the trace when the clear action is confirmed', async () => {
    const cleared = transactionReport({
      stats: {...transactionReport().stats, totalTransactions: 0},
      entries: []
    })
    const fetchMock = vi.fn((url) => {
      if (url === 'api/transactions/clear') return Promise.resolve(jsonResponse(cleared))
      return Promise.resolve(jsonResponse(transactionReport()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Transactions, {props: {panel: {id: 'transactions'}}})
    await flushPromises()

    await wrapper.get('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/transactions/clear', {method: 'POST'})
    expect(wrapper.text()).toContain('No transactions have been captured yet')
  })
})
