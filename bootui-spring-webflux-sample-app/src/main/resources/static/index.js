const notesButton = document.getElementById('notes-button')
const noteLookupButton = document.getElementById('note-lookup-button')
const greetingButton = document.getElementById('greeting-button')
const exceptionButton = document.getElementById('exception-button')
const metricsBurstButton = document.getElementById('metrics-burst-button')
const allocateButton = document.getElementById('allocate-button')
const slowButton = document.getElementById('slow-button')
const poolStressButton = document.getElementById('pool-stress-button')
const transactionSamplesButton = document.getElementById('transaction-samples-button')
const chainedButton = document.getElementById('chained-button')
const sendEmailButton = document.getElementById('send-email-button')
const sampleActionStatus = document.getElementById('sample-action-status')
const sampleActionResult = document.getElementById('sample-action-result')
const sampleActionFeedback = document.getElementById('sample-action-feedback')
const sampleActionDetails = document.getElementById('sample-action-details')

async function runSampleAction(button, loadingText, action) {
  button.disabled = true
  sampleActionFeedback.hidden = false
  sampleActionDetails.open = false
  sampleActionStatus.textContent = loadingText
  sampleActionResult.hidden = true
  sampleActionResult.textContent = ''

  try {
    await action()
  } catch (error) {
    sampleActionStatus.textContent = 'Action failed'
    sampleActionResult.textContent = error instanceof Error ? error.message : String(error)
    sampleActionResult.hidden = false
    sampleActionDetails.open = true
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

notesButton.addEventListener('click', () =>
  runSampleAction(notesButton, 'Loading sample notes...', async () => {
    const response = await fetch('/api/notes', {headers: {Accept: 'application/json'}})
    const notes = await response.json()
    if (!response.ok) {
      throw new Error(`Notes request failed with HTTP ${response.status}`)
    }
    const titles = notes.map((note) => `- ${note.title}`).join('\n') || '(no rows)'
    showSampleResult(
      `Loaded ${notes.length} note(s)`,
      `${titles}\n\nOpen BootUI SQL Trace, Database Connection Pools, or HTTP Exchanges to inspect this request.`
    )
  })
)

noteLookupButton.addEventListener('click', () =>
  runSampleAction(noteLookupButton, 'Looking up a note...', async () => {
    const response = await fetch('/api/notes/1', {headers: {Accept: 'application/json'}})
    const body = await response.json()
    if (!response.ok) {
      throw new Error(`Note lookup failed with HTTP ${response.status}`)
    }
    showSampleResult(
      `Loaded note #${body.id}`,
      `${body.title}\n${body.body}\n\nOpen BootUI SQL Trace or HTTP Exchanges to inspect this request.`
    )
  })
)

greetingButton.addEventListener('click', () =>
  runSampleAction(greetingButton, 'Calling configured greeting...', async () => {
    const response = await fetch('/api/greetings/chained')
    const text = await readTextResponse(response)
    if (!response.ok) {
      throw new Error(text)
    }
    showSampleResult(
      'Configured greeting returned',
      `${text}\n\nOpen Configuration, Cache, or HTTP Probe to inspect it.`
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
    const response = await fetch('/api/sample/slow?ms=5000', {headers: {Accept: 'application/json'}})
    const body = await response.json()
    if (!response.ok) {
      throw new Error(`Slow request failed with HTTP ${response.status}`)
    }
    showSampleResult(
      `Request slept for ${body.sleptMillis} ms on thread ${body.thread}`,
      'Thread source → Live: locate the worker by its returned name.\n' +
        'Capture snapshot → Contention: inspect its wait and stack.\n' +
        'Observe threads → Observe & compare: compare the sampled interval. Timed waiting is not a deadlock.\n' +
        'Virtual workers (the default on JDK 21+) are not listed. Restart with ' +
        '--spring.threads.virtual.enabled=false for a visible worker.'
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

transactionSamplesButton.addEventListener('click', () =>
  runSampleAction(transactionSamplesButton, 'Generating transaction samples...', async () => {
    const response = await fetch('/api/sample/transaction-samples', {headers: {Accept: 'application/json'}})
    const body = await response.json()
    if (!response.ok) {
      throw new Error(`Transaction samples request failed with HTTP ${response.status}`)
    }
    showSampleResult(
      `Generated ${body.scenarios.length} transaction scenarios`,
      `The slow transaction held its boundary for ${body.slowMillis} ms. ` +
        'Open BootUI Transactions to inspect the committed and rolled-back boundaries.'
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
      `Completed a multi-hop request (${body.notes} notes, greeting "${body.greeting}")`,
      'This request nests child spans across a notes read and a greeting call.\n\n' +
        'Open BootUI Traces or HTTP Exchanges to inspect the correlated timeline.'
    )
  })
)

sendEmailButton.addEventListener('click', () =>
  runSampleAction(sendEmailButton, 'Sending test email...', async () => {
    const response = await fetch('/api/sample/send-email')
    const text = await readTextResponse(response)
    if (!response.ok) {
      throw new Error(text)
    }
    showSampleResult('Test email sent', `${text}\n\nOpen BootUI Email to inspect the captured message.`)
  })
)
