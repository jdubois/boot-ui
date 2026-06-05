# BootUI Build-Time Analysis: With AI vs. Without AI

> An estimate of the effort that went into building BootUI with AI assistance,
> compared with an estimate of building the same project by a single developer
> without AI. Figures are derived from git history, GitHub pull-request metadata,
> and code metrics — not from time-tracking logs — and are intended as
> informed estimates rather than precise measurements.
>
> **Updated for the 1.0.0 release (2026-06-05).** The final day added a VuePress
> documentation site, the redesigned Overview security & health scanner
> dashboard, Copilot token charts, a proxied-Hikari fix, a README/docs refactor,
> and the 1.0.0 release cut.

## What the project actually is (measured facts)

| Dimension | Measurement (v1.0.0) |
| --- | --- |
| Repository span | First commit 2026-05-25, 1.0.0 release 2026-06-05 → ~10.8 calendar days |
| Commits | ~264 total (on `main`) |
| Pull requests | ~223 (squash-merged, up to #239) |
| Authors | Julien Dubois (human driver) · Copilot agent (~44 commits) · dependabot · github-actions · 1 collaborator |
| Java | ~461 files / ~50,000 lines; ~81 test classes |
| Frontend | Vue 3 SPA: ~52 `.vue` components, ~40 feature panels, ~7k lines JS, ~35 Playwright e2e specs |
| Total tracked source | ~83,000 lines (Java, Vue, JS, XML, YAML, properties, Markdown) |
| Docs | ~5,800 lines of Markdown + a redesigned VuePress site published to GitHub Pages |
| Architecture | 5-module Maven build (core, autoconfigure, starter, ui, sample-app), Spring Boot 4 / Java 17, Maven Central publishing, full CI (build, CodeQL, release) |

This is not a toy. It is a production-grade, multi-module Spring Boot starter that
deeply integrates Actuator, Spring Security filter chains, Flyway/Liquibase,
Hibernate, Micrometer/OTLP tracing, GraalVM reachability metadata, OSV
vulnerability scanning, ArchUnit, and an embedded Vue SPA — roughly 40 distinct
feature panels, each with backend endpoints, frontend views, and tests.

## Part 1 — How long it took with AI

### Executive summary

The project was built — through a tagged **1.0.0 release** — in **~11 calendar days**
(25 May → 5 June 2026) by **one human developer driving the GitHub Copilot coding
agent**. The cadence — **~223 PRs in ~11 days (~20 PRs/day)** with an AI agent
co-authoring commits — is impossible to achieve by hand. The estimate of **actual
human hands-on effort is ~80–110 hours** (roughly 2 intense solo weeks), with the AI
agent performing the overwhelming majority of the actual typing, scaffolding, and
test writing.

### Details / evidence

- **Velocity:** ~223 merged PRs / ~10.8 days (~264 commits). The commit clock runs
  from ~05:00 to ~midnight across most days, consistent with a single person
  dispatching many **asynchronous agent tasks in parallel** rather than typing
  continuously.
- **Authorship pattern:** "Copilot" is a named commit/PR author (~44 commits)
  alongside the human. The repo even contains `.github/copilot-instructions.md`
  and per-panel conventions — the workflow was explicitly agent-oriented.
- **The final day (1.0.0):** even on release day the agent shipped a redesigned
  VuePress docs site, the Overview scanner dashboard, Copilot token charts, a
  proxied-Hikari detection fix, and a README/docs refactor before cutting the
  release — a volume of polish that would itself be several days of solo work.
- **Where the human time actually went:** writing task prompts, reviewing/merging
  ~223 PRs, resolving CI failures (Spring Boot 4 migration quirks, Flyway 11 API
  changes, OTLP property renames), and steering architecture. This is
  *review-and-orchestrate* time, not *write-every-line* time.
- **Realistic human-effort band:** ~80–110 hours. The ~11-day calendar window
  understates raw output (because the agent works in parallel) but the human
  reviewer/driver is the true bottleneck.

## Part 2 — How long it would take without AI (single developer)

### Executive summary

A single, experienced Spring Boot + Vue developer building the same scope by hand —
no AI codegen, through a polished 1.0.0 release with a published docs site — would
need an estimated **6.5 to 8.5 months of full-time work (~28–36 weeks,
~1,100–1,450 hours)**. The dominant cost is the **~40 feature panels**, each
requiring backend integration with a non-trivial Spring/JVM subsystem, a frontend
view, and tests. Cross-check: a COCOMO "organic" estimate on ~50 KLOC of
hand-written source yields well over 100 person-months — unrealistically high
because much of the code is repetitive panel scaffolding — so a domain-expert solo
figure of **~7.5 months** is the defensible middle ground.

### Detailed steps and estimated times (single senior developer)

| Phase | Work | Est. time |
| --- | --- | --- |
| 1. Project setup & architecture | 5-module Maven layout, auto-configuration skeleton, starter wiring, panel-registration framework, safety/access filter design | 1–1.5 weeks |
| 2. Frontend foundation | Vue 3 + Vite SPA, app shell, routing, grouped menu, shared components, build packaged into starter (no Node needed by consumers) | 1.5–2 weeks |
| 3. Core "easy" panels (~15) | Health, Metrics, Memory, Beans, Conditions, Mappings, Loggers, Config, Scheduled, Caches, etc. — mostly Actuator-backed. ~1.5–2 days each (backend + view + tests) | 5–6 weeks |
| 4. Complex / deep-integration panels (~20) | Spring Security filter-chain introspection, Security Advisor (37 rules), Pentesting (OWASP), Vulnerabilities (OSV), Hibernate Advisor, Flyway/Liquibase actions, GraalVM reachability generator, Threads/HeapDump, Tracing/OTLP, AI Usage, GitHub/Copilot dashboards, Architecture (ArchUnit). ~3–5 days each | 12–16 weeks |
| 5. Safety & security model | Local-only enforcement, action confirmation gating, secret masking, access filter — done carefully by hand | 1–1.5 weeks |
| 6. Testing | ~81 Java test classes + ~35 Playwright e2e specs, written manually alongside features | 3–4 weeks (partly overlapped above) |
| 7. CI/CD & release engineering | Build/CodeQL/release workflows, GPG signing, Maven Central publishing, javadoc/source jar plumbing | 1–1.5 weeks |
| 8. Documentation | ~5,800 lines: FEATURES, per-check catalogs, SPECIFICATION, properties, screenshots, plus a published VuePress GitHub Pages site | 2.5–3.5 weeks |
| 9. Integration, polish, Spring Boot 4 migration debugging, 1.0.0 release hardening, buffer | Cross-cutting bugs, version upgrades, regressions, release engineering | 2–3 weeks |
| **Total** | | **~28–36 weeks ≈ 6.5–8.5 months** |

## Part 3 — Full comparison

| Aspect | With AI (actual) | Without AI (estimated) |
| --- | --- | --- |
| Calendar time | ~11 days (to 1.0.0) | ~6.5–8.5 months |
| Human effort (hours) | ~80–110 h | ~1,100–1,450 h |
| Throughput | ~20 PRs/day, parallel agent tasks | A few features/week, serial |
| Human's role | Architect + prompt-writer + reviewer | Architect + author of every line |
| Bottleneck | PR review & steering | Typing, debugging, learning each subsystem |
| Test coverage | Generated alongside features (~81 + ~35 suites) | Same scope but hand-written, slower |
| Boilerplate (40 panels) | Near-free to replicate | Repetitive, the single biggest cost |
| Risk profile | Review fatigue; subtle AI-introduced bugs; needs strong CI (which exists here) | Fewer "surprise" bugs but far higher fatigue/abandonment risk |
| Effective speed-up | — | ~17–23× calendar, ~12–17× human-hours |

**Why the multiplier is so large here:** this codebase is unusually well-suited to
AI assistance. It has (1) **massive repetition** — ~40 structurally similar panels —
that an agent clones cheaply; (2) **broad-but-shallow API integration** across many
Spring subsystems, where the cost without AI is mostly *looking things up*; and
(3) **strong guardrails** (multi-module CI, CodeQL, e2e tests, explicit
copilot-instructions) that let the human safely accept high agent throughput. Net
effect: the AI compresses an estimated 6.5–8.5 month solo effort into ~11 calendar
days and ~2 weeks of human attention — roughly a **12–17× reduction in human hours**
and a **~17–23× reduction in calendar time**.

## Conclusion

BootUI is a substantial, production-quality Spring Boot 4 starter (~83k lines,
5 modules, ~40 deeply-integrated panels, ~116 test suites, full release pipeline,
published 1.0.0 with a VuePress docs site).
**With AI it was built — through a tagged 1.0.0 release — in ~11 calendar days and
an estimated ~80–110 hours of human effort.** The same scope **without AI would
realistically take a single experienced developer ~6.5–8.5 months
(~1,100–1,450 hours).**

The AI didn't merely type faster — it changed the developer's *role* from author to
**architect-and-reviewer**, enabling ~21 merged PRs/day by running many tasks in
parallel. The speed-up (~17–23× calendar, ~12–17× human-hours) is at the high end of
what's achievable precisely because the project combines high structural repetition
with broad shallow integrations and strong automated guardrails. The lasting lesson
is that AI's biggest leverage is not on hard algorithmic problems but on
**large-surface-area, pattern-heavy, well-tested codebases** — exactly this one. The
human's judgment (architecture, the safety/security model, deciding what to build
and what to merge) remained the irreplaceable bottleneck and the reason the result
is coherent rather than just voluminous.

---

*Caveats: estimates are derived from git/PR metadata and code metrics, not
time-tracking logs; "without AI" figures assume one senior full-stack Spring/Vue
developer already fluent in these subsystems and exclude calendar overhead
(meetings, context-switching) that would lengthen real-world delivery.*
