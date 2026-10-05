// Quarkus Super Heroes rest-villains traffic, as in the first run (traffic-rest-villains.sh), with a fixed iteration
// count: a warm-up of the UI, hello, health, and OpenAPI, then villain list, filters, random, found, missing, and
// non-numeric ids, the UI page, a create-read-update-patch-delete cycle, invalid creation, and updates, patches, and
// deletes of a missing villain; health and OpenAPI every fourth iteration. The first run took 130 s.
//
//   node validation/traffic/super-heroes.mjs --base-url http://localhost:18183 [--iterations 21] [--summary file.json]

import {Traffic, options, sleep} from './lib.mjs'

const opts = options({baseUrl: 'http://localhost:18183', iterations: 21, pauseMs: 1000})
const traffic = new Traffic('super-heroes', opts)
const http = traffic.client()

for (const path of [
  '/',
  '/?name_filter=har',
  '/api/villains/hello',
  '/q/health',
  '/q/health/live',
  '/q/health/ready'
]) {
  await http.get(path, {label: `GET ${path.split('?')[0]}`, expect: [200]})
}
await http.get('/q/openapi', {label: 'GET /q/openapi', expect: [200]})

const villain = (n, suffix = '') => ({
  name: `BootUI Probe Villain ${n}${suffix}`,
  otherName: `Probe ${n}`,
  level: 10 + (n % 40),
  picture: `probe-${n}.png`,
  powers: 'validation'
})

for (let i = 1; i <= opts.iterations; i++) {
  await http.get('/api/villains', {label: 'GET /api/villains', expect: [200]})
  await http.get('/api/villains?name_filter=har', {label: 'GET /api/villains', expect: [200]})
  await http.get('/api/villains?name_filter=zz-no-match', {label: 'GET /api/villains', expect: [200]})
  await http.get('/api/villains/random', {label: 'GET /api/villains/random', expect: [200]})
  await http.get('/api/villains/50', {label: 'GET /api/villains/{id}', expect: [200]})
  await http.get('/api/villains/999999', {label: 'GET /api/villains/{id}', expect: [404]})
  await http.get('/api/villains/not-a-number', {label: 'GET /api/villains/{id}', expect: [404]})
  await http.get('/', {label: 'GET /', expect: [200]})
  await http.get('/?name_filter=Brain', {label: 'GET /', expect: [200]})
  const created = await http.post('/api/villains', {label: 'POST /api/villains', json: villain(i), expect: [201]})
  const id = (created.location || '').split('/').pop()
  if (/^\d+$/.test(id)) {
    await http.get(`/api/villains/${id}`, {label: 'GET /api/villains/{id}', expect: [200]})
    await http.request('PUT', `/api/villains/${id}`, {
      label: 'PUT /api/villains/{id}',
      json: {...villain(i, ' Updated'), level: 20 + (i % 40)},
      expect: [204]
    })
    await http.request('PATCH', `/api/villains/${id}`, {
      label: 'PATCH /api/villains/{id}',
      json: {level: 30 + (i % 40), powers: 'patched'},
      expect: [200]
    })
    await http.request('DELETE', `/api/villains/${id}`, {label: 'DELETE /api/villains/{id}', expect: [204]})
  }
  await http.post('/api/villains', {label: 'POST /api/villains', json: {name: 'no', level: -1}, expect: [400]})
  await http.request('PUT', '/api/villains/999999', {
    label: 'PUT /api/villains/{id}',
    json: {name: 'Missing Villain', otherName: 'Nobody', level: 11, picture: 'missing.png', powers: 'none'},
    expect: [404]
  })
  await http.request('PATCH', '/api/villains/999999', {
    label: 'PATCH /api/villains/{id}',
    json: {name: 'Missing Villain'},
    expect: [404]
  })
  await http.request('DELETE', '/api/villains/999999', {label: 'DELETE /api/villains/{id}', expect: [204, 404]})
  if (i % 4 === 0) {
    await http.get('/q/health', {label: 'GET /q/health', expect: [200]})
    await http.get('/q/openapi', {label: 'GET /q/openapi', expect: [200]})
  }
  await sleep(opts.pauseMs)
}

traffic.finish()
