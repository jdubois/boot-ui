# Contributing to BootUI

Thanks for your interest in improving BootUI! This document explains how to
get a working development environment and submit changes.

## Code of conduct

This project adheres to the [Contributor Covenant](CODE_OF_CONDUCT.md). By
participating you are expected to uphold this code.

## Prerequisites

- **Java 25** (or newer). The reference toolchain is OpenJDK 25.
- **Maven 3.9+**. The wrapper is not committed; use your system Maven.
- **Node.js 24+** and npm 11+ are downloaded automatically by the
  `frontend-maven-plugin` when you run the build. You do not need to install
  Node manually.
- **Spring Boot 4.0+** is targeted. BootUI does not support Spring Boot 3.x.

## Project layout

```
bootui-core/                  Shared DTOs and helpers
bootui-autoconfigure/         Auto-configuration, REST controllers, safety filter
bootui-spring-boot-starter/   Drop-in starter that pulls in everything
bootui-ui/                    Vue 3 SPA bundled into META-INF/resources/bootui
bootui-sample-app/            Reference Spring Boot 4 app that demos the starter
docs/                         Specification and roadmap
```

## Build

```bash
mvn clean install
```

This downloads Node + npm, runs `npm install`, builds the Vue UI with Vite, and
packages every module. A full clean build takes about a minute on a warm cache.

To rebuild only the backend (useful while iterating on Java code):

```bash
mvn -pl bootui-core,bootui-autoconfigure,bootui-spring-boot-starter,bootui-sample-app -am install
```

## Run the sample app

```bash
cd bootui-sample-app
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open <http://localhost:8080/bootui>.

## Front-end development

The Vue source lives in `bootui-ui/src/main/frontend`. For a fast inner loop:

```bash
cd bootui-ui/src/main/frontend
npm install
npm run dev
```

This starts Vite on a separate port and proxies `/bootui/api/*` to a locally
running sample app. When you are done, run `mvn install -pl bootui-ui` once to
re-bundle the assets into the JAR.

## Submitting a change

1. Open or claim an issue describing the change before you write code.
2. Create a topic branch off `main`. Branch names should start with your
   GitHub username (e.g. `jdubois/improve-config-ui`).
3. Keep PRs small and focused. Update `docs/` whenever public behaviour
   changes.
4. Run `mvn clean install` before pushing.
5. Use the pull request template — it links to the verifications we expect.

## Reporting bugs and security issues

- **Bugs**: open an issue using the *Bug report* template.
- **Security vulnerabilities**: do **not** open a public issue. Use GitHub's
  private security advisory flow: see [SECURITY.md](SECURITY.md).

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
