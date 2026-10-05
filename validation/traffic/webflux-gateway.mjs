// JHipster WebFlux gateway traffic, as in the first run (traffic-webflux.sh): anonymous health, info, public users,
// and 401s; an admin login per iteration; account, metrics, user paging with a valid and an invalid sort, user reads
// (found and missing); a user created, updated, read, rejected when invalid, and deleted (each creation sends an
// activation email through an SMTP server that does not run); authority create, list, read, missing, duplicate, and
// delete over R2DBC; and two gateway routes to a service that does not run. The first run used 24 iterations.
//
//   node validation/traffic/webflux-gateway.mjs --base-url http://localhost:18184 [--iterations 24] [--summary f.json]

import {Traffic, options, sleep} from './lib.mjs'

const opts = options({baseUrl: 'http://localhost:18184', iterations: 24, pauseMs: 5000})
const traffic = new Traffic('webflux-gateway', opts)
const anon = traffic.client()

async function adminClient() {
  const res = await anon.post('/api/authenticate', {
    label: 'POST /api/authenticate',
    json: {username: 'admin', password: 'admin', rememberMe: true},
    expect: [200]
  })
  const client = traffic.client()
  client.headers = {Authorization: `Bearer ${res.json().id_token}`}
  return client
}

const any = [200, 201, 204, 400, 401, 403, 404, 500]
for (let i = 1; i <= opts.iterations; i++) {
  const suffix = `v${opts.runId}x${i}`
  const login = `${suffix}user`
  const authority = `ROLE_${suffix.toUpperCase()}`

  await anon.get('/management/health', {label: 'GET /management/health', expect: [200]})
  await anon.get('/management/info', {label: 'GET /management/info', expect: [200]})
  await anon.get('/api/users?page=0&size=5&sort=login,asc', {label: 'GET /api/users', expect: [200, 401]})
  await anon.get('/api/account', {label: 'GET /api/account', expect: [401]})
  await anon.get('/api/admin/users?page=0&size=5&sort=login,asc', {label: 'GET /api/admin/users', expect: [401]})

  const admin = await adminClient()
  await admin.get('/api/authenticate', {label: 'GET /api/authenticate', expect: [200, 204]})
  await admin.get('/api/account', {label: 'GET /api/account', expect: [200]})
  await admin.get('/management/metrics', {label: 'GET /management/metrics', expect: [200, 404]})
  await admin.get('/api/admin/users?page=0&size=10&sort=login,asc', {label: 'GET /api/admin/users', expect: [200]})
  await admin.get('/api/admin/users?page=0&size=10&sort=password,asc', {label: 'GET /api/admin/users', expect: [400]})
  await admin.get('/api/admin/users/admin', {label: 'GET /api/admin/users/{login}', expect: [200]})
  await admin.get(`/api/admin/users/does-not-exist-${suffix}`, {label: 'GET /api/admin/users/{login}', expect: [404]})

  const user = {
    login,
    firstName: 'Validation',
    lastName: `User${i}`,
    email: `${suffix}@example.com`,
    activated: true,
    langKey: 'en',
    authorities: ['ROLE_USER']
  }
  await admin.post('/api/admin/users', {label: 'POST /api/admin/users', json: user, expect: [201]})
  // As in the first run, the update omits the id the resource requires, so every update fails: a finding (WF-3).
  await admin.request('PUT', `/api/admin/users/${login}`, {
    label: 'PUT /api/admin/users/{login}',
    json: {...user, firstName: 'ValidationUpdated'},
    expect: any
  })
  await admin.get(`/api/admin/users/${login}`, {label: 'GET /api/admin/users/{login}', expect: [200]})
  await admin.post('/api/admin/users', {
    label: 'POST /api/admin/users',
    json: {id: 1, login: 'bad id', email: 'not-an-email', activated: true, langKey: 'en', authorities: ['ROLE_USER']},
    expect: [400]
  })

  await admin.post('/api/authorities', {label: 'POST /api/authorities', json: {name: authority}, expect: [201]})
  await admin.get('/api/authorities', {label: 'GET /api/authorities', expect: [200]})
  await admin.get(`/api/authorities/${authority}`, {label: 'GET /api/authorities/{id}', expect: [200]})
  await admin.get(`/api/authorities/ROLE_NOT_FOUND_${suffix}`, {label: 'GET /api/authorities/{id}', expect: [404]})
  await admin.post('/api/authorities', {label: 'POST /api/authorities', json: {name: authority}, expect: any})
  await admin.request('DELETE', `/api/authorities/${authority}`, {label: 'DELETE /api/authorities/{id}', expect: [204]})

  await admin.get('/services/absent/api/orders?page=0&size=5', {
    label: 'GET /services/absent/**',
    expect: [500, 502, 503]
  })
  await anon.get('/services/absent/management/health/readiness', {label: 'GET /services/absent/**', expect: any})

  await admin.request('DELETE', `/api/admin/users/${login}`, {label: 'DELETE /api/admin/users/{login}', expect: [204]})
  await sleep(opts.pauseMs)
}

traffic.finish()
