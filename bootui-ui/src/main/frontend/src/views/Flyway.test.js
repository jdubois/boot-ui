import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {confirmState, settleConfirm} from '../utils/useConfirm.js'
import FlashBanner from './components/FlashBanner.vue'

import Flyway from './Flyway.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function flywayReport() {
  return {
    available: true,
    total: 1,
    databases: [
      {
        name: 'dataSource',
        currentVersion: '1',
        applied: 1,
        pending: 0,
        total: 1,
        migrateEnabled: true,
        cleanEnabled: false,
        migrations: [
          {
            version: '1',
            description: 'init schema',
            script: 'V1__init.sql',
            type: 'SQL',
            state: 'Success',
            installedOn: '2026-01-01',
            executionTime: 12
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

function actionResult(status = 'success', message = 'Flyway migrated 1 migration(s).', beanName = 'dataSource') {
  return {
    status,
    message,
    beanName,
    migrationsExecuted: status === 'success' ? 1 : null,
    schemasCleaned: [],
    schemasDropped: [],
    migrationPath: null,
    warnings: []
  }
}

async function migrate(wrapper, accept = true) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Migrate')
    .trigger('click')
  await flushPromises()
  expect(confirmState.open).toBe(true)
  settleConfirm(accept)
  await flushPromises()
}

describe('Flyway', () => {
  let wrapper

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=flyway-test; path=/'
  })

  afterEach(() => {
    settleConfirm(false)
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  })

  it('shows a shared unavailable reason when Flyway is not on the classpath', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 404)))

    wrapper = mount(Flyway)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-info')
    expect(alert.text()).toContain('Flyway is not on the classpath')
    expect(alert.find('code').text()).toBe('flyway-core')
  })

  it('reports when Flyway is present but no beans are detected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({total: 0, databases: []})))

    wrapper = mount(Flyway)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toContain('no Flyway beans were detected')
  })

  it('renders migrations when Flyway beans are present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(flywayReport())))

    wrapper = mount(Flyway)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/flyway/migrations', expect.anything())
    expect(wrapper.text()).not.toContain('not on the classpath')
    expect(wrapper.text()).toContain('migration(s) across')
    expect(wrapper.text()).toContain('V1__init.sql')
    expect(wrapper.get('.table-responsive.bootui-table-scroll .flyway-migrations-table').exists()).toBe(true)
    expect(wrapper.get('code.bootui-break-anywhere').text()).toBe('dataSource')
  })

  it.each(['spring-boot', 'spring-boot-reactive', 'quarkus'])(
    'accepts native success DTOs and re-reads history on %s',
    async (platform) => {
      const report = flywayReport()
      const name = platform === 'quarkus' ? '<default>' : 'dataSource'
      report.databases[0].name = name
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(report))
        .mockResolvedValueOnce(jsonResponse(actionResult('success', 'Migration accepted.', name)))
        .mockResolvedValueOnce(jsonResponse({...report, total: 2}))
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Flyway, {global: {provide: {panels: {value: {platform}}}}})
      await flushPromises()
      await migrate(wrapper)

      expect(wrapper.get('.alert-success').text()).toContain('Migration accepted.')
      expect(wrapper.text()).toContain('2 migration(s) across')
      expect(fetchMock).toHaveBeenCalledTimes(3)
      const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
      expect(posts).toHaveLength(1)
      expect(posts[0][0]).toBe('api/flyway/migrate')
      expect(JSON.parse(posts[0][1].body)).toEqual({beanName: name, confirm: true})
    }
  )

  it.each([200, 500])(
    'keeps the native failed outcome visible and reconciles partial history after HTTP %s',
    async (status) => {
      const report = flywayReport()
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(report))
        .mockResolvedValueOnce(
          jsonResponse(actionResult('failed', 'V3 failed after V2 committed.'), status < 400, status)
        )
        .mockResolvedValueOnce(jsonResponse({...report, total: 2}))
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Flyway)
      await flushPromises()
      await migrate(wrapper)

      expect(wrapper.findComponent(FlashBanner).text()).toContain('V3 failed after V2 committed.')
      expect(wrapper.find('.alert-success').exists()).toBe(false)
      expect(wrapper.text()).toContain('2 migration(s) across')
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
        .mockResolvedValueOnce(jsonResponse(flywayReport()))
        .mockImplementationOnce(response)
        .mockReturnValueOnce(fresh.promise)
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(Flyway)
      await flushPromises()
      await migrate(wrapper)

      expect(wrapper.text()).toContain('V1__init.sql')
      expect(wrapper.findComponent(FlashBanner).text()).toMatch(/outcome is unknown/i)
      expect(wrapper.text()).not.toContain('action completed')
      expect(fetchMock).toHaveBeenCalledTimes(3)
      fresh.resolve(jsonResponse({...flywayReport(), total: 2}))
      await flushPromises()
      expect(wrapper.text()).toContain('2 migration(s) across')
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    }
  )

  it.each([400, 403, 404, 409])('preserves a canonical HTTP %s refusal without a follow-up read', async (status) => {
    const reason =
      status === 403
        ? "Panel 'flyway' is read-only (bootui.panels.flyway.read-only=true)"
        : 'Action requires confirm=true because it mutates the application database.'
    const body =
      status === 403
        ? {error: 'BootUI panel access denied', panel: 'flyway', reason}
        : actionResult(status === 404 ? 'unavailable' : 'blocked', reason)
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(flywayReport()))
      .mockResolvedValueOnce(jsonResponse(body, false, status))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Flyway)
    await flushPromises()
    await migrate(wrapper)

    expect(wrapper.findComponent(FlashBanner).text()).toContain(reason)
    expect(wrapper.text()).not.toMatch(/outcome is unknown/i)
    expect(wrapper.text()).toContain('V1__init.sql')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('keeps accepted history and the action failure when reconciliation also fails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(flywayReport()))
      .mockResolvedValueOnce(jsonResponse(actionResult('failed', 'V3 failed after V2 committed.'), false, 500))
      .mockRejectedValueOnce(new Error('History unavailable'))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Flyway)
    await flushPromises()
    await migrate(wrapper)

    expect(wrapper.text()).toContain('V1__init.sql')
    expect(wrapper.findComponent(FlashBanner).text()).toContain('V3 failed after V2 committed.')
    expect(wrapper.text()).toContain('History unavailable')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('does not let an older GET restore stale history after reconciliation', async () => {
    const older = deferred()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(flywayReport()))
      .mockReturnValueOnce(older.promise)
      .mockResolvedValueOnce(jsonResponse(actionResult('failed', 'Migration failed.'), false, 500))
      .mockResolvedValueOnce(jsonResponse({...flywayReport(), total: 2}))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Flyway)
    await flushPromises()
    const staleRead = wrapper.vm.load()
    await migrate(wrapper)
    older.resolve(jsonResponse(flywayReport()))
    await staleRead
    await flushPromises()

    expect(wrapper.text()).toContain('2 migration(s) across')
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('does not write or reconcile when confirmation is cancelled or policy is read-only', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(flywayReport()))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Flyway)
    await flushPromises()
    await migrate(wrapper, false)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await wrapper.setProps({panel: {readOnly: true, readOnlyReason: 'Read-only by configuration.'}})
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === 'Migrate')
        .attributes('disabled')
    ).toBeDefined()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not reconcile an unknown action after unmount', async () => {
    const pending = deferred()
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(flywayReport())).mockReturnValueOnce(pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Flyway)
    await flushPromises()
    await migrate(wrapper)
    wrapper.unmount()
    wrapper = null
    pending.resolve(jsonResponse({}))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
