# Verify my changes

## Goal and entry

Use before/after an application edit, or as the verification stage of another playbook. Establish that the intended
code ran and behavior improved without introducing unexplained work. Load the [shared contract](README.md).
This guide does not authorize further fixes by itself.

## Prerequisites

Inspect the actual diff and user-approved intent. Preserve unrelated work. Record a pre-change baseline when possible:
runtime/revision, relevant request/data, finding and target identities, measurements, and exact acceptance assertions.
Include the Overview `run` identity (`runId`, `ordinal`, `startedAt`) when available and record retained comparison run
IDs before the first reload and each verification cycle.
If the change already happened without a baseline, report that limitation; do not invent one.

## Investigation

| MCP tool | CLI equivalent | Read for |
| --- | --- | --- |
| `get_overview` | `bootui overview --json` | Application/start identity and current run, when available |
| `get_runtime_impact` | `bootui insights impact <name> --json` | Routes through a bean/class/repository/table or an agent-observed method |
| `get_agent_status` | `bootui agent status --json` | Whether code-execution and side-effect evidence is available |
| `get_code_inventory` | `bootui code inventory --json` | Changed/added methods and whether this run executed them |
| `get_runtime_insights` | `bootui insights list --query repeated-selects --json` | Repeated SQL omitted from the default list |
| `get_runtime_run_comparison` | `bootui insights compare previous --json` | Retained before/after evidence and comparability |
| `get_side_effects` | `bootui side-effects --json` | Current coverage and attributed process/host/file/variable metadata |

1. Before editing, query impact for the affected name. Resolve `AMBIGUOUS` candidates explicitly. Impact describes
   observed and mapped routes, not a safety guarantee. With the agent, use `Class#method` with parameter types to
   disambiguate overloads, as described in [runtime recipes](../java-agent-and-recipes.md).
2. Run focused tests and permitted reload/restart, confirm new runtime identity matches the expected source change,
   and generate only the authorized traffic reaching affected routes. A unit test in a separate JVM does not exercise
   the application BootUI is observing.
3. Check agent status before Code Inventory. Changed methods need a retained previous run and a supported restart/live
   reload boundary; a full JVM restart can lose the comparison. Do not imply persistent method baselines are guaranteed.
   The inventory diff uses the immediately preceding application run, not an arbitrary older run selected for behavior
   comparison. Match that predecessor to the pre-edit baseline. If an intervening idle/reload run makes them differ,
   derive affected methods from the source diff and query their class/method names explicitly; do not read an empty
   `changed` list as full coverage. Inspect scan status/reason and skipped classes: pending, partial, or failed scans
   limit the claim. An older-run comparison does not provide equivalent changed-code evidence.
4. Read Code Inventory and its `limitations` before latency. Calls before the agent claim are unseen, so
   `NEVER_EXECUTED` may represent a coverage gap, not missing workload. `EXECUTED` establishes execution, not correctness;
   `NOT_TRACKED` is unknown. When evidence supports missing execution, request the missing test/request or follow the
   [verify-then-probe recipe](../java-agent-and-recipes.md#verify-that-a-change-ran-then-probe) only with separate
   approval. Without the Java agent, report method execution as unmeasured and verify only what other evidence supports.
   `EXECUTED` is run-wide: its first hit may be startup or an unrelated request. Check first request/route/timestamp
   against the intended workload. A different first hit does not prove that the target route ran the method later;
   use suitable route/request evidence or a separately approved probe, or report only run-wide execution.
5. Read Runtime Insights query `repeated-selects` and `lazy-sql-after-handler`, then compare with `previous` or the
   specifically recorded retained run ID. The newest run may be idle/listener-only; confirm the selected pair.
6. Read comparability first: `NO_PREVIOUS_RUN`, `INSUFFICIENT`, and `NOT_COMPARABLE` are not passes. Inspect changed-code coverage and new
   statement fingerprints/counts. Explain or fix additional work within the authorized scope.
7. Inspect side-effect changes marked `ADDED` only for sensors whose comparison status is `COMPARED`. A disabled,
   failed, or changed sensor cannot establish that a host/file/process/variable access disappeared.

## Fix and verify

Require agreed functional assertions, intended behavior under the same workload, exercised changed paths where
measurable, and no unexplained regression. For advisor findings compare rule plus affected target, not score alone.
An authorized re-scan is needed to refresh a stale report; a cached report does not verify a fix.

For latency, allocation, or retention claims use the originating playbook's controlled measurement procedure. Never edit
from a noisy latency row. If a new fix is needed, obtain approval unless it is already covered by bounded delegation.

## Stop and report

Report action status and separate what was proved: tests passed, runtime path observed, finding resolved, or performance
criterion met. Missing baseline, retention eviction, absent agent, paused evidence, unexecuted methods, and incompatible
runs limit the claim. A behavior can be partially verified without claiming every changed method ran.
