---
applyTo: "bootui-core/**,bootui-engine/**,bootui-spring-autoconfigure/**,bootui-spring-boot-starter-reactive/**,bootui-spring-sample-app/**,bootui-spring-webflux-sample-app/**,bootui-quarkus/**,bootui-quarkus-deployment/**,bootui-quarkus-sample-app/**,bootui-conformance/**,bootui-ui/**,docs/**"
---

# Panel, API, and safety contracts

- Spring MVC, Spring WebFlux, and Quarkus serve the same Vue UI and JSON contract. Make shared behavior indistinguishable
  unless a capability is intentionally stack-specific.
- Before public or visible behavior changes, read `docs/SPECIFICATION.md`, `docs/PLAN.md`, `docs/features/`,
  `docs/WEBFLUX-SUPPORT.md`, and `docs/QUARKUS-SUPPORT.md`.
- Preserve normalized configurable UI/API mounts. Browser calls are relative to the UI base.
- Route every property name/value exposed to the browser through `SecretMasker` behind the live `ExposurePolicy`.
- External and mutating work must be explicit, user-initiated, bounded, and clearly reported on failure. Page render must remain network-free.
- Keep `LocalhostGuard` as the single source of truth for loopback, Host/DNS-rebinding, and cross-site-write policy.
  Preserve exact canonical rejection messages across MVC, WebFlux, and Quarkus bindings.
- Register action endpoints in `BootUiPanels` so per-panel enable/read-only policy applies consistently.
- Keep route metadata, `BootUiPanels`, adapter availability, MCP tool availability, conformance fixtures, docs, and end-to-end coverage aligned.
- Before completing a panel addition or rename, audit the full vertical surface: DTO/API contract, engine service and
  SPI, Spring MVC and WebFlux wiring, Quarkus resource/build-time capability/availability, `BootUiPanels`, route
  metadata, configuration keys, setup/features/platform-support docs, screenshots, focused adapter tests, conformance,
  and affected browser specs. Mark unsupported stacks explicitly unavailable or not applicable.
- `/bootui/api/panels` must carry the platform discriminator (`spring-boot`, `spring-boot-reactive`, or `quarkus`). The
  UI defaults only for backward compatibility.
- Keep the shared `spring` panel id for the platform-aware Spring/Quarkus application advisor.
- The conformance suite is availability-driven. A newly available data panel must answer its root GET with 200 JSON on that adapter.
- Advisor logic changes require the corresponding `docs/*-CHECKS.md` update.
- Do not add Spring-only compatibility behavior to shared code. Use the engine/SPI seam and honest capability-specific empty states.
