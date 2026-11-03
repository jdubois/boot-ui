// @ts-check
import {acceptConfirm, expect, test as base} from '../tests/fixtures.js'
import {registerThreadAnalysisTests} from '../scenarios/thread-analysis.js'

const test = base.extend({
  openView: async ({page}, use) => {
    await use(async (route, heading) => {
      await page.goto(`/host/dev-console/#/${route}`)
      await expect(page.getByRole('heading', {name: heading, exact: true})).toBeVisible()
      return page
    })
  }
})

registerThreadAnalysisTests(test, expect, acceptConfirm)
