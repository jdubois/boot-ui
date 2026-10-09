# Participant worksheet

Keep this locally. Use synthetic identifiers and reviewed excerpts, not secrets or full request/log dumps.
Blank or unknown values are honest results; mark fallback evidence as reference, not your live run.

## Setup and wiring

| Item | Your evidence |
| --- | --- |
| Release/tag and commit | |
| JDK, OS, port, active profile | |
| App identity and initial run id | |
| Coding-agent client/transport; successful read | |
| Exposure and panel policy | |
| Effective datasource property's source | |
| Cache provider/stats availability | |
| Handler -> service -> repository | |
| Advisor rule id, severity, affected symbol | |
| Advisor report's process/run; historical after Chapter 05 restart | |
| One unavailable integration and its reason | |

## Requests and observations

| Item | Your evidence |
| --- | --- |
| Original/control request ids and run ids | |
| Request SQL counts/groups | |
| Correlation tier and limitation | |
| Import: HTTP result, transaction outcome, ERROR evidence | |
| Journal dropped/evicted/source counters | |
| Runtime observation id/kind/status and sample count | |
| Counterexample and independent evidence | |
| What the Scorecard cannot conclude here | |

## Java instrumentation

| Item | Your evidence |
| --- | --- |
| Instrumented run id | |
| Sensor installation/self-tests/coverage | |
| Service method and owning route | |
| Total/self time and associated SQL | |
| Raw-executor propagation and after-response work | |
| Optional process/control: observed, demonstrated, or not performed | |
| Optional probe: observed, demonstrated, or not performed; bounds | |

## Approval

| Item | Your evidence |
| --- | --- |
| Plan version and action id | |
| Evidence reads and assessment coverage | |
| Runtime impact: reached routes and gaps | |
| Approved files/change and prohibited scope | |
| Exact approval and separate action permissions | |
| Expected red test verified before sending approval | |

## Before and after

| Measure | Before | After |
| --- | --- | --- |
| Run id and comparison baseline identity | | |
| Original-route request id/sample count | | |
| Original-route SELECT count | | |
| Control-route SELECT count | | |
| Original JSON: count/fields/order/equality | | |
| Changed method hash/change indicator | | |
| Changed method executed or not exercised | | |
| Side-effect source coverage | | |
| Acceptance test command/result | | |

Record the known incompatibility with the upstream bad-fixture test. Which live runtime evidence did the acceptance
test **not** establish? What observation states prevented a stronger conclusion?

## Handoff

Write a four-sentence result: what ran, what changed, what supports the result, and what remains unmeasured.
Name one capability you would need on your own request stack.
