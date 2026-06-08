# Contributing to BootUI

Thanks for your interest in improving BootUI! This document explains how to
get a working development environment and submit changes.

## Code of conduct

This project adheres to the [Contributor Covenant](CODE_OF_CONDUCT.md). By
participating you are expected to uphold this code.

## Prerequisites

- **Java 17** (or newer). The reference toolchain is OpenJDK 17.
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

This downloads Node + npm, runs `npm install`, runs the Vue unit tests with Vitest,
builds the Vue UI with Vite, and packages every module. A full clean build takes
about a minute on a warm cache.

To rebuild only the backend (useful while iterating on Java code):

```bash
./mvnw -pl bootui-core,bootui-autoconfigure,bootui-spring-boot-starter,bootui-sample-app -am install
```

## Testing

Use the CI-equivalent build before opening or updating a pull request:

```bash
./mvnw -B -ntp clean install
```

For frontend-only unit test iteration:

```bash
cd bootui-ui/src/main/frontend
npm install
npm test
```

Run the browser end-to-end suite when you change the UI, browser-facing API responses, or sample-app behavior:

```bash
cd bootui-sample-app/e2e
npm install
npx playwright install chromium
npm test
```

Playwright can start the sample app automatically. If you already have the sample app running on port 8080, it will
reuse that server.

## Code quality and coverage

[SonarCloud](https://sonarcloud.io/project/overview?id=jdubois_boot-ui) analysis runs in the `Build` workflow on pushes
and pull requests targeting `main`. The build generates the coverage reports Sonar consumes:

- **Java** — JaCoCo instruments the Surefire tests and writes `target/site/jacoco/jacoco.xml` in each module during the
  `verify` phase of `./mvnw clean install`.
- **Frontend** — Vitest emits `bootui-ui/src/main/frontend/coverage/lcov.info` (a `posttest` hook rewrites the report
  paths so they resolve against the `bootui-ui` Maven module).

The `bootui-sample-app` module is a reference/demo app and is excluded from Sonar analysis via `sonar.skip=true` in its
POM, so its sources and coverage are not reported to SonarCloud.

End-to-end (Playwright) tests still run in CI but do not contribute coverage.

The analysis step is a no-op until two one-time setup steps are done:

1. Add a `SONAR_TOKEN` repository secret (generated from your SonarCloud account) in GitHub.
2. In SonarCloud, disable **Automatic Analysis** (Administration → Analysis Method) — it is mutually exclusive with the
   CI-based analysis and must be off for coverage to be imported.

To reproduce the coverage reports locally, run `./mvnw clean install` (Java) and `npm test` in
`bootui-ui/src/main/frontend` (frontend).

## Formatting

Use Spotless for Java and repository whitespace checks:

```bash
./mvnw spotless:apply
./mvnw spotless:check
```

Use Prettier for the Vue app and Playwright tests:

```bash
(cd bootui-ui/src/main/frontend && npm run format)
(cd bootui-sample-app/e2e && npm run format)
```

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
running sample app. Use `npm run test:watch` for Vitest watch mode while iterating.
When you are done, run `./mvnw install -pl bootui-ui` once to re-bundle the assets
into the JAR.

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

To prepare and publish a release, run the **Release** GitHub Actions workflow
from the branch you want to release, usually `main`, and enter the target version
without the leading `v`, for example `1.0.0`. The workflow updates all Maven
module versions, refreshes the documentation dependency examples, optionally verifies
with the `release` Maven profile, commits the release, creates an annotated
`v1.0.0` tag, pushes the branch plus tag, and publishes to Maven Central in the
same run. The selected branch must allow `github-actions[bot]` to push the
release commit and tag.

The same workflow (`.github/workflows/release.yml`) also runs on manually pushed
`v*` tags, or manually with an empty version when the selected ref is already
tagged with the Maven project version. Configure these repository or environment
secrets before running it:

| Secret                   | Value                                            |
| ------------------------ | ------------------------------------------------ |
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal user token username      |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal user token password      |
| `GPG_PRIVATE_KEY`        | ASCII-armored private key used to sign artifacts |
| `MAVEN_GPG_PASSPHRASE`   | Passphrase for the GPG private key               |

Manual runs publish automatically by default; disable `auto_publish` when you
want to review and publish the deployment in the Central Portal.

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

- **Bugs**: open an issue using the _Bug report_ template.
- **Security vulnerabilities**: do **not** open a public issue. Use GitHub's
  private security advisory flow: see [SECURITY.md](SECURITY.md).

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
