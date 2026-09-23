/*
 * Consent handling is a privacy control, so it gets the only automated coverage on the site: the
 * real module is driven against a minimal window/document stub. Each case imports its own copy of
 * the module, because the loader deliberately keeps per-page-session state in module scope.
 */

import assert from 'node:assert/strict'
import test from 'node:test'

let nextCase = 0

async function withStubbedBrowser() {
  const store = new Map()
  const listeners = new Map()
  const injectedScripts = []

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
    localStorage: {
      getItem: (key) => (store.has(key) ? store.get(key) : null),
      setItem: (key, value) => store.set(key, String(value))
    },
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
    pageViews: () => (globalThis.window.dataLayer ?? []).filter((entry) => entry[0] === 'event').length
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
  assert.equal(pageViews(), 0, 'a withdrawn reader is not tracked')

  analytics.setConsent('granted')
  analytics.trackPageView('/guide/panels/')
  assert.equal(pageViews(), 2, 'the page consent was given on, then the next navigation')
})
