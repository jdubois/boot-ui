// @ts-check
import {test} from './fixtures.js'
import {assertSpringAdvisorFlow} from '../spring-advisor.assertions.js'

test('Spring advisor runs the audited rules on demand and preserves its cached report', async ({page, request}) => {
  await assertSpringAdvisorFlow(page, request)
})
