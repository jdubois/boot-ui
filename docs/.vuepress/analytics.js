/*
 * Consent-gated Google Analytics.
 *
 * Nothing here runs on the server and nothing touches the network until the reader has explicitly
 * accepted: the gtag.js script is injected only after a `granted` decision, so a visitor who
 * ignores or declines the banner never has an analytics cookie written. The decision itself lives
 * in localStorage rather than a cookie, which keeps the "remember my choice" storage strictly
 * necessary and outside the consent requirement.
 */

export const GA_MEASUREMENT_ID = 'G-V55EF46P7M'

const CONSENT_STORAGE_KEY = 'bootui-analytics-consent'
const CONSENT_EVENT = 'bootui:analytics-consent'
const GRANTED = 'granted'
const DENIED = 'denied'

let scriptLoaded = false
let lastTrackedPath = null

export function readConsent() {
  if (typeof window === 'undefined') {
    return null
  }

  try {
    const stored = window.localStorage.getItem(CONSENT_STORAGE_KEY)
    return stored === GRANTED || stored === DENIED ? stored : null
  } catch {
    // Private browsing modes can throw on access. Treat an unreadable store as "not asked yet"
    // rather than assuming consent.
    return null
  }
}

export function setConsent(consent) {
  const normalized = consent === GRANTED ? GRANTED : DENIED

  try {
    window.localStorage.setItem(CONSENT_STORAGE_KEY, normalized)
  } catch {
    // A rejected write only costs the reader the banner on their next visit, so honour the choice
    // for this session anyway.
  }

  applyConsent(normalized)
  window.dispatchEvent(new CustomEvent(CONSENT_EVENT, {detail: normalized}))
}

export function onConsentChange(listener) {
  if (typeof window === 'undefined') {
    return () => {}
  }

  const handler = (event) => listener(event.detail)
  window.addEventListener(CONSENT_EVENT, handler)
  return () => window.removeEventListener(CONSENT_EVENT, handler)
}

export function applyConsent(consent) {
  if (consent === GRANTED) {
    loadGoogleAnalytics()
    return
  }

  disableGoogleAnalytics()
}

export function trackPageView(path) {
  if (!scriptLoaded || readConsent() !== GRANTED || path === lastTrackedPath) {
    return
  }

  lastTrackedPath = path
  window.gtag('event', 'page_view', {
    page_path: path,
    page_location: window.location.href,
    page_title: document.title
  })
}

function loadGoogleAnalytics() {
  if (scriptLoaded) {
    return
  }

  scriptLoaded = true
  window[`ga-disable-${GA_MEASUREMENT_ID}`] = false
  window.dataLayer = window.dataLayer || []
  window.gtag = function gtag() {
    // gtag.js reads `arguments` verbatim, so this cannot be a rest-parameter forward.
    window.dataLayer.push(arguments)
  }

  window.gtag('js', new Date())
  window.gtag('config', GA_MEASUREMENT_ID)
  lastTrackedPath = window.location.pathname

  const script = document.createElement('script')
  script.async = true
  script.src = `https://www.googletagmanager.com/gtag/js?id=${GA_MEASUREMENT_ID}`
  document.head.appendChild(script)
}

function disableGoogleAnalytics() {
  // Withdrawal has to be as effective as refusal, so an already-loaded tag is muted through the
  // opt-out flag gtag.js checks before every hit, and its cookies are removed.
  window[`ga-disable-${GA_MEASUREMENT_ID}`] = true
  lastTrackedPath = null
  clearAnalyticsCookies()
}

function clearAnalyticsCookies() {
  const names = document.cookie
    .split(';')
    .map((cookie) => cookie.split('=')[0].trim())
    .filter((name) => name === '_ga' || name === '_gid' || name.startsWith('_ga_') || name.startsWith('_gat'))

  const hostname = window.location.hostname
  // The tag may have scoped its cookie to the exact host or to the registrable domain, and a
  // mismatched delete is a silent no-op, so try every candidate.
  const domains = new Set(['', hostname, `.${hostname}`, toRegistrableDomain(hostname)].filter(Boolean))
  domains.add('')

  names.forEach((name) => {
    domains.forEach((domain) => {
      const domainPart = domain ? `; domain=${domain}` : ''
      document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:01 GMT${domainPart}`
    })
  })
}

function toRegistrableDomain(hostname) {
  const labels = hostname.split('.')
  return labels.length > 2 ? `.${labels.slice(-2).join('.')}` : undefined
}
