// @ts-check
import {test} from '@playwright/test'
import {assertSpringAdvisorFlow} from '../spring-advisor.assertions.js'

test('WebFlux Spring advisor runs the audited rules on demand and preserves its cached report', async ({
  page,
  request
}) => {
  await assertSpringAdvisorFlow(page, request)
})
