# Known limitations of BootUI 2.0

BootUI 2.0 adds exact correlation, the runtime journal, Runtime Insights, the change loop, and the optional BootUI Java
agent ([v2 plan](PLAN-v2.md)). This page lists what 2.0 does not do, or does only in part, so you can tell a gap from a
healthy result. Every gap below is also reported in the product itself: an unavailable panel, check, or column says so
with its reason, and is never shown as empty or zero.

The final scope is recorded in the [release sign-off](V2-VALIDATION-REPORT.md#release-sign-off) of the validation
report. Until 2.0.0 is released, items marked **planned** describe work that is still in progress on the `v2` branch
and **may not be in 2.0**: whatever is not merged when 2.0.0 is cut ships in a later 2.x release.

## Runtime Insights

- **Several kinds are not listed by default.** `route-time-breakdown`, `exception-hotspots`,
  `connections-per-request`, and `ai-usage-by-route` are reached from the panels that show the same evidence;
  `repeated-selects`, `lazy-sql-after-handler`, `split-transaction-writes`, `framework-warnings-by-route`, and
  `anonymous-data-reach` only with **Show all routes**, a search, or an agent query naming them. Every row stays
  reachable, and any row is a fact to verify against the code before acting on it.
- **Agent-backed observations stay in their own panels before 2.0.0**, such as Side Effects, rather than in Runtime
  Insights.
- **Kafka Streams processing is not recorded** on any stack. Producer sends and listener executions are. Runtime
  Insights names this first among its limitations whenever Kafka Streams is on the classpath, as it does R2DBC.
- **Non-JDBC data stores are not recorded** on any stack: Redis, MongoDB, and similar commands do not enter the journal,
  so `repeated-selects` and the runtime model do not cover them.
- **Many narrower observations are deferred** until after 2.0, such as retry amplification, cache effectiveness, pool
  pressure by route, and virtual-thread pinning. See the [v2 plan](PLAN-v2.md), §5.10.

## Spring WebFlux

See [WebFlux design notes](WEBFLUX-SUPPORT.md) for the panel-by-panel detail.

- **R2DBC statements are not recorded.** Only JDBC statements are, so on an R2DBC application SQL Trace stays empty, the
  Runtime Insights checks that read SQL, such as `repeated-selects`, `connections-per-request`, and `safe-method-dml`,
  report `UNAVAILABLE`, and no SQL time appears in `route-time-breakdown`. R2DBC capture is deferred until after 2.0.
- **`route-time-breakdown` has no handler or response phase.** It times authentication and the recorded calls; the
  rest of a request's time is reported as unattributed, never as application code.
- **Transactions are blocking only.** `transaction-across-remote-call` and `split-transaction-writes` read blocking
  transactions; a `ReactiveTransactionManager` is not captured.
- **`lazy-sql-after-handler` does not apply**, and WebSocket frames and the HTTP Sessions panel are unavailable.

## Quarkus

See [Quarkus design notes](QUARKUS-SUPPORT.md) for the panel-by-panel detail.

- **55 of the 65 panels ship.** GraalVM, CRaC, Conditions, Startup Timeline, HTTP Sessions, Spring Data, Spring Security,
  Spring DevTools, and Transactions do not apply to Quarkus; JMS is not available yet.
- **No transaction capture.** SQL statements carry no transaction id, and `transaction-across-remote-call` and
  `split-transaction-writes` are unavailable.
- **Hibernate ORM statements are preparations.** Their durations are unknown, so ORM SQL time is not part of
  `route-time-breakdown`; plain JDBC statements are timed.
- **No cache events and no WebSocket frames.** `quarkus-cache` exposes no access listener.
- **`route-time-breakdown` has no authentication phase**, run-start facts are a live-reload total without steps, and
  application events are recorded on the observer side only.
- **The Java agent runs in dev and test modes only**, never in native mode or in production (`LaunchMode.NORMAL`).

## Spring MVC and Spring WebFlux

- **No application events beside the application's own event multicaster.** BootUI records application events as the
  context's `applicationEventMulticaster`, and backs off when the application defines its own, as Spring Modulith's
  event publication registry does. Then no application event is recorded, `transactional-listener-skipped` and
  `after-commit-writes` report `UNAVAILABLE`, and change impact and run comparison say so.

## Spring MVC

- **Executors that are not beans keep no request link** without the BootUI agent: an executor an `AsyncConfigurer`
  creates without `@Bean`, a `FactoryBean`'s product, a `SimpleAsyncTaskScheduler`'s tasks, and a
  `VirtualThreadTaskExecutor`. The application's executor and scheduler beans, and a pool another executor bean wraps,
  are followed (also on WebFlux), including a task one of a request's tasks hands on to them. A periodic or cron task
  belongs to the request that scheduled it on its first run only.
- **CPU on virtual threads** is not read by the per-request scope readings; use the opt-in JFR attribution (**Profile
  resources**) for it.

## The BootUI Java agent

The agent is optional: without it, every 2.0 feature that does not name it works. With it, BootUI records what the
application's own code did. See [Java Agent](features/java-agent.md).

**Ships in 2.0** (delivered on the `v2` branch):

- the published `bootui-agent` jar, its claim lifecycle across DevTools restarts and Quarkus live reloads, and the
  **Java Agent** panel;
- executor propagation and work still running after the response (`work-after-response`);
- **Code Inventory**: executed and changed methods since the previous run, dependency use, and
  `changed-code-not-executed`;
- **Code Paths**: route trees, component-boundary timing, and the handler split of `route-time-breakdown`;
- change impact by method, and a run comparison led by code changes;
- metadata-only method probes: invocations, durations, outcomes, and request ids, never arguments or return values,
  with optional argument and return shapes, shown in the panel only: types and sizes, plus an enum constant's name and
  a string's length under `FULL`;
- runtime reach in the Vulnerabilities panel;
- the **Side Effects** panel with the default `network` (outbound hosts), `processes`, and `blocking` sensors, and the
  opt-in `files`, `environment`, `thread-activity` (threads per request), and `thread-locals` (`thread-local-left-set`)
  sensors, which the Java Agent and Side Effects panels switch on and off at run time;
- side effects in the run comparison: hosts, file patterns, processes, and variable names new or gone since the
  previous run;
- the Exceptions panel's **Caught in application code** section, with the agent's opt-in `caught-exceptions` sensor;
- `request-input-in-sink` as opt-in Security sinks rows: request input reaching SQL text, a command, a file path, or
  an outbound URL unchanged, with query and path parameters;
- agent guidance in the MCP instructions and prompts, and the scripted "did my change run?" agent investigation.

**Planned, may not be in 2.0:**

- the `resources` sensor: resources a request opened and left open, such as leaked streams (`resource-not-closed`);
- caught exceptions as evidence of `errors-behind-2xx`;
- the rest of security sinks: form values in `request-input-in-sink`, outbound URLs opened through `HttpClient` or
  `URL.openConnection`, deserialization without a filter, weak algorithms, and trust managers;
- side effects in change impact, and methods no longer executed on routes exercised in both runs;
- dynamic access recording.

**Limits of the agent itself:**

- JVM mode only, attached with `-javaagent` or an opt-in self-attach; it is unavailable in a GraalVM native image.
- It appends itself to the bootstrap class path, so class data sharing, AppCDS, and AOT caches stop applying outside
  the boot loader and HotSpot prints a warning. A development tool: never attach it to a production or AOT-cached JVM.

## Overhead budget

These are the targets 2.0 is measured against. The measured values are recorded in the
[release sign-off](V2-VALIDATION-REPORT.md#release-sign-off).

| Path | Budget |
| --- | --- |
| Application thread: capture into the journal | < 2 µs p99 on a reference machine; never blocks |
| Sample-app throughput, journal on versus off | Within 5 % |
| Retained journal rows | At most the smaller of 32 MB and 5 % of the maximum heap; evictions are counted |
| Java agent, default sensors claimed | Sample-app throughput within 10 % of the same scenario without the agent |
| Java agent claim | Retransformation of already-loaded classes within 1 second on Spring PetClinic |

BootUI itself, with or without the journal, is not free: on a worst-case route that answers in about 0.7 ms, the
Spring MVC sample sustains about 83 to 87 % of the throughput it reaches with BootUI off. Slower, realistic requests
dilute this cost. If the journal misses its 5 % target before 2.0.0, it ships disabled by default and the release notes
say so.
