// The Runtime Insights demo on Quarkus (docs/PLAN-v2.md M3-6): sends the sample's seeded traffic, one case per
// observation this stack records and its counterexample, to a running Quarkus sample app. Browser tests import
// seedInsights; developers run it directly:
//
//   node scripts/insights-demo.mjs [base-url]
//
// then open Runtime Insights in BootUI. Tracing is not needed.

const ADMIN = `Basic ${Buffer.from('admin:admin').toString('base64')}`

/** Each step: a method, a path, how many times, and the headers it needs. */
const STEPS = [
  ['GET', '/api/secure/products', 7, {Authorization: ADMIN}],
  ['GET', '/api/sample/products', 3],
  ['GET', '/api/insights/orders', 3],
  ['GET', '/api/insights/orders/joined', 3],
  ['GET', '/api/insights/orders/on-event-loop', 3],
  ['GET', '/api/insights/orders/1', 1],
  ['POST', '/api/insights/orders/1/archive', 1],
  ['POST', '/api/insights/debug/reset-totals', 1],
  ['GET', '/api/insights/reports/payroll', 1],
  ['GET', '/api/insights/reports/PAYROLL', 1],
  ['GET', '/api/insights/reports/summary', 1],
  ['GET', '/api/insights/orders/after-response', 1],
  ['GET', '/api/insights/orders/after-response/waits', 1]
]

/**
 * Sends the seeded traffic with `send(method, path, headers)`, which returns the response status.
 * @param {(method: string, path: string, headers: Record<string, string>) => Promise<number>} send
 */
export async function seedInsights(send) {
  for (const [method, path, times, headers = {}] of STEPS) {
    for (let i = 0; i < times; i += 1) {
      await send(method, path, headers)
    }
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const base = process.argv[2] || 'http://localhost:8082'
  await seedInsights(async (method, path, headers) => {
    const response = await fetch(base + path, {method, headers})
    console.log(`${response.status} ${method} ${path}`)
    return response.status
  })
  console.log(`Seeded. Open ${base}/bootui/#/runtime-insights`)
}
