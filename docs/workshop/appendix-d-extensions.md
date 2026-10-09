# Appendix D - Extensions

These labs are **outside the three-hour agenda**. Use a separate clone/session where they restart the JVM or change
sensors. Approve one bounded action at a time and keep the core capstone's comparison intact.

## Durable history and baseline files (15 minutes)

Question: What remains after the buffers or process end?

1. Generate three eager-order requests. Save their ids and journal status.
2. In Live Activity choose **Use a database -> Use the existing datasource** and review/confirm table creation.
   This is an explicit database mutation against the synthetic H2 sample.
3. Generate three more requests and wait beyond the default five-second flush interval. Inspect persisted history.
4. Explain why persisted structural rows do not archive raw exception/log messages, principals, bind values, or every
   rich request detail.

The runtime datasource switch is not automatically persisted to configuration. The sample's H2 database is
in-memory, so it does **not** demonstrate survival across full JVM stops. To prove that, use an intentionally prepared
persistent datasource and [documented history settings](../features/overview.md#durable-history), then verify its rows.

A `bootui.runtime-journal.baseline-file` stores a prior **run summary**, not complete history. A same-JVM Code Inventory
method-change comparison is a third mechanism; a baseline file cannot restore it after a process stop.

Checkpoint: distinguish durable rows, run summary, and method-history lifecycle. Do not delete a shared database.

## JFR versus instrumented timing (15 minutes)

Question: Where did sampled CPU/allocation occur?

Open **Runtime Insights -> JFR profile** and review a labeled facilitator capture, or explicitly approve one short bounded recording
using the panel's available settings. Send only a small agreed workload while it records, then stop and inspect.
Do not capture heap dumps or export raw recordings by default.

Compare JFR sampling with Code Paths' method timing. They answer different questions: sampled CPU/allocation is not
total/self instrumented duration, and lack of a sample does not prove a method never ran.

Checkpoint: label recorder/window/workload and one limitation. Follow
[runtime tools](../features/runtime.md) and the JDK's JFR availability.

## Optional sensors and file/network evidence (20 minutes)

Question: Which effect escapes framework-level capture?

Review **Java Agent**'s sensor descriptions. Optional `threads`, `environment`, `thread-activity`, and
`security-sinks` capabilities are not interchangeable and can have extra risk/overhead. Approve one sensor change
only after reading its bounds/self-tests. Changing sensors can make full-run comparisons not comparable.

For an explicit default-network demo:

```bash
curl -fsS "$BOOTUI_URL/api/side-effects/sdk-call"
curl -fsS "$BOOTUI_URL/api/side-effects/rest-call"
```

Both call this same application over loopback. Compare Side Effects Network evidence with **REST Client**;
an SDK socket need not appear in the framework client panel. No external host is required.
In Windows PowerShell use `curl.exe`, check its exit code, or use `Invoke-RestMethod ... -ErrorAction Stop`;
the `curl` alias may refer to a different command.

For file evidence, `/api/side-effects/report` explicitly writes a synthetic report outside the temporary directory;
`/scratch` writes/deletes a temporary file. Read the source first, approve the mutation, inspect returned path
metadata, and remove only the exact file you created. Contents are not captured. Do not use real documents.

Checkpoint: source coverage plus observed process/host/path metadata, not a blanket no-effects claim.

## Services, cache, and advisor breadth (20 minutes)

Question: How do optional integrations change availability?

Use the sample welcome page's documented cache scenario and inspect **Cache** after repeated calls. Distinguish
Caffeine statistics from per-request access capture. Inspect **Metrics**, **Health**, **Loggers**, **Threads**,
**Database Connection Pools**, and a chosen advisor's rule explanation. Any logger mutation or scan needs explicit approval.

If Docker and time are available, use the sample's documented `run-local-all.sh` in a separate session for Redis,
Kafka, and other integrations. First inspect the compose file and services it starts; this is not core workshop
pre-work. Stop only services you started.

Do not point diagnostics at production, launch every advisor, or approve an external vulnerability lookup merely
because a panel exists. A Scorecard score needs its coverage/context.

Checkpoint: one changed availability reason, one captured interaction, and one unmeasured integration.

## Application AI is not coding-agent usage (20 minutes)

Question: What can BootUI observe when the application calls an AI model?

The core dev sample excludes application AI; that does not prevent a coding agent reading BootUI. The optional
`bootui-spring-sample-app/run-local-ai.sh` uses a different profile/model setup. Review its README, provider,
downloads, resource requirements, and data policy before starting it.

With explicit permission and synthetic input, make one bounded chat request. Inspect the application AI
diagnostic evidence and its masks/limits. Do not send employer prompts, real documents, or unreviewed tool output.

Checkpoint: explain the three distinct actors: application model client, external coding agent, and JVM
instrumentation agent.

## Security/logging reasoning challenge (10 minutes)

In the disposable sample, compare anonymous GET `/api/insights/reports/payroll` and
`/api/insights/reports/PAYROLL`. The expected outcomes are 403 and 200: an exact case-sensitive security matcher
protects one spelling while the handler recognizes report names case-insensitively.

Read the security/handler source and captured request/security evidence. This is an intentionally unsafe fixture;
do not copy it or interpret the successful uppercase request as authorized production access.
Propose a scoped matcher/authorization regression test on paper; it is not part of the approved SQL capstone.

**Return:** [Workshop overview](README.md).
