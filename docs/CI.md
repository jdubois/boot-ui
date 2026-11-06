# BootUI in CI/CD

BootUI can check a running application and add the result to your GitHub Actions job. You can optionally run an
integration workload between attachment and assessment. The application stays on the runner; BootUI is not a
production or remote scanner.

<!-- bootui-ci:release:start -->
> **CI support is not yet published.** Published BootUI CLI 2.3.0 does not contain these CI commands. The examples below are prepared for the first signed CI-capable release; `UNRELEASED` is not an installable version. Do not substitute the old published CLI.
<!-- bootui-ci:release:end -->

## Quick start: GitHub Actions

Use a Linux runner. The Action starts a diagnostic application process, waits for BootUI, assesses that same process,
and stops it afterward. By default it does not duplicate your existing test job.

1. Add BootUI to your development or diagnostic build, not your production artifact. See the
   [Spring Boot](SETUP.md) or [Quarkus](setup/quarkus.md) setup guide.
2. Add the following step to the same job as your normal build/tests:

<!-- bootui-ci:source:snippets/action.yml.template:start -->
```yaml
- name: BootUI diagnostics
  uses: jdubois/boot-ui/.github/actions/bootui@0572c2f676a0d0ead3fa9ce29b8fc9354ab7622f # vUNRELEASED CLI interface; independent Action commit
```
<!-- bootui-ci:source:snippets/action.yml.template:end -->

Your workflow still needs its usual checkout and Java setup. By default, the Action detects Maven or Gradle and chooses:

| Framework | Application process |
| --- | --- |
| Maven + Spring Boot | `./mvnw spring-boot:run` |
| Maven + Quarkus | `./mvnw quarkus:dev` |
| Gradle + Spring Boot | `./gradlew --no-daemon bootRun` |
| Gradle + Quarkus | `./gradlew --no-daemon quarkusDev` |

For a Maven project that keeps BootUI in a `bootui-ci` profile, as the starter kit does, the Action adds
`-Pbootui-ci` automatically. Other profiles and multi-module selections can be provided with `app-command`.

The application command is a **long-lived foreground process**. The Action waits until
`http://127.0.0.1:8080/bootui/api/cli` reports that BootUI is enabled, then runs the selected advisors. The default
Action does not run `./mvnw verify`: most advisors inspect the live application rather than requiring a test workload,
and your job can keep running its normal tests separately. `verify` is a **finite build/test command** that compiles,
runs configured tests, packages, and exits. It can be supplied as `test-command` when those tests send traffic to the
separately started application and therefore provide useful runtime evidence. Running `verify` does not by itself prove
that its tests contacted that process.

The Action supplies loopback-only host/port settings, enables the BootUI CLI endpoint for the child process, uses
`METADATA_ONLY` exposure, and isolates its dismissal file. It stops only the process group that it started. You do not
need a policy file, BootUI helper scripts, a separately installed CLI, or a separate application-start step.
Both detected commands run from `GITHUB_WORKSPACE`. The Action also reads the stable BootUI dependency version from
the application's Maven or Gradle files and downloads the matching CLI. This keeps the usual Action configuration
to one line without coupling the action pin to a Maven coordinate.

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

The default policy runs every CI-capable local advisor—Architecture, Hibernate, Spring, REST API, Security, and
Database—and fails on any HIGH or CRITICAL finding. Architecture is required; stack-specific or data-source-dependent
advisors are run when available and reported with a `SKIPPED` reason when the target cannot assess them. An
unavailable or incomplete required check also fails; it is not treated as a clean result.

The CI policy gates individual findings, not the browser's aggregate **Overall score**. That UI score currently averages
known-findings scores in the browser and may include GitHub security signals, so it is not a deterministic CLI policy
contract. The default CI policy excludes the GitHub advisor and vulnerability queries: they require repository
credentials or external advisory services and are not needed for the local runtime assessment. If configured, a test
command still runs if BootUI cannot attach, and its failure fails the job independently of BootUI.

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

The `bootui-version` must match the CI-capable BootUI release in your application. When omitted, the Action detects
exactly one stable BootUI version from the project's Maven or Gradle files and fails explicitly if it cannot. The Action is pinned to a reviewed
commit; its pin and the CLI version are updated in these examples when BootUI releases. Managed applications must use
literal-loopback HTTP; externally managed TLS targets use `app-command: none`. Your own copied workflow
remains under your control.

### Spring Boot: add BootUI only to the diagnostic build

The Spring starter works with MVC and WebFlux. Add it only to the diagnostic build; the Action enables BootUI and
the CLI endpoint in its child process and supplies an isolated dismissal file.

### Quarkus development-mode target

The default Action starts Quarkus with `quarkus:dev`: BootUI endpoints are available in DEV/TEST, not a production
jar. The Action supplies an isolated dismissal file outside watched paths to avoid a reload during assessment.
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
