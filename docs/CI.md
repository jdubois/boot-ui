# BootUI in CI

BootUI can gate an existing integration workload with fresh runtime diagnostics. The runner must host both the
application and CLI: the live-target workflow accepts only a literal loopback URL and does not cross a container
network boundary. Keep application startup, test execution, and cleanup in the caller's workflow.

The composite Action supports live Spring Boot and external Quarkus development-mode targets, plus offline validation
of a saved bundle. The live workflow requires a published BootUI CLI release that contains `bootui ci attach` and
`bootui ci assess`; if the policy opts in to Runtime Insights, the CLI must also understand `runtimeContext`. Older CLI
releases reject unknown policy keys. Framework-managed test collection is available through the Java `CiCollection`
API and its `bootui ci prepare-contexts` / `check-contexts` commands. Baselines, SARIF, and annotations are not
included.

## Framework-managed test lifecycles

Use framework-managed collection when the test framework owns the application lifetime, including Spring
`@SpringBootTest` and Quarkus `@QuarkusTest`. Prepare the evidence root and its complete expected-window list before
starting tests. Retain the printed plan digest and assessment ID outside the writable evidence root, then call
`CiCollection.begin(...)` once for each expected window and `Window.finish()` before the framework stops its
application. After every producer process has exited, run `ci check-contexts` as the final gate.

Use the Action's `prepare-cli` phase to download and verify the signed CLI once, before preparing the plan. The
`BOOTUI_CLI_JAR` value is exported through `GITHUB_ENV` for following steps:

```yaml
- name: Set up the verified BootUI CLI
  uses: jdubois/boot-ui/.github/actions/bootui@<reviewed-commit-sha>
  with:
    phase: prepare-cli
    bootui-version: '<published-ci-capable-version>'
```

Add the matching, non-shaded CLI artifact as a test-scoped dependency; keep its version identical to
`bootui-version`:

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-cli</artifactId>
  <version>${bootui.version}</version>
  <scope>test</scope>
</dependency>
```

For Spring MVC and WebFlux, pass the actual random port from `@LocalServerPort`; for Quarkus, use the test URL from
`@TestHTTPResource`. `BOOTUI_CI_*` values written to `GITHUB_ENV` are inherited by Maven's forked test JVMs and their
workload processes unless the test runner explicitly replaces the environment.

```bash
evidence="$RUNNER_TEMP/bootui-ci/$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT/contexts"
assessment_id="$GITHUB_RUN_ID:$GITHUB_RUN_ATTEMPT:integration"
mkdir -p "$(dirname "$evidence")"
plan_file="$RUNNER_TEMP/bootui-ci-plan.sha256"
java -jar "$BOOTUI_CLI_JAR" ci prepare-contexts \
  --root "$evidence" \
  --assessment-id "$assessment_id" \
  --policy ci/bootui-policy.json \
  --contexts orders,catalog > "$plan_file"
printf 'BOOTUI_CI_EVIDENCE_ROOT=%s\n' "$evidence" >> "$GITHUB_ENV"
printf 'BOOTUI_CI_ASSESSMENT_ID=%s\n' "$assessment_id" >> "$GITHUB_ENV"
printf 'BOOTUI_CI_PLAN_SHA256=%s\n' "$(cat "$plan_file")" >> "$GITHUB_ENV"
```

`prepare-contexts` creates the evidence root but not its parent, so create the parent first as shown. Keep the
assessment ID and plan digest outside that writable root.

In the test class, add the published `bootui-cli` dependency to the test classpath and bind each ID to the framework's
actual loopback port. The expected IDs must be fixed before the workload; a skipped, filtered, crashed, or retried
window then fails the gate instead of disappearing from the denominator. Disable automatic test retries for these
classes, or assign each planned attempt its own context ID.

```java
CiCollection.Window window = CiCollection.begin(
    Path.of(System.getenv("BOOTUI_CI_EVIDENCE_ROOT")),
    System.getenv("BOOTUI_CI_PLAN_SHA256"),
    System.getenv("BOOTUI_CI_ASSESSMENT_ID"),
    "orders",
    "http://127.0.0.1:" + localPort,
    "/bootui/api",
    60);
try {
    runIntegrationWorkload();
} finally {
    window.finish();
}
```

An ordinary attach refusal returns an unattached window rather than skipping the workload; `finish()` records the
refusal and the later gate fails. Do not ignore exceptions that prevent the evidence root or claim from being safely
accessed. Always finish before framework teardown so the assessment can read the live test application.

Run the check only after all Maven/Surefire/Failsafe or other producer processes have terminated. Guard the step with
`!cancelled()` so it doesn't race a cancelled test process; set a job timeout to bound failures outside the gate's
control:

```yaml
- name: Gate framework collections
  if: ${{ !cancelled() }}
  run: |
    java -jar "$BOOTUI_CLI_JAR" ci check-contexts \
      --root "$BOOTUI_CI_EVIDENCE_ROOT" \
      --assessment-id "$BOOTUI_CI_ASSESSMENT_ID" \
      --plan-sha256 "$BOOTUI_CI_PLAN_SHA256"
```

The gate prints each context's status and, for completed assessments, the directory containing its sanitized
`bundle.json`, `summary.md`, and `junit.xml`:

```text
orders: VIOLATION (reports: /runner/temp/.../slots/<context-sha256>)
catalog: PASS (reports: /runner/temp/.../slots/<context-sha256>)
```

`NOT_ASSESSED` contexts include a stable error code and have no report bundle. Inspect a violation's bundle and summary
at the printed path; no findings are copied into an unbounded aggregate log.

Exit codes are `0` for a complete pass, `3` for complete evidence with a threshold violation, and `1` for missing,
invalid, refused, or incomplete evidence. Errors take precedence over findings. Each root contains one exact policy
snapshot; use separate roots and gates when stacks require different policies. Keep the root outside cleanable build
output and Quarkus watched paths. Collection requires a filesystem provider with `SecureDirectoryStream`; unsupported
providers fail closed (including common Windows providers).

This gate checks evidence consistency and explicit expected-window accounting, not workload attestation. A test can
mislabel a window or control the whole CI job; plan, report, and witness hashes do not prove which requests exercised
the application. Protect the evidence root from concurrent writers: secure path handling does not make a hostile
shared filesystem safe, and the final check should run under a bounded job timeout. Retain the plan digest
independently and protect CI artifacts and job permissions. The final gate detects persisted refusals and incomplete
windows only when it runs after every producer has finished.

## Spring Boot: add BootUI only to the diagnostic build

An application that does not normally use BootUI can add the Spring starter through an opt-in Maven profile:

```xml
<profiles>
  <profile>
    <id>bootui-ci</id>
    <dependencies>
      <dependency>
        <groupId>com.julien-dubois.bootui</groupId>
        <artifactId>bootui-spring-boot-starter</artifactId>
        <version>${bootui.version}</version>
        <scope>runtime</scope>
      </dependency>
    </dependencies>
  </profile>
</profiles>
```

Pin `bootui.version` to a published, compatible release. Use the build profile only for the diagnostic process;
keep the dependency out of the production runtime artifact and verify that exclusion in the production build.
For Gradle, apply the equivalent opt-in `runtimeOnly` dependency only when a dedicated CI property is set.

For Spring, explicitly configure the diagnostic process before startup:

```yaml
bootui:
  enabled: ON
  cli:
    enabled: true
  mcp:
    enabled: false
  expose-values: METADATA_ONLY
```

Also set `BOOTUI_ENABLED=ON` and point `BOOTUI_OVERRIDES_FILE` at a fresh, empty, absolute per-assessment path. Do not
copy developer-local dismissal state. Enable only the panels needed by the selected scans; existing panel enable and
read-only policy still applies.

## Quarkus development-mode target

Quarkus BootUI endpoints are active only in DEV and TEST launch modes. For the Action workflow, run a separate
`quarkus:dev` application process on the same runner as the CLI; do not try to assess an `@QuarkusTest` application
after its test lifecycle has torn down. Add the extension only to an opt-in diagnostic Maven profile:

```xml
<profiles>
  <profile>
    <id>bootui-ci</id>
    <dependencies>
      <dependency>
        <groupId>com.julien-dubois.bootui</groupId>
        <artifactId>bootui-quarkus</artifactId>
        <version>${bootui.version}</version>
      </dependency>
    </dependencies>
  </profile>
</profiles>
```

Pin `bootui.version` to a published, compatible release, activate the profile only for the diagnostic process, and
leave it out of the production artifact. Quarkus has no `BOOTUI_ENABLED` switch: the extension is wired by launch
mode, so never use the CI profile for a `NORMAL` production launch. Bind the HTTP listener to `127.0.0.1` (not
`0.0.0.0`) and use the exact same loopback URL for attach, workload, and assessment. The CLI checks the URL literal;
it cannot verify which interface the application actually listens on.

For example, the caller-owned startup script can run:

```bash
BOOTUI_CLI_ENABLED=true \
BOOTUI_EXPOSE_VALUES=METADATA_ONLY \
BOOTUI_OVERRIDES_FILE="$RUNNER_TEMP/bootui-ci/${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/boot-ui.yml" \
./mvnw -Pbootui-ci quarkus:dev \
  -Dquarkus.http.host=127.0.0.1 \
  -Dquarkus.http.port=18080 \
  -Dbootui.cli.execution-timeout=60s
```

Quarkus reads advisor dismissals from `boot-ui.yml` in the directory containing `bootui.overrides-file`, even though
its Configuration panel is read-only. Point `BOOTUI_OVERRIDES_FILE` at a fresh, empty, absolute path outside the
watched project tree so checkout-local dismissals cannot suppress findings. Keep the CLI enabled; increase
`bootui.cli.execution-timeout` for scans that need longer than its 30-second default.

Start the application before attach, allow Quarkus augmentation and readiness to finish, then leave its sources and
compiled output untouched through assessment. A dev-mode reload can rotate the run identity and causes the assessment
to fail closed. Keep witnesses, reports, and other CI-generated files outside Quarkus's watched project paths.
Quarkus Dev Services may start containers; for example, this repository's Quarkus sample uses PostgreSQL Dev
Services in dev/test and needs Docker or Podman.

Evidence availability is advisor- and application-specific. The Quarkus application advisor still uses policy key
`spring`, but dev-mode evidence cannot establish effective production configuration, so attach rejects that selection
with `UNSUPPORTED_ADVISOR_FOR_FRAMEWORK`.
The REST API and Security advisors can report incomplete observations; the Database advisor is advertised even when no
datasource exists and then reports `DISABLED`. `required: false` does not turn an available but partial or disabled
scan into a skip. Architecture, Hibernate, and Database scans can be complete on a suitable application, but the
strict assessment decides from the actual evidence; incomplete evidence always fails. Select only advisors whose
observations apply to the target, and do not infer a pass from zero findings.

## Attach, run, assess, and publish

Store a strict JSON policy in the application repository. Only selected advisors run; every one must produce complete,
usable evidence. `memory` is deliberately not in the live-target CI allow-list because its scan may trigger a full
garbage collection. The Database advisor's policy key is `database-advisor`.

To include the optional, report-only Runtime Insights snapshot, set `runtimeContext.includeInsights` to `true`. Omit
`runtimeContext` or set it to `false` to make no extra request. The snapshot does not gate the assessment.

For Spring, a policy might include REST API checks and the optional runtime snapshot:

```json
{
  "schemaVersion": 1,
  "advisors": {
    "architecture": { "required": true, "failOn": "high" },
    "rest-api": { "required": true, "failOn": "high" }
  },
  "runtimeContext": { "includeInsights": true }
}
```

For a Quarkus application with Hibernate and a configured datasource, one possible policy is:

```json
{
  "schemaVersion": 1,
  "advisors": {
    "architecture": { "required": true, "failOn": "critical" },
    "hibernate": { "required": true, "failOn": "critical" },
    "database-advisor": { "required": true, "failOn": "critical" }
  }
}
```

Choose advisors and thresholds for the target; an optional advisor is still assessed when available, and incomplete
evidence fails rather than becoming a skip.

Use the composite Action in two phases. Pin the Action to a reviewed immutable commit SHA, pin the CLI to a published
stable version that includes the `ci` commands, and install Java before invoking it. Replace the sample's
`<reviewed-commit-sha>` with the full 40-character commit you reviewed.

The Action is implemented in this repository at `.github/actions/bootui/action.yml`; its consumer reference therefore
uses `jdubois/boot-ui/.github/actions/bootui@<commit-sha>`. The shorthand `jdubois/bootui-action@v1` would require a
separate repository and is not the selected layout. The Action commit and `bootui-version` input are independent
versions. The input is deliberately explicit: it is not resolved to the latest BootUI release at runtime. Use
Dependabot or Renovate to propose updates to both pins as pull requests, then review and merge them in the consumer
repository. BootUI releases cannot silently update another repository's workflow.

```yaml
name: Integration diagnostics
on: [push, pull_request]
permissions:
  contents: read
jobs:
  integration:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    env:
      BOOTUI_URL: http://127.0.0.1:18080
      BOOTUI_ASSESSMENT_ID: ${{ github.run_id }}:${{ github.run_attempt }}:orders
      BOOTUI_WITNESS: ${{ runner.temp }}/bootui-ci/witness.json
      BOOTUI_REPORTS: ${{ runner.temp }}/bootui-ci/assessment
      BOOTUI_ENABLED: ON
      BOOTUI_CLI_ENABLED: true
      BOOTUI_MCP_ENABLED: false
      BOOTUI_EXPOSE_VALUES: METADATA_ONLY
      BOOTUI_OVERRIDES_FILE: ${{ runner.temp }}/bootui-ci/${{ github.run_id }}-${{ github.run_attempt }}/boot-ui.yml
    steps:
      - uses: actions/checkout@<reviewed-commit-sha>
      - uses: actions/setup-java@<reviewed-commit-sha>
        with:
          distribution: temurin
          java-version: '21'
      - name: Start the diagnostic application
        id: app
        run: ./ci/start-app.sh "$BOOTUI_URL"
      - name: Attach before integration tests
        id: attach
        if: ${{ !cancelled() && steps.app.outcome == 'success' }}
        continue-on-error: true
        uses: jdubois/boot-ui/.github/actions/bootui@<reviewed-commit-sha>
        with:
          phase: attach
          bootui-version: '<published-ci-capable-version>'
          url: ${{ env.BOOTUI_URL }}
          assessment-id: ${{ env.BOOTUI_ASSESSMENT_ID }}
          policy: ci/bootui-policy.json
          witness: ${{ env.BOOTUI_WITNESS }}
      - name: Run existing integration scenarios
        if: ${{ !cancelled() && steps.app.outcome == 'success' }}
        run: ./ci/run-integration-tests.sh "$BOOTUI_URL"
      - name: Assess while the application is still running
        id: assess
        if: ${{ !cancelled() }}
        uses: jdubois/boot-ui/.github/actions/bootui@<reviewed-commit-sha>
        with:
          phase: assess
          bootui-version: '<published-ci-capable-version>'
          url: ${{ env.BOOTUI_URL }}
          assessment-id: ${{ env.BOOTUI_ASSESSMENT_ID }}
          policy: ci/bootui-policy.json
          witness: ${{ env.BOOTUI_WITNESS }}
          output-dir: ${{ env.BOOTUI_REPORTS }}
          attach-exit-code: ${{ steps.attach.outputs.exit-code }}
      - name: Require complete assessment reports
        if: ${{ !cancelled() }}
        env:
          REPORT_DIR: ${{ steps.assess.outputs.report-dir }}
        run: |
          test -n "$REPORT_DIR"
          test -f "$REPORT_DIR/bundle.json"
          test -f "$REPORT_DIR/summary.md"
          test -f "$REPORT_DIR/junit.xml"
      - name: Upload bounded assessment reports
        if: ${{ !cancelled() && steps.assess.outputs.report-dir != '' }}
        uses: actions/upload-artifact@<reviewed-commit-sha>
        with:
          name: bootui-assessment
          path: ${{ steps.assess.outputs.report-dir }}/
          if-no-files-found: error
          retention-days: 7
      - name: Stop the application owned by this job
        if: ${{ always() }}
        run: ./ci/stop-app.sh
```

Both Spring and Quarkus startup scripts must own the process they start, use the same loopback URL for attach,
workload, and assessment, and wait for readiness before attach. For Spring, `start-app.sh` must use the `bootui-ci`
build profile and create a fresh empty overrides file and its cleanup path before startup. For Quarkus, use the
`quarkus:dev` process and an isolated `BOOTUI_OVERRIDES_FILE` as described above. The action's attach phase records a
run/instance witness without scans; assess checks it before and after collection. This detects a restarted or replaced
target, but does not prove that the integration scenarios exercised it. The application's test harness remains
responsible for target/workload correlation.

The workload runs even if attach fails. The attach step prints its stable failure code to the log. Pass the attach
action's `exit-code` to assess: anything other than `0` prevents scans and produces a minimal failure report without
using a possibly stale witness. If CLI setup or report
creation fails, the action writes a separate fresh `<output-dir>.action-error` directory and exposes its location as
`report-dir`; it never overwrites existing output. If even that report cannot be written, the required-report step
fails. Do not reuse witness, output, or fallback paths between attempts.

An optional advisor is skipped only when unavailable or disabled; if it is available, incomplete evidence still
fails the assessment. Hibernate and Security commonly report partial coverage on real applications, so select them
only when the application can produce complete evidence. Never interpret a partial scan as a clean result.
Database Advisor can report informational skips for inapplicable dialects or genuinely absent optional integrations;
failed discovery and incomplete metadata remain errors rather than neutral skips.

The client uses a three-minute total assessment budget, a per-request timeout (60 seconds by default), a 2 MiB response
limit, and limits of 20,000 retained details and 1,000 detail pages. The application's independent
`bootui.cli.execution-timeout` defaults to 30 seconds; configure it explicitly if a selected scan needs longer.

The assessment writes bounded `bundle.json`, `summary.md`, and `junit.xml`. The Action adds the sanitized summary to
the GitHub step summary and returns a failing action result for either an unevaluable assessment or a threshold
violation. The summary shows at most 50 retained rule summaries, with threshold-meeting findings first, their severity
and count, and an omission count when more are in the bundle. It also names stable assessment error codes; it never
includes free-form finding text. The caller uploads reports before the final job result is applied. Test failures remain
failures. Use `!cancelled()` for assessment and publication; `always()` is reserved for cleanup of the process owned
by the job. A cancelled runner or hard timeout can still prevent collection and upload.

When requested, Runtime Insights makes one read-only call bounded to 10 seconds and reserves time for the final target
identity check. If the remaining budget is no greater than the configured `--timeout` plus one second, the call is
skipped and its report-only status is `ERROR` so the final identity check retains its full timeout. The bundle and step
summary include aggregate journal coverage and at most 20 default-listed observation summaries (kind, status, and counts),
but no routes, request IDs, application prose, or follow-up suggestions. `PARTIAL` marks
low-footprint mode; `UNAVAILABLE`, `ERROR`, and `NOT_COLLECTED` remain report-only and do not change the advisor
verdict or exit code. The summary shows captured counts only for `CAPTURED` or `PARTIAL`; other statuses do not imply
zero activity. Omitted or false `includeInsights` is recorded as `NOT_REQUESTED`. This bounded projection is incomplete:
its HTTP exchange count does not prove that the CI workload exercised the witnessed process.
`junit.xml` remains advisor-only.

The bundle omits free-form finding prose and raw runtime data. Artifact access, retention, and report review remain the
workflow owner's responsibility; do not treat GitHub log masking as report redaction.
