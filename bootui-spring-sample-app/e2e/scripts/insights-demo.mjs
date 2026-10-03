// The Runtime Insights demo (docs/PLAN-v2.md M3-6): sends the sample's seeded traffic, one case per observation and
// its counterexample, to a running sample app. Browser tests import seedInsights; developers run it directly:
//
//   node scripts/insights-demo.mjs [base-url] [mvc|webflux]
//
// then open Runtime Insights in BootUI. Tracing is not needed.

const ADMIN = `Basic ${Buffer.from('admin:admin').toString('base64')}`

/** Each step: a method, a path, how many times, and the headers it needs. */
const STEPS = {
  mvc: [
    ['GET', '/api/secure/products', 7, {Authorization: ADMIN}],
    ['GET', '/api/sample/products', 3],
    ['GET', '/api/insights/orders', 3],
    ['GET', '/api/insights/orders/joined', 3],
    ['GET', '/api/insights/orders/report', 3],
    ['GET', '/api/insights/orders/1', 1],
    ['POST', '/api/insights/orders/2/confirm', 1],
    ['POST', '/api/insights/orders/3/ship', 1],
    ['GET', '/api/insights/orders/4/price-check', 1],
    ['GET', '/api/insights/orders/4/price-check-after-commit', 1],
    ['POST', '/api/insights/orders/5/recalculate', 1],
    ['POST', '/api/insights/orders/5/recalculate-through-bean', 1],
    ['POST', '/api/insights/orders/6/import', 1],
    ['POST', '/api/insights/orders', 1, {'Content-Type': 'application/json'}, '{not json'],
    ['POST', '/api/insights/orders/1/notify', 1],
    ['POST', '/api/insights/orders/1/notify-in-transaction', 1],
    ['POST', '/api/insights/orders/1/archive', 1],
    ['POST', '/api/insights/orders/1/restore', 1],
    ['POST', '/api/insights/debug/reset-totals', 1],
    ['GET', '/api/insights/reports/payroll', 1],
    ['GET', '/api/insights/reports/PAYROLL', 1],
    ['GET', '/api/insights/reports/summary', 1],
    ['GET', '/api/insights/orders/after-response', 1],
    ['GET', '/api/insights/orders/after-response/waits', 1],
    ['GET', '/api/sample/boom', 3]
  ],
  webflux: [
    ['GET', '/api/notes', 7],
    ['GET', '/api/insights/notes/on-event-loop', 3],
    ['GET', '/api/insights/notes/one-by-one', 3],
    ['GET', '/api/insights/notes/at-once', 3],
    ['GET', '/api/insights/notes/after-response', 1],
    ['GET', '/api/insights/notes/after-response/waits', 1],
    ['GET', '/api/sample/rest-client', 3]
  ]
}

/**
 * Sends the seeded traffic with `send(method, path, headers, body)`, which returns the response status.
 * @param {(method: string, path: string, headers: Record<string, string>, body?: string) => Promise<number>} send
 * @param {'mvc' | 'webflux'} stack
 */
export async function seedInsights(send, stack = 'mvc') {
  for (const [method, path, times, headers = {}, body] of STEPS[stack]) {
    for (let i = 0; i < times; i += 1) {
      await send(method, path, headers, body)
    }
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const base = process.argv[2] || 'http://localhost:8080'
  const stack = process.argv[3] === 'webflux' ? 'webflux' : 'mvc'
  await seedInsights(async (method, path, headers, body) => {
    const response = await fetch(base + path, {method, headers, body})
    console.log(`${response.status} ${method} ${path}`)
    return response.status
  }, stack)
  console.log(`Seeded. Open ${base}/bootui/#/runtime-insights`)
}
