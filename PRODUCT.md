# Product

## Register

product

## Users

Spring Boot developers working in the **inner development loop** on their own machine, against a single app
running on `localhost`. They reach for BootUI with the app already running, mid-task, to answer a concrete
question fast ("why is this bean wired like that?", "what config is actually effective?", "is this endpoint
healthy?") and get back to coding. Four recurring shapes (from the spec's persona table):

- **Solo / backend developer** — wants to understand a project quickly, configure it correctly, and inspect
  endpoints. Value: one local URL with beans, config, mappings, health, and logs.
- **Enterprise service onboarder** — inherits unfamiliar profiles, conditional beans, and dependencies. Value:
  BootUI explains the effective runtime state without reading the whole codebase first.
- **Platform engineer** — wants a standard way to inspect many Spring Boot services. Value: one common
  diagnostic surface every team can rely on.
- **Microservices developer** — debugging local service wiring and environment issues. Value: local health,
  connection details, mappings, and config sources at a glance.

These are technical, time-pressured users who already know Spring. They don't need hand-holding; they need the
runtime made legible. Increasingly, an **AI coding agent** (Copilot, Claude Code) is a second "user" reading the
same panels through the opt-in MCP server — so the information has to be structured and explained, not just drawn.

## Product Purpose

BootUI is a **Spring Boot 4 starter** that drops an embedded, **local-only** developer console into a running
app. Its primary goal is blunt: **make a running Spring Boot application understandable in minutes.** It is a
Spring-native *visualization and explanation layer* over Actuator and the live application context — not an APM,
not production monitoring, not a hosted dashboard, not an Actuator replacement.

Secondary goals: cut time spent debugging auto-configuration and configuration; help developers onboard onto
unfamiliar services; give an IDE-agnostic surface for runtime insight; make Actuator data readable and
actionable; and act as an extensible platform where Spring ecosystem libraries (and BootUI's own advisors) add
panels.

Success looks like: a developer opens `/bootui`, and within a couple of minutes understands their app's
profiles, wiring, config, health, and risks well enough to act — without leaving their normal workflow, and
without BootUI ever leaking a secret, calling the network unprompted, or staying enabled in production.

## Brand Personality

**Polished, modern, confident.** BootUI is a craft demonstration: a tool that advises Spring developers on
architecture, security, and performance has to *look* like it was built by someone who holds those standards. It
should feel like best-in-class developer tooling (the Linear / Stripe / Raycast tier), translated into the
Spring world — calm, precise, and quietly authoritative.

- **Voice:** expert and direct. Explains, never lectures. States findings plainly ("1 violation found"),
  recommends a concrete fix, and gets out of the way.
- **Tone:** reassuring under pressure. Status, risk, and "why unavailable" messages stay matter-of-fact, never
  alarmist and never cute at the user's expense.
- **Emotional goal:** *trust and relief.* The developer should feel the app just became legible and that BootUI
  is a safe, careful guest in their process — confident enough to rely on, restrained enough to disappear into
  the task.

## Anti-references

- **Generic Bootstrap admin templates (AdminLTE, SB Admin, "Bootstrap dashboard" kits).** This is the primary
  thing to steer away from — and the sharpest constraint, because BootUI is itself built on **Bootstrap 5.3**.
  The mandate is therefore: *use Bootstrap, never look like default Bootstrap.* Stock components, the default
  blue, flat card-grid admin layouts, and untouched utility-class styling are failure states here.
- **Old-school enterprise Java tooling** — JConsole / VisualVM chrome, or raw Actuator JSON dumped on screen.
  BootUI exists to *replace* that experience, not echo it.
- **Overwhelming APM walls-of-charts** (Grafana / Datadog density with no narrative). Density is fine; density
  *without explanation* is not.
- **AI SaaS slop** — gradient hero + identical icon-card grids + cream/sand backgrounds + per-section uppercase
  eyebrows. The tool should never read as template-generated.

## Design Principles

1. **Practice what you preach.** BootUI critiques other people's apps for quality; its own UI is the proof. Every
   panel is held to the standard the advisors recommend — accessible, consistent, intentional.
2. **Explain before you dump.** Lead with the human-readable interpretation (what it means, why it matters, what
   to do); keep the raw Actuator/JSON detail one disclosure away. The value is the explanation layer, not the
   data firehose.
3. **Search first, then scan.** Users arrive with a specific question. Filtering and search are the primary
   affordance on data-heavy panels; the layout serves "find the one thing," not "admire the dashboard."
4. **Never surprise the user.** No network calls, scans, or mutations without an explicit action. Destructive or
   external operations are opt-in, clearly labeled, and reversible-by-default. Predictability *is* the feature.
5. **Fail closed to earn trust.** Local-only, secret-masked, disabled-and-silent in production. When state is
   ambiguous, BootUI does less, not more — and says clearly why a panel is unavailable, with the fix.

## Accessibility & Inclusion

Target: **WCAG 2.1 AA across both light and dark themes** — the professional bar that matches the "best-in-class"
personality, and a credibility requirement for a tool that audits other apps.

- **Contrast:** all meaningful text and state colors meet AA (≥4.5:1 body, ≥3:1 large/UI) in *both* themes. The
  recurring trap is semantic status colors (log levels, severities) and code/identifier text on tinted or
  selected backgrounds — these must be verified, not assumed, since the app leans on Bootstrap's contextual
  colors that are tuned for light backgrounds only.
- **Keyboard & focus:** every interactive control is reachable and operable by keyboard with a visible,
  consistent focus indicator (including custom buttons, nav toggles, and the command palette).
- **Reduced motion:** honor `prefers-reduced-motion` for all decorative and transitional motion (already a
  baseline; keep it).
- **Don't rely on color alone:** pair status color with text/icon (badges, level labels) so color-blind users
  and grayscale contexts still read the meaning.
- **Screen readers:** semantic landmarks, labelled controls, and `aria-live` for async/result regions so the
  console is navigable non-visually.
