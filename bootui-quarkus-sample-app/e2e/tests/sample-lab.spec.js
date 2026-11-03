import {expect, test} from './fixtures.js'
import {registerSampleLabTests} from '../../../bootui-spring-sample-app/e2e/scenarios/sample-lab.js'

registerSampleLabTests(test, expect, {
  groups: ['Requests', 'Runtime', 'Threads', 'Data', 'Security', 'Integrations & AI'],
  previewGroup: 'Integrations & AI',
  failureEndpoint: '/api/sample/products',
  actions: [
    ['Data', 'Check products', /Loaded \d+ active products/],
    ['Data', 'Run an SQL query', 'Ran a live SQL SELECT'],
    ['Data', 'Exercise the connection pool', /Ran \d+ concurrent queries/],
    ['Runtime', 'Trigger an exception', 'Triggered a sample exception (HTTP 500)'],
    ['Runtime', 'Generate metrics traffic', 'Recorded 5 samples'],
    ['Runtime', 'Allocate sample memory', 'Allocated 32 MB'],
    ['Threads', 'Run a slow request', 'Request slept for 5000 ms'],
    ['Security', 'Secure API as admin', 'Admin access succeeded'],
    ['Security', 'Secure SQL request as admin', 'Authenticated as admin and ran a live SQL SELECT'],
    ['Security', 'Secure API as developer', 'Developer access returned HTTP 403'],
    ['Security', 'Fail authentication', 'Authentication failed with HTTP 401'],
    ['Integrations & AI', 'Send test email', 'Test email sent'],
    ['Requests', 'Call public API', 'Public API returned'],
    ['Requests', 'Call configured greeting', 'Configured greeting returned'],
    ['Requests', 'Make a multi-hop request', 'Completed a multi-hop request']
  ]
})

test('preserves the LLM prompt across tabs and reports unavailable AI without hiding other actions', async ({page}) => {
  await page.goto('/#lab-integrations')
  const prompt = page.getByLabel('Question for the LLM')
  await prompt.fill('Keep this question while I inspect data')
  await page.getByRole('tab', {name: 'Data', exact: true}).click()
  await page.getByRole('tab', {name: 'Integrations & AI', exact: true}).click()
  await expect(prompt).toHaveValue('Keep this question while I inspect data')
  await page.route('**/api/chat', (route) =>
    route.fulfill({status: 503, contentType: 'application/json', body: '{"error":"AI unavailable"}'})
  )
  await page.getByRole('button', {name: 'Ask the LLM', exact: true}).click()
  await expect(page.locator('#ai-chat-status')).toHaveText('Unable to ask the LLM')
  await expect(page.locator('#ai-chat-result')).toHaveText('AI unavailable')
  await expect(page.getByRole('button', {name: 'Ask the LLM', exact: true})).toBeEnabled()
  await page.getByRole('tab', {name: 'Data', exact: true}).click()
  await page.getByRole('button', {name: 'Check products', exact: true}).click()
  await expect(page.locator('#sample-action-status')).toContainText('active products')
})
