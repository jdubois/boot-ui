import {test, expect} from '@playwright/test'

async function expectReadingPositionToStay(page, heading) {
  const positions = await heading.evaluate(async (element) => {
    const top = window.scrollY + element.getBoundingClientRect().top + 24
    window.scrollTo({top, behavior: 'instant'})
    const samples = []
    const started = performance.now()
    while (performance.now() - started < 1600) {
      await new Promise(requestAnimationFrame)
      samples.push({top: window.scrollY, hash: window.location.hash})
    }
    return {top, samples}
  })

  expect(positions.samples.every(({hash}) => hash === '')).toBe(true)
  expect(Math.max(...positions.samples.map(({top}) => Math.abs(top - positions.top)))).toBeLessThanOrEqual(1)
}

test('reading an article does not navigate to or snap back to its heading', async ({page}) => {
  await page.goto('/workshop/participant-worksheet')
  await page.getByRole('button', {name: 'Decline', exact: true}).click()
  await expectReadingPositionToStay(page, page.getByRole('heading', {name: 'Requests and observations', exact: true}))
})

test('closely spaced headings do not cause an automatic scrolling loop', async ({page}) => {
  await page.goto('/specification')
  await page.getByRole('button', {name: 'Decline', exact: true}).click()
  const heading = page.getByRole('heading', {name: '2.1 Primary goal', exact: true})
  const separation = await heading.evaluate((element) => {
    const previous = element.previousElementSibling
    return element.getBoundingClientRect().top - previous.getBoundingClientRect().top
  })
  expect(separation).toBeLessThan(200)
  await expectReadingPositionToStay(page, heading)
})

test('explicit heading links, deep links and browser history still navigate', async ({page}, testInfo) => {
  await page.goto('/workshop/participant-worksheet')
  await page.getByRole('button', {name: 'Decline', exact: true}).click()

  if (testInfo.project.name === 'mobile') {
    await page.getByRole('button', {name: 'toggle sidebar', exact: true}).click()
  }
  await page.locator('.vp-sidebar').getByRole('link', {name: 'Requests and observations', exact: true}).click()
  await expect(page).toHaveURL(/#requests-and-observations$/)
  if (testInfo.project.name === 'mobile') {
    await page.getByRole('button', {name: 'toggle sidebar', exact: true}).click()
  }
  await expect(page.getByRole('heading', {name: 'Requests and observations', exact: true})).toBeInViewport()

  await page.locator('#approval .header-anchor').click()
  await expect(page).toHaveURL(/#approval$/)
  await page.goBack()
  await expect(page).toHaveURL(/#requests-and-observations$/)
  await expect(page.getByRole('heading', {name: 'Requests and observations', exact: true})).toBeInViewport()
  await page.goForward()
  await expect(page).toHaveURL(/#approval$/)
  await expect(page.getByRole('heading', {name: 'Approval', exact: true})).toBeInViewport()

  await page.goto('/workshop/participant-worksheet#java-instrumentation')
  await expect(page.getByRole('heading', {name: 'Java instrumentation', exact: true})).toBeInViewport()
})
