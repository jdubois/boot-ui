# Contributing to BootUI

Thanks for your interest in improving BootUI! This document explains how to
get a working development environment and submit changes.

## Code of conduct

This project adheres to the [Contributor Covenant](CODE_OF_CONDUCT.md). By
participating you are expected to uphold this code.

## Prerequisites

- **Java 25** (or newer). The reference toolchain is OpenJDK 25.
- **Maven Wrapper**. Use the committed `./mvnw` script; no system Maven
  installation is required.
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
./mvnw clean install
```

This downloads Node + npm, runs `npm install`, builds the Vue UI with Vite, and
packages every module. A full clean build takes about a minute on a warm cache.

To rebuild only the backend (useful while iterating on Java code):

```bash
./mvnw -pl bootui-core,bootui-autoconfigure,bootui-spring-boot-starter,bootui-sample-app -am install
```

## Testing

Use the CI-equivalent build before opening or updating a pull request:

```bash
./mvnw -B -ntp clean install
```

Run the browser end-to-end suite when you change the UI, browser-facing API responses, or sample-app behavior:

```bash
cd bootui-sample-app/e2e
npm install
npx playwright install chromium
npm test
```

Playwright can start the sample app automatically. If you already have the sample app running on port 8080, it will reuse that server.

## Run the sample app

```bash
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev
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
running sample app. When you are done, run `./mvnw install -pl bootui-ui` once to
re-bundle the assets into the JAR.

## Publishing

Maven Central publication uses the `release` Maven profile:

```bash
./mvnw -B -ntp -Prelease clean deploy
```

The release profile attaches source and Javadoc JARs, signs artifacts with GPG,
and publishes through the Sonatype Central Publishing plugin using the `central`
server from `~/.m2/settings.xml`. The sample app is not deployed. By default,
Central uploads are published automatically; set `-Dcentral.autoPublish=false`
to stage for manual publishing instead.

The GitHub Actions release workflow (`.github/workflows/release.yml`) runs on
`v*` tags or manually through `workflow_dispatch`. Configure these repository or
environment secrets before running it:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal user token password |
| `GPG_PRIVATE_KEY` | ASCII-armored private key used to sign artifacts |
| `MAVEN_GPG_PASSPHRASE` | Passphrase for the GPG private key |

For a tag release, create a tag that matches the Maven project version, for
example `v0.1.0-alpha.1`. Manual runs publish automatically by default; disable
`auto_publish` when you want to review and publish the deployment in the Central
Portal.

## Submitting a change

1. Open or claim an issue describing the change before you write code.
2. Create a topic branch off `main`. Branch names should start with your
   GitHub username (e.g. `jdubois/improve-config-ui`).
3. Keep PRs small and focused. Update `docs/` whenever public behaviour
   changes.
4. Run `./mvnw clean install` before pushing.
5. Run the Playwright end-to-end suite when you change the UI, browser-facing
   API responses, or sample-app behavior.
6. Use the pull request template — it links to the verifications we expect.

## Reporting bugs and security issues

- **Bugs**: open an issue using the *Bug report* template.
- **Security vulnerabilities**: do **not** open a public issue. Use GitHub's
  private security advisory flow: see [SECURITY.md](SECURITY.md).

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
