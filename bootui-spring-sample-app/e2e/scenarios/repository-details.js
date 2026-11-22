// @ts-check

/**
 * @param {typeof import('../tests/fixtures.js').test} test
 * @param {typeof import('../tests/fixtures.js').expect} expect
 */
export function registerRepositoryDetailTests(test, expect) {
  test('Spring Data details announce loading and let the selected repository be retried by keyboard', async ({
    openView,
    page
  }) => {
    const repository = {
      beanName: 'orderRepository',
      repositoryInterface: 'com.example.OrderRepository',
      domainType: 'com.example.Order',
      idType: 'java.lang.Long',
      storeModule: 'JPA',
      queryMethodCount: 1,
      fragmentCount: 0
    }
    const archive = {...repository, beanName: 'archiveOrderRepository'}
    // Run against the real shell with a Spring Data-capable fixture, even when the sample has no repository beans.
    await page.route('**/api/panels', async (route) => {
      const response = await route.fetch()
      const manifest = await response.json()
      manifest.panels = manifest.panels.map((panel) => (panel.id === 'data' ? {...panel, available: true} : panel))
      await route.fulfill({response, json: manifest})
    })
    await page.route('**/api/data/repositories', (route) =>
      route.fulfill({json: {springDataPresent: true, total: 2, repositories: [repository, archive]}})
    )
    await page.route(`**/api/data/repositories/${repository.beanName}`, (route) =>
      route.fulfill({json: {...repository, methods: []}})
    )

    let finishFirst
    const pendingFirst = new Promise((resolve) => {
      finishFirst = resolve
    })
    let attempts = 0
    await page.route(`**/api/data/repositories/${archive.beanName}`, async (route) => {
      if (++attempts === 1) {
        await pendingFirst
        await route.fulfill({status: 503})
      } else {
        await route.fulfill({json: {...archive, methods: [{origin: 'DERIVED', signature: 'findByNumber(String)'}]}})
      }
    })

    try {
      await openView('data', 'Spring Data repositories')
      const main = page.getByRole('main')
      const primary = main.getByRole('button', {name: /OrderRepository.*orderRepository/})
      const button = main.getByRole('button', {name: /OrderRepository.*archiveOrderRepository/})
      const current = main.locator('button[aria-current="true"]')
      await expect(primary.getByText('orderRepository', {exact: true})).toBeVisible()
      await expect(button.getByText('archiveOrderRepository', {exact: true})).toBeVisible()
      await expect(current).toHaveCount(0)

      await primary.focus()
      await page.keyboard.press('Enter')
      await expect(current).toHaveCount(1)
      await expect(primary).toHaveAttribute('aria-current', 'true')

      await button.focus()
      await page.keyboard.press('Enter')
      await expect(current).toHaveCount(1)
      await expect(button).toHaveAttribute('aria-current', 'true')
      const loading = main.getByRole('status').filter({hasText: 'Loading repository details…'})
      await expect(loading).toHaveCount(1)
      await expect(loading).toBeVisible()
      await expect(main.getByText('Select a repository to see its methods.')).toHaveCount(0)

      finishFirst()
      const error = main.getByRole('alert')
      await expect(error).toHaveCount(1)
      await expect(error).toContainText('Unable to load repository details: HTTP 503')
      await expect(error).toContainText('Select the repository again to retry.')
      await expect(loading).toHaveCount(0)
      await expect(button).toBeFocused()

      await page.keyboard.press('Enter')
      await expect(main.getByRole('heading', {name: 'OrderRepository'})).toBeVisible()
      await expect(main.getByText('findByNumber(String)')).toBeVisible()
      await expect(error).toHaveCount(0)
      await expect(current).toHaveCount(1)
      await expect(button).toHaveAttribute('aria-current', 'true')
      await expect(button).toBeFocused()
    } finally {
      finishFirst()
    }
  })
}
