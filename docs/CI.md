# BootUI in CI

BootUI can gate an existing integration workload with fresh runtime diagnostics. The runner must host both the
application and CLI: the live-target workflow accepts only a literal loopback URL and does not cross a container
network boundary. Keep application startup, test execution, and cleanup in the caller's workflow.

The current consumer path is a Spring Boot live target. The workflow below requires a published BootUI CLI release
that contains `bootui ci attach` and `bootui ci assess`; do not use an older CLI release. Quarkus test-lifecycle
collection, offline bundle evaluation, baselines, SARIF, and annotations are not included.

## Add BootUI only to the diagnostic build

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

Explicitly configure the diagnostic process before startup:

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

## Attach, run, assess, and publish

Store a strict JSON policy in the application repository. Only selected advisors run; every one must produce complete,
usable evidence. `memory` is deliberately not in the live-target CI allow-list because its scan may trigger a full
garbage collection. The Database advisor's policy key is `database-advisor`.

```json
{
  "schemaVersion": 1,
  "advisors": {
    "architecture": { "required": true, "failOn": "high" },
    "rest-api": { "required": true, "failOn": "high" }
  }
}
```

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
      BOOTUI_OVERRIDES_FILE: ${{ runner.temp }}/bootui-ci/boot-ui.yml
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

`start-app.sh` must use the `bootui-ci` build profile, create a fresh empty overrides file and its cleanup path before
startup, own the process it starts, use the same loopback URL, and wait for BootUI readiness. The workload and
assessment must use that exact URL. The action's attach
phase records a run/instance witness without scans; assess checks it before and after collection. This detects a
restarted or replaced target, but does not prove that the integration scenarios exercised it. The application's
test harness remains responsible for target/workload correlation.

The workload runs even if attach fails. Pass the attach action's `exit-code` to assess: anything other than `0`
prevents scans and produces a minimal failure report without using a possibly stale witness. If CLI setup or report
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
violation. The caller uploads reports before the final job result is applied. Test failures remain failures. Use
`!cancelled()` for assessment and publication; `always()` is reserved for cleanup of the process owned by the job.
A cancelled runner or hard timeout can still prevent collection and upload.

The bundle omits free-form finding prose and raw runtime data by default, but artifact access, retention, and report
review remain the workflow owner's responsibility. Do not treat GitHub log masking as report redaction.
