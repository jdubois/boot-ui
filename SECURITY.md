# Security Policy

## Supported versions

BootUI is currently pre-1.0. Only the latest released version receives
security fixes; older releases do not.

| Version | Supported |
|---------|-----------|
| 0.2.x   | ✅         |
| < 0.2   | ❌         |

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
  `secret`, `key`, …) — controlled by `bootui.expose-values`, which defaults to
  `MASKED`.

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
  (the Claude Code panel disables it by default);
- automatically disabled when `bootui.expose-values=METADATA_ONLY`;
- subject to the standard loopback-only filter applied to every BootUI
  endpoint.

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
- Path traversal through the runtime overrides file store, or through the
  Copilot/Claude Code session-state directories.
- Sanitized agent session payloads leaking prompts, raw tool arguments,
  command output, or diffs that should only be reachable through the gated raw
  reveal endpoint.
