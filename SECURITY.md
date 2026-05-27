# Security Policy

## Supported versions

BootUI is currently pre-1.0. Only the latest release on `main` receives
security fixes.

| Version          | Supported |
|------------------|-----------|
| 0.1.x (snapshot) | ✅         |

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's private vulnerability reporting on this repository:

1. Go to the **Security** tab.
2. Click **Report a vulnerability**.
3. Describe the issue, the affected version, and a reproduction.

You will receive an acknowledgement within five working days. We aim to
provide a fix or mitigation within thirty days for high-severity issues.

## Threat model and intended use

BootUI is a **local developer console**. By design it:

- activates only on the `dev` / `local` profile, when DevTools is on the
  classpath, or when explicitly enabled with `bootui.enabled=ON`;
- exposes its endpoints on the loopback interface only — non-loopback
  requests are rejected unless `bootui.allow-non-localhost=true` is set;
- masks values for property keys that look like secrets (`password`, `token`,
  `secret`, `key`, …).

**BootUI must never be enabled in production.** Issues that require running
BootUI in a production-like setting (publicly exposed, with security
disabled) will be closed as out-of-scope.

In-scope security issues include:

- A way to access BootUI endpoints from a non-loopback origin when
  `bootui.allow-non-localhost=false`.
- A configuration that causes BootUI to activate when neither the `dev`
  profile is active, DevTools is present, nor `bootui.enabled=ON`.
- Secret values leaked in API responses despite default masking.
- Stored XSS or RCE against the bundled Vue UI.
- Path traversal through the runtime overrides file store.
