# What is BootUI?

BootUI is a **Spring Boot 4 starter** that adds an embedded, local-only developer console to your application.

It is served by the host application at `/bootui/`, uses internal `/bootui/api/**` endpoints, and packages the browser
UI into the starter so consuming applications do not need Node.js or npm.

![BootUI overview](/images/bootui-overview.png)

## Why BootUI?

- **Zero frontend toolchain.** The Vue 3 UI is pre-built into the starter; your app just adds a dependency.
- **Local-first and safe.** BootUI activates only in development contexts, rejects non-loopback callers, masks
  secret-like values, and disables itself for production profiles unless explicitly forced on.
- **Deep Spring insight.** Over twenty-five panels surface runtime health, configuration, beans, mappings, traces,
  caches, security wiring, dev services, and more — grouped exactly like the in-app menu.
- **Configurable.** Every visible panel can be disabled, and action-capable panels can be made read-only. The entire
  console can be locked down with a single property.

## How it works

When BootUI is active it:

- activates in `AUTO` mode only for `dev` / `local` profiles or when DevTools is present
- rejects non-loopback requests
- permits `/bootui/**` through Spring Security when present, with a startup warning, so the local console remains
  reachable while the loopback-only filter still applies
- masks secret-like configuration values
- exposes the local Actuator endpoints used by BootUI panels when BootUI is active
- captures local application spans for the Traces panel when telemetry and the panel are enabled
- disables itself for `prod` / `production` profiles
- stores runtime configuration overrides in `.bootui/application-bootui.properties`, not in your source config files

## Repository modules

- `bootui-spring-boot-starter`: dependency to add to your app
- `bootui-autoconfigure`: Spring Boot auto-configuration
- `bootui-ui`: Vue 3 frontend packaged into the starter
- `bootui-core`: shared DTOs and core helpers
- `bootui-sample-app`: demo and integration sample app

## Next steps

- [Getting started](./getting-started) — add the dependency and run your app
- [Features](./features) — a panel-by-panel guide with screenshots
- [Configuration & safety](./configuration) — the safety model and common properties
- [Property reference](./properties) — every global and per-panel property
