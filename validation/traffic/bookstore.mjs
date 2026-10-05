// Bookstore holdout traffic (sivaprasadreddy/spring-modular-monolith): anonymous catalog browsing and cart, anonymous
// probes of protected pages, registration, customer login, orders (whose OrderCreatedEvent runs two
// @ApplicationModuleListener handlers and goes to RabbitMQ), a customer probing the admin area, and an administrator
// managing products, inventory, and order status. Every form carries Spring Security's CSRF token, as a browser does.
//
//   node validation/traffic/bookstore.mjs --base-url http://localhost:18188 [--iterations 40] [--summary file.json]

import {Traffic, csrfField, options, sleep} from './lib.mjs'

const opts = options({baseUrl: 'http://localhost:18188', iterations: 40, pauseMs: 500})
const traffic = new Traffic('bookstore', opts)
const PRODUCTS = ['P100', 'P101', 'P102', 'P103', 'P104']

async function formToken(client, path, label) {
  const page = await client.get(path, {label, expect: [200]})
  return csrfField(page.text)
}

async function login(client, username, password) {
  const csrf = await formToken(client, '/login', 'GET /login')
  const res = await client.post('/login', {
    label: 'POST /login',
    form: {username, password, _csrf: csrf},
    expect: [302]
  })
  if (!res.location || res.location.includes('error')) throw new Error(`login failed for ${username}`)
}

const admin = traffic.client()
await login(admin, 'admin@gmail.com', 'admin')

for (let i = 1; i <= opts.iterations; i++) {
  const code = PRODUCTS[i % PRODUCTS.length]
  const anonymous = traffic.client()

  // Anonymous visitor: catalog pages, cart, and the protected pages that redirect to the login form.
  await anonymous.get('/', {label: 'GET /', expect: [302]})
  await anonymous.get(`/products?page=${(i % 3) + 1}`, {label: 'GET /products', expect: [200]})
  await anonymous.get('/products?page=1', {label: 'GET /products', headers: {'HX-Request': 'true'}, expect: [200]})
  // The catalog page carries a buy form, so its CSRF token; an empty cart page has none.
  let csrf = await formToken(anonymous, '/products', 'GET /products')
  await anonymous.post('/buy', {label: 'POST /buy', form: {code, _csrf: csrf}, expect: [302]})
  await anonymous.get('/cart', {label: 'GET /cart', expect: [200]})
  await anonymous.get('/orders', {label: 'GET /orders', expect: [302]})
  await anonymous.get('/admin/catalog/products', {label: 'GET /admin/catalog/products', expect: [302]})
  await anonymous.get('/actuator/health', {label: 'GET /actuator/health', expect: [200]})

  // Registration, invalid then valid, and the new customer's login.
  const email = `buyer${opts.runId}-${i}@example.com`
  csrf = await formToken(anonymous, '/registration', 'GET /registration')
  await anonymous.post('/registration', {
    label: 'POST /registration',
    form: {name: '', email: 'not-an-email', password: '', _csrf: csrf},
    expect: [200]
  })
  await anonymous.post('/registration', {
    label: 'POST /registration',
    form: {name: `Buyer ${i}`, email, password: 'secret-pass', _csrf: csrf},
    expect: [302]
  })
  await anonymous.get('/registration-success', {label: 'GET /registration-success', expect: [200]})

  const customer = traffic.client()
  await login(customer, email, 'secret-pass')
  csrf = await formToken(customer, '/products', 'GET /products')
  await customer.post('/buy', {label: 'POST /buy', form: {code, _csrf: csrf}, expect: [302]})
  await customer.post('/update-cart', {
    label: 'POST /update-cart',
    form: {code, quantity: String((i % 3) + 1), _csrf: csrf},
    headers: {'HX-Request': 'true'},
    expect: [200]
  })
  // An invalid order form re-renders the cart, a valid one creates the order and redirects to it.
  await customer.post('/orders', {
    label: 'POST /orders',
    form: {'customer.name': '', 'customer.email': '', 'customer.phone': '', deliveryAddress: '', _csrf: csrf},
    expect: [200]
  })
  const order = await customer.post('/orders', {
    label: 'POST /orders',
    form: {
      'customer.name': `Buyer ${i}`,
      'customer.email': email,
      'customer.phone': `555${String(i).padStart(4, '0')}`,
      deliveryAddress: `${i} Validation Street`,
      _csrf: csrf
    },
    expect: [302]
  })
  const orderNumber = order.location ? order.location.split('/').pop() : null
  await customer.get('/orders', {label: 'GET /orders', expect: [200]})
  await customer.get('/orders', {label: 'GET /orders', headers: {'HX-Request': 'true'}, expect: [200]})
  if (orderNumber) await customer.get(`/orders/${orderNumber}`, {label: 'GET /orders/{orderNumber}', expect: [200]})
  await customer.get('/orders/NO-SUCH-ORDER', {label: 'GET /orders/{orderNumber}', expect: [404, 500]})
  // A customer is not an administrator: the role rule answers 403.
  await customer.get('/admin/orders', {label: 'GET /admin/orders', expect: [403]})
  await customer.get('/admin/inventory', {label: 'GET /admin/inventory', expect: [403]})

  // Administrator: products, a create-edit-delete-restore cycle, inventory, and the new order's status.
  await admin.get(`/admin/catalog/products?page=${(i % 2) + 1}`, {label: 'GET /admin/catalog/products', expect: [200]})
  await admin.get(`/admin/catalog/products/${code}`, {label: 'GET /admin/catalog/products/{code}', expect: [200]})
  const newCode = `V${opts.runId}${i}`
  csrf = await formToken(admin, '/admin/catalog/products/new', 'GET /admin/catalog/products/new')
  await admin.post('/admin/catalog/products', {
    label: 'POST /admin/catalog/products',
    form: {code: '', name: '', price: '0', _csrf: csrf},
    expect: [200]
  })
  await admin.post('/admin/catalog/products', {
    label: 'POST /admin/catalog/products',
    form: {
      code: newCode,
      name: `Validation book ${i}`,
      description: 'A book',
      imageUrl: '',
      price: '12.5',
      _csrf: csrf
    },
    expect: [302]
  })
  csrf = await formToken(admin, `/admin/catalog/products/${newCode}/edit`, 'GET /admin/catalog/products/{code}/edit')
  await admin.post(`/admin/catalog/products/${newCode}/edit`, {
    label: 'POST /admin/catalog/products/{code}/edit',
    form: {name: `Validation book ${i} (2nd edition)`, description: 'A book', imageUrl: '', price: '14', _csrf: csrf},
    expect: [302]
  })
  csrf = await formToken(
    admin,
    `/admin/catalog/products/${newCode}/delete`,
    'GET /admin/catalog/products/{code}/delete'
  )
  await admin.post(`/admin/catalog/products/${newCode}/delete`, {
    label: 'POST /admin/catalog/products/{code}/delete',
    form: {_csrf: csrf},
    expect: [302]
  })
  await admin.post(`/admin/catalog/products/${newCode}/restore`, {
    label: 'POST /admin/catalog/products/{code}/restore',
    form: {_csrf: csrf},
    expect: [302]
  })
  await admin.get('/admin/catalog/products/NO-SUCH', {label: 'GET /admin/catalog/products/{code}', expect: [404, 500]})
  csrf = await formToken(admin, '/admin/inventory', 'GET /admin/inventory')
  await admin.post(`/admin/inventory/${code}`, {
    label: 'POST /admin/inventory/{productCode}',
    form: {quantity: String(500 + i), _csrf: csrf},
    expect: [302]
  })
  await admin.get('/admin/orders', {label: 'GET /admin/orders', expect: [200]})
  if (orderNumber) {
    csrf = await formToken(admin, `/admin/orders/${orderNumber}`, 'GET /admin/orders/{orderNumber}')
    await admin.post(`/admin/orders/${orderNumber}/status`, {
      label: 'POST /admin/orders/{orderNumber}/status',
      form: {status: i % 4 === 0 ? 'CANCELLED' : 'IN_PROCESS', _csrf: csrf},
      expect: [302]
    })
  }
  await sleep(opts.pauseMs)
}

traffic.finish()
