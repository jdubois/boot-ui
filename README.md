# BootUI

[![Build](https://github.com/jdubois/boot-ui/actions/workflows/build.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/build.yml)
[![CodeQL](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.x-6db33f?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)

BootUI is a **Spring Boot 4 starter** that adds an embedded, local-only developer console to your application.

It is served by the host application at `/bootui/`, uses internal `/bootui/api/**` endpoints, and packages the browser UI into the starter so consuming applications do not need Node.js or npm.

![BootUI overview](docs/images/bootui-overview.png)

## Features

BootUI exposes these panels in the same order as the application menu. See the [feature details guide](docs/FEATURES.md) for explanations and screenshots for every panel.

| Feature | What it helps with |
|---|---|
| [Overview](docs/FEATURES.md#overview) | See runtime identity, versions, ports, active profiles, activation reason, and safety state. |
| [Startup Timeline](docs/FEATURES.md#startup-timeline) | Inspect Spring Boot startup steps and durations when startup data is available. |
| [Memory](docs/FEATURES.md#memory) | Review JVM heap, non-heap, memory pools, garbage collectors, and suggested JVM options. |
| [Health](docs/FEATURES.md#health) | Explore the Actuator health tree and contributor details. |
| [Metrics](docs/FEATURES.md#metrics) | Browse Micrometer meters, tags, measurements, and a local live chart for selected metrics. |
| [Conditions](docs/FEATURES.md#conditions) | Understand why auto-configuration classes matched, did not match, or were unconditional. |
| [Beans](docs/FEATURES.md#beans) | Search Spring beans by name, type, scope, resource, dependency, and BootUI classification. |
| [Mappings](docs/FEATURES.md#mappings) | Review HTTP routes, handlers, methods, patterns, and produces/consumes metadata. |
| [Configuration](docs/FEATURES.md#configuration) | Inspect effective configuration values, metadata, masking, and local runtime overrides. |
| [Profile Diff](docs/FEATURES.md#profile-diff) | Compare profile-specific property sources and values while preserving secret masking. |
| [Loggers](docs/FEATURES.md#loggers) | Inspect and change logger levels at runtime through the Actuator loggers endpoint. |
| [Log Tail](docs/FEATURES.md#log-tail) | Read recent application logs and stream new local log events from the running process. |
| [HTTP Probe](docs/FEATURES.md#http-probe) | Send local-only HTTP requests to the app and inspect response status, headers, and body. |
| [DevTools](docs/FEATURES.md#devtools) | Check Spring Boot DevTools status, LiveReload availability, and restart controls. |
| [Dev Services](docs/FEATURES.md#dev-services) | Inspect Docker Compose snapshots, Testcontainers beans, service connection metadata, and bounded logs. |
| [Scheduled Tasks](docs/FEATURES.md#scheduled-tasks) | View registered scheduled tasks and their trigger metadata. |
| [Data](docs/FEATURES.md#data) | Explore Spring Data repositories, domain types, IDs, and query methods. |
| [Security](docs/FEATURES.md#security) | Inspect Spring Security filter chains and best-effort endpoint rule explanations. |
| [Vulnerabilities](docs/FEATURES.md#vulnerabilities) | Review dependency inventory and local OSV vulnerability scan results. |

Some panels depend on optional Spring, Actuator, or development infrastructure. When data is unavailable, BootUI returns stable empty responses or shows an explanatory empty state.

## Setup

### 1) Prerequisites

- Java 25
- Spring Boot 4.x application
- Maven or your application's Maven Wrapper

### 2) Add the starter dependency

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>0.1.0-alpha.4</version>
</dependency>
```

### 3) Run your app in development mode

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

BootUI also activates automatically when `spring-boot-devtools` is on the classpath. To force it on or off:

```properties
bootui.enabled=AUTO
bootui.enabled=ON
bootui.enabled=OFF
```

`prod` and `production` profiles disable BootUI unless `bootui.enabled=ON` is set. Invalid `bootui.enabled` values fail closed and keep BootUI disabled.

### 4) Open BootUI

Visit: <http://localhost:8080/bootui>

## Configuration and safety

BootUI is intended for local development only. By default it:

- activates in `AUTO` mode only for `dev` / `local` profiles or DevTools
- rejects non-loopback requests
- masks secret-like configuration values
- exposes the local Actuator endpoints used by BootUI panels when BootUI is active
- disables itself for `prod` / `production` profiles
- stores runtime configuration overrides in `.bootui/application-bootui.properties`, not in your source config files

Common properties:

| Property | Default | Description |
|---|---|---|
| `bootui.enabled` | `AUTO` | `AUTO`, `ON`, or `OFF`. |
| `bootui.enabled-profiles` | `dev,local` | Profiles that activate BootUI in auto mode. |
| `bootui.disabled-profiles` | `prod,production` | Profiles that disable BootUI unless forced on. |
| `bootui.allow-non-localhost` | `false` | Explicit opt-out of loopback-only protection. |
| `bootui.expose-values` | `MASKED` | `MASKED`, `METADATA_ONLY`, or `FULL`; `FULL` can disclose secrets and should stay local. |
| `bootui.overrides-file` | `.bootui/application-bootui.properties` | Runtime override persistence file. |
| `bootui.dev-services.restart-enabled` | `false` | Enables restart controls for bean-backed Testcontainers services. Disabled by default. |
| `bootui.dev-services.log-tail-bytes` | `65536` | Maximum bytes returned by one Dev Services log request. |

## Runtime overrides

The Configuration panel can create, update, and delete local runtime overrides. Overrides are stored in `.bootui/application-bootui.properties` by default, loaded at high precedence on the next startup, and never modify your application source configuration. Already-bound `@ConfigurationProperties` beans may keep their previous value until the app restarts; BootUI returns that warning with every override mutation.

## Troubleshooting

| Symptom | Check |
|---|---|
| `/bootui` returns 404 | Use the `dev` or `local` profile, add DevTools, or set `bootui.enabled=ON`. |
| BootUI is disabled in `prod` | This is intentional; only `bootui.enabled=ON` can force activation with a disabled profile. |
| Browser is rejected | BootUI accepts loopback callers by default. Use `bootui.allow-non-localhost=true` only for a trusted local network. |
| A panel is empty | Enable the relevant Actuator endpoint or optional Spring module; BootUI degrades to stable empty DTOs when data is unavailable. |
| Startup Timeline is empty | Configure `BufferingApplicationStartup` in the host app. |
| Secrets are hidden | Default exposure is `MASKED`; use `METADATA_ONLY` to hide all values or `FULL` only in trusted local sessions. |

## Repository modules

- `bootui-spring-boot-starter`: dependency to add to your app
- `bootui-autoconfigure`: Spring Boot auto-configuration
- `bootui-ui`: Vue 3 frontend packaged into the starter
- `bootui-core`: shared DTOs and core helpers
- `bootui-sample-app`: demo and integration sample app

## More docs

- [Feature details](docs/FEATURES.md): panel-by-panel guide with screenshots
- [Sample app walkthrough](bootui-sample-app/README.md): the demo app behind the screenshots and Playwright suite
- [CHANGELOG.md](CHANGELOG.md): release notes
- [CONTRIBUTING.md](CONTRIBUTING.md): contributor workflow, build, test, and publishing instructions
- [SECURITY.md](SECURITY.md): threat model and security policy
- [docs/SPECIFICATION.md](docs/SPECIFICATION.md): full product and technical specification
- [docs/PLAN.md](docs/PLAN.md): implementation roadmap

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
