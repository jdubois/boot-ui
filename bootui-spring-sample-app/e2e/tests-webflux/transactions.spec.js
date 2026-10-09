// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * Transactions on Spring WebFlux: the reactive sample's `@Transactional` boundaries run on a Reactor bounded-elastic
 * worker over its blocking JDBC datasource, and Spring's `TransactionExecutionListener` records them exactly as on
 * Spring MVC. Fails if capture regresses on the reactive stack.
 */
test.describe('Transactions view on Spring WebFlux', () => {
  test('captures the sample commit, slow commit, and rollback', async ({openView, page}) => {
    const generated = await page.request.get('/api/sample/transaction-samples')
    expect(generated.ok()).toBeTruthy()
    expect((await generated.json()).scenarios).toHaveLength(3)

    const report = await (await page.request.get('/bootui/api/transactions')).json()
    expect(report.available).toBe(true)
    const samples = report.entries.filter((entry) => entry.methodName.includes('SampleTransactionScenarios.'))
    expect(samples).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          methodName: expect.stringMatching(/SampleTransactionScenarios\.commit$/),
          status: 'COMMITTED'
        }),
        expect.objectContaining({
          methodName: expect.stringMatching(/SampleTransactionScenarios\.slowCommit$/),
          status: 'COMMITTED',
          slow: true
        }),
        expect.objectContaining({
          methodName: expect.stringMatching(/SampleTransactionScenarios\.rollBack$/),
          status: 'ROLLED_BACK'
        })
      ])
    )

    await openView('transactions', 'Transactions')
    const executions = page.locator('section').filter({hasText: 'Recent transactions'})
    await expect(executions).toContainText('SampleTransactionScenarios.commit')
    await expect(executions).toContainText('SampleTransactionScenarios.slowCommit')
    await expect(executions).toContainText('SampleTransactionScenarios.rollBack')
    await expect(executions).toContainText('ROLLED_BACK')
  })
})
