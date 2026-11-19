# Security Policy

## Supported versions

BootUI's latest stable release line receives security fixes; older pre-1.0
releases do not.

| Version | Supported |
| ------- | --------- |
| 1.x.x   | ✅        |
| < 1.0   | ❌        |

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's private vulnerability reporting on this repository:

1. Go to the **Security** tab.
2. Click **Report a vulnerability**.
3. Describe the issue, the affected version, and a reproduction.

You will receive an acknowledgement within five working days. We aim to
provide a fix or mitigation within thirty days for high-severity issues.

## Threat model and intended use

BootUI is a **local developer console**, not an application authentication
system. Spring MVC and Spring WebFlux activate it in `AUTO` mode only for the
configured local profiles or when DevTools is present, unless
`bootui.enabled=ON` explicitly forces activation. The Quarkus extension wires
the console only in development and test launch modes; its API is absent and
its static shell is suppressed in a normal production launch.

BootUI uses the host application's HTTP listener. It does not separately bind
the console to a loopback interface. Instead, every adapter applies the same
request-time safety and authentication policy described below.

### Request filter and authentication model

For Spring MVC, Spring WebFlux, and Quarkus, BootUI evaluates requests in this
order:

1. Security headers are attached to the BootUI response.
2. `LocalhostGuard` protects the entire configured UI and API surface.
3. Bearer/cookie authentication protects the configured API surface.
4. Per-panel enabled/read-only policy is applied before an API handler runs.

Spring MVC implements this with servlet filters ordered at
`Integer.MIN_VALUE` through `Integer.MIN_VALUE + 3`; WebFlux uses the same
relative order with `WebFilter`; Quarkus uses Vert.x route-filter priorities
`1000` (guard), `975` (authentication), and `950` (panel policy). The Spring
Security integration deliberately permits BootUI routes in its own
highest-precedence chain: it is the BootUI filters above, not the host
application's login, that enforce this boundary.

Unless `bootui.allow-non-localhost=true`, `LocalhostGuard` applies these checks
in order:

1. The raw TCP peer must be loopback, in a CIDR listed by
   `bootui.trusted-proxies`, or an auto-detected container gateway trusted by
   `bootui.trust-container-gateway`. Forwarded client-address headers are not
   consulted.
2. A present `Host` header must use a built-in loopback name or a hostname in
   `bootui.allowed-hosts`.
3. A state-changing request is rejected when `Sec-Fetch-Site` is `cross-site`,
   or when a present `Origin` has a different host. The comparison intentionally
   ignores scheme and port; requests with neither header are not rejected by
   this check.

`bootui.allowed-hosts` does not trust a source address. Conversely,
`bootui.trusted-proxies` and `bootui.trust-container-gateway` treat the matching
raw peer as trusted: those callers remain authentication-free, but the `Host`
and cross-site-write checks still apply. Scope these trust mechanisms to
controlled local/development networks.

`bootui.allow-non-localhost=true` is a broader escape hatch. It bypasses all
three `LocalhostGuard` checks, but it does **not** mark the peer as trusted.
The static SPA may load, while every `/bootui/api/**` request from that peer
must present either the configured/generated token as
`Authorization: Bearer <token>` or the BootUI session cookie. Authentication
runs before panel policy, so an unauthenticated API caller receives `401`
without learning panel availability.

### Token generation and browser cookie exchange

`bootui.authentication.token` is used verbatim when configured. When it is
blank, BootUI generates a 256-bit URL-safe token for the lifetime of that
application process. A generated token is logged once at startup when any
remote-access option is configured (`allow-non-localhost`, trusted CIDRs, or
container-gateway trust); a configured token is never logged. Keep configured
tokens in an environment-backed secret rather than source control, and treat
startup logs containing a generated token as credentials.

The unlock screen sends the token once in a bearer-authenticated
`POST <api-path>/auth/session`. After the guard and token checks pass, an
untrusted remote caller receives `204` plus a `BOOTUI_SESSION` cookie containing
the same token. The cookie is `HttpOnly`, `SameSite=Strict`, and scoped to the
configured API path including the application's context/root path. It is marked
`Secure` only when the adapter sees the request as HTTPS. Trusted loopback,
CIDR, and container-gateway callers do not receive or need this cookie.

Use end-to-end HTTPS for remote access. Bearer credentials and a cookie without
the `Secure` attribute can be intercepted over plain HTTP. When TLS terminates
at a development proxy, configure the host framework/proxy so the backend
correctly recognizes the original HTTPS scheme before relying on the cookie's
`Secure` attribute. BootUI does not provide token rotation, login throttling,
or user-level authorization.

### Data exposure

BootUI masks values for property keys that look like secrets (`password`,
`token`, `secret`, `key`, …). This is controlled by `bootui.expose-values`,
which defaults to `MASKED`.

### CLI download verification

The installers require a published checksum and, when GnuPG is installed, an OpenPGP signature from primary key
`7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9`. The GitHub Action bundles that public key and never contacts a keyserver.
Installers fetch the same key from BootUI's site into a private temporary keyring. A checksum authenticates only
repository integrity, not the signer; the output says which verification completed. Older releases can use SHA-1
with a warning; new release bundles additionally include SHA-256.

For manual verification, download the jar and its `.asc` from the same Maven repository path, then:

```bash
curl -fsSLo bootui-release-key.asc https://www.julien-dubois.com/boot-ui/bootui-release-key.asc
gpg --show-keys --with-fingerprint bootui-release-key.asc
# Compare the primary fingerprint with the pinned value above before importing.
gpg --import bootui-release-key.asc
gpg --status-fd 1 --verify bootui-cli-VERSION-all.jar.asc bootui-cli-VERSION-all.jar
```

Require a successful exit and a `VALIDSIG` whose primary fingerprint is the pinned value. An installer
`--allow-unverified` / `-AllowUnverified` only bypasses a missing checksum, never a checksum mismatch or invalid
signature. `bootui update` remains checksum-verified; it does not claim OpenPGP signer authentication.

### Local agent session panels (Copilot and Claude Code)

The Copilot and Claude Code panels surface activity from local AI coding
agents by reading the session state each CLI writes on disk:

- the Copilot panel reads `~/.copilot/session-state/` (or the path configured
  via `bootui.copilot.session-state-dir`), including each session's
  `events.jsonl` file;
- the Claude Code panel reads `~/.claude/projects/` (or the path configured
  via `bootui.claude-code.session-state-dir`), including its per-session JSONL
  logs.

Both data flows are local-only and **read-only** — BootUI never writes to or
deletes from those directories.

The default `/bootui/api/copilot/**` and `/bootui/api/claude-code/**` payloads
contain only allowlisted, sanitized fields: event type, tool name, category,
timestamp, success flag, and a short summary. Prompts, raw tool arguments,
command output, file diffs, and other agent session content are deliberately
excluded from the default payloads.

The per-event raw reveal endpoint
(`/bootui/api/{copilot,claude-code}/sessions/{id}/events/{eventId}/raw`)
returns the source JSON for one event on demand. It is:

- gated by `bootui.copilot.allow-raw-reveal` / `bootui.claude-code.allow-raw-reveal`
  (Copilot enables it by default; Claude Code disables it by default because its
  logs can contain prompts and outputs);
- automatically disabled when `bootui.expose-values=METADATA_ONLY`;
- subject to the standard `LocalhostGuard` and API authentication policy
  applied to every BootUI endpoint.

**BootUI must never be enabled in production.** Issues that require running
BootUI in a production-like setting (publicly exposed, with security
disabled) will be closed as out-of-scope.

In-scope security issues include:

- A way to access BootUI endpoints from a non-loopback origin when
  `bootui.allow-non-localhost=false` and `bootui.trusted-proxies` is empty.
- A way to bypass the `Host` allow-list (DNS-rebinding) or cross-site write
  protections while `LocalhostGuard` is enabled.
- A way for a peer admitted only by `bootui.allow-non-localhost=true` to access
  a BootUI API without the correct bearer token or session cookie.
- A way for an untrusted caller to exchange an invalid token for a
  `BOOTUI_SESSION` cookie, or to bypass authentication through filter ordering.
- Trusting a client-supplied forwarded address instead of the raw TCP peer.
- Disclosure of a configured token, or disclosure of a generated token outside
  the intentional startup log and authenticated session-cookie exchange.
- A configuration that activates a Spring adapter without a configured enabled
  profile, DevTools, or `bootui.enabled=ON`, or that wires the Quarkus API in a
  normal production launch.
- Secret values leaked in API responses despite default masking.
- Stored XSS or RCE against the bundled Vue UI.
- Path traversal through the runtime overrides file store, or through the
  Copilot/Claude Code session-state directories.
- Sanitized agent session payloads leaking prompts, raw tool arguments,
  command output, or diffs that should only be reachable through the gated raw
  reveal endpoint.
- Raw thread dumps or heap dumps bypassing their confirmation/read-only gates or
  configured raw-download controls.
