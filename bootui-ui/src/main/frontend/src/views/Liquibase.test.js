import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {confirmState, settleConfirm} from '../utils/useConfirm.js'
import FlashBanner from './components/FlashBanner.vue'

import Liquibase from './Liquibase.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function liquibaseReport() {
  return {
    available: true,
    total: 1,
    databases: [
      {
        name: 'dataSource',
        applied: 1,
        pending: 0,
        total: 1,
        updateEnabled: true,
        changeSets: [
          {
            id: 'create-users',
            author: 'dev',
            changeLog: 'db/changelog.xml',
            execType: 'EXECUTED',
            orderExecuted: 1,
            dateExecuted: '2026-01-01',
            description: 'create users table'
          }
        ]
      }
    ]
  }
}

function deferred() {
  let resolve
  const promise = new Promise((yes) => {
    resolve = yes
  })
  return {promise, resolve}
}

function actionResult(status = 'success', message = 'Liquibase applied 1 change set(s).', beanName = 'dataSource') {
  return {
    status,
    message,
    beanName,
    pendingBefore: status === 'success' ? 1 : null,
    pendingAfter: status === 'success' ? 0 : null,
    changeSetsApplied: status === 'success' ? 1 : null,
    warnings: []
  }
}

async function update(wrapper, accept = true) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Update')
    .trigger('click')
  await flushPromises()
  expect(confirmState.open).toBe(true)
  settleConfirm(accept)
  await flushPromises()
}

describe('Liquibase', () => {
  let wrapper

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=liquibase-test; path=/'
  })

  afterEach(() => {
    settleConfirm(false)
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  })

  it('shows a shared unavailable reason when Liquibase is not on the classpath', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 404)))

    wrapper = mount(Liquibase)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-info')
    expect(alert.text()).toContain('Liquibase is not on the classpath')
    expect(alert.find('code').text()).toBe('liquibase-core')
  })

  it('reports when Liquibase is present but no beans are detected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({total: 0, databases: []})))

    wrapper = mount(Liquibase)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toContain('no Liquibase beans were detected')
  })

  it('renders change sets when Liquibase beans are present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(liquibaseReport())))

    wrapper = mount(Liquibase)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/liquibase/changesets', expect.anything())
    expect(wrapper.text()).not.toContain('not on the classpath')
    expect(wrapper.text()).toContain('change set(s) across')
    expect(wrapper.text()).toContain('create-users')
    expect(wrapper.get('.table-responsive.bootui-table-scroll .liquibase-changesets-table').exists()).toBe(true)
    expect(wrapper.findAll('code.bootui-break-anywhere').map((node) => node.text())).toEqual([
      'dataSource',
      'create-users',
      'db/changelog.xml'
    ])
  })

  it('shows Quarkus-specific copy when Liquibase is not configured on Quarkus', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 404)))

    wrapper = mount(Liquibase, {
      global: {provide: {panels: {value: {platform: 'quarkus'}}}}
    })
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('quarkus-liquibase')
    expect(alert.text()).not.toContain('liquibase-core')
  })

  it('reports a Quarkus-specific empty state when no datasource is detected on Quarkus', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({total: 0, databases: []})))

    wrapper = mount(Liquibase, {
      global: {provide: {panels: {value: {platform: 'quarkus'}}}}
    })
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('no Liquibase datasource was detected')
    expect(alert.text()).not.toContain('application context')
  })

  it.each(['spring-boot', 'spring-boot-reactive', 'quarkus'])(
    'accepts native success DTOs and re-reads history on %s',
    async (platform) => {
      const report = liquibaseReport()
      const name = platform === 'quarkus' ? '<default>' : 'dataSource'
      report.databases[0].name = name
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(report))
        .mockResolvedValueOnce(jsonResponse(actionResult('success', 'Update accepted.', name)))
        .mockResolvedValueOnce(jsonResponse({...report, total: 2}))
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Liquibase, {global: {provide: {panels: {value: {platform}}}}})
      await flushPromises()
      await update(wrapper)

      expect(wrapper.get('.alert-success').text()).toContain('Update accepted.')
      expect(wrapper.text()).toContain('2 change set(s) across')
      expect(fetchMock).toHaveBeenCalledTimes(3)
      const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
      expect(posts).toHaveLength(1)
      expect(posts[0][0]).toBe('api/liquibase/update')
      expect(JSON.parse(posts[0][1].body)).toEqual({beanName: name, confirm: true})
    }
  )

  it.each([200, 500])(
    'keeps the native failed outcome visible and reconciles partial history after HTTP %s',
    async (status) => {
      const report = liquibaseReport()
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(report))
        .mockResolvedValueOnce(
          jsonResponse(actionResult('failed', 'Change set 3 failed after 2 committed.'), status < 400, status)
        )
        .mockResolvedValueOnce(jsonResponse({...report, total: 2}))
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Liquibase)
      await flushPromises()
      await update(wrapper)

      expect(wrapper.findComponent(FlashBanner).text()).toContain('Change set 3 failed after 2 committed.')
      expect(wrapper.find('.alert-success').exists()).toBe(false)
      expect(wrapper.text()).toContain('2 change set(s) across')
      expect(fetchMock).toHaveBeenCalledTimes(3)
    }
  )

  it.each([
    ['lost response', () => Promise.reject(new TypeError('Failed to fetch'))],
    [
      'invalid JSON',
      () => Promise.resolve({ok: true, status: 200, json: () => Promise.reject(new SyntaxError('Invalid JSON'))})
    ],
    ['empty result', () => Promise.resolve(jsonResponse({}))],
    ['unrecognized status', () => Promise.resolve(jsonResponse(actionResult('unexpected')))],
    ['wrong target', () => Promise.resolve(jsonResponse(actionResult('success', 'Wrong database.', 'other')))]
  ])(
    'reports an unknown outcome after %s, keeps accepted history, and re-reads once without retrying',
    async (_, response) => {
      const fresh = deferred()
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(liquibaseReport()))
        .mockImplementationOnce(response)
        .mockReturnValueOnce(fresh.promise)
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Liquibase)
      await flushPromises()
      await update(wrapper)

      expect(wrapper.text()).toContain('create-users')
      expect(wrapper.findComponent(FlashBanner).text()).toMatch(/outcome is unknown/i)
      expect(wrapper.text()).not.toContain('action completed')
      expect(fetchMock).toHaveBeenCalledTimes(3)
      fresh.resolve(jsonResponse({...liquibaseReport(), total: 2}))
      await flushPromises()
      expect(wrapper.text()).toContain('2 change set(s) across')
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    }
  )

  it.each([400, 403, 404, 409])('preserves a canonical HTTP %s refusal without a follow-up read', async (status) => {
    const reason =
      status === 403
        ? "Panel 'liquibase' is read-only (bootui.panels.liquibase.read-only=true)"
        : 'Action requires confirm=true because it mutates the application database.'
    const body =
      status === 403
        ? {error: 'BootUI panel access denied', panel: 'liquibase', reason}
        : actionResult(status === 404 ? 'unavailable' : 'blocked', reason)
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(liquibaseReport()))
      .mockResolvedValueOnce(jsonResponse(body, false, status))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Liquibase)
    await flushPromises()
    await update(wrapper)

    expect(wrapper.findComponent(FlashBanner).text()).toContain(reason)
    expect(wrapper.text()).not.toMatch(/outcome is unknown/i)
    expect(wrapper.text()).toContain('create-users')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('keeps accepted history and the action failure when reconciliation also fails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(liquibaseReport()))
      .mockResolvedValueOnce(jsonResponse(actionResult('failed', 'Change set 3 failed after 2 committed.'), false, 500))
      .mockRejectedValueOnce(new Error('History unavailable'))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Liquibase)
    await flushPromises()
    await update(wrapper)

    expect(wrapper.text()).toContain('create-users')
    expect(wrapper.findComponent(FlashBanner).text()).toContain('Change set 3 failed after 2 committed.')
    expect(wrapper.text()).toContain('History unavailable')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('does not let an older GET restore stale history after reconciliation', async () => {
    const older = deferred()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(liquibaseReport()))
      .mockReturnValueOnce(older.promise)
      .mockResolvedValueOnce(jsonResponse(actionResult('failed', 'Update failed.'), false, 500))
      .mockResolvedValueOnce(jsonResponse({...liquibaseReport(), total: 2}))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Liquibase)
    await flushPromises()
    const staleRead = wrapper.vm.load()
    await update(wrapper)
    older.resolve(jsonResponse(liquibaseReport()))
    await staleRead
    await flushPromises()

    expect(wrapper.text()).toContain('2 change set(s) across')
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('does not write or reconcile when confirmation is cancelled or policy is read-only', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(liquibaseReport()))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Liquibase)
    await flushPromises()
    await update(wrapper, false)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await wrapper.setProps({panel: {readOnly: true, readOnlyReason: 'Read-only by configuration.'}})
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === 'Update')
        .attributes('disabled')
    ).toBeDefined()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not reconcile an unknown action after unmount', async () => {
    const pending = deferred()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(liquibaseReport()))
      .mockReturnValueOnce(pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Liquibase)
    await flushPromises()
    await update(wrapper)
    wrapper.unmount()
    wrapper = null
    pending.resolve(jsonResponse({}))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
