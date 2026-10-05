// Shared plain-HTTP client for the validation traffic scripts: no dependency beyond Node 20 or newer.
// Every script runs a fixed number of iterations in a fixed order, so two runs send the same requests in the same
// order; only generated identifiers (logins, product codes) carry the run id. Each script prints its duration and
// request count, and writes a summary file when asked.

import {writeFileSync} from 'node:fs'
import {parseArgs} from 'node:util'

export function options(defaults) {
  const {values} = parseArgs({
    options: {
      'base-url': {type: 'string', default: defaults.baseUrl},
      iterations: {type: 'string', default: String(defaults.iterations)},
      'pause-ms': {type: 'string', default: String(defaults.pauseMs ?? 0)},
      'run-id': {type: 'string', default: process.env.RUN_ID || String(Date.now() % 1_000_000_000)},
      summary: {type: 'string'},
      'allow-unexpected': {type: 'boolean', default: false}
    },
    allowPositionals: false
  })
  const baseUrl = values['base-url'].replace(/\/+$/, '')
  if (new URL(baseUrl).port === '8080') {
    throw new Error('Port 8080 is reserved for the developer’s own application; pass --base-url with another port')
  }
  return {
    baseUrl,
    iterations: Number.parseInt(values.iterations, 10),
    pauseMs: Number.parseInt(values['pause-ms'], 10),
    runId: values['run-id'],
    summary: values.summary,
    allowUnexpected: values['allow-unexpected']
  }
}

export const sleep = (ms) => (ms > 0 ? new Promise((resolve) => setTimeout(resolve, ms)) : Promise.resolve())

export class Traffic {
  constructor(name, opts) {
    this.name = name
    this.opts = opts
    this.started = Date.now()
    this.requests = 0
    this.byRoute = {}
    this.statuses = {}
    this.unexpected = []
    this.errors = 0
  }

  /** A client with its own cookie jar, so an anonymous visitor, a user, and an administrator stay apart. */
  client(baseUrl = this.opts.baseUrl) {
    return new Client(this, baseUrl)
  }

  record(label, status, expect) {
    this.requests++
    this.byRoute[label] = (this.byRoute[label] || 0) + 1
    this.statuses[status] = (this.statuses[status] || 0) + 1
    if (expect && !expect.includes(status)) {
      if (this.unexpected.length < 50) this.unexpected.push({route: label, status, expected: expect})
      else this.unexpected.push(null)
    }
  }

  finish() {
    const summary = {
      application: this.name,
      baseUrl: this.opts.baseUrl,
      runId: this.opts.runId,
      iterations: this.opts.iterations,
      pauseMs: this.opts.pauseMs,
      startedAt: new Date(this.started).toISOString(),
      durationSeconds: Math.round((Date.now() - this.started) / 100) / 10,
      requests: this.requests,
      statuses: this.statuses,
      byRoute: Object.fromEntries(Object.entries(this.byRoute).sort(([a], [b]) => a.localeCompare(b))),
      unexpected: this.unexpected.filter(Boolean),
      unexpectedCount: this.unexpected.length
    }
    if (this.opts.summary) writeFileSync(this.opts.summary, JSON.stringify(summary, null, 2) + '\n')
    console.log(
      `${this.name}: ${summary.requests} requests in ${summary.durationSeconds} s ` +
        `(${this.opts.iterations} iterations), statuses ${JSON.stringify(summary.statuses)}, ` +
        `${summary.unexpectedCount} unexpected`
    )
    for (const miss of summary.unexpected.slice(0, 10)) {
      console.log(`  unexpected ${miss.status} for ${miss.route}, expected ${miss.expected.join(' or ')}`)
    }
    if (summary.unexpectedCount > 0 && !this.opts.allowUnexpected) process.exitCode = 2
    return summary
  }
}

class Client {
  constructor(traffic, baseUrl) {
    this.traffic = traffic
    this.baseUrl = baseUrl
    this.cookies = new Map()
    this.headers = {}
  }

  cookieHeader() {
    return [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
  }

  storeCookies(response) {
    for (const line of response.headers.getSetCookie?.() ?? []) {
      const [pair] = line.split(';')
      const index = pair.indexOf('=')
      if (index > 0) this.cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim())
    }
  }

  /**
   * Sends one request and records it under `label` (the route template, such as `GET /owners/{ownerId}`). Redirects
   * are not followed, so each request the application serves is counted once.
   */
  async request(method, path, {label, form, json, body, headers = {}, expect, timeoutMs = 30_000} = {}) {
    const init = {method, redirect: 'manual', headers: {...this.headers, ...headers}}
    if (form) {
      init.body = new URLSearchParams(form).toString()
      init.headers['Content-Type'] = 'application/x-www-form-urlencoded'
    } else if (json !== undefined) {
      init.body = JSON.stringify(json)
      init.headers['Content-Type'] = 'application/json'
    } else if (body !== undefined) {
      init.body = body
    }
    if (this.cookies.size) init.headers.Cookie = this.cookieHeader()
    init.signal = AbortSignal.timeout(timeoutMs)
    let status = 'ERROR'
    let text = ''
    let location = null
    try {
      const response = await fetch(this.baseUrl + path, init)
      this.storeCookies(response)
      status = response.status
      location = response.headers.get('location')
      text = await response.text()
    } catch (error) {
      this.traffic.errors++
      text = String(error)
    }
    this.traffic.record(label || `${method} ${path.split('?')[0]}`, status, expect)
    return {status, text, location, json: () => JSON.parse(text)}
  }

  get(path, opts) {
    return this.request('GET', path, opts)
  }

  post(path, opts) {
    return this.request('POST', path, opts)
  }
}

/** Reads the hidden `_csrf` field Spring Security renders into a form. */
export function csrfField(html) {
  const match = /name="_csrf"\s+value="([^"]+)"/.exec(html) || /value="([^"]+)"\s+name="_csrf"/.exec(html)
  return match ? match[1] : null
}
