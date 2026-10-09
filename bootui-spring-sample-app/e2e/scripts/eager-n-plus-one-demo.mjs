// Bounded, explicit localhost load for the Hibernate eager-loading sample. Both routes return the same data;
// compare recorded statement counts in BootUI rather than assuming a particular H2 latency improvement.

import {performance} from 'node:perf_hooks'

const base = new URL(process.argv[2] || 'http://localhost:8080')
if (!['localhost', '127.0.0.1', '[::1]'].includes(base.hostname) || !['http:', 'https:'].includes(base.protocol)) {
  throw new Error('The demo accepts only a loopback HTTP server')
}

const REQUESTS = 48
const CONCURRENCY = 8

async function request(path) {
  const start = performance.now()
  const response = await fetch(new URL(path, base), {signal: AbortSignal.timeout(10_000)})
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`)
  const orders = await response.json()
  if (!Array.isArray(orders) || orders.length !== 16) throw new Error(`${path}: expected 16 orders`)
  return performance.now() - start
}

async function run(label, path) {
  let next = 0
  const samples = []
  await Promise.all(
    Array.from({length: CONCURRENCY}, async () => {
      while (next < REQUESTS) {
        next += 1
        samples.push(await request(path))
      }
    })
  )
  samples.sort((a, b) => a - b)
  console.log(
    `${label}: ${REQUESTS} requests, ${CONCURRENCY} concurrent; median ${samples[23].toFixed(1)} ms, ` +
      `p95 ${samples[45].toFixed(1)} ms`
  )
}

await request('/api/insights/eager-orders')
await request('/api/insights/eager-orders/joined')
await run('Eager N+1', '/api/insights/eager-orders')
await run('JOIN FETCH control', '/api/insights/eager-orders/joined')
