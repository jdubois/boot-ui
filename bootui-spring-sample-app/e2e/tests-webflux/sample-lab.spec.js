import {expect, test} from '../tests/fixtures.js'
import {registerSampleLabTests} from '../scenarios/sample-lab.js'
import {registerBrowserIconTests} from '../scenarios/browser-icons.js'

registerBrowserIconTests(test, expect)

registerSampleLabTests(test, expect, {
  groups: ['Requests', 'Runtime', 'Threads', 'Data', 'Integrations & AI'],
  failureEndpoint: '/api/notes',
  actions: [
    ['Data', 'Check notes', /Loaded \d+ note\(s\)/],
    ['Data', 'Look up a note', 'Loaded note #1'],
    ['Data', 'Exercise the connection pool', /Ran \d+ concurrent queries/],
    ['Data', 'Generate transaction samples', 'Generated 3 transaction scenarios'],
    ['Runtime', 'Trigger an exception', 'Triggered a sample exception (HTTP 500)'],
    ['Runtime', 'Generate metrics traffic', 'Recorded 5 samples'],
    ['Runtime', 'Allocate sample memory', 'Allocated 32 MB'],
    ['Threads', 'Run a slow request', 'Request slept for 5000 ms'],
    ['Integrations & AI', 'Send test email', 'Test email sent'],
    ['Requests', 'Call configured greeting', 'Configured greeting returned'],
    ['Requests', 'Make a multi-hop request', 'Completed a multi-hop request']
  ]
})
