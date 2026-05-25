# BootUI

[![Build](https://github.com/jdubois/boot-ui/actions/workflows/build.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/build.yml)
[![CodeQL](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml/badge.svg)](https://github.com/jdubois/boot-ui/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.x-6db33f?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)

BootUI is a **Spring Boot 4 starter** that adds a local-first web console to your app.

It is designed for local development (not production) and helps you quickly inspect:

- application overview and active profiles
- beans and auto-configuration conditions
- effective configuration values
- HTTP mappings
- health contributors
- logger levels
- startup timeline and scheduled tasks
- database connection pool and recent SQL requests

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

### 4) Open BootUI

Visit: <http://localhost:8080/bootui>

## Setup (run this repository locally)

### 1) Prerequisites

- Java 25
- Node.js 24+ (used by the `bootui-ui` module)
- Maven

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

## Repository modules

- `bootui-spring-boot-starter`: dependency to add to your app
- `bootui-autoconfigure`: Spring Boot auto-configuration
- `bootui-ui`: Vue.js frontend
- `bootui-core`: shared DTOs and core helpers
- `bootui-sample-app`: demo/integration sample app

## More docs

- [CONTRIBUTING.md](CONTRIBUTING.md): contributor workflow
- [SECURITY.md](SECURITY.md): threat model and security policy
- [docs/SPECIFICATION.md](docs/SPECIFICATION.md): full product/technical specification
- [docs/PLAN.md](docs/PLAN.md): implementation roadmap

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
