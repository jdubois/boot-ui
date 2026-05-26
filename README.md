# BootUI

[![Build](https://github.com/jdubois/boot-ui/actions/workflows/build.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/build.yml)
[![CodeQL](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.x-6db33f?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)

BootUI is a **Spring Boot 4 starter** that adds an embedded, local-only developer console to your app.

It is designed for local development (not production) and helps you quickly inspect:

- application overview and active profiles
- beans and auto-configuration conditions
- effective configuration values
- HTTP mappings
- health contributors
- logger levels
- startup timeline, JVM memory, and scheduled tasks
- Spring Data repositories
- local HTTP probes
- live log tail
- profile-specific configuration
- Spring Security filter chains and endpoint rules

BootUI is served by the host application at `/bootui/` and uses internal `/bootui/api/**` endpoints. The browser UI is packaged into the starter; consumers do not need Node.js or npm.

## Screenshot

![BootUI overview](https://github.com/user-attachments/assets/065013d1-73df-46a0-914c-0a5c88127995)

## Setup (use in your own Spring Boot app)

### 1) Prerequisites

- Java 25
- Spring Boot 4.x application
- Maven

### 2) Add the starter dependency

```xml
<dependency>
  <groupId>io.github.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 3) Run your app in development mode

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

BootUI also activates automatically when `spring-boot-devtools` is on the classpath. To force it on or off:

```properties
bootui.enabled=ON
bootui.enabled=OFF
```

`prod` and `production` profiles disable BootUI unless `bootui.enabled=ON` is set.

### 4) Open BootUI

Visit: <http://localhost:8080/bootui>

## Setup (run this repository locally)

### 1) Prerequisites

- Java 25
- Maven

Node.js and npm are downloaded automatically by Maven for the UI build.

### 2) Build everything

```bash
mvn clean install
```

### 3) Run the sample app

```bash
cd bootui-sample-app
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 4) Open BootUI

Visit: <http://localhost:8080/bootui>

## Configuration and safety

BootUI is intended for local development only. By default it:

- activates in `AUTO` mode only for `dev` / `local` profiles or DevTools
- rejects non-loopback requests
- masks secret-like configuration values
- disables itself for `prod` / `production` profiles
- stores runtime configuration overrides in `.bootui/application-bootui.properties`, not in your source config files

Common properties:

| Property | Default | Description |
|---|---|---|
| `bootui.enabled` | `AUTO` | `AUTO`, `ON`, or `OFF`. |
| `bootui.enabled-profiles` | `dev,local` | Profiles that activate BootUI in auto mode. |
| `bootui.disabled-profiles` | `prod,production` | Profiles that disable BootUI unless forced on. |
| `bootui.allow-non-localhost` | `false` | Explicit opt-out of loopback-only protection. |
| `bootui.expose-values` | `MASKED` | `MASKED`, `METADATA_ONLY`, or `FULL`. |
| `bootui.overrides-file` | `.bootui/application-bootui.properties` | Runtime override persistence file. |

## Panels

Current visible panels:

| Panel | Purpose |
|---|---|
| Overview | Runtime, profiles, ports, activation, and safety state. |
| Beans | Spring bean summaries and classifications. |
| Conditions | Auto-configuration positive and negative matches. |
| Configuration | Effective properties, metadata, masking, and local overrides. |
| Mappings | HTTP mappings from Actuator. |
| Health | Health tree and contributor details. |
| Loggers | Runtime logger levels and mutations. |
| Data | Spring Data repositories and query methods. |
| Startup Timeline | Startup steps from Actuator startup data. |
| Memory | JVM heap/non-heap usage and suggested JVM options. |
| Scheduled Tasks | Registered scheduled tasks. |
| HTTP Probe | Local-only request probe against the running app. |
| Log Tail | Recent and streaming application logs. |
| Profile Diff | Profile-specific property sources and values. |
| Security | Spring Security filter chains and best-effort endpoint rules. |

Some panels depend on optional Spring or Actuator infrastructure. When data is unavailable, BootUI returns stable empty responses or shows an explanatory empty state.

## Testing

```bash
# CI-equivalent build
mvn -B -ntp clean install

# Browser end-to-end tests for the sample app
cd bootui-sample-app/e2e
npm install
npx playwright install chromium
npm test
```

## Repository modules

- `bootui-spring-boot-starter`: dependency to add to your app
- `bootui-autoconfigure`: Spring Boot auto-configuration
- `bootui-ui`: Vue 3 frontend packaged into the starter
- `bootui-core`: shared DTOs and core helpers
- `bootui-sample-app`: demo/integration sample app

## More docs

- [CONTRIBUTING.md](CONTRIBUTING.md): contributor workflow
- [SECURITY.md](SECURITY.md): threat model and security policy
- [docs/SPECIFICATION.md](docs/SPECIFICATION.md): full product/technical specification
- [docs/PLAN.md](docs/PLAN.md): implementation roadmap

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
