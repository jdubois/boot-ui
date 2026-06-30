const chatForm = document.getElementById('ai-chat-form')
const chatMessage = document.getElementById('ai-chat-message')
const chatStatus = document.getElementById('ai-chat-status')
const chatResult = document.getElementById('ai-chat-result')
const productsButton = document.getElementById('products-button')
const sqlQueryButton = document.getElementById('sql-query-button')
const greetingButton = document.getElementById('greeting-button')
const publicApiButton = document.getElementById('public-api-button')
const secureAdminButton = document.getElementById('secure-admin-button')
const secureSqlButton = document.getElementById('secure-sql-button')
const secureUserButton = document.getElementById('secure-user-button')
const exceptionButton = document.getElementById('exception-button')
const metricsBurstButton = document.getElementById('metrics-burst-button')
const allocateButton = document.getElementById('allocate-button')
const slowButton = document.getElementById('slow-button')
const poolStressButton = document.getElementById('pool-stress-button')
const chainedButton = document.getElementById('chained-button')
const authFailButton = document.getElementById('auth-fail-button')
const sampleActionStatus = document.getElementById('sample-action-status')
const sampleActionResult = document.getElementById('sample-action-result')

chatForm.addEventListener('submit', async event => {
  event.preventDefault()

  const message = chatMessage.value.trim()
  if (!message) {
    chatStatus.textContent = 'Enter a question first.'
    chatResult.hidden = true
    return
  }

  const submitButton = chatForm.querySelector('button[type="submit"]')
  submitButton.disabled = true
  chatStatus.textContent = 'Asking the LLM...'
  chatResult.textContent = ''
  chatResult.hidden = true

  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({message})
    })
    const contentType = response.headers.get('content-type') || ''
    const body = contentType.includes('application/json')
      ? await response.json()
      : {error: await response.text()}

    if (!response.ok) {
      throw new Error(body.error || `Request failed with HTTP ${response.status}`)
    }

    chatStatus.textContent = 'Response from the LLM'
    chatResult.textContent = body.reply || '(empty response)'
    chatResult.hidden = false
  } catch (error) {
    chatStatus.textContent = 'Unable to ask the LLM'
    chatResult.textContent = error instanceof Error ? error.message : String(error)
    chatResult.hidden = false
  } finally {
    submitButton.disabled = false
  }
})

async function runSampleAction(button, loadingText, action) {
  button.disabled = true
  sampleActionStatus.textContent = loadingText
  sampleActionResult.hidden = true
  sampleActionResult.textContent = ''

  try {
    await action()
  } catch (error) {
    sampleActionStatus.textContent = 'Action failed'
    sampleActionResult.textContent = error instanceof Error ? error.message : String(error)
    sampleActionResult.hidden = false
  } finally {
    button.disabled = false
  }
}

function showSampleResult(status, result) {
  sampleActionStatus.textContent = status
  sampleActionResult.textContent = result
  sampleActionResult.hidden = false
}

async function readTextResponse(response) {
  const text = await response.text()
  return text || `(empty response, HTTP ${response.status})`
}

function basicAuth(username, password) {
  return 'Basic ' + btoa(`${username}:${password}`)
}

productsButton.addEventListener('click', () =>
  runSampleAction(productsButton, 'Loading sample products...', async () => {
    const response = await fetch('/api/sample/products', {headers: {Accept: 'application/json'}})
    const products = await response.json()
    if (!response.ok) {
      throw new Error(`Products request failed with HTTP ${response.status}`)
    }
    const names = products.map(product => `- ${product.name} (${product.category})`).join('\n')
    showSampleResult(
      `Loaded ${products.length} active products`,
      `${names}\n\nOpen BootUI Cache, SQL Trace, or HTTP Exchanges to inspect this request.`
    )
  })
)

sqlQueryButton.addEventListener('click', () =>
  runSampleAction(sqlQueryButton, 'Running an SQL query...', async () => {
    const term = 'console'
    const response = await fetch(`/api/sample/product-search?term=${encodeURIComponent(term)}`, {
      headers: {Accept: 'application/json'}
    })
    const products = await response.json()
    if (!response.ok) {
      throw new Error(`SQL query failed with HTTP ${response.status}`)
    }
    const names = products.map(product => `- ${product.name} (${product.category})`).join('\n') || '(no rows)'
    showSampleResult(
      `Ran a live SQL SELECT and matched ${products.length} product(s) for "${term}"`,
      `${names}\n\nOpen BootUI SQL Trace to inspect the captured statement.`
    )
  })
)

greetingButton.addEventListener('click', () =>
  runSampleAction(greetingButton, 'Calling configured greeting...', async () => {
    const response = await fetch('/api/sample/hello')
    const text = await readTextResponse(response)
    if (!response.ok) {
      throw new Error(text)
    }
    showSampleResult('Configured greeting returned', `${text}\n\nOpen Configuration or Cache to inspect it.`)
  })
)

publicApiButton.addEventListener('click', () =>
  runSampleAction(publicApiButton, 'Calling public API...', async () => {
    const response = await fetch('/api/hello')
    const text = await readTextResponse(response)
    if (!response.ok) {
      throw new Error(text)
    }
    showSampleResult('Public API returned', `${text}\n\nOpen Mappings, HTTP Probe, or HTTP Exchanges to inspect it.`)
  })
)

secureAdminButton.addEventListener('click', () =>
  runSampleAction(secureAdminButton, 'Calling secured API as admin...', async () => {
    const response = await fetch('/api/secure', {
      headers: {Authorization: basicAuth('admin', 'admin')}
    })
    const text = await readTextResponse(response)
    if (!response.ok) {
      throw new Error(text)
    }
    showSampleResult('Admin access succeeded', `${text}\n\nOpen Security or Security Logs to inspect it.`)
  })
)

secureSqlButton.addEventListener('click', () =>
  runSampleAction(secureSqlButton, 'Calling secured SQL API as admin...', async () => {
    const response = await fetch('/api/secure/products', {
      headers: {Accept: 'application/json', Authorization: basicAuth('admin', 'admin')}
    })
    const products = await response.json()
    if (!response.ok) {
      throw new Error(`Secure SQL request failed with HTTP ${response.status}`)
    }
    const names = products.map(product => `- ${product.name} (${product.category})`).join('\n') || '(no rows)'
    showSampleResult(
      `Authenticated as admin and ran a live SQL SELECT returning ${products.length} product(s)`,
      `${names}\n\nOpen BootUI Live Activity and profile this request: it links a security event to the SQL it ran.`
    )
  })
)

secureUserButton.addEventListener('click', () =>
  runSampleAction(secureUserButton, 'Calling secured API as developer...', async () => {
    const response = await fetch('/api/secure', {
      headers: {Authorization: basicAuth('developer', 'developer')}
    })
    const text = await readTextResponse(response)
    showSampleResult(
      `Developer access returned HTTP ${response.status}`,
      `${text}\n\nThis forbidden response is useful in Security, Security Logs, and Pentesting.`
    )
  })
)

exceptionButton.addEventListener('click', () =>
  runSampleAction(exceptionButton, 'Triggering a sample exception...', async () => {
    const response = await fetch('/api/sample/boom', {
      headers: {Accept: 'application/json'}
    })
    await response.text()
    showSampleResult(
      `Triggered a sample exception (HTTP ${response.status})`,
      'The server threw an IllegalStateException on purpose.\n\n' +
        'Open BootUI Exceptions to inspect the captured stack trace and masked details.'
    )
  })
)

metricsBurstButton.addEventListener('click', () =>
  runSampleAction(metricsBurstButton, 'Recording sample metrics...', async () => {
    const response = await fetch('/api/sample/metrics-burst', {headers: {Accept: 'application/json'}})
    const body = await response.json()
    if (!response.ok) {
      throw new Error(`Metrics request failed with HTTP ${response.status}`)
    }
    showSampleResult(
      `Recorded ${body.iterations} samples to ${body.counter} and ${body.timer}`,
      `Counter total: ${body.counterTotal}\nTimer count: ${body.timerCount}\n\n` +
        'Open BootUI Metrics and search for "sample.orders" to inspect the custom meters.'
    )
  })
)

allocateButton.addEventListener('click', () =>
  runSampleAction(allocateButton, 'Allocating sample memory...', async () => {
    const response = await fetch('/api/sample/allocate', {headers: {Accept: 'application/json'}})
    const body = await response.json()
    if (!response.ok) {
      throw new Error(`Allocation request failed with HTTP ${response.status}`)
    }
    showSampleResult(
      `Allocated ${body.allocatedMb} MB (heap used ~${body.heapUsedMb} MB)`,
      'The buffer is released after the request.\n\n' +
        'Open BootUI Live Memory or Heap Dump to inspect the heap usage.'
    )
  })
)

slowButton.addEventListener('click', () =>
  runSampleAction(slowButton, 'Running a slow request...', async () => {
    const response = await fetch('/api/sample/slow', {headers: {Accept: 'application/json'}})
    const body = await response.json()
    if (!response.ok) {
      throw new Error(`Slow request failed with HTTP ${response.status}`)
    }
    showSampleResult(
      `Request slept for ${body.sleptMillis} ms on thread ${body.thread}`,
      'Open BootUI Threads (during the request) or Metrics to inspect the timing.'
    )
  })
)

poolStressButton.addEventListener('click', () =>
  runSampleAction(poolStressButton, 'Exercising the connection pool...', async () => {
    const response = await fetch('/api/sample/pool-stress', {headers: {Accept: 'application/json'}})
    const body = await response.json()
    if (!response.ok) {
      throw new Error(`Pool stress request failed with HTTP ${response.status}`)
    }
    showSampleResult(
      `Ran ${body.queries} concurrent queries in ${body.elapsedMillis} ms`,
      'Open BootUI Database Connection Pools to inspect active connections and usage.'
    )
  })
)

chainedButton.addEventListener('click', () =>
  runSampleAction(chainedButton, 'Making a multi-hop request...', async () => {
    const response = await fetch('/api/sample/chained', {headers: {Accept: 'application/json'}})
    const body = await response.json()
    if (!response.ok) {
      throw new Error(`Chained request failed with HTTP ${response.status}`)
    }
    showSampleResult(
      `Completed a multi-hop request (${body.activeProducts} active, ${body.searchMatches} matched)`,
      'This request nests child spans across a catalog read and a live query.\n\n' +
        'Open BootUI Traces or Diagnostics to inspect the correlated timeline.'
    )
  })
)

authFailButton.addEventListener('click', () =>
  runSampleAction(authFailButton, 'Calling secured API with bad credentials...', async () => {
    const response = await fetch('/api/secure', {
      headers: {Authorization: basicAuth('intruder', 'wrong-password')}
    })
    const text = await readTextResponse(response)
    showSampleResult(
      `Authentication failed with HTTP ${response.status}`,
      `${text}\n\nThis unauthorized response is useful in Security, Security Logs, and Pentesting.`
    )
  })
)
