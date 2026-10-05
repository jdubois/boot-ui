// Timeless holdout traffic (mathecruz/timeless, Quarkus): sign-up and JWT sign-in, records created, paged, and
// deleted by their owner, a user reading and updating their profile, another user's profile refused, anonymous calls
// to authenticated resources, and WhatsApp-style messages to POST /api/messages, which the application leaves open
// at the HTTP level (its comment says the network blocks it) and which call the LangChain4j AI service: transactions
// to record, and balance questions that make the AI call the getBalance tool. The model is the validation stub.
//
//   node validation/traffic/timeless.mjs --base-url http://localhost:18189 [--iterations 40] [--summary file.json]

import {Traffic, options, sleep} from './lib.mjs'

const opts = options({baseUrl: 'http://localhost:18189', iterations: 40, pauseMs: 500})
const traffic = new Traffic('timeless', opts)
const CATEGORIES = ['FIXED_COSTS', 'PLEASURES', 'KNOWLEDGE', 'GOALS', 'COMFORT', 'FINANCIAL_FREEDOM']
const MESSAGES = [
  'I paid 35 reais at the gas station in the mall.',
  'I received 500 from a freelance job.',
  'What is my balance?',
  'Spent 12.50 on coffee and cake',
  'How much do I have in my account?'
]

const users = []
for (let i = 1; i <= opts.iterations; i++) {
  const anonymous = traffic.client()
  const email = `user${opts.runId}-${i}@example.com`
  const phone = `+5511${opts.runId}${String(i).padStart(4, '0')}`

  await anonymous.post('/api/sign-up', {
    label: 'POST /api/sign-up',
    json: {email: 'not-an-email', password: 'short', firstName: '', lastName: '', phoneNumber: ''},
    expect: [400]
  })
  const signUp = await anonymous.post('/api/sign-up', {
    label: 'POST /api/sign-up',
    json: {email, password: 'validation-pass', firstName: 'Val', lastName: `User${i}`, phoneNumber: phone},
    expect: [201]
  })
  await anonymous.post('/api/sign-up', {
    label: 'POST /api/sign-up',
    json: {email, password: 'validation-pass', firstName: 'Val', lastName: `User${i}`, phoneNumber: phone},
    expect: [409]
  })
  await anonymous.post('/api/sign-in', {
    label: 'POST /api/sign-in',
    json: {email, password: 'wrong-password'},
    expect: [401]
  })
  const signIn = await anonymous.post('/api/sign-in', {
    label: 'POST /api/sign-in',
    json: {email, password: 'validation-pass'},
    expect: [200]
  })
  const userId = signUp.status === 201 ? signUp.json().id : null
  const token = signIn.status === 200 ? signIn.json().token : null
  users.push({userId, phone})

  // Anonymous calls to authenticated resources answer 401.
  await anonymous.get('/api/records?page=0&limit=10', {label: 'GET /api/records', expect: [401]})
  if (userId) await anonymous.get(`/api/users/${userId}`, {label: 'GET /api/users/{id}', expect: [401]})

  const user = traffic.client()
  user.headers.Authorization = `Bearer ${token}`
  for (let r = 0; r < 3; r++) {
    await user.post('/api/records', {
      label: 'POST /api/records',
      json: {
        amount: 10 + i + r,
        description: `Validation record ${i}.${r}`,
        transaction: r === 2 ? 'IN' : 'OUT',
        from: phone,
        category: CATEGORIES[(i + r) % CATEGORIES.length]
      },
      expect: [201]
    })
  }
  await user.post('/api/records', {
    label: 'POST /api/records',
    json: {amount: -1, description: '', transaction: null, from: phone, category: null},
    expect: [400]
  })
  const page = await user.get('/api/records?page=0&limit=10', {label: 'GET /api/records', expect: [200]})
  await user.get('/api/records?page=1&limit=2', {label: 'GET /api/records', expect: [200]})
  // Without page and limit the resource calls Optional.of(null): kept, as the application's own client could send it.
  await user.get('/api/records', {label: 'GET /api/records', expect: [500]})
  const first = page.status === 200 ? (page.json().items?.[0]?.id ?? page.json().records?.[0]?.id) : null
  if (first) await user.request('DELETE', `/api/records/${first}`, {label: 'DELETE /api/records/{id}', expect: [204]})

  if (userId) {
    await user.get(`/api/users/${userId}`, {label: 'GET /api/users/{id}', expect: [200]})
    await user.request('PUT', '/api/users', {
      label: 'PUT /api/users',
      json: {firstName: 'Val', lastName: `Updated${i}`, email, phoneNumber: phone, id: userId},
      expect: [200]
    })
  }
  const other = users.at(-2)
  if (other?.userId) await user.get(`/api/users/${other.userId}`, {label: 'GET /api/users/{id}', expect: [403]})

  // WhatsApp-style messages: no token, the sender is a phone number.
  for (let m = 0; m < 2; m++) {
    await anonymous.post('/api/messages', {
      label: 'POST /api/messages',
      json: {from: phone, message: MESSAGES[(i + m) % MESSAGES.length]},
      expect: [204]
    })
  }
  await anonymous.post('/api/messages', {
    label: 'POST /api/messages',
    json: {from: '+000000000', message: 'I paid 10 for lunch'},
    expect: [404]
  })
  await anonymous.get('/q/health', {label: 'GET /q/health', expect: [200]})
  await sleep(opts.pauseMs)
}

traffic.finish()
