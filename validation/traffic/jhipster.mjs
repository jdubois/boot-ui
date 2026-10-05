// JHipster sample app traffic, as in the first run (traffic.sh), with a fixed iteration count: JWT authentication as
// admin and user, anonymous probes (health, info, 401s, a bad login, a password reset with a JSON-quoted email),
// account reads and saves, user administration with paging (create, update, read, delete: each creation sends an
// activation email through an SMTP server that does not run), and create-read-patch-delete on labels, bank accounts,
// and operations, with eager and lazy paging, invalid bodies, and missing ids. The first run took about 130 s.
//
//   node validation/traffic/jhipster.mjs --base-url http://localhost:18182 [--iterations 13] [--summary file.json]

import {Traffic, options, sleep} from './lib.mjs'

const opts = options({baseUrl: 'http://localhost:18182', iterations: 13, pauseMs: 2000})
const traffic = new Traffic('jhipster', opts)
const anon = traffic.client()
anon.headers.Accept = 'application/json'

async function token(username, password) {
  const res = await anon.post('/api/authenticate', {
    label: 'POST /api/authenticate',
    json: {username, password, rememberMe: false},
    expect: [200]
  })
  if (res.status !== 200) throw new Error(`authentication failed for ${username}: ${res.status}`)
  const client = traffic.client()
  client.headers = {Accept: 'application/json', Authorization: `Bearer ${res.json().id_token}`}
  return client
}

const idOf = (res) => {
  try {
    return res.json().id ?? ''
  } catch {
    return ''
  }
}

for (let i = 1; i <= opts.iterations; i++) {
  const admin = await token('admin', 'admin')
  const user = await token('user', 'user')

  await anon.get('/management/health', {label: 'GET /management/health', expect: [200]})
  await anon.get('/management/info', {label: 'GET /management/info', expect: [200]})
  await anon.get('/api/authenticate', {label: 'GET /api/authenticate', expect: [204, 401]})
  await anon.get('/api/account', {label: 'GET /api/account', expect: [401]})
  await anon.get('/api/bank-accounts', {label: 'GET /api/bank-accounts', expect: [401]})
  await anon.get('/api/admin/users', {label: 'GET /api/admin/users', expect: [401]})
  await anon.post('/api/authenticate', {
    label: 'POST /api/authenticate',
    json: {username: 'admin', password: 'wrong', rememberMe: false},
    expect: [401]
  })
  await anon.post('/api/account/reset-password/init', {
    label: 'POST /api/account/reset-password/init',
    body: '"nobody@example.invalid"',
    headers: {'Content-Type': 'application/json'},
    expect: [200, 400]
  })

  await user.get('/api/authenticate', {label: 'GET /api/authenticate', expect: [200, 204]})
  await user.get('/api/account', {label: 'GET /api/account', expect: [200]})
  await user.post('/api/account', {
    label: 'POST /api/account',
    json: {
      login: 'user',
      firstName: 'User',
      lastName: 'User',
      email: 'user@localhost',
      langKey: 'en',
      authorities: ['ROLE_USER']
    },
    expect: [200]
  })

  await admin.get('/api/admin/users?page=0&size=5&sort=login,asc', {label: 'GET /api/admin/users', expect: [200]})
  await admin.get('/api/admin/users/admin', {label: 'GET /api/admin/users/{login}', expect: [200]})
  await admin.get('/api/admin/users/missing-validation-user', {label: 'GET /api/admin/users/{login}', expect: [404]})
  // The first run requested it too; this JHipster version does not expose it, so it answers 404.
  await admin.get('/management/metrics', {label: 'GET /management/metrics', expect: [200, 404]})

  const login = `val${opts.runId}${i}`
  const created = await admin.post('/api/admin/users', {
    label: 'POST /api/admin/users',
    json: {
      login,
      firstName: 'Validation',
      lastName: `User${i}`,
      email: `${login}@example.com`,
      activated: true,
      langKey: 'en',
      authorities: ['ROLE_USER']
    },
    expect: [201]
  })
  const userId = idOf(created)
  if (userId) {
    await admin.request('PUT', `/api/admin/users/${login}`, {
      label: 'PUT /api/admin/users/{login}',
      json: {
        id: userId,
        login,
        firstName: 'Validation',
        lastName: `Updated${i}`,
        email: `${login}@example.com`,
        activated: true,
        langKey: 'en',
        authorities: ['ROLE_USER']
      },
      expect: [200]
    })
  }

  const label = `validation-label-${opts.runId}-${i}`
  const labelId = idOf(await user.post('/api/labels', {label: 'POST /api/labels', json: {label}, expect: [201]}))
  await user.get('/api/labels', {label: 'GET /api/labels', expect: [200]})
  await user.get(`/api/labels/${labelId}`, {label: 'GET /api/labels/{id}', expect: [200]})
  await user.request('PATCH', `/api/labels/${labelId}`, {
    label: 'PATCH /api/labels/{id}',
    json: {id: labelId, label: `${label}-updated`},
    expect: [200]
  })
  await user.post('/api/labels', {label: 'POST /api/labels', json: {label: 'x'}, expect: [400]})
  await user.get('/api/labels/99999999', {label: 'GET /api/labels/{id}', expect: [404]})

  const bankId = idOf(
    await user.post('/api/bank-accounts', {
      label: 'POST /api/bank-accounts',
      json: {name: `Validation account ${i}`, balance: 1000 + i + 0.25},
      expect: [201]
    })
  )
  await user.get('/api/bank-accounts?eagerload=true', {label: 'GET /api/bank-accounts', expect: [200]})
  await user.get('/api/bank-accounts?eagerload=false', {label: 'GET /api/bank-accounts', expect: [200]})
  await user.get(`/api/bank-accounts/${bankId}`, {label: 'GET /api/bank-accounts/{id}', expect: [200]})
  await user.request('PATCH', `/api/bank-accounts/${bankId}`, {
    label: 'PATCH /api/bank-accounts/{id}',
    json: {id: bankId, balance: 2000 + i + 0.5},
    expect: [200]
  })
  await user.post('/api/bank-accounts', {
    label: 'POST /api/bank-accounts',
    json: {name: null, balance: null},
    expect: [400]
  })
  await user.get('/api/bank-accounts/99999999', {label: 'GET /api/bank-accounts/{id}', expect: [404]})

  const operationId = idOf(
    await user.post('/api/operations', {
      label: 'POST /api/operations',
      json: {
        date: new Date().toISOString(),
        description: `Validation operation ${i}`,
        amount: 50 + i + 0.75,
        bankAccount: {id: bankId},
        labels: [{id: labelId}]
      },
      expect: [201]
    })
  )
  await user.get('/api/operations?page=0&size=5&sort=amount,desc&eagerload=true', {
    label: 'GET /api/operations',
    expect: [200]
  })
  await user.get('/api/operations?page=0&size=5&sort=date,asc&eagerload=false', {
    label: 'GET /api/operations',
    expect: [200]
  })
  await user.get(`/api/operations/${operationId}`, {label: 'GET /api/operations/{id}', expect: [200]})
  await user.request('PATCH', `/api/operations/${operationId}`, {
    label: 'PATCH /api/operations/{id}',
    json: {id: operationId, description: `Validation operation patched ${i}`},
    expect: [200]
  })
  await user.post('/api/operations', {
    label: 'POST /api/operations',
    json: {description: 'missing required fields'},
    expect: [400]
  })
  await user.get('/api/operations/99999999', {label: 'GET /api/operations/{id}', expect: [404]})

  await user.request('DELETE', `/api/operations/${operationId}`, {label: 'DELETE /api/operations/{id}', expect: [204]})
  await user.request('DELETE', `/api/bank-accounts/${bankId}`, {label: 'DELETE /api/bank-accounts/{id}', expect: [204]})
  await user.request('DELETE', `/api/labels/${labelId}`, {label: 'DELETE /api/labels/{id}', expect: [204]})
  await admin.request('DELETE', `/api/admin/users/${login}`, {label: 'DELETE /api/admin/users/{login}', expect: [204]})
  await sleep(opts.pauseMs)
}

traffic.finish()
