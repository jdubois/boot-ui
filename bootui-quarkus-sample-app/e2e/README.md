# BootUI Quarkus sample app — end-to-end tests

[Playwright](https://playwright.dev/) end-to-end tests that drive the BootUI console served by the
[`bootui-quarkus-sample-app`](../) Quarkus module from a real Chromium browser. They are the Quarkus
analogue of [`bootui-sample-app/e2e`](../../bootui-sample-app/e2e) and exist to prove that the **one
shared Vue UI** works end to end against the **Quarkus** adapter, not just Spring Boot.

The suite is deliberately focused rather than a 1:1 copy of the ~40 Spring specs. Per-panel UI logic is
already covered by the Spring e2e suite (same Vue code) and the Quarkus API/response contract is covered
by the `bootui-quarkus-integration-tests` `@QuarkusTest` modules. What only a browser against a live
Quarkus backend can prove is what these tests cover:

- **`app-shell.spec.js`** — the parity proof. Reads the real `GET /bootui/api/panels`, then navigates to
  **every panel the Quarkus adapter reports as available** and asserts each renders its heading. Also
  checks the topbar reports `Quarkus <version>`, the framework-aware **Quarkus** advisor label in the
  sidebar, and that Spring-only panels are collected in the "Disabled / unavailable" group.
- **`quarkus-advisor.spec.js`** — the Spring advisor is replaced by a framework-aware **Quarkus** advisor
  (same shared view, `platform`-driven copy): heading, "Run Quarkus checks", Quarkus idiom rules.
- **`dev-services.spec.js`** — the Dev Services panel renders the build-time Dev Services snapshot (the
  throwaway PostgreSQL container) and explains that live logs/restart are managed by Quarkus, not BootUI.
- **`cache.spec.js`** — the renamed **Cache** panel lists the Quarkus caches and clears one.
- **`not-applicable.spec.js`** — Spring-only panels (DevTools, Conditions, HTTP Sessions, …) degrade
  honestly with a "Not applicable on Quarkus" explanation instead of breaking.

## Prerequisites

1. **A supported JDK (17 or 21).** The Quarkus sample is wired into the Maven reactor only on JDK 17/21
   (see the root `pom.xml` `quarkus-sample-app` profile); on newer JDKs it is skipped and cannot be built.
2. **Docker or Podman running.** Quarkus Dev Services starts a throwaway PostgreSQL container at boot.
3. **The BootUI artifacts installed locally**, so `quarkus:dev` can resolve the `bootui-quarkus`
   extension:

   ```bash
   ./mvnw -pl bootui-quarkus-sample-app -am -DskipTests install
   ```

## Running

```bash
cd bootui-quarkus-sample-app/e2e
npm ci
npx playwright install --with-deps chromium
npm test
```

Playwright boots the sample app for you with `./mvnw quarkus:dev` and waits for
`http://localhost:8080/bootui/api/overview`. If you already have it running, the existing server is
reused.

## Useful environment variables

| Variable                   | Default                   | Purpose                                                    |
| -------------------------- | ------------------------- | ---------------------------------------------------------- |
| `BOOTUI_SAMPLE_PORT`       | `8080`                    | Port the Quarkus sample listens on.                        |
| `BOOTUI_BASE_URL`          | `http://localhost:<port>` | Base URL the browser hits.                                 |
| `BOOTUI_SKIP_WEBSERVER`    | _(unset)_                 | Set to `1` to test an already-running server / list tests. |
| `BOOTUI_WEBSERVER_TIMEOUT` | `300000`                  | Startup timeout (ms); raise it on slow Dev Services pulls. |

## CI

The `quarkus-e2e` job in [`.github/workflows/build.yml`](../../.github/workflows/build.yml) runs this
suite on JDK 17 (Docker is available on the GitHub-hosted runner), after building the extension and the
sample app.
