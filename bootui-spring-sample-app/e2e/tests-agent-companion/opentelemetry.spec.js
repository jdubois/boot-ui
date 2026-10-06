// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * The OpenTelemetry Java agent beside the BootUI agent, in the order the configuration chose (agent-config.js,
 * docs/PLAN-v2.md §5.13): it still instruments the sample and exports its spans, here over OTLP/HTTP to BootUI's own
 * receiver under a service name of its own, so the Traces API tells them from the spans Spring's tracing records.
 * The other specs of the leg prove the BootUI agent's side: its claim and sensors, Live Activity, and Code Paths.
 */
test.describe('OpenTelemetry Java agent beside the BootUI agent', () => {
  test('exports the sample request spans it records', async ({page, agentCompanion}) => {
    expect(agentCompanion?.serviceName).toBeTruthy()
    const serviceName = /** @type {string} */ (agentCompanion?.serviceName)

    expect((await page.request.get('/api/hello')).ok()).toBeTruthy()

    let traceId = null
    await expect
      .poll(
        async () => {
          const response = await page.request.get('/bootui/api/traces')
          if (!response.ok()) return null
          const report = await response.json()
          const trace = (report.traces ?? []).find(
            (candidate) =>
              (candidate.services ?? []).includes(serviceName) &&
              `${candidate.httpPath ?? ''} ${candidate.rootSpanName ?? ''}`.includes('/api/hello')
          )
          traceId = trace?.traceId ?? null
          return traceId
        },
        {message: `a trace of GET /api/hello exported by ${serviceName}`, timeout: 30_000}
      )
      .not.toBeNull()

    const detail = await page.request.get(`/bootui/api/traces/${traceId}`)
    expect(detail.ok()).toBeTruthy()
    const spans = (await detail.json()).spans.filter((span) => span.serviceName === serviceName)
    expect(spans.length).toBeGreaterThan(0)
    const server = spans.find((span) => span.kind === 'SERVER')
    expect(server, 'the agent recorded the server span').toBeTruthy()
    expect(server.scope).toMatch(/^io\.opentelemetry\./)
  })
})
