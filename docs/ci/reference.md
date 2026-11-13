# CI reference

Start with the [GitHub Actions quick start](../CI.md), or use the
[framework-managed test recipe](framework-tests.md) when the test framework owns the application.
This page explains the policy and failure behavior; it is not an additional setup checklist.

## Policy

The default Action runs every CI-capable local advisor—Architecture, Hibernate, Spring, REST API, Security, and
Database—and fails on HIGH or CRITICAL findings. Architecture is required; the other advisors run when available and
can be skipped explicitly when the target cannot produce complete evidence. You do not need a policy file for this
setup. To select a narrower set or other thresholds, commit a strict JSON policy and pass its path as `policy`:

```json
{
  "schemaVersion": 1,
  "advisors": {
    "architecture": { "required": true, "failOn": "high" },
    "hibernate": { "required": false, "failOn": "high" },
    "spring": { "required": false, "failOn": "high" },
    "rest-api": { "required": false, "failOn": "high" },
    "security": { "required": false, "failOn": "high" },
    "database-advisor": { "required": false, "failOn": "high" }
  }
}
```

`failOn` accepts `critical`, `high`, `medium`, `low`, or `info`. Findings at that severity or higher fail the gate.
A passing assessment can still contain findings below the threshold. At least one advisor must be required.

Every required advisor must produce complete, usable evidence. An optional advisor that is unavailable, unsupported on
the target framework, or cannot produce a complete assessment is retained as an explicit `SKIPPED` entry with a stable
reason. When an optional advisor completes, its findings are gated normally. Required-advisor discovery failures,
incomplete observations, unknown severities, truncation, suppressed findings, and inconsistent scan/report data remain
assessment errors.

The Markdown **Reason** column and JUnit skipped-test message carry the same stable reason code as the JSON report,
such as `UNAVAILABLE`, `EVIDENCE_UNEVALUABLE`, or `REQUEST_FAILED`. A skipped advisor has not been assessed;
its zero finding count is not evidence that it passed.

Overall **PASS** means the configured policy passed, not that every advisor passed. `UNAVAILABLE` means required
diagnostic tools were absent from the catalog. `EVIDENCE_UNEVALUABLE` means tools returned a report, but usable,
complete evidence was not established. These implementation paths can differ for unconfigured integrations.

::: details Clearer skipped-advisor explanations (unreleased)

The human summary explains these paths and reports bounded coverage diagnostics: evidence flags, reported check/subject
counts, and the number of coverage limitations. Counts are explicitly unvalidated observations, not assessed findings.
Raw limitation text, application configuration, and finding prose are excluded. The JSON schema and stable reason codes
are unchanged, so offline checks retain their existing contract. Partial coverage is still not a passed assessment.

:::

::: details Advisor selection and framework differences

The live CI allow-list deliberately excludes Memory because it may trigger a full garbage collection. Vulnerability
queries are excluded because they call an external advisory service, and the GitHub advisor is excluded because it
reads repository security signals with repository credentials. Neither source belongs in the local runtime scan.
The browser's Overall score can average those remote signals with local known-findings scores, but CI does not use that
client-side aggregate: it applies each selected advisor's severity threshold deterministically.
Database Advisor uses the policy key `database-advisor`.

Hibernate and Security can report partial coverage on real applications. Database Advisor needs applicable integration
and usable metadata. An advertised scan is not a promise that every application can produce complete CI evidence.

On Quarkus, application-advisor key `spring` is rejected for an external dev-mode target: that evidence cannot
establish effective production configuration. REST API and Security observations can also be incomplete.
Choose advisors from the actual application's capabilities, not from assumed cross-framework parity.

:::

## Action phases and outputs

The Action entry point is the repository-root `action.yml`; use `jdubois/boot-ui@vX.Y.Z`, replacing `X.Y.Z` with
a published CI-capable release. When upgrading a former `.github/actions/bootui` reference, remove that subpath.
Existing old-subpath SHA pins continue to use their original source; the entry point is absent from new releases.

| Phase | Purpose |
| --- | --- |
| `run` (default) | Start or reuse an app, wait for readiness, attach, execute `test-command`, assess, stop the owned app, publish, and upload |
| `prepare-cli` | Prepare the CLI (signature-verified release by default); export `BOOTUI_CLI_JAR` for later steps |
| `attach` | Record the target's instance/run identity before the workload; no advisor scans |
| `assess` | Check identity, collect diagnostics, apply the policy, write reports and append the GitHub summary |
| `offline` | Validate an existing bundle without contacting an application |

The Action tag selects the released Action code. The CLI version is resolved from the application's BootUI dependency
by default, or can be set explicitly with `bootui-version`; no `latest` resolution occurs inside a job. After adopting an
example, maintain your own workflow pins through reviewed updates. BootUI's release automation updates its documentation,
not other repositories.

For workflows that require SHA pins, use `jdubois/boot-ui@<full-commit-sha>` with the commit peeled from the signed
release tag. GitHub Actions does not verify the tag's signature when executing an Action.

| Input | Used by |
| --- | --- |
| `phase` | Every phase; defaults to `run` |
| `bootui-version` | Every phase; defaults to `auto`, resolving exactly one stable BootUI dependency version from Maven or Gradle; advanced phases may still pin it explicitly |
| `cli-jar` (unreleased) | Every phase; repeat on each Action invocation when using a trusted local CLI jar for source-build testing. Relative to the workspace or absolute; skips release download and signature verification and cannot be combined with an explicit `bootui-version` |
| `test-command` | `run`; defaults to `none`; set it to a finite workload, or to `auto` for Maven `verify`/Gradle `check`; an explicit workload must exercise the running application |
| `app-command` | `run`; defaults to `auto`, selecting the conventional Spring Boot or Quarkus development command; `none` uses an externally managed app |
| `startup-timeout` | `run`; defaults to 120 seconds and is bounded to 1–600 |
| `url`, `policy`, `assessment-id` | Optional in `run`; defaults to loopback port 8080, the built-in policy, and a fresh ID |
| `upload-artifact` | `run`; defaults to `true`, set to `false` to manage uploads yourself |
| `assessment-id`, `policy` | Required by attach, assess, and offline |
| `url`, `witness` | Required by attach and assess |
| `attach-exit-code`, `output-dir` | Assess |
| `input-bundle` | Offline |
| `api-path`, `timeout` | Live phases; defaults `/bootui/api` and 60 seconds |

`report-dir` points to the fresh report directory, including a fallback directory on failure when one can be produced.
An early setup failure can leave it unset.
`exit-code` reports the assessment result: `0` PASS, `3` VIOLATION, or `1` NOT_ASSESSED/error. It is unset for
`prepare-cli`.
The composite Action returns a failing shell result for both `1` and `3`; do not confuse its shell exit with the
`exit-code` output. In `run`, a failed test command or report upload also fails the Action even when the validated
assessment's `exit-code` is `0`; an invalid report makes the assessment result `1`.

## Reports and failures

An assessment writes `bundle.json`, `summary.md`, `junit.xml`, and `sarif.json`. The Action adds the Markdown summary
to the GitHub job summary. The default `run` phase uploads only those bounded reports; the advanced phases leave artifact
upload to the caller. Uploading JUnit XML alone does not create a native GitHub test-results dashboard.

`sarif.json` is a SARIF 2.1.0 projection of the sanitized bundle, so it holds the same facts as the Markdown summary: one
result per retained rule summary, with the rule ID, a level from the severity (`error` for CRITICAL and HIGH, `warning`
for MEDIUM, `note` for LOW and INFO), the advisor, the count, and whether the severity meets the advisor's `failOn`.
Security-advisor rules also carry a GitHub `security-severity`. A skipped optional advisor and each assessment error are
tool execution notifications, and a NOT_ASSESSED report is an unsuccessful run, so an empty result list never reads as a
pass. The bundle retains no source files, routes, or finding text, so results have no physical location; viewers that
require one, such as GitHub code scanning, may not display them as file annotations. A runtime-gate violation of
`request-input-in-sink` names up to five call sites (`Class#method`) as SARIF logical locations, and every runtime-gate
rule carries its CWE and OWASP Top 10 category (`cwe`, `owasp`, and `external/cwe/...` tags). Advisor findings carry no
CWE or OWASP mapping, because advisor reports do not provide one.

If attachment failed, pass its original result to assess. Assessment then avoids scans and stale witnesses and writes
a minimal `ATTACH_FAILED` report. CLI setup/report failures use a separate fresh `<output-dir>.action-error` directory,
never overwrite existing evidence, and remain failures. Use the Action's returned `report-dir` when uploading.

An application-start failure produces `APP_START_FAILED`; CLI setup and start failures do not imply that the target
was clean. The default `run` phase executes the test command even if CLI setup, application start, or attachment fails,
but never scans after a failed attachment. A failed workload stays a failed job even if the later assessment passes.
The Action starts the process from `app-command` in a fresh process group and stops only that group. For
`app-command: none`, the caller remains responsible for starting and stopping the application. Assessment and upload
use `!cancelled()`; bounded cleanup of the owned process uses `always()`.
Cancellation or a hard runner timeout can still prevent collection and upload.

## Maven and Gradle profiles

**For BootUI 2.4.0**, `app-command: auto` activates Maven profile `bootui-ci` when the POM declares BootUI.
It does not activate a profile merely because it is called `dev`. If your BootUI dependency is inside `dev`, use:

```yaml
- name: BootUI diagnostics
  uses: jdubois/boot-ui@v2.4.0
  with:
    bootui-version: '2.4.0'
    app-command: ./mvnw -B -Pdev spring-boot:run
```

Keep the application's BootUI dependency and CLI version aligned. The explicit `bootui-version` also handles a version
property declared inside `dev`, which 2.4.0's version discovery cannot resolve. For Quarkus, replace `spring-boot:run`
with `quarkus:dev`. For a Gradle build using the documented `dev` project property, use
`app-command: ./gradlew --no-daemon bootRun -Pdev` (or `quarkusDev`).

A dedicated Maven `bootui-ci` profile containing the dependency is the predictable CI alternative; it does not replace
your local development profile. Maven `-Pdev` selects build dependencies, not Spring's runtime profile.
The Action enables BootUI and its CLI endpoint through environment settings, but cannot load an absent dependency.

If startup times out, run the exact command locally and check `http://127.0.0.1:8080/bootui/api/cli` (adjust the port/path).
Check dependency inclusion and the API path before increasing the timeout. A live process or a 404 alone does not
establish that BootUI is configured. Raw application logs are intentionally not uploaded.

::: details Automatic profile selection and named Action steps (unreleased)

The next Action release preserves the `bootui-ci` convention and additionally activates one unambiguous root-POM
profile containing the Spring starter or Quarkus extension. Namespace-aware inspection includes selected-profile
properties and dependency management, but dependency management alone, test/plugin dependencies, and comments do not
establish runtime availability. A direct optional dependency still counts.

Existing Maven profile options and activation rules retain the original launch command with a notice; custom parent,
settings, or multi-module models should use an explicit `app-command`. Multiple unconfigured candidate profiles fail
with explicit-command guidance. Inferred development profiles apply only to startup: `test-command: auto` retains
plain `verify`, or `-Pbootui-ci verify` for the established CI convention. Use an explicit workload for other flags.
Gradle behavior is unchanged.

Composite substeps have readable operation names such as **Start application** and **Assess application**.
Expanded raw logs still show the underlying scripts. Readiness failures show process/HTTP status without publishing
response bodies or application logs. These improvements do not change the published `v2.4.0` Action.

:::

::: details GPG owner-trust warning

`Good signature` followed by an owner-trust warning is not a failed verification. The Action checks the signature
against the pinned BootUI release signing fingerprint. GPG separately warns that its temporary keyring has no personal
trust certification for the key's owner. Do not disable signature verification or mark arbitrary keys as trusted.

:::

::: details Collection limits and sanitized content

Live assessment has a three-minute total budget, a configurable per-request timeout, a 2 MiB response limit,
20,000 retained details, and 1,000 detail pages. The application also enforces its own
`bootui.cli.execution-timeout` (30 seconds by default); configure it if selected scans need longer.

The Markdown summary retains at most 50 rule summaries, with threshold-meeting findings first and an omission count
for additional retained rules. Reports contain rule IDs, severity and counts, not free-form application finding prose.
Every retained violation detail must be a JSON string. A malformed value on any page makes the assessment
`NOT_ASSESSED` with `DETAIL_PAGE_INCONSISTENT` when the advisor is required, or skips an optional advisor with that reason.
GitHub log masking is not a substitute for report sanitization. Protect artifact access and retention independently.

:::

## Runtime Insights

Add `"runtimeContext": { "includeInsights": true }` to request a bounded, report-only Runtime Insights snapshot.
Omitting it, or setting it to false, makes no extra request. Runtime observations never change the advisor verdict
unless the policy names them in `failOnObservations` ([runtime gate](#runtime-gate)).

The optional read has a ten-second bound and preserves time for the final target identity check. `PARTIAL`,
`UNAVAILABLE`, `ERROR`, and `NOT_COLLECTED` do not imply zero activity or a passing workload. The report retains aggregate
coverage and at most 20 default-listed observation summaries, not routes, request IDs, application prose, or suggestions.
Workload attribution remains `UNVERIFIED`: the observed process identity and counts do not prove that your tests exercised it.
Without a runtime gate, JUnit output remains advisor-only.

### Runtime gate

To fail the assessment on a runtime security observation, name it in `failOnObservations` (unreleased):

```json
"runtimeContext": {
  "includeInsights": true,
  "failOnObservations": [
    "anonymous-success-on-restricted-route",
    "anonymous-data-reach",
    "request-input-in-sink"
  ]
}
```

| Kind | Source | Needs |
| --- | --- | --- |
| `anonymous-success-on-restricted-route`, `anonymous-data-reach` | [Runtime Insights](../features/runtime-insights.md) | `includeInsights: true`; a workload that sends anonymous requests to restricted routes |
| `request-input-in-sink` | the [BootUI agent](../features/java-agent.md#the-security-sinks-sensor)'s `security-sinks` sensor | the target started with the `-javaagent`, `bootui.agent.sensors` including `security-sinks`, `bootui.agent.security-sinks.request-values=true`, and a workload such as your tests or a DAST scan |

The assessment reads each named kind separately (`anonymous-data-reach` is not in the default listing, and sink rows
come from Side Effects) and judges it:

| Result | When |
| --- | --- |
| `VIOLATION` | The run recorded at least one observation of the kind, or one confirmed request-input sink row (SQL text, command, file path, or outbound URL; the sensor's JDK checks do not count); the assessment fails with exit code `3`. |
| `PASS` | The check ran with complete evidence and recorded nothing. |
| `NOT_ASSESSED` | The source was unavailable; capture was paused or dropped records; the check did not run or ran partly; rows were below their evidence minimum, partial, or left out by the limit; request-value matching was off, held no request value since the target started, skipped checks, or the target is too old to say; or sink matches were still waiting for a second request to confirm them. The assessment reports `RUNTIME_GATE_INCOMPLETE` and exits `1`. |

The anonymous-access checks rely only on requests an authorization decision proved anonymous, so a workload without
anonymous requests to restricted routes leaves them insufficient: not assessed, not passed. The bundle's
`runtimeContext.gate` keeps each kind's result, reason, affected count, and, for a sink violation, up to five
application call sites (`Class#method`), never routes, tables, parameter names, values, or sentences; the summary, a `runtime:<kind>` JUnit test case, and a SARIF result per violation report the same
facts, and offline checks re-validate them against the policy.

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
