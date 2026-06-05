# BootUI

[![Build](https://github.com/jdubois/boot-ui/actions/workflows/build.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/build.yml)
[![CodeQL](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.x-6db33f?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)

BootUI is a **Spring Boot 4 starter** that adds an embedded, local-only developer console to your application.

It is served by the host app at `/bootui/`, uses internal `/bootui/api/**` endpoints, and packages the Vue UI into the
starter so consuming applications do not need Node.js or npm.

![BootUI overview](docs/images/bootui-overview.png)

## Install

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>0.5.1</version>
</dependency>
```

Run your app with a local development profile and open <http://localhost:8080/bootui>:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

BootUI also activates automatically when `spring-boot-devtools` is on the classpath. It stays local by default: dev-only
activation, loopback-only access, secret masking, read-only controls, and production-profile disablement.

## What you get

- Runtime views for health, metrics, memory, threads, heap dumps, startup timing, and JVM tuning.
- Configuration tools for masked properties, profile diffs, runtime overrides, loggers, beans, conditions, and mappings.
- Data and service panels for database pools, Spring Data, Hibernate, Flyway, Liquibase, caches, scheduled tasks, and dev
  services.
- Diagnostics and security panels for traces, logs, HTTP exchanges, local probes, architecture checks, GraalVM readiness,
  dependency vulnerabilities, Spring Security, and security advisors.
- Developer tooling dashboards for DevTools, GitHub, Copilot, and Claude Code local activity.

Some panels depend on optional Spring, Actuator, or development infrastructure. When data is unavailable, BootUI returns
stable empty responses or shows an actionable empty state.

## Try the sample app

The sample app script clones the repository, builds it, starts PostgreSQL, Redis, and Ollama with Docker Compose, then
opens the same local BootUI console used by the screenshots.

```bash
curl -fsSL https://raw.githubusercontent.com/jdubois/boot-ui/main/scripts/run-sample.sh | bash
```

```powershell
irm https://raw.githubusercontent.com/jdubois/boot-ui/main/scripts/run-sample.ps1 | iex
```

Review the scripts before running them in a trusted local development environment.

## Documentation

The public VuePress documentation site is <https://www.julien-dubois.com/boot-ui/>.

| Topic | Link |
| ----- | ---- |
| Setup | <https://www.julien-dubois.com/boot-ui/SETUP.html> |
| Feature tour | <https://www.julien-dubois.com/boot-ui/FEATURES.html> |
| Configuration properties | <https://www.julien-dubois.com/boot-ui/PROPERTIES.html> |
| Sample app walkthrough | <https://www.julien-dubois.com/boot-ui/TRY-SAMPLE-APP.html> |
| Repository and documentation guide | <https://www.julien-dubois.com/boot-ui/REPOSITORY.html> |
| Product specification | <https://www.julien-dubois.com/boot-ui/SPECIFICATION.html> |
| Roadmap | <https://www.julien-dubois.com/boot-ui/PLAN.html> |

## Project resources

- [CHANGELOG.md](CHANGELOG.md): release notes
- [CONTRIBUTING.md](CONTRIBUTING.md): contributor workflow, build, test, and publishing instructions
- [SECURITY.md](SECURITY.md): threat model and security policy

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
