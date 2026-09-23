/*
 * Consent handling is a privacy control, so it gets the only automated coverage on the site: the
 * real module is driven against a minimal window/document stub. Each case imports its own copy of
 * the module, because the loader deliberately keeps per-page-session state in module scope.
 */

import assert from 'node:assert/strict'
import test from 'node:test'

let nextCase = 0

async function withStubbedBrowser({storage = 'available'} = {}) {
  const store = new Map()
  const listeners = new Map()
  const injectedScripts = []

  const unavailable = () => {
    throw new DOMException('The operation is insecure.', 'SecurityError')
  }

  const localStorage =
    storage === 'available'
      ? {
          getItem: (key) => (store.has(key) ? store.get(key) : null),
          setItem: (key, value) => store.set(key, String(value))
        }
      : {getItem: unavailable, setItem: unavailable}

  globalThis.CustomEvent = class CustomEvent {
    constructor(type, init = {}) {
      this.type = type
      this.detail = init.detail
    }
  }

  globalThis.document = {
    title: 'BootUI',
    cookie: '',
    head: {
      appendChild(script) {
        injectedScripts.push(script)
      }
    },
    createElement: () => ({})
  }

  globalThis.window = {
    location: {hostname: 'bootui.dev', href: 'https://bootui.dev/guide/', pathname: '/guide/'},
    localStorage,
    addEventListener(type, handler) {
      listeners.set(type, [...(listeners.get(type) ?? []), handler])
    },
    removeEventListener() {},
    dispatchEvent(event) {
      ;(listeners.get(event.type) ?? []).forEach((handler) => handler(event))
    }
  }

  // A query string gives every case an unshared module instance.
  const analytics = await import(`./analytics.js?case=${nextCase++}`)

  return {
    analytics,
    injectedScripts,
    disabled: () => globalThis.window[`ga-disable-${analytics.GA_MEASUREMENT_ID}`],
    pageViews: () =>
      Array.from(globalThis.window.dataLayer ?? [])
        .filter((entry) => entry[0] === 'event' && entry[1] === 'page_view')
        .map((entry) => entry[2].page_path)
  }
}

test('nothing is loaded before a decision is made', async () => {
  const {analytics, injectedScripts, disabled} = await withStubbedBrowser()

  analytics.applyConsent(analytics.readConsent())

  assert.equal(injectedScripts.length, 0)
  assert.equal(disabled(), true)
})

test('refusing keeps the tag out of the document', async () => {
  const {analytics, injectedScripts, disabled} = await withStubbedBrowser()

  analytics.setConsent('denied')

  assert.equal(injectedScripts.length, 0)
  assert.equal(disabled(), true)
})

test('accepting, withdrawing and accepting again re-enables analytics in the same page session', async () => {
  const {analytics, injectedScripts, disabled} = await withStubbedBrowser()

  analytics.setConsent('granted')
  assert.equal(disabled(), false)

  analytics.setConsent('denied')
  assert.equal(disabled(), true)

  analytics.setConsent('granted')
  assert.equal(disabled(), false)
  assert.equal(injectedScripts.length, 1, 'the gtag.js script is injected only once')
})

test('page views resume after re-consent', async () => {
  const {analytics, pageViews} = await withStubbedBrowser()

  analytics.setConsent('granted')
  analytics.setConsent('denied')
  analytics.trackPageView('/guide/panels/')
  assert.deepEqual(pageViews(), [], 'a withdrawn reader is not tracked')

  analytics.setConsent('granted')
  analytics.trackPageView('/guide/panels/')
  assert.deepEqual(pageViews(), ['/guide/', '/guide/panels/'], 'the page consent was given on, then the navigation')
})

test('re-picking the answer already in force does not report the page again', async () => {
  const {analytics, pageViews} = await withStubbedBrowser()

  // `gtag('config', ...)` reports the current page itself on a first accept, so an accept that
  // changes nothing must stay silent rather than counting the page a second time.
  analytics.setConsent('granted')
  analytics.setConsent('granted')

  assert.deepEqual(pageViews(), [])
})

test('a browser that refuses storage still gets the banner, not assumed consent', async () => {
  const {analytics, injectedScripts, disabled} = await withStubbedBrowser({storage: 'unavailable'})

  assert.equal(analytics.readConsent(), null)

  analytics.applyConsent(analytics.readConsent())

  assert.equal(injectedScripts.length, 0)
  assert.equal(disabled(), true)
})

test('the choice is honoured for the session when it cannot be persisted', async () => {
  const {analytics, disabled, pageViews} = await withStubbedBrowser({storage: 'unavailable'})

  analytics.setConsent('granted')
  assert.equal(analytics.readConsent(), 'granted')
  assert.equal(disabled(), false)

  analytics.trackPageView('/guide/panels/')
  assert.deepEqual(pageViews(), ['/guide/panels/'], 'navigation is measured despite the failed write')

  analytics.setConsent('denied')
  assert.equal(analytics.readConsent(), 'denied')
  assert.equal(disabled(), true)

  analytics.trackPageView('/guide/setup/')
  assert.deepEqual(pageViews(), ['/guide/panels/'], 'withdrawal is honoured too')

  analytics.setConsent('granted')
  assert.equal(disabled(), false)
  assert.deepEqual(pageViews(), ['/guide/panels/', '/guide/'], 'the page re-consent was given on')
})
