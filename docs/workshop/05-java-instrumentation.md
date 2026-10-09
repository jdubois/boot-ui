# 05 - Java instrumentation

**Time:** 25 minutes. **Goal:** Inspect executed code and establish an instrumented baseline.

Budget ten minutes for attachment/identity/self-tests, ten for code paths and the eager-order baseline, and five
for a facilitator-led async example. Process and probe exercises below are optional demonstrations or take-home
work, not extra prerequisites for completing the capstone.

## Attach the Java agent explicitly

Stop the sample with Ctrl+C in its terminal. This full JVM stop ends the earlier run and method history.
It also loses cached advisor reports and in-memory journal observations, including the import evidence from
Chapter 03. Keep their worksheet notes labeled **prior-process evidence**. The new process must capture its own
eager-order baseline; do not pretend earlier reports remain available to its tools.
Use a DevTools trigger file so compilation/testing cannot create extra idle runs between the measured baseline and
the final reload. This controls reload timing only; it does not change BootUI recording or security.
On macOS/Linux:

```bash
touch bootui-spring-sample-app/target/classes/.workshop-reload
./bootui-spring-sample-app/run-local-agent.sh \
  '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --spring.devtools.restart.trigger-file=.workshop-reload'
```

On Windows, with the warmed 2.0.0 clone:

```powershell
$agent = (Resolve-Path "bootui-agent/target/bootui-agent-2.0.0.jar" -ErrorAction Stop).Path
if ($agent.Contains(",")) { throw "Use an agent jar path without commas" }
New-Item -ItemType File -Force bootui-spring-sample-app/target/classes/.workshop-reload -ErrorAction Stop | Out-Null
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.m2" "-Dmaven.test.skip=true" `
  -pl bootui-spring-sample-app spring-boot:run "-Dspring-boot.run.profiles=dev" `
  "-Dspring-boot.run.agents=$agent" `
  "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --spring.devtools.restart.trigger-file=.workshop-reload"
```

For an alternate port, append `--server.port=8085` inside the **same** `spring-boot.run.arguments` value, not a second
copy of that Maven option. MCP's runtime toggle may reset; re-enable it if your transport needs
it. Wait for startup/seeding and confirm the application identity again.
The Chapter 01 warm build should already have produced the agent jar; if it is missing, repeat that build before
launching. Do not continue with an empty agent path.

In terminal 2, keep the same `BOOTUI_URL`. If this is a new terminal, set it to your actual port again before running:

```bash
bootui overview --json
bootui agent status --json
```

Check that the overview's application/port matches the browser, then note the new run id in
**Live Activity -> Recording**. Open **Java Agent** for sensor details.

Check individual sensors, installed hooks, self-test outcomes, and limits. `ARMED` alone is insufficient.
For this chapter you need `executors`, `inventory`, `code-paths`, and the relevant side-effect sensors.
The default set includes processes/network/files/blocking/resources too; leave switches unchanged for run comparison.

A rejected sensor or failed hook is a checkpoint failure, not permission to claim it recorded nothing.
Use the documented [Java agent setup](../setup/java-agent.md) and [troubleshooting](appendix-b-troubleshooting.md).

## Read an application code path

Repeat Chapter 04's six sequential calls to both eager-order routes. In **Code Paths**, select the original route.
Find `EagerDemoOrderService#listWithSecondarySelects` and SQL attributed under it.

Compare total time and self time. Instrumented method durations are not sampled CPU time. Trees have depth/row
limits, and public/protected application-bean coverage is not all JVM code. Excluded/unobservable methods are not zero.

In **Code Inventory**, find the service and mark that the target method executed in this run. Find one unexecuted
method and distinguish “not exercised” from “unnecessary code.”

## Follow work after the response (facilitator-led)

```bash
curl -fsS "$BOOTUI_URL/api/insights/orders/after-response"
curl -fsS "$BOOTUI_URL/api/insights/orders/after-response/waits"
```

PowerShell uses `Invoke-RestMethod ... -ErrorAction Stop` for the same URLs. Wait at least one second for the delayed work.
The first route submits raw executor work, answers accepted, and performs SQL after about 200 ms; the control waits
for that future. Open the request/code path and inspect its propagated child/execution and timing.

The `executors` sensor supplies exact propagated ownership for supported tasks. Without it, missing child SQL
does not mean nothing happened. A task that outlives the response is not automatically a bug; decide from intent.
**Live Activity -> Resources** is also useful for work without an owning HTTP request.

## Optional: observe a bounded process side effect

These explicit local demo actions start a Java subprocess and compare it to an in-JVM implementation:

```bash
curl -fsS "$BOOTUI_URL/api/side-effects/java-version"
curl -fsS "$BOOTUI_URL/api/side-effects/runtime-version"
```

In **Side Effects**, inspect the Processes section and originating method. The first route starts `java`; the
second reads the running JVM version. Do not compare version-string formatting as the lesson.
Capture process metadata/exit status, not arguments or environment values.

The workshop deliberately avoids file-writing and external-network demos in the timed core. Metadata-only capture
does not expose file contents, command arguments, or secret values.

## Optional: probe one method

In Code Paths, select the service method and choose **Probe this method**. Review and confirm the single-method
action. Leave argument/return shapes off. Wait until the probe is **active**, then send two original-route requests.

Read its invocation count, timings, and request identities. Stop it, or observe its bounded completion:
**20 invocations**, **60 seconds**, **five live probes maximum**.

Use the actual descriptor selected from Code Paths/Inventory; overloaded method names without descriptors can
be ambiguous. MCP/CLI probes are metadata-only. Starting a probe is a separate action, not covered by read approval.
On reactive methods, assembly-only timing does not measure asynchronous completion.

## Checkpoint

Record installed sensor/self-test status, one method path, and six original/control samples with the target method
executed in the current run. Record the facilitator's async example as demonstrated evidence if you did not run it.
For optional process/probe work, record results or **not performed**, including bounds and coverage limits.
Preserve this JVM and its instrumented baseline for Chapters 06-07.
Do not clear recording, switch sensors, or full-stop it now.

**Previous:** [04 - Runtime Insights](04-runtime-insights.md).
**Next:** [06 - Assessment and impact](06-assessment-and-impact.md).
