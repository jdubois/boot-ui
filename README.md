# BootUI

[![Build](https://github.com/jdubois/boot-ui/actions/workflows/build.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/build.yml)
[![CodeQL](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.x-6db33f?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)

BootUI is a **Spring Boot 4 Starter** that adds a local developer console to Spring Boot 4 applications.

It is not a standalone application. Developers add the BootUI starter to an existing Spring Boot 4 app, run the app locally, and open a browser-based workbench for understanding beans, auto-configuration, effective configuration, profiles, mappings, health, loggers, startup performance, and local service connections. BootUI must also let developers list and modify the application's Spring Boot configuration properties during local development.

## Product thesis

Spring Boot already exposes the data developers need through Actuator, DevTools, configuration metadata, Docker Compose support, and Testcontainers integration. BootUI turns those scattered primitives into a cohesive local-first developer experience.

## Target platform

- Spring Boot 4.x applications.
- Java 25.
- Maven first.
- Servlet web applications first, with WebFlux support later.

Spring Boot 3.x compatibility is out of scope for the first version unless later validation shows it is required for adoption.

## MVP goal

A developer should be able to add the `bootui-spring-boot-starter` dependency to a Spring Boot 4 app, run it locally, open BootUI, and answer these questions in under five minutes:

1. Which beans exist, and where did they come from?
2. Why was an auto-configuration applied or skipped?
3. Which property value is active, from which source/profile, and can I safely override it locally?
4. Which HTTP endpoints does this app expose?
5. Which health contributor or local service is failing?
6. What made startup slow?
7. Can I change a logger level without restarting?

## Initial repository contents

- `docs/SPECIFICATION.md`: full product and technical specification.
- `docs/PLAN.md`: phased implementation plan and milestones.

## Proposed distribution

BootUI should ship as:

- `bootui-spring-boot-starter`: development-only starter for app projects.
- `bootui-autoconfigure`: Spring Boot auto-configuration.
- `bootui-ui`: Vue.js browser UI, automatically built and packaged into the starter during the Maven build.
- `bootui-sample-app`: sample app used for demos and integration tests.

## Guiding principles

- Local development only by default.
- No production exposure by accident.
- No account, cloud service, or telemetry in the open-source core.
- Reuse Spring Boot Actuator and metadata instead of inventing new data sources.
- Work with any IDE.
- Prefer readable explanations over raw endpoint dumps.

## Quick start

Add the starter to a Spring Boot 4 application:

```xml
<dependency>
  <groupId>io.github.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Run the app with the `dev` profile (or with `spring-boot-devtools` on the classpath):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open <http://localhost:8080/bootui>.

## Build from source

```bash
mvn clean install
```

Then run the bundled sample app:

```bash
cd bootui-sample-app
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full developer workflow,
[SECURITY.md](SECURITY.md) for the threat model, and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
