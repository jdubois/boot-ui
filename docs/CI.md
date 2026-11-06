# BootUI in CI/CD

Run your integration tests, then let BootUI assess the running application. GitHub Actions shows a readable summary and
keeps the reports; your policy decides whether diagnostic findings fail the job.
BootUI stays local to the runner. It is not a production scanner or a hosted dashboard.

<!-- bootui-ci:release:start -->
> **CI support is not yet published.** Published BootUI CLI 2.3.0 does not contain these CI commands. The examples below are prepared for the first signed CI-capable release; `UNRELEASED` is not an installable version. Do not substitute the old published CLI.
<!-- bootui-ci:release:end -->

## Choose your setup

| Who starts the application? | Use |
| --- | --- |
| Your workflow starts a separate Spring Boot process | The quick start below |
| Your workflow starts a separate Quarkus dev-mode process | [Quarkus guidance](#quarkus-development-mode-target) |
| `@SpringBootTest` or `@QuarkusTest` starts it inside tests | [Framework-managed tests](ci/framework-tests.md) |
| You already have a saved report bundle | [Offline rechecking](ci/reference.md#offline-rechecking) |

## Quick start: GitHub Actions

Use a Linux runner and a compatible JDK. Both the application and CLI must use the same literal loopback URL.
The complete starter example supplies the application, Maven configuration, real workload, readiness checks, and
owned-process cleanup. You do not need to invent missing helper scripts.

<!-- bootui-ci:kit:start -->
The starter-kit download becomes available with the first CI-capable release. The complete example sources are in the repository's `examples/ci` directory; candidate testing is separate from a published-release installation.
<!-- bootui-ci:kit:end -->

### 1. Add BootUI only to the diagnostic build

The example's diagnostic profile adds BootUI without making it part of the production artifact. The application and
CLI use one explicit BootUI release. Configure the diagnostic process for metadata-only exposure and keep MCP off.
Use a fresh empty dismissal store so developer-local findings cannot be hidden in CI.

Extract the **whole kit into a new repository**, including dotfiles. Its `spring` project supports MVC and WebFlux.
Keep the helper scripts beside the projects; they implement the bounded readiness, workload, reporting, and cleanup
lifecycle. Copying a workflow alone is not enough.

::: details Complete Spring Maven configuration

<!-- bootui-ci:source:spring/pom.xml.template:start -->
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.2</version>
        <relativePath/>
    </parent>
    <groupId>example</groupId>
    <artifactId>orders-spring</artifactId>
    <version>1.0.0</version>
    <properties>
        <java.version>17</java.version>
        <bootui.version>UNRELEASED</bootui.version>
        <spring.web.starter>spring-boot-starter-web</spring.web.starter>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>${spring.web.starter}</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <profiles>
        <profile>
            <id>webflux</id>
            <properties>
                <spring.web.starter>spring-boot-starter-webflux</spring.web.starter>
            </properties>
        </profile>
        <profile>
            <id>bootui-ci</id>
            <dependencies>
                <dependency>
                    <groupId>com.julien-dubois.bootui</groupId>
                    <artifactId>bootui-spring-boot-starter</artifactId>
                    <version>${bootui.version}</version>
                    <scope>runtime</scope>
                </dependency>
                <dependency>
                    <groupId>com.julien-dubois.bootui</groupId>
                    <artifactId>bootui-cli</artifactId>
                    <version>${bootui.version}</version>
                    <scope>test</scope>
                </dependency>
            </dependencies>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <version>3.6.1</version>
                        <executions>
                            <execution>
                                <id>ci-tests</id>
                                <phase>generate-test-sources</phase>
                                <goals><goal>add-test-source</goal></goals>
                                <configuration>
                                    <sources><source>src/ci/java</source></sources>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <forkCount>1</forkCount>
                    <rerunFailingTestsCount>0</rerunFailingTestsCount>
                    <systemPropertyVariables>
                        <junit.jupiter.execution.parallel.enabled>false</junit.jupiter.execution.parallel.enabled>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```
<!-- bootui-ci:source:spring/pom.xml.template:end -->

:::

The starter policy assesses Architecture and fails on HIGH or CRITICAL findings. Commit it with your workflow:

<!-- bootui-ci:source:bootui-policy.json:start -->
```json
{
  "schemaVersion": 1,
  "advisors": {
    "architecture": {
      "required": true,
      "failOn": "high"
    }
  }
}
```
<!-- bootui-ci:source:bootui-policy.json:end -->

### 2. Add the workflow

The workflow starts the app and waits for readiness, attaches, runs the workload, and assesses while the app is still
alive. It then stops the job-owned app, adds the result to the job summary, and uploads reports. Attachment refusal
does not suppress the test workload, but the final gate still fails. An ordinary test failure remains a failed job,
even if BootUI later passes.

From the extracted kit's root, select the external-process workflow:

```bash
mkdir -p .github/workflows
cp workflows/external.yml .github/workflows/bootui.yml
```

Keep the remaining kit files at the repository root. The source template is
`examples/ci/workflows/external.yml.template`; the downloadable kit contains its release-rendered version.

::: details Complete GitHub Actions workflow

<!-- bootui-ci:source:workflows/external.yml.template:start -->
```yaml
name: BootUI external application
on:
  push:
  pull_request:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  integration:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    strategy:
      fail-fast: false
      matrix:
        stack: [mvc, webflux]
    env:
      BOOTUI_VERSION: 'UNRELEASED'
      BOOTUI_ASSESSMENT_ID: ${{ github.run_id }}:${{ github.run_attempt }}:${{ matrix.stack }}
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@77dd5b2ce225ea11469928ac317850245a74ae78 # v6.2.0
        with:
          distribution: temurin
          java-version: '25'
      - name: Verify the matching published CLI
        uses: jdubois/boot-ui/.github/actions/bootui@0572c2f676a0d0ead3fa9ce29b8fc9354ab7622f # vUNRELEASED CLI interface; independent Action commit
        with:
          phase: prepare-cli
          bootui-version: 'UNRELEASED'
      - name: Locate source checkout or extracted kit
        run: |
          recipe_dir=examples/ci
          test -f "$recipe_dir/run-external.py" || recipe_dir=.
          test -f "$recipe_dir/run-external.py"
          printf 'BOOTUI_EXAMPLE_DIR=%s\n' "$recipe_dir" >> "$GITHUB_ENV"
          printf 'BOOTUI_CI_ROOT=%s\n' "$RUNNER_TEMP/bootui-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-${{ matrix.stack }}" >> "$GITHUB_ENV"
      - name: Build, start, attach, run workload, assess, and stop
        run: python3 "$BOOTUI_EXAMPLE_DIR/run-external.py" --stack "${{ matrix.stack }}"
      - name: Add BootUI report to job summary
        if: ${{ !cancelled() && env.BOOTUI_CI_ROOT != '' }}
        shell: bash
        run: |
          report="$BOOTUI_CI_ROOT/artifacts/assessment/summary.md"
          fallback="$BOOTUI_CI_ROOT/artifacts/not-assessed/summary.md"
          if [[ -L "$report" || -L "$fallback" ]]; then
            echo "::error::BootUI summary must not be a symlink."
            exit 1
          fi
          if [[ -f "$fallback" ]]; then report="$fallback"; fi
          if [[ -f "$report" ]]; then
            bytes="$(wc -c < "$report")"
            if (( bytes > 262144 )); then
              echo "::error::BootUI summary exceeds the upload bound."
              exit 1
            fi
            cat "$report" >> "$GITHUB_STEP_SUMMARY"
          else
            printf '%s\n' '## BootUI assessment unavailable' '' \
              'No complete report was produced; check the job logs.' >> "$GITHUB_STEP_SUMMARY"
          fi
      - name: Upload only bounded sanitized BootUI reports
        if: ${{ !cancelled() && env.BOOTUI_CI_ROOT != '' }}
        uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2
        with:
          name: bootui-external-${{ matrix.stack }}
          path: ${{ env.BOOTUI_CI_ROOT }}/artifacts/
          if-no-files-found: error
          retention-days: 7
      - name: Require both workload and assessment success
        if: ${{ !cancelled() }}
        run: python3 "$BOOTUI_EXAMPLE_DIR/check-result.py"
```
<!-- bootui-ci:source:workflows/external.yml.template:end -->

:::

The workflow runs MVC and WebFlux independently and bootstraps the matching signature-verified released CLI; no global
installation is needed. When adapting it to your application, keep only your stack.

### 3. Run it and read the result

Commit and push the chosen workflow and the complete kit: it runs on pushes and pull requests. You can also use
**Run workflow** in the **Actions** tab once the workflow is on your repository's default branch; no inputs are needed.
While it runs, GitHub shows the normal step progress and logs. BootUI's summary appears after assessment; it is not a
live dashboard.

| Result | What it means | What to do |
| --- | --- | --- |
| **PASS** | Complete assessment; no finding meets your failure threshold | Review lower-severity findings when useful |
| **VIOLATION** | Complete assessment; a finding meets the threshold | Open the rule summary and fix it, or deliberately review your policy |
| **NOT_ASSESSED** | BootUI could not obtain a reliable assessment | Fix setup or collection; do not interpret this as a clean scan |

Open the workflow run's **Summary** for the BootUI report. Under **Artifacts**, download the report containing
`summary.md`, `bundle.json`, and `junit.xml`. The example preserves reports when the assessment fails. If setup fails
before the report directory can be created, the summary may say the assessment is unavailable. An earlier workflow
failure can leave no BootUI summary or artifact; check the failed step's logs.
JUnit XML is an interchange file, not an automatically created GitHub test-results dashboard.

::: details How a passing run can still have findings

If your policy fails at HIGH, a LOW finding remains visible without failing the job. If it fails at CRITICAL, a HIGH
finding is below that threshold. PASS means the configured gate passed, not “zero findings.”
The report retains rule IDs, severity, counts, and whether they meet the threshold; it omits raw application prose.

:::

## Adapt it to your application

Start from the complete example, then replace its application and workload with yours. Keep the ordering and failure
guards unchanged. Set your actual packaged application/start command, readiness endpoint, port, API mount, and
integration-test command. Select only advisors for which your application can produce complete evidence.
The first policy selects Architecture; adding every advisor at once is not a good first setup.

### Spring Boot: add BootUI only to the diagnostic build

The same Spring starter supports MVC and WebFlux; the application's web starter selects the stack.
Activate the diagnostic profile only for the process under assessment and verify the normal production build excludes
BootUI. Use the same loopback URL for startup, workload, attachment, and assessment.

### Quarkus development-mode target

For an external process, run the opt-in extension-enabled application through `quarkus:dev`, not a production jar.
BootUI endpoints exist in DEV/TEST only. Quarkus has no Spring-style `BOOTUI_ENABLED` switch.
Keep generated evidence and the fresh dismissal store outside watched sources so a reload cannot change the app's
run identity during assessment. Dev Services may require Docker or Podman.
For `@QuarkusTest`, use the [test-lifecycle recipe](ci/framework-tests.md#quarkus), not a post-test live assessment.

### Framework-managed test lifecycles

When the test framework starts and stops the app, collect before its teardown and run an offline gate after the
producer processes exit. The [complete test recipes](ci/framework-tests.md) provide the dependency, actual port
injection, lifecycle hooks, and workflow. This path requires explicit test integration; no hooks are installed
automatically.

### Attach, run, assess, and publish

Keep the app alive until assessment finishes. Use fresh witness/output paths per attempt, pass attachment's original
exit code to assessment, upload from the returned `report-dir`, and reserve `always()` for owned-process cleanup.
The [CI reference](ci/reference.md) explains Action inputs, policy, limits, and error behavior.

## Troubleshooting

| Symptom | First thing to check |
| --- | --- |
| CLI does not recognize `ci` | Use a published CI-capable release, not the earlier CLI; app and CLI versions must match |
| `ATTACH_FAILED` | Wait for readiness; check loopback URL, actual port/API mount, activation, and CLI enablement |
| Target changed during assessment | Avoid restarting the app or writing into Quarkus watched paths |
| Advisor is unavailable or evidence is incomplete | Start with a small applicable policy; do not turn partial evidence into a pass |
| Reports disappeared after a failed gate | Keep the starter's staged-report upload guarded by `!cancelled()`; direct Action users can upload its `report-dir` output |
| Framework gate reports a missing window | Match the planned ID, run the hook once, finish before teardown, and disable unplanned retries |
| Collection store rejects the filesystem | Use the Linux recipe; common Windows providers lack `SecureDirectoryStream` |

## Versions and other CI platforms

Every BootUI release updates this guide's generated examples and starter kit before the release is signed.
The documentation website waits until that exact CI release and its CLI signature are available. Your repository's
copied workflow is not silently updated; review future changes to its explicit CLI release and Action pin.

Other platforms can use the same [CLI commands and report contract](ci/reference.md), provided the CLI and application
share one local runner. Their application lifecycle, artifact retention, and failure handling remain the caller's
responsibility. Baselines, SARIF, and PR annotations are not included.
