---
# The sidebar entry for this page is a single top-level link, so its headings must not expand
# under it: that first level is reserved for other pages everywhere else in the sidebar.
sidebarDepth: 0
---

# Privacy

This page describes what the BootUI documentation site collects, and how to change your mind at any time.

## The BootUI console itself collects nothing

BootUI is a local-only developer console. It runs inside your own application, binds to loopback, and never sends
telemetry, usage data, or analytics anywhere. Nothing on this page applies to the console — only to this documentation
website.

## Analytics on this website

This website can use Google Analytics 4 (measurement ID `G-V55EF46P7M`) to count page views and understand which
documentation pages are actually read.

- **Nothing is loaded until you accept.** The Google Analytics script is not requested, and no analytics cookie is
  written, unless you choose "Accept" in the banner. Declining, or ignoring the banner, leaves the site entirely
  free of third-party requests.
- **What is stored if you accept:** Google Analytics sets the `_ga` and `_ga_*` cookies, which hold a randomly
  generated identifier used to recognise a returning browser. Google receives your IP address, page URL, referrer,
  and basic device and browser information as the data processor for these measurements.
- **Retention:** analytics data is retained according to the Google Analytics data-retention setting for this
  property, after which it is deleted by Google.
- **Legal basis:** your consent, under Article 6(1)(a) GDPR and the ePrivacy Directive. You can withdraw it at any
  time, as easily as you gave it, using the controls below.

Your answer to the banner is remembered in your browser's `localStorage` under `bootui-analytics-consent`. That entry
is strictly necessary to honour your choice, is never sent anywhere, and is not used to identify you.

## Change your choice

<CookieSettings />

Refusing here also deletes any Google Analytics cookies already present in this browser and stops further measurement
immediately. You can additionally remove the site's data through your browser settings, or install Google's
[opt-out browser add-on](https://tools.google.com/dlpage/gaoptout).

## Hosting

The site is served as static files from GitHub Pages. GitHub processes request metadata, including IP addresses, to
deliver the pages and protect the service, as described in the
[GitHub Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-privacy-statement).

## Your rights and contact

Under GDPR you may request access to, correction of, or erasure of your personal data, object to processing, or lodge a
complaint with your supervisory authority. For any request relating to this site, open an issue on the
[BootUI repository](https://github.com/jdubois/boot-ui/issues) or contact the maintainer through
[julien-dubois.com](https://www.julien-dubois.com).
