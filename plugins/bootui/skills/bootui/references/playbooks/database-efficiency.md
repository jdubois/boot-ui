# Database efficiency

## Goal and entry

Use for repeated SQL, slow database-backed requests, transaction surprises, or a database-focused review.
Load the [shared contract](README.md); the goal is confirmed application fixes, not speculative database tuning.

## Prerequisites

Confirm the relevant datasource, ORM/client, capture state, representative data/request, source, and authorization.
WebFlux R2DBC-only and Quarkus reactive-client-only SQL are not JDBC capture. Mixed clients give partial evidence.
Do not install JDBC or enable statistics just to populate diagnostics. Ask before changing capture or statistics.

## Investigation

| MCP tool | CLI equivalent | Read for |
| --- | --- | --- |
| `get_hibernate_report` | `bootui hibernate report --json` | Cached ORM findings and coverage |
| `get_database_advisor_report` | `bootui db report --json` | Cached database findings and coverage |
| `get_runtime_insights` | `bootui insights list --query all --json` | Transaction observations and SQL findings, including omitted kinds |
| `get_runtime_insight` | `bootui insights show <id> --json` | One observation's evidence and follow-up calls |
| `get_request_profile` | `bootui request-profile <id> --json` | SQL groups, timing, call sites, transaction and correlation context |
| `get_sql_traces` | `bootui sql traces --limit 20 --json` | Bounded retained statements |
| `get_transactions` | `bootui tx list --json` | Retained transaction boundaries on Spring MVC/WebFlux; not advertised on Quarkus |
| `get_hibernate_statistics` | `bootui hibernate statistics --json` | Existing counters, if enabled |

1. Record SQL counts/fingerprints, affected rule/target identities, timestamps, response assertions, and workload.
   A scan summary is not the full report. Follow retained violation pages as described in
   [runtime recipes](../java-agent-and-recipes.md#read-retained-advisor-violations).
2. Use query `all` or the default list for transaction observations; `repeated-selects` alone filters those out.
   For repeats, narrow to query `repeated-selects`, open the observation ID, then its exemplar request ID. Inspect profile source, truncation, and notes;
   approximate time-window correlation is weaker than exact ownership. Check repeated SQL after the handler separately
   with Runtime Insights query `lazy-sql-after-handler`.
3. Locate source from structured locations or call sites. Check fetch strategies, lazy access, loops, projections,
   pagination/cardinality, response serialization, and transaction scope. Repetition alone does not prescribe a fix.
4. For transaction observations, inspect actual source call paths and effective configuration: proxy bypass,
   independent transactions, writes at commit, and listener execution can look different. Never infer absence of work
   solely from the HTTP request counter.
5. If cached findings are stale or insufficient, propose the relevant `hibernate_scan` (`bootui hibernate scan --json`)
   or `database_advisor_scan` (`bootui db scan --json`). A named delegation covers only its named scan:
   `database_advisor_scan` additionally contacts the database for metadata and needs that separate explicit approval;
   this is not a requirement for `hibernate_scan`. Rescan only within the approved scope.
6. If the hypothesis needs MySQL server evidence, follow
   [the MySQL recipe](../java-agent-and-recipes.md#read-mysql-operational-evidence), not arbitrary SQL. Server-wide
   counters include other clients; absent observed index activity never authorizes dropping an index.

## Fix and verify

Validate the mechanism and read runtime impact before editing, as taught by
[Verify my changes](verify-my-changes.md). Consider a focused fetch plan, projection, batching, or transaction fix only
when evidence supports it. Do not mechanically add a fetch join: it may break pagination or multiply result rows.
Do not hide statements or move work outside capture to improve a result.

In review-first mode propose the fix and stop. Under approved execution, add focused assertions, reload the correct
application, and replay the same data/request. Require correct response contents/order/pagination, expected SQL work,
and intended transaction/rollback behavior. Compare stable rule plus target identities; Hibernate/Database finding
diffs are useful only when their `complete` and limitation fields support the comparison.

## Stop and report

Report unresolved/unverified when statements were not captured, a rule failed, retention is partial, or no comparable
baseline exists. Migrations, index operations, dependency upgrades, and unrelated database configuration are outside
the ordinary code-fix scope and need explicit approval.
