// @ts-check
import {expect, test} from './fixtures.js'

async function sampleJourney(page) {
  const response = await page.request.get('/api/explorer-demo/1')
  expect(response.ok()).toBeTruthy()
}

async function selectJourney(page) {
  const row = page.locator('.explorer-event').filter({hasText: '/api/explorer-demo/1'}).first()
  await expect(row).toBeVisible({timeout: 15000})
  await row.click()
  await expect(page.locator('.explorer-inspector')).toContainText('/api/explorer-demo/1')
  return row.getAttribute('data-event-id')
}

test.describe('3D Explorer real captured evidence', () => {
  test('replays a real request one tree step at a time through beans and SQL', async ({page, openView}) => {
    expect((await page.request.get('/api/explorer-demo/2')).ok()).toBeTruthy()
    await openView('explorer', '3D Explorer')
    await page.locator('.explorer-event').filter({hasText: 'GET /api/explorer-demo/2'}).first().click()
    const tree = page.getByRole('tree', {name: 'Captured execution tree'})
    await expect(tree).toContainText('REPOSITORY')
    await expect(tree).toContainText('SQL')
    const expected = await tree
      .getByRole('treeitem')
      .evaluateAll((items) =>
        items.map((item) => item.getAttribute('data-row-id')).filter((id) => !id.startsWith('reference:'))
      )
    await tree.evaluate((element) => {
      const history = []
      element.setAttribute('data-playback-history', '[]')
      const observer = new MutationObserver(() => {
        const active = [...element.querySelectorAll('[role="treeitem"].is-active')]
          .map((item) => item.getAttribute('data-row-id'))
          .filter((id) => !id.startsWith('reference:'))
        if (active.length && JSON.stringify(active) !== JSON.stringify(history.at(-1))) {
          history.push(active)
          element.setAttribute('data-playback-history', JSON.stringify(history))
        }
      })
      observer.observe(element, {subtree: true, attributes: true, attributeFilter: ['class']})
      element.addEventListener('stop-recording', () => observer.disconnect(), {once: true})
    })
    await page.getByRole('button', {name: 'Replay', exact: true}).click()
    await expect(page.getByRole('button', {name: 'Stop replay', exact: true})).toBeVisible()
    await expect(page.getByRole('button', {name: 'Replay', exact: true})).toBeVisible({timeout: 16000})
    await tree.evaluate((element) => element.dispatchEvent(new Event('stop-recording')))
    const history = JSON.parse(await tree.getAttribute('data-playback-history'))
    expect(history).toEqual(expected.map((id) => [id]))
    await expect(page.locator('.explorer-replay')).toContainText('Execution-order replay')
  })

  test('spreads circuit-breaker signals into readable counted models while retaining every observation', async ({
    page,
    openView
  }) => {
    await page.setViewportSize({width: 1600, height: 1000})
    expect((await page.request.get('/api/sample/fault-tolerance/circuit-breaker')).ok()).toBeTruthy()
    await openView('explorer', '3D Explorer')
    const row = page
      .locator('.explorer-event')
      .filter({hasText: 'GET /api/sample/fault-tolerance/circuit-breaker'})
      .first()
    await row.click()
    const eventId = await row.getAttribute('data-event-id')
    const payload = await (await page.request.get(`/bootui/api/explorer/events/${encodeURIComponent(eventId)}`)).json()
    const evidence = payload.related.filter((event) => event.type === 'FAULT_TOLERANCE')
    expect(evidence.length).toBeGreaterThanOrEqual(7)
    const tree = page.getByRole('tree')
    await expect(tree.getByRole('treeitem').filter({hasText: 'FAULT_TOLERANCE'})).toHaveCount(evidence.length)
    const signals = page.locator('.explorer-node-label').filter({hasText: 'Fault tolerance'})
    const groups = new Set(evidence.map((event) => JSON.stringify([event.summary, event.detail, event.severity])))
    await expect(signals).toHaveCount(groups.size)
    await expect(signals.filter({hasText: 'ERROR'})).toContainText('×4')
    await expect(signals.filter({hasText: 'SHORT_'})).toContainText('×2')
    const labels = page.locator('.explorer-node-label')
    await expect.poll(() => labels.evaluateAll((items) => items.every((item) => !item.hidden))).toBe(true)
    const rects = await labels.evaluateAll((items) => items.map((item) => item.getBoundingClientRect().toJSON()))
    for (const [index, rect] of rects.entries()) {
      for (const other of rects.slice(index + 1)) {
        expect(
          rect.left < other.right && rect.right > other.left && rect.top < other.bottom && rect.bottom > other.top
        ).toBe(false)
      }
    }
    const centers = await signals.evaluateAll((items) => items.map((item) => item.getBoundingClientRect().x))
    expect(Math.max(...centers) - Math.min(...centers)).toBeGreaterThan(250)
    await page.setViewportSize({width: 1280, height: 960})
    await page.evaluate(() => localStorage.setItem('bootui.theme', 'dark'))
    await page.reload()
    await page.locator(`[data-event-id="${eventId}"]`).click()
    await expect(signals).toHaveCount(groups.size)
    await expect.poll(() => labels.evaluateAll((items) => items.every((item) => !item.hidden))).toBe(true)
  })

  test('opens full screen with working navigation and restores focus and framing when exiting', async ({
    page,
    openView
  }) => {
    await sampleJourney(page)
    await openView('explorer', '3D Explorer')
    await selectJourney(page)
    const scene = page.getByRole('application', {name: '3D captured journey'})
    const originalWidth = await scene.evaluate((element) => element.clientWidth)
    const enter = page.getByRole('button', {name: 'Full screen', exact: true})
    await enter.click()
    const full = page.locator('.explorer-scene:fullscreen')
    await expect(full).toBeVisible()
    await expect(scene).toBeFocused()
    await expect.poll(() => scene.evaluate((element) => element.clientWidth)).toBeGreaterThan(originalWidth)
    await page.keyboard.press('Shift+Tab')
    await expect(page.getByRole('button', {name: 'Exit full screen', exact: true})).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(scene).toBeFocused()
    await page.keyboard.press('ArrowRight')
    await expect(page.locator('.explorer-scene [aria-live="polite"]')).not.toBeEmpty()
    await page.keyboard.press('Escape')
    await expect(full).toHaveCount(0)
    await expect(enter).toBeFocused()
    await expect.poll(() => scene.evaluate((element) => element.clientWidth)).toBe(originalWidth)
    await enter.click()
    await page.getByRole('button', {name: 'Exit full screen', exact: true}).click()
    await expect(full).toHaveCount(0)
    await expect(page.locator('canvas[data-explorer-canvas]')).toHaveCount(1)
  })

  test('browses directly from the 3D keyboard surface, synchronizes inspector and tree, and keeps shortcuts local', async ({
    page,
    openView
  }) => {
    const initial = await (await page.request.get('/bootui/api/explorer')).json()
    test.skip(!initial.setup.beanCaptureEnabled, 'Launch with BOOTUI_EXPLORER_ENABLED=true for bean navigation.')
    await sampleJourney(page)
    await openView('explorer', '3D Explorer')
    const eventId = await selectJourney(page)
    const scene = page.getByRole('application', {name: '3D captured journey'})
    await expect(scene.locator('canvas')).toBeVisible()
    await expect(page.getByRole('tree')).toContainText('CONTROLLER')
    await scene.focus()
    await expect(scene).toBeFocused()
    await page.keyboard.press('ArrowRight')
    await expect(page.locator('.explorer-inspector-name')).toContainText('Controller')
    await expect(page.getByRole('treeitem', {selected: true})).toContainText('CONTROLLER')
    await expect(page.locator('.explorer-scene [aria-live="polite"]')).toContainText('Controller')
    await page.keyboard.press('ArrowRight')
    await expect(page.locator('.explorer-inspector-name')).toContainText('Service')
    await expect(page.getByRole('treeitem', {selected: true})).toContainText('SERVICE')
    const selection = await page.locator('.explorer-inspector-name').textContent()
    const selectedLabel = scene.locator('.explorer-node-label[data-selected="true"]')
    await expect(selectedLabel).toBeVisible()
    const beforeOrbit = await selectedLabel.getAttribute('style')
    await page.keyboard.press('Shift+ArrowLeft')
    await expect(selectedLabel).not.toHaveAttribute('style', beforeOrbit)
    const beforeZoom = await selectedLabel.getAttribute('style')
    await page.keyboard.press('-')
    await expect(selectedLabel).not.toHaveAttribute('style', beforeZoom)
    await page.keyboard.press('+')
    await page.keyboard.press('Home')
    await expect(page.locator('.explorer-inspector-name')).toHaveText(selection)
    await expect(page.locator(`[data-event-id="${eventId}"]`)).toHaveAttribute('aria-pressed', 'true')
    await expect(scene).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(page.getByRole('button', {name: 'Reset view'})).toBeFocused()
    await scene.focus()
    await page.keyboard.press('Escape')
    await expect(scene).not.toBeFocused()
    const search = page.locator('#explorer-search')
    await search.focus()
    await page.keyboard.press('ArrowRight')
    await page.keyboard.press('Shift+ArrowLeft')
    await expect(search).toBeFocused()
    await expect(page.locator('.explorer-inspector-name')).toHaveText(selection)
    await expect(page.locator('.explorer-scene [aria-live="polite"]')).toHaveCount(1)
  })

  test('uses the canonical endpoint and renders actual Three.js with no application requests on navigation', async ({
    page,
    openView
  }) => {
    await sampleJourney(page)
    const applicationRequests = []
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.startsWith('/api/')) applicationRequests.push(request.url())
    })
    await openView('explorer', '3D Explorer')
    await expect(page.locator('canvas[data-explorer-canvas]')).toBeVisible()
    await selectJourney(page)
    await expect(page.getByRole('tree', {name: 'Captured execution tree'})).toBeVisible()
    const root = await page.request.get('/bootui/api/explorer')
    expect(root.ok()).toBeTruthy()
    const payload = await root.json()
    expect(payload.activity.entries.length).toBeGreaterThan(0)
    expect(payload.setup).toHaveProperty('requestSlowThresholdMs')
    expect(applicationRequests).toEqual([])
    await expect(page.getByRole('button', {name: 'Replay', exact: true})).toBeEnabled()
    await page.getByRole('button', {name: 'Replay', exact: true}).click()
    await expect(page.getByRole('slider', {name: 'Recorded replay position'})).not.toHaveValue('0')
    await page.getByRole('checkbox', {name: 'Toggle auto-refresh'}).uncheck()
    await expect(page.getByRole('button', {name: 'Replay', exact: true})).toBeDisabled()
    expect(applicationRequests).toEqual([])
  })

  test('expands real controller, service, repository, SQL references and cache evidence when capture is enabled', async ({
    page,
    openView
  }) => {
    const initial = await (await page.request.get('/bootui/api/explorer')).json()
    test.skip(
      !initial.setup.beanCaptureEnabled,
      'Bean capture is disabled; enable BOOTUI_EXPLORER_ENABLED to exercise it.'
    )
    // The sample endpoint accepts an ID and performs actual application bean/JDBC/cache work.
    await sampleJourney(page)
    const snapshot = await (await page.request.get('/bootui/api/explorer')).json()
    const candidates = snapshot.activity.entries
      .filter((entry) => entry.type === 'REQUEST' && entry.path === '/api/explorer-demo/1')
      .slice(0, 20)
    let payload
    // A prior test may already have warmed the cache. Inspect retained cold evidence, not an invented
    // repository path beneath a hit. Every candidate is still read through the canonical detail API.
    for (const candidate of candidates) {
      const captured = await (
        await page.request.get(`/bootui/api/explorer/events/${encodeURIComponent(candidate.id)}`)
      ).json()
      if (
        captured.invocations.some((invocation) => invocation.role === 'REPOSITORY') &&
        captured.sqlReferences.length
      ) {
        payload = captured
        break
      }
    }
    expect(payload, 'A retained cold request should include repository and SQL evidence').toBeTruthy()
    await openView('explorer', '3D Explorer')
    await page.locator(`[data-event-id="${payload.event.id}"]`).click()
    expect(payload.found).toBe(true)
    expect(payload.invocations.map((invocation) => invocation.role)).toEqual(
      expect.arrayContaining(['CONTROLLER', 'SERVICE'])
    )
    expect(payload.invocations.some((invocation) => invocation.role === 'REPOSITORY')).toBe(true)
    expect(
      payload.links.some((link) => payload.invocations.some((invocation) => invocation.id === link.invocationId))
    ).toBe(true)
    expect(payload.sqlReferences.some((reference) => reference.identifiers.length > 0)).toBe(true)
    expect(payload.cacheOperations.length).toBeGreaterThan(0)
    const tree = page.getByRole('tree', {name: 'Captured execution tree'})
    await expect(tree).toContainText('CONTROLLER')
    await expect(tree).toContainText('REPOSITORY')
    await expect(tree).toContainText('SQL reference')
    for (const invocation of payload.invocations) {
      await expect(tree.locator(`[data-row-id="invocation:${invocation.id}"]`)).toHaveCount(1)
    }
    const first = tree.getByRole('treeitem').first()
    await first.focus()
    await page.keyboard.press('ArrowDown')
    await expect(tree.getByRole('treeitem').nth(1)).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(tree.getByRole('treeitem').nth(1)).toHaveAttribute('aria-selected', 'true')
    const repositories = [
      ...new Map(
        payload.invocations
          .filter((invocation) => invocation.role === 'REPOSITORY')
          .map((invocation) => [JSON.stringify([invocation.beanName, invocation.typeName]), invocation])
      )
    ].sort(([a], [b]) => a.localeCompare(b))
    expect(repositories.length, 'Sample captures separate Spring Data and JDBC repository branches').toBeGreaterThan(1)
    await tree.locator(`[data-row-id="invocation:${repositories[0][1].id}"]`).click()
    const scene = page.getByRole('application', {name: '3D captured journey'})
    await scene.focus()
    await page.keyboard.press('ArrowDown')
    await expect(page.locator('.explorer-inspector-name')).toContainText(repositories[1][1].beanName)
    await expect(tree.getByRole('treeitem', {selected: true})).toContainText(repositories[1][1].beanName)
    await page.keyboard.press('ArrowUp')
    await expect(page.locator('.explorer-inspector-name')).toContainText(repositories[0][1].beanName)
  })

  test('releases real GPU buffers on route changes and offers a context-loss fallback', async ({page, openView}) => {
    await page.addInitScript(() => {
      let disposed = 0
      const original = WebGL2RenderingContext.prototype.deleteBuffer
      WebGL2RenderingContext.prototype.deleteBuffer = function (buffer) {
        disposed++
        document.documentElement.dataset.explorerDisposedBuffers = String(disposed)
        return original.call(this, buffer)
      }
    })
    await sampleJourney(page)
    await openView('explorer', '3D Explorer')
    await expect(page.locator('canvas[data-explorer-canvas]')).toBeVisible()
    await page.locator('canvas[data-explorer-canvas]').evaluate((canvas) => {
      const gl = /** @type {HTMLCanvasElement} */ (canvas).getContext('webgl2')
      gl.getExtension('WEBGL_lose_context').loseContext()
    })
    await expect(page.getByRole('heading', {name: 'Execution tree available'})).toBeVisible()
    await expect(page.getByRole('tree')).toBeVisible()
    await expect(page.locator('canvas[data-explorer-canvas]')).toHaveCount(0)
    await page.getByRole('button', {name: 'Retry 3D'}).click()
    await expect(page.locator('canvas[data-explorer-canvas]')).toHaveCount(1)
    const before = await page.locator('html').getAttribute('data-explorer-disposed-buffers')
    await page.evaluate(() => {
      location.hash = '#/health'
    })
    await expect(page.locator('canvas[data-explorer-canvas]')).toHaveCount(0)
    await expect
      .poll(async () => Number(await page.locator('html').getAttribute('data-explorer-disposed-buffers')))
      .toBeGreaterThan(Number(before))
    await page.evaluate(() => {
      location.hash = '#/explorer'
    })
    await expect(page.locator('canvas[data-explorer-canvas]')).toHaveCount(1)
  })

  test('keeps keyboard inspection in reduced motion and uses the tree first on narrow screens', async ({
    page,
    openView
  }) => {
    await page.emulateMedia({reducedMotion: 'reduce'})
    await page.setViewportSize({width: 390, height: 844})
    await sampleJourney(page)
    await openView('explorer', '3D Explorer')
    await selectJourney(page)
    await expect(page.locator('canvas[data-explorer-canvas]')).toHaveCount(0)
    await expect(page.getByRole('tree')).toBeVisible()
    await page.getByRole('button', {name: 'Replay', exact: true}).click()
    // The shared header has its own connection status; Explorer owns one playback announcement.
    await expect(page.locator('.explorer-panel > [aria-live="polite"]')).toHaveCount(1)
    await expect(page.locator('.explorer-panel > [aria-live="polite"]')).toContainText('without motion')
    await page.getByRole('button', {name: 'Show 3D'}).click()
    await expect(page.locator('canvas[data-explorer-canvas]')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  })
})
