# BootUI in CI/CD

**One Action step. A live application assessment in your job Summary.**

<!-- bootui-ci:release:start -->
> **CI support is not yet published.** Published BootUI CLI 2.3.0 does not contain these CI commands. The examples below are prepared for the first signed CI-capable release; `UNRELEASED` is not an installable version. Do not substitute the old published CLI.
<!-- bootui-ci:release:end -->

## Add BootUI to GitHub Actions <a id="quick-start-github-actions"></a>

On a Linux runner, add BootUI to the diagnostic build for [Spring Boot](SETUP.md) or [Quarkus](setup/quarkus.md),
then add this step after your usual checkout and Java setup:

<!-- bootui-ci:source:snippets/action.yml.template:start -->
```yaml
- name: BootUI diagnostics
  uses: jdubois/boot-ui@vUNRELEASED
```
<!-- bootui-ci:source:snippets/action.yml.template:end -->

That is the default setup. The Action:

1. Detects and starts your Spring Boot or Quarkus app on loopback.
2. Runs every available local advisor and gates HIGH/CRITICAL findings.
3. Adds a readable Markdown report to the job Summary, uploads reports, and stops the app.

## What appears in GitHub Actions <a id="results"></a>

Open the workflow run's **Summary**. An abbreviated failed assessment looks like this:

```markdown
# BootUI assessment: VIOLATION

| Advisor | Required | Status | Findings | Rules skipped |
|---|---:|---|---:|---:|
| architecture | true | ASSESSED | 1 | 0 |
| rest-api | false | ASSESSED | 0 | 0 |
| security | false | SKIPPED | 0 | 0 |

### Retained finding summaries

| Advisor | Rule | Severity | Count | Meets threshold |
|---|---|---|---:|---|
| architecture | ARCH-PKG-001 | HIGH | 1 | yes |
```

| Result | Meaning |
| --- | --- |
| **PASS** | The assessment completed and no finding met the failure threshold. |
| **VIOLATION** | A finding met the threshold; the job fails. |
| **NOT_ASSESSED** | BootUI could not complete a reliable assessment; the job fails. |

The Action also uploads these files for seven days:

- `summary.md` — the Summary shown above.
- `bundle.json` — bounded sanitized machine-readable evidence.
- `junit.xml` — standard JUnit XML for CI test reporters.

::: details Display BootUI as a JUnit test/check

GitHub does not turn uploaded `junit.xml` into a Tests view. To publish one, feed
`${{ steps.bootui.outputs.report-dir }}/junit.xml` to a JUnit reporter with `checks: write` and add `id: bootui` to the BootUI step.
For fork pull requests, use a trusted follow-up workflow because their token is read-only.

For example, with `dorny/test-reporter`:

```yaml
- if: ${{ always() && steps.bootui.outputs.report-dir != '' && (github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository) }}
  uses: dorny/test-reporter@d1b5dff021aa968b288b02285a8bf47471c5928a # v3.2.0
  with:
    name: BootUI diagnostics
    path: ${{ steps.bootui.outputs.report-dir }}/junit.xml
    reporter: java-junit
```
:::

The Action runs six CI-capable local advisors: Architecture, Hibernate, Spring, REST API, Security, and Database.
Architecture is required; stack-specific advisors can be `SKIPPED`. HIGH or CRITICAL findings fail the job.

The policy excludes the GitHub advisor and vulnerability-service queries, which need credentials or external services;
CI gates advisor findings directly, not the browser's aggregate score.

::: details How startup and `verify` are handled <a id="spring-boot-add-bootui-only-to-the-diagnostic-build"></a><a id="quarkus-development-mode-target"></a><a id="attach-run-assess-and-publish"></a>

The Action detects these long-lived commands:

| Framework | Application process |
| --- | --- |
| Maven + Spring Boot | `./mvnw spring-boot:run` |
| Maven + Quarkus | `./mvnw quarkus:dev` |
| Gradle + Spring Boot | `./gradlew --no-daemon bootRun` |
| Gradle + Quarkus | `./gradlew --no-daemon quarkusDev` |

Maven projects with a `bootui-ci` profile get `-Pbootui-ci` automatically.

`spring-boot:run` or `quarkus:dev` **starts the app and keeps it running**. `mvn verify` **builds and tests, then
exits**. Most advisors do not need `verify`, so the Action does not run it by default. Add `test-command` only when
your tests exercise the separately running app and you want runtime evidence from that workload.

The Action forces literal-loopback networking, metadata-only exposure, an isolated dismissal file, and cleans up only
the process group it created.
:::

### Optional settings

| Input | When to set it |
| --- | --- |
| `bootui-version` | Pin a version explicitly when the project uses dependency indirection that auto-detection cannot resolve, or when several BootUI versions are present. |
| `app-command` | Set the foreground command for a custom or multi-module start, or use `none` for an application that another step already started and will stop. |
| `test-command` | Run a finite command before assessment, such as `./mvnw verify`; it must exercise the running application when runtime evidence is the goal. Use `auto` for Maven `verify` or Gradle `check`. |
| `startup-timeout` | BootUI needs more than the default 120 seconds to become ready. |
| `url` | Your app uses a different literal-loopback URL, such as `http://127.0.0.1:8081`. |
| `policy` | You want to [select other checks or thresholds](ci/reference.md#policy). |
| `upload-artifact` | Set to `'false'` if you do not want the Action to upload the reports. |
| `api-path` | You changed the BootUI API mount from `/bootui/api`. |

For example, a multi-module project can set a custom startup and workload explicitly:

```yaml
with:
  app-command: ./mvnw -pl service spring-boot:run
  test-command: ./mvnw -pl integration-tests verify
```

When omitted, exactly one stable `bootui-version` is detected from the project's Maven or Gradle files. Managed applications use
literal-loopback HTTP; externally managed TLS targets use `app-command: none`. Quarkus BootUI endpoints are available
in DEV/TEST, not production mode. The [CI reference](ci/reference.md#action-phases-and-outputs) covers advanced phases
and custom workflows.

## Optional complete sample <a id="try-a-complete-sample"></a>
The starter kit includes runnable Spring MVC, WebFlux, and Quarkus examples. It is not required for the Action.

<!-- bootui-ci:kit:start -->
The starter-kit download becomes available with the first CI-capable release. The complete example sources are in the repository's `examples/ci` directory; candidate testing is separate from a published-release installation.
<!-- bootui-ci:kit:end -->

## Troubleshooting

| Symptom | First thing to check |
| --- | --- |
| `NOT_ASSESSED` or `ATTACH_FAILED` | The application is ready, bound to loopback, and has BootUI and its CLI endpoint enabled. |
| Tests pass but BootUI fails | Open the summary for the failing rule or incomplete required check. |
| The CLI does not recognize `ci` | Use a published CI-capable release, not the older 2.3.0 CLI. |
| The app starts but BootUI never becomes ready | Add BootUI to the started build (often `-Pbootui-ci`) or set `app-command` explicitly. |
| `@SpringBootTest` / `@QuarkusTest` starts and stops its own app | <a id="framework-managed-test-lifecycles"></a>Use the [framework-managed test recipe](ci/framework-tests.md). |

An early workflow failure, cancellation, or hard runner timeout can prevent a summary or artifact from being created. Reports contain sanitized diagnostic summaries, not application logs or the attachment witness.
