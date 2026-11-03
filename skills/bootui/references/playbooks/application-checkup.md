# Application checkup

## Goal and entry

Use when the user asks "what should I fix first?" or wants a general application assessment. Triage into one focused
investigation rather than scanning every panel. Load the [shared contract](README.md) before collecting evidence.

## Prerequisites

Confirm the user's goal (otherwise general application health), application/repository identity, catalog, permissions,
and collection budget. Source and representative traffic are needed to turn runtime findings into verified fixes.
Do not assume native-image readiness, a production audit, or dependency upgrades are the goal.

## Investigation

Start with existing evidence and permitted low-impact reads. `get_health` invokes live health indicators, which may
query databases or contact services. Prefer a supplied prior health result; if indicator behavior is unknown or its
external work is outside the authorized scope, obtain approval before calling it. It is not a cached report.

| MCP tool | CLI equivalent | Read for |
| --- | --- | --- |
| `get_overview` | `bootui overview --json` | Application identity and framework context |
| `get_health` | `bootui health --json` | Live component checks, only within scope; distinguish intentionally absent local services |
| `get_runtime_insights` | `bootui insights list --json` | Coverage, checks not run, limitations, observations, and suggested next calls |
| `get_architecture_report` | `bootui architecture report --json` | Existing structure findings |
| `get_spring_report` | `bootui spring report --json` | Spring or Quarkus application advisor, according to the actual stack |
| `get_hibernate_report` | `bootui hibernate report --json` | Existing ORM findings when available |

1. Select relevant cached reports from the catalog; do not call all of them. Inspect report timestamps,
   `evidence.usable`, `evidence.coverageComplete`, limitations, and scan status. Missing reports are not clean reports.
2. Read Runtime Insights coverage before observations. `requests` counts completed HTTP exchanges, not every job or
   message. Follow non-HTTP evidence and eviction caveats. An idle application needs an approved representative workload.
3. If the default observations do not explain the symptom, use query `all`, a route, or a named kind. The default list
   omits kinds including repeated SELECTs; a missing default row is not proof of absence.
4. Rank a few candidates by impact on the goal and confidence, checking source/configuration and dismissed findings.
   An unavailable capability is a coverage gap, not an installation defect to work around.
5. Hand off conditionally, preserving budget, authorization, and baseline:

| Evidence | Follow |
| --- | --- |
| Repeated SQL, transaction symptoms, ORM findings | [Database efficiency](database-efficiency.md) |
| One reproducibly slow route | [Slow endpoint](slow-endpoint.md) |
| Exceptions or an error behind a successful response | [Errors and hidden failures](errors-and-hidden-failures.md) |
| CPU/GC/heap/thread growth unexplained by requests | [JVM performance and memory](jvm-performance-and-memory.md) |

Name any proposed fresh scans and obtain the required approval first. Do not run memory, pentest, vulnerability,
database metadata, or method-probe actions as an implicit "check everything" step.

## Fix and verify

Produce the shared Context/Coverage/Actions/Approval plan. Distinguish confirmed fixes, investigations, and optional
improvements. In review-first mode stop here. Under explicit bounded delegation, execute only covered actions through
the focused guide, then [verify the changes](verify-my-changes.md). A higher score is not acceptance criteria.

## Stop and report

Stop if identity/source is unknown, traffic is insufficient, the budget expires, or a focused branch requires new
permission. Report the highest-impact supported actions and coverage gaps, not "healthy" because cached reports are
empty. An intentionally unavailable Spring-specific feature on Quarkus is not a failed check.
