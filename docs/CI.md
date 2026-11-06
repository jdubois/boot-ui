# BootUI in CI/CD

BootUI can check your application while your integration tests run and add the result to your GitHub Actions job.
The application stays on the runner; BootUI is not a production or remote scanner.

<!-- bootui-ci:release:start -->
> **CI support is not yet published.** Published BootUI CLI 2.3.0 does not contain these CI commands. The examples below are prepared for the first signed CI-capable release; `UNRELEASED` is not an installable version. Do not substitute the old published CLI.
<!-- bootui-ci:release:end -->

## Quick start: GitHub Actions

Use a Linux runner. This is the simplest setup when your workflow starts an application **before** its tests:

1. Add BootUI to your development or diagnostic build, not your production artifact. See the
   [Spring Boot](SETUP.md) or [Quarkus](setup/quarkus.md) setup guide.
2. Start the application on the runner, bound to loopback, and wait until it is ready. Use
   `SERVER_ADDRESS=127.0.0.1` for Spring Boot or `QUARKUS_HTTP_HOST=127.0.0.1` for Quarkus. Set
   `BOOTUI_CLI_ENABLED=true` and `BOOTUI_EXPOSE_VALUES=METADATA_ONLY` for that process; on Spring Boot, also set
   `BOOTUI_ENABLED=ON`. Keep the application running until the Action finishes.
3. Replace your existing test step with the following Action. Change `test-command` to your tests that exercise
   **that running application**.

<!-- bootui-ci:source:snippets/action.yml.template:start -->
```yaml
- name: BootUI diagnostics
  uses: jdubois/boot-ui/.github/actions/bootui@0572c2f676a0d0ead3fa9ce29b8fc9354ab7622f # vUNRELEASED CLI interface; independent Action commit
  with:
    bootui-version: 'UNRELEASED'
    test-command: ./mvnw verify
```
<!-- bootui-ci:source:snippets/action.yml.template:end -->

Your workflow still needs its usual checkout, Java setup, and application-start steps. By default the Action connects
to `http://127.0.0.1:8080`, downloads and verifies the matching CLI, runs your test command, and publishes the results.
You do not need a policy file, BootUI helper scripts, or a separately installed CLI.
`test-command` runs from `GITHUB_WORKSPACE`; for a subdirectory, use e.g. `cd my-service && ./mvnw verify`.

::: tip Tests that start the application themselves
If `@SpringBootTest` or `@QuarkusTest` starts and stops the application inside the test process, use the
[framework-managed test recipe](ci/framework-tests.md) instead. A separate Action cannot assess an application
that has already stopped.
:::

### Results

Open the workflow run's **Summary**. The Action also uploads `summary.md`, `bundle.json`, and `junit.xml` as an artifact
with a seven-day retention period.

| Result | Meaning |
| --- | --- |
| **PASS** | The assessment completed and no finding met the failure threshold. |
| **VIOLATION** | A finding met the threshold; the job fails. |
| **NOT_ASSESSED** | BootUI could not complete a reliable assessment; the job fails. |

The default policy checks **Architecture** and fails on HIGH or CRITICAL findings. An unavailable or incomplete
required check also fails; it is not treated as a clean result. A test failure fails the job independently of BootUI.
The test command still runs if BootUI cannot attach, and BootUI attempts assessment after a failed test.

### Optional settings

| Input | When to set it |
| --- | --- |
| `url` | Your app uses a different literal-loopback URL, such as `http://127.0.0.1:8081`. |
| `policy` | You want to [select other checks or thresholds](ci/reference.md#policy). |
| `upload-artifact` | Set to `'false'` if you do not want the Action to upload the reports. |
| `api-path` | You changed the BootUI API mount from `/bootui/api`. |

The `bootui-version` must match the CI-capable BootUI release in your application. The Action is pinned to a reviewed
commit; its pin and the CLI version are updated in these examples when BootUI releases. Your own copied workflow
remains under your control.

### Spring Boot: add BootUI only to the diagnostic build

The Spring starter works with MVC and WebFlux. Enable BootUI and the CLI endpoint in the process under assessment,
and keep the starter out of the production artifact. To isolate dismissals in either framework, set
`BOOTUI_OVERRIDES_FILE` on the application process to `application-bootui.properties` in a fresh, empty directory.

### Quarkus development-mode target

Run an extension-enabled application in `quarkus:dev`: BootUI endpoints are available in DEV/TEST, not a production
jar. Keep evidence and the fresh dismissal store outside watched paths to avoid a reload during assessment.
For `@QuarkusTest`, use the [framework-managed recipe](ci/framework-tests.md#quarkus).

### Framework-managed test lifecycles

When the test framework owns application startup and teardown, the [framework-managed recipe](ci/framework-tests.md)
shows the explicit test integration and final offline gate for Spring MVC, WebFlux, and Quarkus.

### Attach, run, assess, and publish

The default Action handles these steps for you. The [CI reference](ci/reference.md#action-phases-and-outputs)
documents the lower-level phases for custom workflows and other CI platforms.

## Try a complete sample

The starter kit includes runnable Spring MVC, WebFlux, and Quarkus projects and more detailed lifecycle examples.
It is optional; you do not need to copy it into your application to use the Action.

<!-- bootui-ci:kit:start -->
The starter-kit download becomes available with the first CI-capable release. The complete example sources are in the repository's `examples/ci` directory; candidate testing is separate from a published-release installation.
<!-- bootui-ci:kit:end -->

## Troubleshooting

| Symptom | First thing to check |
| --- | --- |
| `NOT_ASSESSED` or `ATTACH_FAILED` | The application is ready, bound to loopback, and has BootUI and its CLI endpoint enabled. |
| Tests pass but BootUI fails | Open the summary for the failing rule or incomplete required check. |
| The CLI does not recognize `ci` | Use a published CI-capable release, not the older 2.3.0 CLI. |
| Tests start and stop their own app | Use the [framework-managed recipe](ci/framework-tests.md). |

An early workflow failure, cancellation, or hard runner timeout can prevent a summary or artifact from being created.
Reports contain sanitized diagnostic summaries, not application logs or the attachment witness.
