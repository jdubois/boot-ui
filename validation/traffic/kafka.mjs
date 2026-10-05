// Kafka microservices traffic, as in the first run (traffic.sh), with a fixed cycle count: each cycle posts five
// orders to order-service (accepted, accepted with varied amounts, rejected for stock, for payment, and for both) and
// reads the orders back; the saga then runs through the payment and stock listeners and Kafka Streams. After the
// last cycle the script waits 10 s for the saga to settle and reads the orders once more. The first run took 162 s.
//
//   node validation/traffic/kafka.mjs --base-url http://localhost:18185 [--iterations 40] [--summary file.json]

import {Traffic, options, sleep} from './lib.mjs'

const opts = options({baseUrl: 'http://localhost:18185', iterations: 40, pauseMs: 3000})
const traffic = new Traffic('kafka', opts)
const http = traffic.client()

const order = (customerId, productId, productCount, price) => ({
  customerId,
  productId,
  productCount,
  price,
  status: 'NEW'
})

for (let i = 0; i < opts.iterations; i++) {
  const customer = (i % 20) + 1
  const product = (i % 30) + 1
  const orders = [
    order(customer, product, 1, 50),
    order(customer + 1, product + 1, (i % 4) + 1, 75 + (i % 5) * 25),
    order(customer + 2, product + 2, 5000, 100),
    order(customer + 3, product + 3, 1, 999999),
    order(customer + 4, product + 4, 7000, 999999)
  ]
  for (const body of orders) await http.post('/orders', {label: 'POST /orders', json: body, expect: [200]})
  await http.get('/orders', {label: 'GET /orders', expect: [200]})
  await sleep(opts.pauseMs)
}
await sleep(10_000)
await http.get('/orders', {label: 'GET /orders', expect: [200]})

traffic.finish()
