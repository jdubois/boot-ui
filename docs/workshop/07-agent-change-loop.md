# 07 - Agent change loop

**Time:** 35 minutes. **Goal:** Prove one approved change preserved behavior and reduced the original route's SQL.

## 1. Capture the before state (5 minutes)

Keep the Java-agent JVM from Chapter 05 running. Record its run id, method execution flag, and six original/control
request samples. If the baseline is idle or missing, generate Chapter 04's workload now.
First run `bootui overview --json` and `bootui agent status --json` in terminal 2. Confirm the URL/application and
installed sensors still match your worksheet; do not generate traffic against an old/default port.

Save the two JSON responses under the sample's ignored `target` directory:

```bash
curl -fsS "$BOOTUI_URL/api/insights/eager-orders" \
  -o bootui-spring-sample-app/target/workshop-before.json
curl -fsS "$BOOTUI_URL/api/insights/eager-orders/joined" \
  -o bootui-spring-sample-app/target/workshop-control.json
```

PowerShell can use `curl.exe` with those arguments. The routes should return identical 16-summary arrays.
Save a request profile and SQL count from the **original** route, not the joined control.

## 2. Install the acceptance test (5 minutes)

The release-tagged clone includes `workshop/fixtures/WorkshopEagerOrdersTest.java`. Copy it into the sample's test
package:

```bash
cp workshop/fixtures/WorkshopEagerOrdersTest.java \
  bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/
./mvnw -B -ntp -Dmaven.repo.local=.m2 -pl bootui-spring-sample-app \
  -Dtest=WorkshopEagerOrdersTest test
```

PowerShell:

```powershell
Copy-Item workshop/fixtures/WorkshopEagerOrdersTest.java `
  bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/ -ErrorAction Stop
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.m2" -pl bootui-spring-sample-app `
  "-Dtest=WorkshopEagerOrdersTest" test
```

It should fail **only the query-budget assertion** before the fix. The test verifies the original/control responses
are equal, contain 16 summaries with the same fields/order, and each request prepares **one or two** statements.
Two is a small framework-tolerance ceiling; the demonstrated fixture normally moves from 17 to one.

A missing dependency, HTTP error, or wrong data is not the expected red result. Resolve it before editing.
This isolated test starts its own random-port application; it is not evidence that the browser process ran the fix.
Chapter 05's trigger file holds the live restart until you explicitly release it. If your IDE ignores that workflow
and already restarted the application, re-establish six baseline samples and record the new live run id.

## 3. Review the coding agent's patch (5 minutes)

Now send the exact action/version approval prepared in Chapter 06, after confirming the expected red result.
The target is:

`bootui-spring-sample-app/src/main/java/io/github/jdubois/bootui/sample/insights/EagerDemoOrderService.java`

The original method must use the already-present joined finder while retaining its name and output:

```java
@Transactional(readOnly = true)
public List<OrderSummary> listWithSecondarySelects() {
    return orders.findWithCustomerJoin().stream().map(OrderSummary::from).toList();
}
```

Let the agent make the narrow edit. Review both the tracked diff and untracked files:

```bash
git diff -- bootui-spring-sample-app/src/main/java/io/github/jdubois/bootui/sample/insights/EagerDemoOrderService.java
git status --short
```

Expected application changes are only that service and the copied `WorkshopEagerOrdersTest.java`. The consumer
skill directory from setup may also be untracked; it is not part of the approved patch. `git diff` alone does not
show untracked files. Reject all other edits or policy changes.
Manual fallback: make exactly that edit after a partner reviews the scope. The repository-only
`workshop/answers/eager-orders.patch` is a reference solution, not evidence of a live completed exercise.

Do not change the controller, DTO, transaction annotation, entity mapping, or control query.

## 4. Test, compile, and observe reload (8 minutes)

Run the same targeted acceptance test again. It must pass; on PowerShell check `$LASTEXITCODE` before proceeding.
The test compares the two routes in its own process, not your saved live before-response, and does not check that
the transaction annotation or unrelated files stayed unchanged. Those are your diff review and live JSON checks.
Then compile into the classpath used by the existing
DevTools application:

```bash
./mvnw -B -ntp -Dmaven.repo.local=.m2 -pl bootui-spring-sample-app compile &&
  touch bootui-spring-sample-app/target/classes/.workshop-reload
```

On Windows, compile and release the trigger only on success:

```powershell
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.m2" -pl bootui-spring-sample-app compile
if ($LASTEXITCODE -ne 0) { throw "Compile failed; do not reload the live baseline" }
(Get-Item bootui-spring-sample-app/target/classes/.workshop-reload -ErrorAction Stop).LastWriteTime = Get-Date
```

The trigger releases **one** restart after compilation/testing have finished. Observe the live app's DevTools
restart/startup message and changed **run id**.
Wait for seed completion.

**Do not stop/start the JVM, run `clean`, or build a different clone.** Saving source or an IDE HotSwap alone is not
this run-history workflow. A full restart loses same-JVM method history. A journal baseline file cannot restore that
history.

Before sending traffic, inspect **Code Inventory -> changed**:

```bash
bootui code inventory --query changed --json
```

Record the changed method and whether it is still **not exercised**. Automatic browser/other traffic may have
already exercised it; report that honestly rather than pretending you observed an unexecuted interval.

## 5. Exercise and compare (8 minutes)

Now explicitly send six requests to the original and control routes using Chapter 04's loop. Save the new original
response:

```bash
curl -fsS "$BOOTUI_URL/api/insights/eager-orders" \
  -o bootui-spring-sample-app/target/workshop-after.json
cmp bootui-spring-sample-app/target/workshop-before.json \
  bootui-spring-sample-app/target/workshop-after.json
```

The deterministic fixture normally has byte-identical JSON. If object-key formatting differs, compare parsed JSON;
do not accept a changed array order. PowerShell semantic comparison:

```powershell
$before = Get-Content bootui-spring-sample-app/target/workshop-before.json -Raw |
  ConvertFrom-Json | ConvertTo-Json -Depth 10 -Compress
$after = Get-Content bootui-spring-sample-app/target/workshop-after.json -Raw |
  ConvertFrom-Json | ConvertTo-Json -Depth 10 -Compress
if ($before -ne $after) { throw "Order response changed" }
```

Refresh Code Inventory and prove the changed method executed in this live run. Open its Code Path/profile and
count the new SELECTs. The original endpoint should now look like the joined control, normally one SELECT.

In **Runtime Insights -> Compared with the previous run**, inspect **code changes first**, then side effects and
route behavior. The CLI equivalent:

```bash
bootui insights compare --json
```

Several behavior comparisons need at least three route samples in both runs. The CLI comparison intentionally omits
latency. Keep profiles, datasource, and sensors stable; empty/partial/not-compared evidence is not “no effects.”

Confirm the intended query-count reduction, unchanged response, executed changed method, valid comparison source,
and no observed new effect within recorded coverage. The original method name still contains “SecondarySelects”;
that is acceptable for this focused exercise and preserves its identifiable comparison target.

## Sample test expectations

The upstream `EagerDemoOrdersTest` intentionally verifies the bad fixture and its repeated-SELECT observation.
It will no longer pass after your capstone. That is an expected incompatibility in this **exercise clone**, not a
reason to claim the full sample suite passed.

Use the positive workshop acceptance test to prove this approved behavior shift. Record the targeted command and
result and the known fixture incompatibility. If adopting the fix into a real application, update that application's
regression expectations; do not disable failing tests silently.

## Checkpoint (4 minutes)

Complete the before/after worksheet and show a partner:

- The approved diff and passing response/query-budget test.
- Previous/current run ids and a live request to the unchanged route.
- The changed method's executed flag and reduced captured SQL.
- Comparison coverage and one limitation.

An agent's “fixed” message without these four items is incomplete. If you lost the baseline, record that limit and
repeat the exercise in a disposable clone rather than fabricate a comparison.

**Previous:** [06 - Assessment and impact](06-assessment-and-impact.md).
**Next:** [08 - Going further](08-going-further.md).
