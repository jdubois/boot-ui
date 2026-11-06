# CI reference

Start with the [GitHub Actions quick start](../CI.md), or use the
[framework-managed test recipe](framework-tests.md) when the test framework owns the application.
This page explains the policy and failure behavior; it is not an additional setup checklist.

## Policy

Use strict JSON and commit it with your application. Select a small set of advisors whose evidence applies to your
application. Start with Architecture, then add other advisors deliberately.

```json
{
  "schemaVersion": 1,
  "advisors": {
    "architecture": { "required": true, "failOn": "high" }
  }
}
```

`failOn` accepts `critical`, `high`, `medium`, `low`, or `info`. Findings at that severity or higher fail the gate.
A passing assessment can still contain findings below the threshold. At least one advisor must be required.

Every selected available advisor must produce complete, usable evidence. `required: false` is not a way to turn
partial coverage into a pass: it permits a skip only when the catalog confirms the advisor is unavailable or disabled.
Discovery failures, incomplete observations, unknown severities, truncation, suppressed findings, and inconsistent
scan/report data are assessment errors.

::: details Advisor selection and framework differences

The live CI allow-list deliberately excludes Memory because it may trigger a full garbage collection.
Database Advisor uses the policy key `database-advisor`.

Hibernate and Security can report partial coverage on real applications. Database Advisor needs applicable integration
and usable metadata. An advertised scan is not a promise that every application can produce complete CI evidence.

On Quarkus, application-advisor key `spring` is rejected for an external dev-mode target: that evidence cannot
establish effective production configuration. REST API and Security observations can also be incomplete.
Choose advisors from the actual application's capabilities, not from assumed cross-framework parity.

:::

## Action phases and outputs

The Action lives in this repository at `.github/actions/bootui/action.yml`; use
`jdubois/boot-ui/.github/actions/bootui@<reviewed-full-commit-sha>`, not `jdubois/bootui-action@v1`.

| Phase | Purpose |
| --- | --- |
| `prepare-cli` | Download the explicitly pinned stable CLI and verify its signature; export `BOOTUI_CLI_JAR` for later steps |
| `attach` | Record the target's instance/run identity before the workload; no advisor scans |
| `assess` | Check identity, collect diagnostics, apply the policy, write reports and append the GitHub summary |
| `offline` | Validate an existing bundle without contacting an application |

The Action pin and CLI release are independent. The examples pin an immutable source revision and an explicit CLI
release; no `latest` resolution occurs inside a job. After adopting an example, maintain your own workflow pins through
reviewed updates. BootUI's release automation updates its documentation, not other repositories.

| Input | Used by |
| --- | --- |
| `phase`, `bootui-version` | Every phase |
| `assessment-id`, `policy` | Attach, assess, offline |
| `url`, `witness` | Attach and assess |
| `attach-exit-code`, `output-dir` | Assess |
| `input-bundle` | Offline |
| `api-path`, `timeout` | Live phases; defaults `/bootui/api` and 60 seconds |

`report-dir` points to the fresh report directory, including a fallback directory on failure.
`exit-code` preserves the CLI result: `0` PASS, `3` VIOLATION, or `1` NOT_ASSESSED/error. It is unset for `prepare-cli`.
The composite Action returns a failing shell result for both `1` and `3`; do not confuse its shell exit with the
`exit-code` output.

## Reports and failures

An assessment writes `bundle.json`, `summary.md`, and `junit.xml`. The Action adds the Markdown summary to the GitHub
job summary. Artifact upload remains the caller's responsibility. Uploading JUnit XML alone does not create a native
GitHub test-results dashboard.

If attachment failed, pass its original result to assess. Assessment then avoids scans and stale witnesses and writes
a minimal `ATTACH_FAILED` report. CLI setup/report failures use a separate fresh `<output-dir>.action-error` directory,
never overwrite existing evidence, and remain failures. Use the Action's returned `report-dir` when uploading.

The example workflows let the workload continue after attach refusal, but do not use `continue-on-error` on the final
assessment. A failed workload stays a failed job even if the later assessment passes. Assessment and upload use
`!cancelled()`; `always()` is reserved for bounded cleanup of processes owned by the job.
Cancellation or a hard runner timeout can still prevent collection and upload.

::: details Collection limits and sanitized content

Live assessment has a three-minute total budget, a configurable per-request timeout, a 2 MiB response limit,
20,000 retained details, and 1,000 detail pages. The application also enforces its own
`bootui.cli.execution-timeout` (30 seconds by default); configure it if selected scans need longer.

The Markdown summary retains at most 50 rule summaries, with threshold-meeting findings first and an omission count
for additional retained rules. Reports contain rule IDs, severity and counts, not free-form application finding prose.
GitHub log masking is not a substitute for report sanitization. Protect artifact access and retention independently.

:::

## Runtime Insights

Add `"runtimeContext": { "includeInsights": true }` to request a bounded, report-only Runtime Insights snapshot.
Omitting it, or setting it to false, makes no extra request. Runtime observations never change the advisor verdict.

The optional read has a ten-second bound and preserves time for the final target identity check. `PARTIAL`,
`UNAVAILABLE`, `ERROR`, and `NOT_COLLECTED` do not imply zero activity or a passing workload. The report retains aggregate
coverage and at most 20 default-listed observation summaries, not routes, request IDs, application prose, or suggestions.
Workload attribution remains `UNVERIFIED`: the observed process identity and counts do not prove that your tests exercised it.
JUnit output remains advisor-only.

## Offline rechecking

Keep the original assessment ID and exact policy independently of the bundle. Then run:

```bash
java -jar "$BOOTUI_CLI_JAR" ci check \
  --input reports/bundle.json \
  --assessment-id "$BOOTUI_ASSESSMENT_ID" \
  --policy ci/bootui-policy.json
```

A changed policy, wrong identity, malformed bundle, or incomplete evidence fails validation. The policy must match
byte-for-byte, including whitespace. The same exit codes apply. This is evidence consistency checking, not workload
attestation or a baseline comparison.

## Safety and supported environments

Both CLI and app must run on the same runner, using an explicit literal loopback URL. Bind the application itself to
loopback too: the CLI cannot verify the listener's interface. Do not expose BootUI on a deployed application or cross a
Docker service-container network boundary.

Use an opt-in diagnostic dependency/profile, metadata-only exposure, a fresh empty dismissal store, and only the
panels needed by your policy. Keep the dependency out of the production artifact.
Quarkus BootUI endpoints exist only in DEV/TEST, not NORMAL production mode.

Framework collection requires a filesystem provider supporting `SecureDirectoryStream`, including the Linux runner
used in the recipes; common Windows providers fail closed. Keep its evidence root outside cleanable build output and
Quarkus watched paths. Run the final gate only after all producers exit; retain the plan digest outside their writable
root. A missing, filtered, crashed, duplicate, or unfinished expected window must fail.

Secure path handling is not a defense against a hostile shared filesystem. Protect the evidence directory, runner,
job permissions, and artifacts. No plan, witness, or report hash proves workload attribution.

Baselines, SARIF, PR annotations, automatic test-framework hook registration, and automatic app provisioning are not
part of this release.
