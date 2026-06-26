---
name: BootUI
description: The local-only developer console for Spring Boot — a calm control room over a running app.
colors:
  spring-green: "#198754"
  spring-green-deep: "#146c43"
  signal-blue: "#0d6efd"
  indigo-accent: "#6610f2"
  accessible-deep-blue: "#0a53be"
  accessible-danger: "#b02a37"
  accessible-info: "#0b6e85"
  accessible-warning: "#8a6d00"
  ink: "#152033"
  slate-muted: "#64748b"
  slate-subtle: "#94a3b8"
  surface-white: "#ffffff"
  surface-frost: "#ffffffd1"
  border-ink: "#0f172a14"
  skeleton-base: "#e2e8f0"
  green-dark-theme: "#34d068"
  blue-dark-theme: "#60a5fa"
  ink-dark-theme: "#e2e8f0"
  surface-dark-theme: "#1e293b"
typography:
  display:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "clamp(1.45rem, 2vw, 2.1rem)"
    fontWeight: 800
    lineHeight: 1.1
    letterSpacing: "-0.01em"
  title:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "1.15rem"
    fontWeight: 700
    lineHeight: 1.3
  body:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "0.72rem"
    fontWeight: 800
    lineHeight: 1.2
    letterSpacing: "0.06em"
  mono:
    fontFamily: "SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace"
    fontSize: "0.85rem"
    fontWeight: 400
    lineHeight: 1.5
rounded:
  xs: "0.35rem"
  sm: "0.5rem"
  md: "0.75rem"
  lg: "1.1rem"
  xl: "1.25rem"
  pill: "999px"
spacing:
  xs: "0.5rem"
  sm: "0.85rem"
  md: "1.25rem"
  lg: "2rem"
components:
  card:
    backgroundColor: "{colors.surface-frost}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "1.25rem"
  button-primary:
    backgroundColor: "{colors.spring-green}"
    textColor: "{colors.surface-white}"
    rounded: "{rounded.md}"
    padding: "0.5rem 1rem"
  nav-link-active:
    backgroundColor: "{colors.spring-green}"
    textColor: "{colors.surface-white}"
    rounded: "{rounded.sm}"
    padding: "0.55rem 0.7rem"
  pill:
    backgroundColor: "{colors.surface-frost}"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    padding: "0.35rem 0.75rem"
  brand-mark:
    backgroundColor: "{colors.spring-green}"
    textColor: "{colors.surface-white}"
    rounded: "{rounded.lg}"
    height: "2.75rem"
    width: "2.75rem"
---

# Design System: BootUI

## 1. Overview

**Creative North Star: "The Calm Control Room"**

BootUI is the mission console for a single Spring Boot app running on `localhost` — a green-and-blue control room where dense telemetry (beans, config, health, traces, advisor findings) is made *unalarming* through restraint and soft depth. The developer arrives mid-task with a sharp question and an app already running; the room's job is to make the runtime legible in a couple of minutes and then disappear back into their workflow. Every surface is a translucent instrument panel floating a few millimeters above a quiet gradient field; nothing flashes, nothing shouts, and a red light means something is genuinely wrong.

The system's defining tension is productive: BootUI is *built on Bootstrap 5.3 and must never look like it.* Stock Bootstrap is the raw electrical wiring behind the wall, never the visible finish. The finish is a coherent token system — Spring green (`#198754`) and signal blue (`#0d6efd`) carried through a frosted-glass shell, large diffuse ambient shadows, generously rounded corners (0.5–1.25rem), and a green→blue gradient that signals "active / selected." Because BootUI's own advisor panels critique *other* apps for accessibility, architecture, and craft, the console is held to the standard it preaches: this UI is the proof of competence, not a backdrop for it.

This system explicitly rejects four things: the **default-Bootstrap admin template** (AdminLTE / SB Admin — default blue, flat card-grid layouts, untouched utility classes); **old-school enterprise Java chrome** (JConsole / VisualVM, raw Actuator JSON dumped on screen); **APM walls-of-charts** (Grafana / Datadog density with no narrative); and **AI-SaaS slop** (gradient hero + identical icon-card grids + cream/sand backgrounds + per-section uppercase eyebrows). Density is welcome; density *without explanation* is not.

**Key Characteristics:**
- Frosted-glass instrument panels floating over a green→blue→violet near-white gradient (true off-white, never cream/sand).
- Spring green + signal blue as a paired identity; the green→blue gradient is reserved for the active/selected state.
- Calm by default, loud only on real risk — status color always paired with a text label.
- Soft, generous geometry: large radii, large diffuse shadows, a 2px hover lift as the signature motion.
- Light **and** dark theme are first-class and both must clear WCAG 2.1 AA.

## 2. Colors

A cool, confident palette: two brand hues (Spring green, signal blue) over a near-white tri-tone wash, with a disciplined slate neutral ramp for text and borders. Warmth is deliberately absent — there is no cream, sand, or paper bg anywhere.

### Primary
- **Spring Green** (`#198754`): the brand's load-bearing color and Spring's own green. Carries the logo mark, primary actions, active nav, "healthy" status, and positive advisor outcomes. In dark theme it brightens to a legible **Spring Green (Dark)** (`#34d068`).
- **Spring Green Deep** (`#146c43`): pressed/hover state for green surfaces and nav-hover text on light backgrounds.

### Secondary
- **Signal Blue** (`#0d6efd`): the second identity hue. Anchors links, the "input" series in charts, informational emphasis, and the *terminal* of the active-state gradient. In dark theme it lightens to **Signal Blue (Dark)** (`#60a5fa`).

### Tertiary
- **Indigo Accent** (`#6610f2`): a sparing third hue for the "output" data series and multi-series charts. Never used for chrome or interactive state — purely categorical.

### Semantic Status (accessible)
Bootstrap's contextual colors are tuned for white backgrounds and fail WCAG AA as text in places, so BootUI remaps each semantic *text* color per theme. These light-theme values clear ≥4.5:1 on the frosted/white surfaces (dark theme uses the brighter tones in `App.vue`):
- **Accessible Deep Blue** (`#0a53be`): `.text-primary` text and the *selected* master-list row (white-on-blue ~7:1). The accessible companion to Signal Blue.
- **Accessible Danger** (`#b02a37`): `.text-danger` error/at-risk text.
- **Accessible Info** (`#0b6e85`): `.text-info` informational text (the raw Bootstrap cyan is ~1.9:1 and is never used as text).
- **Accessible Warning** (`#8a6d00`): `.text-warning` caution text (the raw amber is ~1.6:1 and is never used as text).
- `.text-success` text uses **Spring Green Deep** (`#146c43`, ~6.5:1); no separate token needed.

### Neutral
- **Ink** (`#152033`): primary body and heading text on light surfaces (near-navy, not pure black). Dark theme inverts to **Ink (Dark)** (`#e2e8f0`).
- **Slate Muted** (`#64748b`): secondary text, captions, and nav-group labels. Verified for AA at body sizes on the light shell.
- **Slate Subtle** (`#94a3b8`): tertiary/disabled hints and placeholder-weight text only — never load-bearing body copy.
- **Surface White / Frost** (`#ffffff` solid, `#ffffffd1` = `rgba(255,255,255,0.82)` frosted): card and panel fills. Dark theme uses **Surface (Dark)** (`#1e293b`).
- **Border Ink** (`#0f172a14` = `rgba(15,23,42,0.08)`): the hairline that separates frosted panels from the gradient field.
- **Body Field**: `linear-gradient(135deg, #f6fbf8 0%, #eef6ff 46%, #f7f4ff 100%)` (mint → sky → lilac) plus a soft radial green orb — the "control room ambiance." Dark theme: `linear-gradient(135deg, #0d1a12, #0f1929, #100f1a)`.

### Named Rules
**The Gradient-Is-State Rule.** The green→blue gradient (`linear-gradient(135deg, #198754, #0d6efd)`) means exactly one thing: *active / selected.* It paints the current nav item and nothing else decorative. Never use it as a hero backdrop or a heading fill — that is the AI-SaaS-slop tell this system rejects.

**The No-Warmth Rule.** Backgrounds are cool near-whites tinted toward the brand's own green/blue, never warm. If a surface reads as cream, sand, paper, or parchment, it is wrong — that is the saturated AI default, and it is forbidden here.

**The Earned-Red Rule.** Danger/warning color appears only when the runtime is genuinely at risk. A screen at rest is green, blue, and slate. Red is a signal, not decoration.

**The AA-Both-Themes Rule.** Every semantic *text* color must clear WCAG AA (≥4.5:1) in **both** light and dark themes. Bootstrap's raw contextual colors don't, so each is remapped per theme in `App.vue` (light) and its dark counterpart — see Semantic Status (accessible). Never let raw `.text-info` / `.text-warning` / `.text-danger` reach the reader as body text.

## 3. Typography

**Display / Body Font:** the native system-ui stack (`system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial`) — inherited from Bootstrap 5.3, deliberately un-replaced. It renders crisply at every weight, costs zero network, and reads as "native developer tool," not "marketing site."
**Mono Font:** the system monospace stack (`SFMono-Regular, Menlo, Monaco, Consolas`) for every identifier, code snippet, log line, config key, and value.

**Character:** invisible-on-purpose. The type does not perform; it gets out of the way of dense technical content. Personality comes from weight contrast (heavy 800 titles against 400 body) and from the mono/sans split that visually separates *prose explanation* from *machine data* — the core of the product's "explain before you dump" principle.

### Hierarchy
- **Display** (800, `clamp(1.45rem, 2vw, 2.1rem)`, line-height ~1.1): the page title in the topbar. One per screen.
- **Title** (700, ~1.15rem): card headers and section titles inside panels.
- **Body** (400, 1rem, line-height 1.5): explanatory prose and table cells. Cap measure at 65–75ch for prose blocks.
- **Label / Eyebrow** (800, 0.72rem, letter-spacing 0.06em, uppercase): reserved for **sidebar nav-group headers** ("RUNTIME", "CONFIGURATION") — a scoped navigational system, not a per-section content kicker.
- **Mono** (400, ~0.85rem): all identifiers, keys, values, code, and log output.

### Named Rules
**The Mono-Means-Machine Rule.** Anything the machine produced — a bean name, a property key, a class, a header value, a log line — is monospace. Anything BootUI wrote to *explain* it is sans. The reader can tell data from narrative without reading a word.

**The Eyebrow-Containment Rule.** The uppercase tracked label is allowed *only* as sidebar nav-group headers. Spraying small uppercase eyebrows above every content section is the AI-SaaS-slop tell and is prohibited.

## 4. Elevation

BootUI is a **layered, lifted** system, not a flat one. Depth is the metaphor: frosted instrument panels hover above a gradient field on large, soft, diffuse ambient shadows — closer to "premium float" than "2014 drop shadow." Glassmorphism is used *purposefully and sparingly* (the sidebar and translucent surfaces over the colored body), never sprayed across every element.

### Shadow Vocabulary
- **Ambient SM** (`box-shadow: 0 1rem 2.5rem rgba(15,23,42,0.07)`): the resting elevation of cards and panels. Large blur, low opacity, no harsh edge.
- **Ambient MD** (`box-shadow: 0 1.2rem 3rem rgba(15,23,42,0.11)`): hover / raised elevation and popovers — the SM shadow deepened on interaction.
- **Sidebar** (`box-shadow: 0.75rem 0 2rem rgba(15,23,42,0.06)`): the horizontal cast that separates the frosted rail from the workspace.
- Dark theme keeps the same geometry with `rgba(0,0,0,0.25–0.4)`.

### Named Rules
**The Soft-Float Rule.** Surfaces rest on a large, diffuse ambient shadow and lift exactly **2px** (`translateY(-2px)`) on hover, deepening to Ambient MD. If a shadow looks tight, dark, or small-blurred, it is wrong — that is the dated-app tell.

**The Purposeful-Glass Rule.** `backdrop-filter: blur(22px)` belongs on the sidebar and over-gradient surfaces only. Glass is a deliberate material here, never a default applied to ordinary cards.

## 5. Components

### Buttons
- **Shape:** softly rounded (0.75rem radius), inherited globally so no Bootstrap default `.btn` slips through square.
- **Primary:** solid Spring green (`#198754`) fill, white text. Reserved for the main action on a panel.
- **Hover / Focus:** subtle darken toward Spring Green Deep; every control needs a **visible, branded focus ring** — never `outline: none` without a replacement.
- **Secondary / Ghost:** frosted surface with a hairline border for low-emphasis and "show raw detail" disclosure actions.

### Chips / Pills
- **Style:** fully rounded (999px), frosted surface (`rgba(255,255,255,0.82)`), hairline border, soft `0 0.5rem 1.2rem rgba(15,23,42,0.06)` shadow. Used for the topbar status pill and profile chip.
- **State:** status pills always pair color with a text label and icon ("● Healthy"), never color alone.

### Cards / Containers
- **Corner Style:** 1.1rem radius (the system's signature roundness).
- **Background:** frosted white (`rgba(255,255,255,0.82)`) over the gradient field; solid white where content needs maximum legibility.
- **Shadow Strategy:** Ambient SM at rest → Ambient MD on hover (see Elevation).
- **Border:** 1px `rgba(15,23,42,0.08)`, warming to a green-tinted `rgba(25,135,84,0.25)` on hover.
- **Internal Padding:** 1.25rem. **Cards never nest.**

### Inputs / Fields
- **Style:** 0.75rem radius, hairline border, frosted/solid surface consistent with cards.
- **Focus:** branded focus ring (green/blue), visible against both themes. Search and filter inputs are the *primary* affordance on data-heavy panels — style them as first-class, not afterthoughts.
- **Placeholder:** must meet the same 4.5:1 contrast as body text; never the faint Slate Subtle gray.

### Navigation
- **Style:** a frosted, blurred left sidebar (`backdrop-filter: blur(22px)`) grouped under uppercase nav-group labels; collapses to a 5.25rem icon rail and to an off-canvas drawer below 992px.
- **Default / Hover:** slate link text; hover tints the row green (`rgba(25,135,84,0.08)` bg, green text).
- **Active:** the green→blue gradient pill with white text — the one place the gradient appears.
- **Focus:** custom nav toggles and the command-palette trigger must carry a visible focus ring, not rely on the UA default.

### Signature: Brand Mark & Ambient Orbs
- **Brand mark:** a solid Spring-green rounded square (1rem radius, 2.75rem) holding a white coffee-cup glyph (`bi-cup-hot-fill`), with a green glow (`0 0.6rem 1.2rem rgba(25,135,84,0.28)`). The "BootUI" wordmark sits beside it.
- **Ambient orbs:** two large, softly blurred color fields (green + blue) rest **statically** behind the shell at `z-index: -1` as quiet ambient glows. They are pure atmosphere — they do not drift or animate (the room stays calm across long sessions), must never sit above content, and must never reduce text contrast.

## 6. Do's and Don'ts

### Do:
- **Do** keep the green→blue gradient for the active/selected state only; everywhere else, use flat Spring green or signal blue.
- **Do** render every machine-produced identifier, key, value, and log line in the mono stack; keep BootUI's own explanation in sans.
- **Do** verify contrast meets **WCAG 2.1 AA in both light and dark themes** — especially semantic status colors (log levels, severities) and code/identifier text on tinted or selected backgrounds, which Bootstrap tunes for light backgrounds only.
- **Do** give every interactive control — including custom buttons, nav toggles, and the command palette — a visible, consistent, branded focus ring.
- **Do** pair every status color with a text label and/or icon, so meaning survives color-blindness and grayscale.
- **Do** honor `prefers-reduced-motion` for hover lifts, loading/skeleton motion, and every transition.
- **Do** lead panels with a search/filter affordance and a human-readable interpretation; keep raw Actuator/JSON one disclosure away.

### Don't:
- **Don't** look like a default-Bootstrap admin template (AdminLTE / SB Admin). Default Bootstrap blue, flat card-grid admin layouts, and untouched utility-class styling are failure states. *Use Bootstrap, never look like default Bootstrap.*
- **Don't** echo old-school enterprise Java tooling — no JConsole / VisualVM chrome, and never dump raw Actuator JSON on screen as the primary view.
- **Don't** build APM walls-of-charts: Grafana / Datadog density with no narrative. Density is fine; density *without explanation* is not.
- **Don't** ship AI-SaaS slop: gradient hero + identical icon-card grids + cream/sand/paper backgrounds + per-section uppercase eyebrows.
- **Don't** use a warm near-white (cream, sand, parchment) as any background. Backgrounds stay cool, tinted toward the brand green/blue.
- **Don't** use `background-clip: text` gradient fills, `border-left`/`border-right` color stripes thicker than 1px, or glassmorphism as a default card treatment.
- **Don't** nest cards, and don't reach for a card when a table, list, or plain section is the better affordance.
- **Don't** set `outline: none` on any control without an equally visible branded replacement.
- **Don't** add page-entrance reveal animations — no `fade-up`/slide-in applied to every panel or section. Navigation carries one gentle ~180ms route transition; panels otherwise appear instantly. Motion is reserved for hover feedback, loading, and live-status indicators, never decorative reveals or perpetual background drift.
- **Don't** trigger network calls, scans, or mutations from a render — every external or destructive action is explicit, labeled, and reversible-by-default.
