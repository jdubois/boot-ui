# BootUI 2.0 validation report

This report checks whether Runtime Insights is worth reading on applications that were not written for BootUI
([v2 plan](PLAN-v2.md) §2.2 and §2.4). It is filled in from an [early-adopter build](V2-EARLY-ADOPTERS.md) and rerun
before 2.0.0. Until then, each section below is a template.

## Release gates

| Measure | Target | Result |
| --- | --- | --- |
| External validity | On the five applications, ≥ 70 % of observations judged actionable or informative by two reviewers, and none misleading | Not run |
| Time to first observation | ≤ 5 minutes from adding the dependency to reading a first observation, with tracing off and no extra property | Not run |
| Agent effectiveness | Ten scripted investigations answered correctly from tool output alone, with fewer tool calls than with 1.x tools; five refusal fixtures where the right answer is not to edit | Not run |

## How to judge an observation

Two reviewers judge every observation the report shows, independently, before comparing. Each judgment is one of:

| Judgment | Meaning |
| --- | --- |
| Actionable | True, and worth changing the code or configuration for |
| Informative | True, and worth knowing, but no change is needed |
| Noise | True, but not worth the reader's time |
| Misleading | False, or true in a way that leads to a wrong change |

An observation counts toward the 70 % when both reviewers judge it actionable or informative. Any observation either
reviewer judges misleading fails the gate until it is fixed or explained, and is listed with its reason. Record the
statuses that are not findings too: an `INSUFFICIENT`, `PARTIAL`, or `NOT_APPLICABLE` check that hid something real is
a finding of this report.

## Applications

| Application | Stack | Version or commit | Traffic used | Observations | Actionable or informative | Misleading |
| --- | --- | --- | --- | --- | --- | --- |
| Spring PetClinic | Spring MVC | | | | | |
| A JHipster sample | Spring MVC | | | | | |
| Quarkus Super Heroes | Quarkus | | | | | |
| A WebFlux sample | Spring WebFlux | | | | | |
| A Kafka application | | | | | | |

For each application, record:

- how BootUI was added, and the minutes until the first observation was read;
- the traffic that produced the run: which tests or flows, and for how long;
- the coverage strip, and every check that did not run with its reason;
- one row per observation:

| Kind | Subject | Status | Reviewer 1 | Reviewer 2 | Notes |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## Agent investigations

Each investigation runs from a clean agent session against a running sample or validation application, with BootUI's
MCP tools or the `bootui` CLI and no other evidence. Record the answer, whether it was correct, and the number of tool
calls, then the same with the 1.x tools as the baseline.

| # | Question | Correct with 2.0 | Tool calls, 2.0 | Tool calls, 1.x |
| --- | --- | --- | --- | --- |
| 1 | Why is the slowest route slow? | | | |
| 2 | Which route repeats a query, and from which call site? | | | |
| 3 | Which `GET` writes to the database? | | | |
| 4 | Which request failed behind a 2xx response? | | | |
| 5 | Which transaction holds a connection across a remote call? | | | |
| 6 | What does this run do that the previous run did not? | | | |
| 7 | Which routes would a change to a given bean affect? | | | |
| 8 | Which data can an anonymous request reach? | | | |
| 9 | Did a change remove a repeated query? | | | |
| 10 | Which scheduled job or listener does the most database work? | | | |

The five refusal fixtures pass when the agent does not edit code:

| Fixture | Right answer | Agent edited | Notes |
| --- | --- | --- | --- |
| H2 in one run, PostgreSQL in the other | `NOT_COMPARABLE` before any delta | | |
| p50 jitter on ten samples, below the noise floor | No change to explain | | |
| A public catalog read | Not a data-reach problem | | |
| An intentional fallback behind a 2xx | Not an error to fix | | |
| CPU of a request served on a virtual thread | Unavailable, not zero | | |

## Findings

List each misleading observation, each check that hid something real, and each failed investigation, with the change
that fixed it or the reason it stays.
