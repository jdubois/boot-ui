# Slow endpoint

## Goal and entry

Use for a named slow route or retained slow request. Explain the bottleneck before editing.
Load the [shared contract](README.md); do not launch a load test without approval.

## Prerequisites

Confirm route, input/data scale, expected response, reproduction permission, warmup, concurrency, measurement window,
and acceptance statistic/threshold. Use a user-agreed threshold, not a universal latency target.
Identify whether the symptom includes startup, compilation, downstream work, streaming, or asynchronous completion.

## Investigation

| MCP tool | CLI equivalent | Read for |
| --- | --- | --- |
| `get_live_activity` | `bootui activity --query "<route>" --limit 20 --json` | Relevant profileable request IDs and severity |
| `get_request_profile` | `bootui request-profile <id> --json` | SQL, outbound calls, exceptions, and request timing |
| `get_rest_client_traces` | `bootui rest-client traces --limit 20 --json` | Retained outbound request timing; correlate with the chosen request and coverage |
| `get_runtime_insights` | `bootui insights list --query "<route>" --json` | Route observations, limitations, and next calls |
| `get_http_routes` | `bootui http routes --limit 20 --json` | Retained route rankings; not a controlled benchmark |
| `get_agent_status` | `bootui agent status --json` | Whether optional sensors actually work |
| `get_code_paths` | `bootui code paths --query "<route>" --json` | Application methods and their stamped calls, with the Java agent |

1. Pick a current request ID from relevant activity or an observation exemplar. Check retention, profile source,
   truncation, and correlation notes. Do not repeatedly retry an evicted ID.
2. Separate database work, outbound service time, handler work, and missing evidence. Follow repeated SQL through
   [Database efficiency](database-efficiency.md); do not treat downstream time as an application CPU hotspot.
3. Read available Code Paths only after agent/sensor status. Follow
   [the handler-timing recipe](../java-agent-and-recipes.md#find-where-a-slow-routes-handler-time-goes).
   `assemblyOnly` times assembly, not asynchronous/reactive completion. Self time includes stamped calls: do not
   double-count them or assume every uninstrumented call is local computation.
4. If request evidence leaves CPU/allocation/contention unexplained, hand off to
   [JVM performance and memory](jvm-performance-and-memory.md), retaining the same budget.
5. For a precise remaining method question, propose a bounded probe under the existing
   [probe recipe](../java-agent-and-recipes.md#check-whether-a-method-runs-and-how). Each probe requires separate
   approval and must be active before reproduction; an expired or failed probe is inconclusive.

## Fix and verify

Read impact, validate the source mechanism, and propose the smallest change. In review-first mode stop before editing.
For approved execution, run functional tests, reload, and replay the agreed workload. Compare the chosen statistic over
the predefined samples, after equivalent warmup, with identical data/concurrency and comparable dependencies.
Include errors/timeouts, not just successful fast samples.

Use [Verify my changes](verify-my-changes.md) to confirm changed code ran and inspect new SQL/side effects.
A route-ranking improvement or runtime-comparison latency row alone cannot justify a fix or prove a speedup.

## Stop and report

Stop with a scoped limitation if samples are insufficient, the workload or dependencies changed, timing covers only
assembly, or instrumentation is missing. Do not tune timeouts, increase pools, add caches, or suppress slow requests
just to improve a number without validating semantics and the bottleneck.
