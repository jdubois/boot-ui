// @ts-check
import {expect, test} from '../tests/fixtures.js'
import {dumpCoverage} from './jacoco-client.js'

/**
 * JaCoCo's agent beside the BootUI agent (agent-config.js, docs/PLAN-v2.md §5.13): both transform the sample's classes,
 * JaCoCo when they load and BootUI's code-paths sensor afterwards, and JaCoCo must still record their coverage. The leg
 * runs JaCoCo with `output=tcpserver`, so this spec reads its execution data from the running sample. The other specs
 * of the leg prove the BootUI agent's side: its claim and sensors, Live Activity, and Code Paths.
 */
test.describe('JaCoCo agent beside the BootUI agent', () => {
  test('records the coverage of the requests through the sample', async ({page, agentCompanion}) => {
    expect(agentCompanion?.jacocoPort).toBeTruthy()
    const port = /** @type {number} */ (agentCompanion?.jacocoPort)

    expect((await page.request.get('/api/hello')).ok()).toBeTruthy()
    expect((await page.request.get('/api/quotes/jacoco')).ok()).toBeTruthy()

    const coverage = await dumpCoverage(port)
    const sample = [...coverage.keys()].filter((name) => name.startsWith('io/github/jdubois/bootui/sample/'))
    expect(sample.length).toBeGreaterThan(10)
    for (const name of [
      'io/github/jdubois/bootui/sample/web/HelloController',
      'io/github/jdubois/bootui/sample/codepaths/SlowPricingService'
    ]) {
      const probes = coverage.get(name)
      expect(probes, `JaCoCo instrumented ${name}`).toBeTruthy()
      expect(probes?.some(Boolean), `JaCoCo recorded ${name}'s coverage`).toBe(true)
    }
  })
})
