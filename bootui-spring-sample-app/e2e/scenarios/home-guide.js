// @ts-check
export function registerHomeGuideTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  const settle = async (page) => {
    await expect(page.locator('.home-guide-stage')).toHaveAttribute('aria-busy', 'false')
    await expect(page.locator('.home-guide-step')).toHaveCount(1)
  }
  const choose = async (page, id) => {
    await page.locator(`[data-home-choice="${id}"]`).click()
    await settle(page)
  }
  const back = async (page, level) => {
    await page.locator(`[data-home-back="${level}"]`).click()
    await settle(page)
  }
  const topic = async (page, section, choice) => {
    await choose(page, section)
    await choose(page, choice)
  }
  const recordMotion = async (page) =>
    page.evaluate(() => {
      const motion = []
      document.documentElement.setAttribute('data-home-motion', '[]')
      document.addEventListener('transitionrun', (event) => {
        const element = event.target
        if (!(element instanceof HTMLElement) || !element.classList.contains('home-guide-step')) return
        const effect = element
          .getAnimations()
          .map((animation) => animation.effect)
          .find((item) => item instanceof KeyframeEffect)
        motion.push({
          classes: element.className,
          inert: element.inert,
          transforms: effect instanceof KeyframeEffect ? effect.getKeyframes().map((frame) => frame.transform) : []
        })
        document.documentElement.setAttribute('data-home-motion', JSON.stringify(motion))
      })
    })
  const readMotion = async (page) =>
    page.evaluate(() => JSON.parse(document.documentElement.getAttribute('data-home-motion') ?? '[]'))

  test('Home guides cover every panel without collecting diagnostics', async ({page}) => {
    await page.emulateMedia({reducedMotion: 'reduce'})
    const requests = []
    const errors = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.startsWith(`${apiPath}/`)) {
        requests.push({method: request.method(), path: new URL(request.url()).pathname})
      }
    })
    const manifestResponse = page.waitForResponse(
      (response) => new URL(response.url()).pathname === `${apiPath}/panels` && response.request().method() === 'GET'
    )
    await page.goto(`${uiPath}/`)
    const manifest = await (await manifestResponse).json()
    await expect(page).toHaveURL(new RegExp(`${uiPath}/#/home$`))
    await expect(page.locator('.home-guide-choice')).toHaveCount(4)
    await expect(page.locator('[data-home-panel]')).toHaveCount(0)
    const ids = new Set()
    let topics = 0
    for (const section of ['understand', 'behavior', 'review']) {
      await choose(page, section)
      const choices = await page
        .locator('[data-home-choice]')
        .evaluateAll((elements) => elements.map((element) => element.getAttribute('data-home-choice')))
      expect(choices).toHaveLength(7)
      for (const id of choices) {
        await choose(page, id)
        const panels = await page
          .locator('[data-home-panel]')
          .evaluateAll((elements) => elements.map((element) => element.getAttribute('data-home-panel')))
        panels.forEach((panel) => ids.add(panel))
        topics++
        await back(page, 'topics')
      }
      await page.locator('.panel-header__title-action .bi-house').click()
      await settle(page)
      await expect(page.locator('[data-home-choice]')).toHaveCount(4)
    }
    expect(topics).toBe(21)
    expect(ids.size).toBe(65)
    for (const panel of manifest.panels) expect(ids.has(panel.id)).toBe(true)
    expect([...ids]).toEqual(expect.arrayContaining(['startup', 'heap-dump', 'threads']))
    expect(requests).toHaveLength(2)
    expect(
      requests.every(
        ({method, path}) => method === 'GET' && [`${apiPath}/overview`, `${apiPath}/panels`].includes(path)
      )
    ).toBe(true)
    expect(errors).toEqual([])
  })

  test('Home guides slide with keyboard exploration and preserve a choice on return', async ({page}) => {
    await page.emulateMedia({reducedMotion: 'no-preference'})
    await page.goto(`${uiPath}/#/home`)
    await recordMotion(page)
    const first = page.getByRole('button', {name: 'Understand this application', exact: true})
    await first.focus()
    await page.keyboard.press('Enter')
    await settle(page)
    await expect(page.locator('.home-guide-step-heading')).toBeFocused()
    const memory = page.getByRole('button', {name: 'Memory and JVM', exact: true})
    await memory.focus()
    await page.keyboard.press('Enter')
    await settle(page)
    await expect(page.locator('.home-guide-step-heading')).toBeFocused()
    const link = page.getByRole('link', {name: 'JVM Tuning', exact: true})
    const arrow = link.locator('.home-guide-arrow')
    await expect(arrow).toBeVisible()
    const rowBounds = await link.boundingBox()
    const arrowBounds = await arrow.boundingBox()
    expect(rowBounds.x + rowBounds.width - arrowBounds.x - arrowBounds.width).toBeGreaterThanOrEqual(0)
    expect(rowBounds.x + rowBounds.width - arrowBounds.x - arrowBounds.width).toBeLessThan(24)
    await page.keyboard.press('Tab')
    await expect(link).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(new RegExp(`${uiPath}/#/jvm-tuning$`))
    await expect(page.locator('.panel-header__title')).toHaveText('JVM Tuning')
    await page
      .locator('aside .bootui-nav-link')
      .filter({hasText: /^Home$/})
      .click()
    await expect(page.locator('.home-guide-step-heading')).toHaveText('Memory and JVM')
    await expect(page.getByRole('link', {name: 'Memory', exact: true}).last()).toBeVisible()
    await back(page, 'topics')
    await expect(memory).toBeFocused()
    await back(page, 'root')
    await expect(first).toBeFocused()
    await choose(page, 'understand')
    await choose(page, 'memory')
    await page.getByRole('button', {name: 'Home', exact: true}).press('Enter')
    await settle(page)
    await expect(page.locator('[data-home-choice]')).toHaveCount(4)
    await expect(first).toBeFocused()
    const motion = await readMotion(page)
    expect(motion.some((item) => item.classes.includes('home-forward-enter-active') && item.inert)).toBe(true)
    expect(motion.some((item) => item.classes.includes('home-back-enter-active') && item.inert)).toBe(true)
    expect(
      motion.some((item) => item.transforms.some((transform) => /^translate(?:X)?\(100%\)$/.test(String(transform))))
    ).toBe(true)
    expect(
      motion.some((item) => item.transforms.some((transform) => /^translate(?:X)?\(-100%\)$/.test(String(transform))))
    ).toBe(true)
  })

  test('Home explains unavailable and read-only destinations on a narrow screen', async ({page}) => {
    await page.emulateMedia({reducedMotion: 'reduce'})
    await page.route(`**${apiPath}/panels`, async (route) => {
      const response = await route.fetch()
      const manifest = await response.json()
      manifest.panels = manifest.panels.filter((panel) => panel.id !== 'mysql')
      for (const panel of manifest.panels) {
        if (panel.id === 'config') panel.enabled = false
        if (panel.id === 'code-paths')
          Object.assign(panel, {available: false, unavailableReason: 'Attach the Java agent.'})
        if (panel.id === 'memory')
          Object.assign(panel, {
            available: true,
            enabled: true,
            readOnly: true,
            readOnlyReason: 'Read-only by local policy.'
          })
      }
      await route.fulfill({response, json: manifest})
    })
    await page.setViewportSize({width: 390, height: 844})
    await page.goto(`${uiPath}/#/home`)
    await recordMotion(page)
    await topic(page, 'understand', 'wiring')
    const config = page.locator('[data-home-panel="config"]')
    await expect(config).toContainText('bootui.panels.config.enabled=false')
    await expect(config.locator('a')).toHaveCount(0)
    await expect(config.getByRole('group')).toHaveAttribute('aria-disabled', 'true')
    await expect(config.getByRole('group')).toHaveAccessibleDescription(/bootui.panels.config.enabled=false/)
    await expect(config.locator('.home-guide-arrow')).toHaveCount(0)
    await back(page, 'root')
    await topic(page, 'behavior', 'database')
    await expect(page.locator('[data-home-panel="mysql"]')).toContainText('not reported')
    await expect(page.locator('[data-home-panel="mysql"] [aria-disabled="true"]')).toBeVisible()
    await back(page, 'root')
    await topic(page, 'behavior', 'code')
    await expect(page.locator('[data-home-panel="code-paths"]')).toContainText('Attach the Java agent.')
    await expect(page.locator('[data-home-panel="code-paths"] [aria-disabled="true"]')).toBeVisible()
    await back(page, 'root')
    await topic(page, 'understand', 'memory')
    const memory = page.locator('[data-home-panel="memory"]')
    await expect(memory.getByRole('link', {name: 'Memory', exact: true})).toBeVisible()
    await expect(memory).toContainText('Read-only by local policy.')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    expect(await readMotion(page)).toEqual([])
  })

  test('Home offers agent skills and investigation guides without enabling tools or polling', async ({
    page,
    context
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.emulateMedia({reducedMotion: 'reduce'})
    const requests = []
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.startsWith(`${apiPath}/`))
        requests.push(request.method() + ' ' + new URL(request.url()).pathname)
    })
    await page.goto(`${uiPath}/#/home`)
    await choose(page, 'agents')
    const guide = page.locator('.home-guide')
    await expect(guide).toContainText('You decide what it may run or change')
    await expect(guide).toContainText('Six playbooks')
    await expect(guide.locator('a[target="_blank"]')).toHaveCount(0)
    await expect(guide.locator('[data-home-panel="mcp-server"]')).toBeVisible()
    await expect(guide.locator('[data-home-panel="cli"]')).toBeVisible()
    await page.setViewportSize({width: 390, height: 844})
    for (const [id, fragment] of [
      ['agent-skill', 'install-the-bootui-agent-skill'],
      ['agent-playbooks', 'application-improvement-playbooks']
    ]) {
      const choice = guide.locator(`[data-home-choice="${id}"]`)
      await expect(choice.locator('.home-guide-arrow')).toBeVisible()
      await choice.focus()
      await page.keyboard.press('Enter')
      await settle(page)
      await expect(guide.locator('.home-guide-step-heading')).toBeFocused()
      await expect(guide.locator('[data-home-panel]')).toHaveCount(0)
      const documentation = guide.getByRole('link', {name: /documentation \(opens in a new tab\)$/})
      await expect(guide.locator('a[target="_blank"]')).toHaveCount(1)
      await expect(documentation).toHaveAttribute('href', new RegExp(`ai-agents#${fragment}$`))
      await expect(documentation).toHaveAttribute('rel', 'noopener noreferrer')
      await expect(documentation.locator('.bi-box-arrow-up-right')).toBeVisible()
      await expect(guide).toContainText('Find the full documentation at:')
      if (id === 'agent-skill') {
        await expect(guide.getByRole('region', {name: 'Install with the skill installer'})).toHaveText(
          'npx skills add https://github.com/jdubois/boot-ui/tree/main/skills/bootui'
        )
        await expect(guide.getByRole('region', {name: 'Or use GitHub CLI 2.90 or later'})).toContainText(
          'gh skill install jdubois/boot-ui skills/bootui'
        )
        for (const command of await guide.locator('.home-guide-command').all()) {
          const copy = command.getByRole('button', {name: /^Copy commands:/})
          const code = command.locator('code')
          await expect(copy.locator('.bi-clipboard')).toBeVisible()
          const buttonBounds = await copy.boundingBox()
          const codeBounds = await code.boundingBox()
          const regionBounds = await command.getByRole('region').boundingBox()
          expect(Math.abs(buttonBounds.x + buttonBounds.width - regionBounds.x - regionBounds.width)).toBeLessThan(2)
          expect(buttonBounds.y + buttonBounds.height).toBeLessThan(codeBounds.y)
          await copy.focus()
          await page.keyboard.press('Enter')
          await expect(command.getByRole('status')).toHaveText('Copied!')
          await expect(copy.locator('.bi-check-lg')).toBeVisible()
          expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(await code.textContent())
        }
      }
      const linkIcon = await documentation.evaluate((element) => {
        const style = getComputedStyle(element, '::before')
        return {display: style.display, mask: style.maskImage}
      })
      expect(linkIcon.display).not.toBe('none')
      expect(linkIcon.mask).not.toBe('none')
      if (id === 'agent-playbooks') {
        await expect(guide.locator('.home-guide-resource li')).toHaveCount(6)
        await expect(guide).toContainText('Errors and hidden failures')
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      await expect(page).toHaveURL(new RegExp(`${uiPath}/#/home$`))
      await back(page, 'topics')
      await expect(choice).toBeFocused()
      await expect(guide.locator('a[target="_blank"]')).toHaveCount(0)
    }
    expect(page.context().pages()).toHaveLength(1)
    await expect(guide.getByRole('checkbox', {name: 'Toggle auto-refresh'})).toHaveCount(0)
    await expect(guide.getByRole('button', {name: 'Refresh panel', exact: true})).toHaveCount(0)
    await page.clock.install()
    await page.clock.fastForward(60_000)
    expect(requests.sort()).toEqual([`GET ${apiPath}/overview`, `GET ${apiPath}/panels`].sort())
  })
}
