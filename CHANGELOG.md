# Changelog

All notable changes to BootUI are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Change impact has its own Runtime Insights tab.** It opens on its search field instead of sitting below the run
  comparison, offers the methods changed since the previous run, and each changed method in **Changes** links to it
  with **See its impact**; `?impact=<symbol>` opens it, and `?tab=` opens any tab.
- **MCP 2026-07-28 beside MCP 2025-06-18.** The MCP endpoint also serves modern clients, with `server/discover`, result
  envelopes, and header checks, while existing clients answer as before ([#1340](https://github.com/jdubois/boot-ui/issues/1340)).
- **Live MCP progress for long scans.** A modern MCP client that asks for progress sees `architecture_scan` and
  `vulnerabilities_scan` phases as they happen, and closing the call stops the scan ([AI agents](docs/AI-AGENTS.md#protocol-eras)).
- **MCP cancellations counted.** The MCP Server panel shows the protocol versions served and counts calls a client
  cancelled by closing their stream apart from timeouts ([#1340](https://github.com/jdubois/boot-ui/issues/1340)).
- **MCP progress and cancellation for 2025-06-18 clients.** A legacy client that sends a progress token, such as GitHub
  Copilot CLI, sees the same scan progress, and `notifications/cancelled` stops the call; a late cancellation can reach
  another local client's call with the same id ([known limitations](docs/KNOWN-LIMITATIONS.md#mcp)).

- **Hibernate Statistics and WebSockets for agents.** `get_hibernate_statistics` (`bootui hibernate statistics`) and
  `get_websockets` (`bootui websockets`) are passive reads on Spring MVC, Spring WebFlux, and Quarkus; enabling
  statistics and the capture switch stay in the panels ([AI agents](docs/AI-AGENTS.md#tools-the-agent-can-call)).
- **MCP tool hints and per-tool argument schemas.** `tools/list` carries `annotations` (`readOnlyHint`,
  `destructiveHint`, `idempotentHint`, `openWorldHint`) derived from each tool's kind, and each argument says what it
  means for that tool, with an example and the `default` page size a call gets.

- **Runtime Insights buttons in the Spring sample.** The welcome page generates each main finding with one click, or all
  at once, and links to it in the panel ([sample README](bootui-spring-sample-app/README.md#runtime-insights-demo)).
- **Caught in application code (M5-6a2).** With the agent's `caught-exceptions` sensor, the Exceptions panel shows what
  became of each exception application code caught; a finding states it was not seen rethrown or logged at `WARN` or
  above, and incomplete evidence is shown as unknown with its reason, never as swallowed
  (`GET /exceptions/caught`, `caughtInCode` in `get_exceptions`). ([PLAN-v2 M5-6](docs/PLAN-v2.md))

- **Opt-in agent sensors switched at run time.** The Java Agent and Side Effects panels switch `threads`, `files`, and
  `environment` on or off without a restart, until the JVM ends ([Java Agent](docs/features/java-agent.md#switching-opt-in-sensors-at-run-time), [#1290](https://github.com/jdubois/boot-ui/pull/1290)).
- **The security-sinks sensor switched at run time.** The Java Agent and Side Effects panels switch `security-sinks`
  on or off like the other opt-in sensors: its JDK checks and its request-value matching together, which still needs
  `bootui.agent.security-sinks.request-values=true` at startup. A switch of `files`, `environment`, or `security-sinks`
  pauses the other Side Effects sensors while the agent reinstalls their hooks, so Runtime Insights no longer compares
  that run for them ([Java Agent](docs/features/java-agent.md#switching-opt-in-sensors-at-run-time)).
- **Side effects in the run comparison (M5-7b).** With the BootUI agent, Runtime Insights' comparison lists the hosts,
  files, processes, and variable names a route, job, or startup newly uses or no longer uses, for sensors that recorded
  both runs whole ([Runtime Insights](docs/features/overview.md#runtime-insights)).
- **Agent guidance for the BootUI agent (M5-10a).** MCP instructions check `get_agent_status` first and read an
  agent-gated `NOT_APPLICABLE` as not measured; `verify_after_change` and the skill add verify-then-probe.
- **Request input reaching a sink (M5-6b1).** Opt-in Security sinks rows show a request parameter reaching SQL, a
  command, a file path, or a URL unchanged, its value redacted. ([#1296](https://github.com/jdubois/boot-ui/pull/1296))
- **Security sinks JDK checks (M5-6b2).** The opt-in `security-sinks` sensor also shows deserialization without a
  filter, weak digests and ciphers, and application trust managers, as facts.
- **Caught exceptions, recorded by the BootUI agent (M5-6a, first part).** The agent's new opt-in `caught-exceptions`
  sensor (`bootui.agent.sensors=...,caught-exceptions`) reports each exception application code catches, at a handler
  that names a type, and which of them are thrown again: by the method itself, by a library helper it calls, or wrapped
  in another exception. Each becomes an event of the runtime journal's new `agent.caught-exceptions` source, owned by
  the Exceptions panel, with the handler's class, method, line, and declared types, the exception's class, and its
  request or execution, never its message. The visit inserts straight-line calls at handler entries and one rethrowing
  catch-any handler per method, computing no frames and loading no class inside the transformer; the Java Agent panel
  shows the sensor's two hooks and self-test. A stress test defines every class of Spring Framework, Hibernate ORM,
  Jackson, Netty, Vert.x, Quarkus, and Kotlin's standard library and coroutines with and without the visit on JDK 17,
  21, and 26. The Exceptions panel's
  **Caught in application code** section, `exceptions-caught-in-code` rows, and the decision on its default follow.
  ([PLAN-v2 M5-6](docs/PLAN-v2.md))

- **Blind spots from the first validation run (M4-22).** On Spring MVC and Spring WebFlux, the application's own
  `ThreadPoolTaskExecutor`, `ThreadPoolTaskScheduler`, and `SimpleAsyncTaskExecutor` beans, including a pool another
  executor bean wraps, like JHipster's `AsyncConfigurer` executor, now run a request's `@Async` tasks as executions of
  that request: BootUI's task decorator is set where there is none and composed inside the application's where there is
  one, never replacing it. `errors-behind-2xx` reports a 2xx whose own task failed (an exception, an `ERROR` log, a
  `WARN` log carrying an exception, or a task the BootUI agent saw fail after the response, not one the request joined),
  such as a swallowed activation-email failure behind a `201`. A periodic or cron task scheduled during a request
  belongs to it on its first run only, and a task is propagated once even under Spring Boot's composite of task
  decorators. Runtime Insights names first among its limitations what the journal cannot record: R2DBC statements on
  Spring (also stated up front in the WebFlux documentation), and Kafka Streams processing when Kafka Streams is on the
  classpath, on Spring MVC, Spring WebFlux, and Quarkus. No new observation kind ([Runtime
  Insights](docs/features/overview.md#runtime-insights), PLAN-v2 M4-22).
- **Known limitations of 2.0.** A [Known limitations](docs/KNOWN-LIMITATIONS.md) page lists what 2.0 does not do, per
  stack (R2DBC statements not recorded on WebFlux, no transaction capture on Quarkus, and the panels each stack lacks),
  the BootUI Java agent's shipped and still-planned scope, and the overhead budget. The validation report gains a
  [release sign-off](docs/V2-VALIDATION-REPORT.md#release-sign-off) template recording each success measure against
  its target, the per-kind gates, and every exception (PLAN-v2 M4-23).
- **One help call instead of many, and answers that name the next call.** `bootui --help` lists every command with
  its arguments, what it returns, where its `<id>` comes from, the words its `--query` understands, and one example,
  and `bootui <group> --help` lists a group the same way; every example is generated from the MCP tool registry and run
  by a test. Every Runtime Insights answer (`get_runtime_insights`, `get_runtime_insight`, `get_runtime_impact`,
  `get_runtime_run_comparison`, and their `bootui insights` commands) gains `next`: at most three follow-up calls, each
  with its `command`, MCP `tool` and `arguments`, and `why`, naming only tools the application advertises, on Spring
  MVC, Spring WebFlux, and Quarkus. An unknown observation id names the list, an unknown run id the runs still kept, and
  an ambiguous or unknown impact symbol the candidates or the beans and mappings to search. A tool called without its
  required `id` now says where the id comes from, such as `Missing required argument: id (an observation id from
  get_runtime_insights)`; the error code is unchanged. The how-to-ask hints in `get_runtime_insights` limitations
  moved to `next` ([Command line](docs/CLI.md#discovering-what-an-application-exposes),
  [AI agents](docs/AI-AGENTS.md#runtime-insights-for-agents), PLAN-v2 M4-21).
- **Change impact by method and a run comparison led by code changes.** With the BootUI agent, change impact accepts
  any application method, as `OrderService#total`, `OrderService.total(long)`, or a JVM descriptor for one overload,
  and its observed routes are those whose requests' own call trees ran it, read from each route's tree at any depth
  (first requests, executor work the agent followed, and late fragments included) or Code Inventory's first request,
  never composed from calls observed across requests; each says how many of its requests ran it. Routes reaching the
  method's bean that ran without their trees showing it are listed apart, **ran without showing it**, with why that
  proves nothing, and are called not exercised only when Code Inventory saw the method never run. The run comparison
  leads with **Code changes**: the methods changed or added since the previous run, not run yet first, with whether
  each ran and on which routes, and the removed methods counted. New `observedFrom`, `methods`, `methodStatus`,
  `notObserved`, and `notObservedTotal` impact fields, `executedRequests` and `partial` per route, and `codeChanges`
  in the comparison, on Spring MVC, Spring WebFlux, and Quarkus; `get_runtime_impact` takes the method form,
  `get_runtime_run_comparison` leads with `codeChanges`, and the `verify_after_change` prompt checks each changed
  method's impact. Without the agent, only handler methods are checked and the comparison is unchanged
  ([Runtime Insights](docs/features/overview.md#runtime-insights), PLAN-v2 §5.7, §5.8, §5.17, M5-7a).
- **Runtime reach in the Vulnerabilities panel.** With the BootUI agent's `inventory` sensor, the Vulnerabilities panel
  gains a **Runtime reach** column and filter: per dependency, whether its jar's classes loaded in this JVM (with how
  many in this run and the first route), whether a class its advisory names loaded (**Named class loaded**), **Not
  loaded yet**, or **Unknown** with why, never not loaded when the evidence cannot show it. Advisories are normalized to
  the classes or methods they name (`advisorySymbols`, from OSV's structured fields, else their text). Reach is an
  additive `runtimeReach` field of `GET {api}/vulnerabilities`, the scan, `get_vulnerabilities_report`, and
  `vulnerabilities_scan` on Spring MVC, Spring WebFlux, and Quarkus, read when answered and only while Code Inventory
  is enabled; it never changes a severity, score, count, or Scorecard penalty. The agent keeps bounded class-name
  evidence per jar for it ([Runtime reach](docs/features/advisors.md#runtime-reach), PLAN-v2 §5.15, M5-9a).
- **Method probes.** With the BootUI agent attached, **Probe this method** on a method selected in a Code Paths tree,
  or **Probe in Code Paths** on a changed method in Code Inventory, records that one method's next 20 invocations, for
  at most 60 seconds, five probes at once: each invocation's duration, thread kind, request id, outcome or exception
  type, and calling frame, never an argument or a return value. The agent retransforms only the current run's copy of
  the class, enforces the bounds in the probe's own advice, ends a probe with its run, and removes it by retransformation
  while the inventory and code-paths advice stay. Probes are Code Paths' only actions, so the panel is now action-capable
  and `bootui.panels.code-paths.read-only` (or `bootui.read-only`) refuses them; `GET`/`POST
  /bootui/api/code-paths/probes`, `GET`/`DELETE /code-paths/probes/{id}`, and `POST /code-paths/probes/{id}/stop` on
  Spring MVC, WebFlux, and Quarkus, and the `start_method_probe` (`bootui probe start`, an action needing the user's
  separate approval, named in `assess_application`) and `get_method_probe` (`bootui probe show`) agent tools.
  Probes and their invocations are a store of the agent evidence contract, cleared by **Clear recording**
  ([Method probes](docs/features/java-agent.md#method-probes), PLAN-v2 §5.14, M5-8, D24, D37).
- **Argument and return shapes for method probes.** A probe can also record argument and return types, nullness, and
  sizes, never values, shown in the panel only ([shapes](docs/features/java-agent.md#argument-and-return-shapes), D44).
- **The agent evidence contract (M5-11).** Code Paths' request and route trees, Code Inventory's first calls, and Side
  Effects rows, which the BootUI agent's evidence keeps outside the runtime journal, now follow one engine projection on
  Spring MVC, Spring WebFlux, and Quarkus: every read resolves once whether its own panel and HTTP Exchanges are visible,
  so a disabled Code Paths, Code Inventory, or Side Effects panel hides its evidence from its reads, MCP tool, CLI
  command, Beans at runtime, the runtime model, and the Runtime Insights observations that read it, with the reason;
  **Clear recording** and **Free BootUI memory** clear it with the journal, the records still queued in the agent's ring
  included, leaving a request that lost a fragment out of Code Paths whole and dropping Side Effects records whose first
  occurrence came before the clear by a watermark; and Live Activity's journal status reports its estimated bytes as
  **Agent evidence**, against `bootui.runtime-journal.agent-evidence-max-bytes` (about 68 MB by default, including about
  5.3 MB for Side Effects rows; a smaller value shrinks Code Paths' trees and Side Effects rows and waiting records in
  proportion). Code Inventory keeps which methods executed through a clear, and says when the recording was cleared
  (`recordingClearedAt`). Code Inventory's first calls are kept in primitive slots per method id, bounded by the agent's
  method limit.

- **Code Paths: calls under methods, Beans at runtime, and the issuing method.** With the BootUI agent's `code-paths`
  sensor, the SQL, REST client, cache, and AI recorders stamp each call, on the thread that issued it, with the
  instrumented method innermost there, so Code Paths shows each method's statements and calls per request under it
  (a statement Hibernate flushes at commit runs after the `@Transactional` method returned, so it shows under the
  method that called it), and work an executor ran under the method that submitted it; calls issued while no
  instrumented method was open, as in a filter or while the response is written, are counted apart from calls recorded
  on another thread. A new **Beans at runtime** tab lists the calls observed between beans, first requests included,
  beside the dependencies they declare, with a filter for declared dependencies not called in this run, said only when
  both beans' classes are instrumented and none of their methods was excluded, else *not observable*; and the runtime
  model gains observed `INVOKES` edges, which change impact never walks. Runtime Insights' `repeated-selects` names the
  method that issued the repeats, past an application repository or DAO method to the method that called it, and
  `route-time-breakdown` splits the handler by each method's own time, its self time minus its stamped calls, unless
  calls without a stamp take a tenth of the handler or none is stamped. `GET {api}/code-paths/beans` on Spring MVC,
  Spring WebFlux, and Quarkus; WebClient calls are stamped where they are subscribed, while calls recorded on another
  thread, as streaming AI calls, carry no stamp. A
  method entered in another request phase under the same caller is now another node, so response-write time is never
  split as handler time; overloads and same-named classes keep their own rows in the handler split, and no part of the
  handler is lost between them; and a slow reactive response's tree, settled before its response completed, now joins
  its route once the response is recorded ([Code Paths](docs/features/java-agent.md#code-paths), PLAN-v2 §5.14,
  M5-4c).
- **Code Paths panel, API, and tools.** With the BootUI agent's `code-paths` sensor, the new view-only Code Paths panel
  (Diagnostics) ranks routes by their warm median and shows each route's call tree of application bean methods, merged
  across its warm requests with the first recorded request kept apart: calls per request, total and self time, share of
  the handler, and approximate (≈) percentiles per method from a compact log2 histogram, asynchronous work shown apart,
  an Other node past each route's budget, callers and reaching routes per method, and the excluded methods. A handler
  that ran on an event loop, returned a reactive or asynchronous result, or whose work BootUI could not place (Spring MVC
  requests that start async processing, every Spring WebFlux request, Quarkus endpoints on the event loop or returning
  `Uni`, `Multi`, or `CompletionStage`) is labelled **assembly only**. Runtime Insights' `route-time-breakdown` splits a
  route's handler work into its top five methods, with the rest as other handler time. `GET {api}/code-paths`,
  `/code-paths/route`, `/code-paths/requests/{id}`, `get_code_paths`, and `bootui code paths` on Spring MVC, Spring
  WebFlux, and Quarkus; the samples gain a seeded slow route, `GET /api/quotes/{sku}`
  ([Code Paths](docs/features/java-agent.md#code-paths), PLAN-v2 §5.14, M5-4b).
- **Code Paths sensor in the BootUI agent.** A new `code-paths` agent sensor, on by default, times the public and
  protected methods of the application's beans per request, as call trees built on the request's own threads, with
  executor handoffs kept apart as asynchronous children, adaptive exclusion of very frequent, very fast methods, and
  bounded memory that drops and counts rather than blocks. It shares one transformer with the `inventory` sensor; the
  Java Agent panel shows its row and counters on Spring MVC, Spring WebFlux, and Quarkus. Spring sends its bean classes
  when the context refreshes and Quarkus at build time. A debugger stepping into a timed method steps over the agent's
  calls, whose bridge carries no line numbers. The Code Paths panel and tools that read the trees follow
  ([Java Agent](docs/features/java-agent.md#the-code-paths-sensor), PLAN-v2 M5-4a).

- **Side Effects panel, API, and tools.** With the BootUI agent armed for the run, the new view-only Side Effects panel
  (Java agent group) lists side-effect sensor coverage and, in M5-5a, the processes application code starts. Rows are
  attributed to a request route, an execution no request owns (a scheduled run, a consumed message), startup, or a
  thread family, with normalized targets, call site, optional Code Paths bean-method stamp, counts, failed starts,
  exits, durations, and exemplar request ids; arguments and environment never appear. The Side Effects panel is an
  AgentEvidence store: panel visibility gates every read, HTTP Exchanges gates route attribution, Code Paths visibility
  gates the inside bean method, and Clear recording and Free BootUI memory clear rows. `GET {api}/side-effects`,
  `/side-effects/sensor`, `get_side_effects`, and `bootui side-effects` are available on Spring MVC, Spring WebFlux, and
  Quarkus while the bridge supports Side Effects ([Side Effects](docs/features/java-agent.md#side-effects), PLAN-v2
  §5.16, M5-5a).
- **Network sensor in the BootUI agent.** A new `network` agent sensor, on by default, records the hosts and ports the
  application connects to (`Socket.connect`, `SocketChannel` connects, a non-blocking connect's finish with its time),
  the datagrams it sends, and the host names the JVM resolves on an address-cache miss, with the client recognized from
  the calling frames (JDBC drivers, messaging and mail clients, the JDK `HttpClient`, Lettuce, MongoDB, cloud SDKs,
  ...), never a byte sent or received. The Side Effects panel's Network tab marks a connection **Not captured by any
  panel** when neither a REST Client Trace call of the same request or time nor, for a JDBC, messaging, or mail client,
  an enabled SQL Trace, broker panel, or Email shows its work; `get_side_effects --query "not captured"` lists these hidden outbound calls. The runtime model gains observed
  `OPENS` edges from routes, jobs, and beans to hosts. Each hook passes a JDK 17, 21, and 26 retransformation check
  and an I/O-free self-test; a failing optional hook is left out and a failing sensor no longer takes the other
  side-effect sensors down. BootUI's own JDK `HttpClient`s run on a `bootui-http-N` executor so they are never recorded
  ([The network sensor](docs/features/java-agent.md#the-network-sensor), PLAN-v2 §5.16, M5-5b).
- **Processes sensor in the BootUI agent.** A new `processes` agent sensor, on by default, hooks the JDK
  `ProcessBuilder.start` path reached by `ProcessBuilder.start()`, `ProcessBuilder.startPipeline(...)`, and
  `Runtime.exec(...)`. It records only the sanitized command name (a started process's executable file name; for a
  failed start, the first element cut at whitespace or `=` first), using `?` for characters outside letters, digits,
  `.`, `_`, `+`, and `-`, plus start failure, exit status, and lifetime; publishes starts at once, attributes exits as
  their starts were, watches exits on the agent-owned `bootui-agent-process-exits` executor; ignores BootUI and agent
  work; disables itself on a failed self-test; and drops/counts bounded records instead of blocking application code
  ([Java Agent](docs/features/java-agent.md#the-processes-sensor), PLAN-v2 M5-5a). The executors sensor's default
  `bootui.agent.executors.skip-tasks` now includes `java.lang.ProcessHandleImpl`, the JDK's process reaper, so a request
  that starts a process is no longer reported as doing work after its response.
- **Files and environment sensors in the BootUI agent.** The opt-in `files` and `environment` agent sensors record
  the files application code opens, deletes, moves, and copies, as path patterns (`./`, `$TMPDIR`, `~`, ids as `{n}`),
  and the environment variables and system properties it reads, by name; never contents or values, with class loading,
  the JDK, and logging appenders grouped apart ([Java Agent](docs/features/java-agent.md#the-files-sensor), M5-5d).
- **Blocking sensor in the BootUI agent.** A new `blocking` sensor reports `Thread.sleep`, `Object.wait`,
  `LockSupport.park`, and the network and files sensors' blocking operations started on an event loop, reported, never
  thrown, in Side Effects' **Blocking** tab; Spring MVC shows it `not-applicable`
  ([Java Agent](docs/features/java-agent.md#the-blocking-sensor), M5-5c).
- **Thread activity sensor in the BootUI agent.** The opt-in `thread-activity` sensor shows, per route, the threads
  application code starts and the executors it creates, and those still running when the request ended, in Side Effects'
  **Threads and leaks** tab, and can be switched at run time ([Java Agent](docs/features/java-agent.md#the-thread-activity-sensor), M5-5e). An async
  Spring MVC request now ends when its async context completes, so work it handed over can be marked after response.
- **Thread locals sensor in the BootUI agent.** The opt-in `thread-locals` sensor shows the thread locals a request or a
  job left set on its pooled thread, by the static field that holds them, never their values, in Side Effects'
  **Threads and leaks** tab, and can be switched at run time ([Java Agent](docs/features/java-agent.md#the-thread-locals-sensor), M5-5f).

- **Executor propagation with the BootUI agent.** With the agent attached, its `executors` sensor carries a request's
  correlation into the tasks it hands to a raw `ExecutorService`, a `ForkJoinPool`, or `CompletableFuture`, so their
  SQL, REST calls, messages, and exceptions are owned by the request at the new `PROPAGATED` correlation tier on Spring
  MVC, Spring WebFlux, and Quarkus, which request profiles report unavailable, with the reason, while the sensor does
  not propagate for the application. One window, `bootui.agent.executors.max-handoff`, bounds each task from its start.
  Each task is recorded by the new `agent.executors` runtime-journal source: Live
  Activity nests it under its request as an `ASYNC` entry badged **after response**, **running**, or **past deadline**,
  and the request's journal profile lists it under **Handoffs** with its thread, queue time, what it did, and its
  outcome. The new Runtime Insights observation `work-after-response` reports work still running after its response
  that ran SQL, called a service, sent a message, or failed (22 checks), with seeds in each sample. The Java Agent panel
  shows the sensor's self-test, hooks, and counters, and warns outside the verified JDKs (17, 21, 25, 26, 27). New
  properties: `bootui.agent.sensors`, `bootui.agent.executors.skip-tasks`, `bootui.agent.executors.skip-threads`, and
  `bootui.agent.executors.max-handoff`. A managed task that runs where its request is already current reuses it instead
  of opening an empty nested execution, and exception groups ignore the agent bridge's frames
  ([Java Agent](docs/features/java-agent.md#the-executors-sensor), PLAN-v2 M5-2).

- **Thread propagation with the BootUI agent (opt-in).** `bootui.agent.sensors=executors,threads` adds the agent's
  `threads` sensor, which carries a request into the platform and virtual threads application code starts, including
  those of a virtual-thread-per-task executor and structured subtasks, keeping inherited scoped values visible. Threads a
  library or framework starts inside a request and pool workers are never propagated, and the Java Agent panel counts
  both
  ([Java Agent](docs/features/java-agent.md#the-threads-sensor), PLAN-v2 M5-2c).

- **Code inventory recording with the BootUI agent.** The agent's new `inventory` sensor, on by default with
  `executors`, records which application methods run in each run, with the first call's request, route, and time, and
  how many classes each jar and class directory loads, through a bounded transport ring in the agent that drops and
  counts rather than blocks (`bootui.agent.ring-capacity`, default 65,536 records). It skips static initializers,
  `$`-prefixed methods, proxies, synthetic classes, test roots, and BootUI's own work, and self-tests before it records.
  The Java Agent panel shows its row, hooks, and counters
  ([Java Agent](docs/features/java-agent.md#the-inventory-sensor), PLAN-v2 M5-3).

- **Code Inventory: did my change run?** A view-only Code Inventory panel in Diagnostics, on Spring MVC, Spring WebFlux,
  and Quarkus, reads the agent's `inventory` sensor: "N of M application methods executed" for this run, then
  **Changed since the previous run** (the methods changed or added since the previous DevTools restart or Quarkus live
  reload, found without git from method hashes of the application's class files that ignore debug information and the
  compiler's renumbering of lambdas and anonymous classes, and count class, field, and abstract-method annotations, each
  executed or not with the first request and route that ran it, or the scan's state while it runs or after it failed),
  **Application code** (packages and classes with
  executed, never-executed, and not-tracked counts), and **Dependencies** (declared jars matched to the jars that loaded
  classes, at startup or later; a jar with no class loaded is *not loaded in this run*, never "unused"). Methods the
  agent could not see in this run, including classes it excludes by name, failed to transform, or ran past its method
  limit for, are *not tracked* with their reason, never counted either way. `GET /bootui/api/code-inventory`,
  `/changes`, `/methods`, and `/dependencies`, the read-only `get_code_inventory` MCP tool and `bootui code inventory`
  serve it; the `verify_after_change` prompt now starts from it, and the new Runtime Insights observation
  `changed-code-not-executed` reports "your change has not run yet" (23 checks). Without the agent it is unavailable with
  the Java Agent panel's reason. New properties: `bootui.code-inventory.max-classes` and
  `bootui.code-inventory.scan-timeout`. The samples seed a never-called method and a declared jar they never load
  ([Code Inventory](docs/features/java-agent.md#code-inventory), PLAN-v2 M5-3).

- **Free BootUI memory.** Live Memory, JVM Tuning, Heap Dump, and the Memory advisor share a header action, with an
  expandable explanation, that empties BootUI's in-memory capture buffers (runtime journal, Live Activity, HTTP
  exchanges, traces, SQL, REST client, transaction, messaging, WebSocket, cache, scheduler, fault-tolerance, exception,
  security-event, and email captures) and then requests a garbage collection, so memory analysis reflects the
  application rather than BootUI. `POST /bootui/api/live-memory/offload` reports heap used before and after, the
  reclaimed estimate, per-store outcomes, and whether `-XX:+DisableExplicitGC` ignores the request, on Spring MVC,
  Spring WebFlux, and Quarkus. Live Memory is now action-capable, so `bootui.read-only=true` and the new
  `bootui.panels.live-memory.read-only` refuse the action.

- **Java Agent panel.** A view-only Java Agent panel in Developer tools, `GET /bootui/api/java-agent`, the read-only
  `get_agent_status` MCP tool, and `bootui agent status` report whether the optional, development-time BootUI agent is
  attached, who holds its claim, and how to attach it, with copyable setup snippets for the Spring Boot Maven plugin,
  Gradle `bootRun`, Quarkus dev mode, Surefire and Failsafe, IntelliJ IDEA, and `JAVA_TOOL_OPTIONS`, on Spring MVC,
  Spring WebFlux, and Quarkus. Spring claims an attached agent from an `EnvironmentPostProcessor` and Quarkus from a
  static-init recorder in dev and test modes, configured by `bootui.agent.enabled`, `bootui.agent.packages`, and
  `bootui.agent.mode`; the dependency inventory no longer counts the agent jar as an application library (PLAN-v2
  M5-1).

- **`bootui-agent` is published to Maven Central.** The release workflow now builds and publishes the `bootui-agent`
  `-javaagent` jar (`com.julien-dubois.bootui:bootui-agent`), polls for it on Maven Central, and smoke-tests the
  published jar: a consumer resolves it with no dependency, and a JVM started with it as its `-javaagent` reports it
  dormant. `bootui-agent-bridge` is built and shaded into the agent but never published (PLAN-v2 M5-1d).

- **Agent-ready request profiles and Copy for AI.** The new read-only `get_request_profile` MCP tool, also the
  `bootui request-profile <id>` command, returns a selection with `available`, `unavailableReason`, `source`,
  `journal`, and `buffers` on Spring MVC, Spring WebFlux, and Quarkus. It consults journal evidence first and, for a
  retained HTTP request, includes the richer HTTP-exchange profile in `buffers`; `source` is `none` when neither
  retention window holds the id. Each buffer-profile exception carries an additive `exceptionGroupId` for
  `get_exception_detail`. The Live Activity profile drawer and the Exceptions detail gain **Copy for AI**, which
  previews one Markdown document, listing what it omits, before anything is copied; **Copy profile** now copies
  Markdown from the same helper. Exports contain only what the panels show, honor `METADATA_ONLY`, and send nothing
  ([Investigate one request](docs/AI-AGENTS.md#investigate-one-request), PLAN §3.25).

- **The MySQL panel reads MariaDB reached through MySQL Connector/J, labelled unsupported.** A MariaDB server behind a
  `jdbc:mysql:` datasource is now read on a best-effort basis on Spring MVC, WebFlux, and Quarkus instead of being
  skipped. The report names the flavor `MARIADB`, the datasource carries an Unsupported badge, and an informational
  diagnostic names the gaps. The panel uses MariaDB's `max_statement_time` guards and its `information_schema` InnoDB
  lock views for row-lock waits. It reports no replication receiver state and computes no counter changes between
  reads, and `super_read_only` and `information_schema_stats_expiry` are omitted. MariaDB 11.4 LTS and 11.8 LTS were checked
  manually; there is no automated MariaDB coverage. MariaDB Connector/J (`jdbc:mariadb:`) is still not offered the
  panel ([MySQL](docs/features/database.md#mysql), [#1194](https://github.com/jdubois/boot-ui/pull/1194)).
- **Four REST API rules catch request and response declarations that break at runtime.** `RAPI-VALID-006` (HIGH)
  reports a Spring handler with several `@RequestBody` parameters, which fails every request on Spring MVC.
  `RAPI-VER-007` (HIGH) reports a GET/HEAD/DELETE handler that binds no body but carries a consumes condition, usually
  a class-level `consumes`, so requests without `Content-Type` get 415 on MVC and WebFlux. `RAPI-RESP-010` (MEDIUM)
  reports a `@ResponseStatus` `reason` on a body-returning Spring MVC handler, which discards the returned body.
  `RAPI-RESP-011` (LOW) reports a GET that returns `Optional`, which answers 200 rather than 404 when empty. The Spring
  adapter now tells the scanner whether the context is servlet or reactive so `RAPI-RESP-010` is skipped on WebFlux
  ([REST API checks](docs/REST-API-CHECKS.md#contract-defect-audit-2026), [#1168](https://github.com/jdubois/boot-ui/pull/1168)).
- **Architecture advisor reports injection and lifecycle annotations the container silently ignores.**
  `ARCH-SPRING-023` (HIGH) flags `@Autowired`, `@Value`, or, on Spring and CDI beans, `jakarta.inject.Inject` on static
  fields and methods, which Spring Framework 7 skips with an INFO log and Quarkus Arc ignores with a warning.
  `ARCH-SPRING-024` (HIGH) flags legacy `javax.annotation.PostConstruct`/`PreDestroy`, and `javax.inject.Inject` or
  `javax.annotation.Resource` on beans, which neither Spring Framework 7 nor Quarkus 3 recognizes. Both run on Spring
  MVC, Spring WebFlux, and Quarkus, and the field-injection rules no longer report the same fields
  ([#1165](https://github.com/jdubois/boot-ui/pull/1165)).

- **Four Database advisor checks (24 → 28).** DB-SCHEMA-010 (LOW) reports MySQL invisible, MariaDB ignored and
  Oracle invisible indexes that every write still maintains; DB-PG-005 (LOW) reports `UNLOGGED` tables and leaf
  partitions; DB-HIB-009 (MEDIUM) reports an explicitly named `@Id` declaring `GenerationType.IDENTITY` whose
  PostgreSQL, MySQL or MariaDB column reports no auto-increment, identity, default or generated value; and DB-HIB-010
  (MEDIUM) reports a positive `@Column(precision, scale)` wider than the bounded physical `DECIMAL`/`NUMERIC` column,
  which rounds or rejects values. Each was accepted by at least two of three independent model reviews
  ([Database checks](docs/DATABASE-ADVISOR-CHECKS.md), [#1169](https://github.com/jdubois/boot-ui/pull/1169)).
- **Failure-preserving retention for HTTP Exchanges, SQL Trace, and REST Client.** Each BootUI-owned capture buffer
  now reserves a share of its existing capacity, 25% by default, for the most recent failed and slow records: `5xx`
  and slow exchanges, failed and slow statements, and failed, `4xx`/`5xx`, and slow calls. Routine records are evicted
  first, so a burst of successful traffic no longer evicts the failure you came to investigate; the reservation never
  adds memory. Tune it with `bootui.http-exchanges.reserved-share-percent`, `bootui.sql-trace.reserved-share-percent`,
  and `bootui.rest-client-trace.reserved-share-percent` (`0` restores strictly oldest-first eviction). The three
  panels state how many records they keep, how many sit in the reserved share, and how many were evicted, and their
  reports, MCP tools, and CLI commands gain an additive `retention` object with the same counts. On Spring, an
  application-provided `HttpExchangeRepository` or recording filter is never replaced and its retention is reported
  as application-managed ([Failure-preserving retention](docs/features/diagnostics.md#failure-preserving-retention)).
- **Architecture, REST API, and Hibernate findings say where the code is.** Each rule result carries
  `sampleLocations`, aligned index-for-index with `sampleViolations`, and each detail page carries `locations`,
  aligned with `violations`, on REST, the report and `get_*_rule_violations` MCP tools, and the CLI. A location names
  the class, member, recorded source file, line, and local source path of the one code element a finding concerns,
  with a `LINE`, `MEMBER`, or `CLASS` precision. Source paths are resolved only during an explicit scan, through the
  Architecture advisor's bounded module and source-set lookup; archives, other layouts, ambiguous matches, and
  exhausted budgets keep no path and say why in `violationDetails.locationNotes`. Kotlin lines inlined from another
  file are dropped rather than shown wrong. The panels show each location with a **Copy location** action and an
  opt-in, per-browser **Open in** preference for VS Code or IntelliJ IDEA. Violation text, counts, severities,
  dismissals, evidence, and scores are unchanged, and findings that span several elements carry no location
  (docs/PLAN.md §3.19).
- **Request profiles show the REST client calls and cache accesses a request made.** The Live Activity profile drawer
  and **Copy profile** gain REST client calls, masked exactly as the REST Client panel shows them, and cache accesses,
  which carry only the hashed key, on Spring MVC, Spring WebFlux, and Quarkus (cache on Spring only, since Quarkus has no
  cache-access capture seam). Every section is labelled with the tier that correlated it — trace id, serving thread, or
  time window — the profile is flagged approximate whenever a time window was used, a tier an adapter cannot provide is
  listed as unavailable, and each section shows at most 200 entries with a count of the rest. The
  `GET /bootui/api/activity/request/{id}` response only gains fields (docs/PLAN.md §3.20a).
- **Route performance rankings in HTTP Exchanges.** A route table above the exchange list summarizes the retained
  window per method and route: request count, 2xx/3xx/4xx/5xx counts, average, p50, p95, p99, and maximum duration, and
  share of retained request time, ranked by requests, total time, p95, slowest request, or errors. Routes resolve from
  the framework's handler template, then the application's declared mappings, then a masked path — exactly as SQL Trace
  attributes database time — and say which source they used. Each route lists its own exchanges, each exchange links to
  its Live Activity request profile, and the evidence window (retained exchanges, buffer size, evictions, oldest
  exchange, hidden BootUI exchanges) is stated inline. The same rankings are available from
  `GET /bootui/api/http-exchanges/routes`, the `get_http_routes` MCP tool, and `bootui http routes`, on Spring MVC,
  Spring WebFlux, and Quarkus.
- **Lightweight PostgreSQL Docker sample profile.** Run the Spring MVC sample with `docker-postgresql`, or the
  dedicated `run-local-postgresql.sh` launcher, to start only PostgreSQL and Redis, without Kafka, Ollama, or AI model
  downloads. PostgreSQL preloads and creates `pg_stat_statements`, so the PostgreSQL panel's Statement ranking is
  readable; the full `docker` profile is unchanged.
- **All-in-one Spring sample launcher.** `run-local-all.sh` runs the Spring MVC sample with the BootUI Java agent, the
  full `docker` profile (PostgreSQL, Redis, Kafka, and Ollama for Spring AI), and a new `run-history` profile that keeps
  Live Activity's history in PostgreSQL and the last run's summary in `.bootui/run-baseline.bin`, so a new run is
  compared with the previous one after a full restart.

### Changed

- **The Java Agent panel opens on its setup when the agent is not attached.** The steps and setup snippets follow the
  **Not attached** status, then a short explanation of what a Java agent is, how BootUI's works, which features need it,
  and its cost; the sections that only describe an attached agent wait until it is attached
  ([Java Agent](docs/features/java-agent.md#java-agent)).
- **Change impact names its unexercised routes for their scope.** The list is now **Reaches it, but didn't run**
  (with the BootUI agent, **Reaches it, but didn't run it**), and a line says it holds only routes that reach what you
  checked, linking to the separate, app-wide **Not exercised in this run** list in **Coverage & limits**. The
  `notExercised` field is unchanged.
- **Timed-out scans stop.** An MCP or CLI `architecture_scan` or `vulnerabilities_scan` past its execution timeout now
  stops at its next step and keeps the previous report, instead of running on or publishing an "interrupted" error
  ([#1340](https://github.com/jdubois/boot-ui/issues/1340)). A panel vulnerabilities scan interrupted at shutdown now
  answers `500` and keeps the previous report, instead of publishing an `ERROR` report.
- **Agent-sized MCP and CLI answers.** Large reads return a short first page without `limit`, take a `query`, and say
  when rows were left out (`page.hasMore`): SQL traces, startup, log tail, coding-agent sessions, the vulnerabilities
  report, Live Activity (by type, severity, or route), HTTP exchanges, configuration, beans, metrics, threads, and
  conditions, which now pages both outcomes together. `get_config` drops the browser's property suggestions, agent status and Side Effects
  summarize sensors until a query names one, and `tools/list` is smaller. A 1.x CLI keeps working
  ([Agent-sized defaults](docs/AI-AGENTS.md#agent-sized-defaults)).
- **Sidebar group names and icons.** The command palette shows and finds each panel by its sidebar group title
  (*Instrumentation*, *Developer tools*), still matching the old group key, and every sidebar group has its own icon,
  never a panel's, so the collapsed rail tells groups apart. The feature docs follow the sidebar's names and order.
- **A tool this application does not advertise says why.** MCP answers a known BootUI tool whose panel is unavailable
  with `Tool not available in this application: <tool>.` and the panel's reason, also in `error.data`, instead of
  `Unknown tool`; the CLI facade still answers `404`.
- **Whole CLI help per command.** `bootui <command> --help` prints the tool's whole description, so `probe start` keeps
  its approval and metadata-only wording and `memory scan` its full-GC warning; every action is tagged
  `[action - needs approval]` in the listing and in its help. The readable output no longer cuts a value outside a table,
  such as a `checksNotRun` reason.
- **Agent guidance.** The MCP instructions say the default `get_runtime_insights` list leaves some kinds out and
  that `start_method_probe` needs separate approval; the skill proposes the Java agent for
  Code Inventory, Code Paths, Side Effects, probes, and caught exceptions, lists the browser-only controls, and documents
  `bootui tools --json` and the exit code of a command whose tool is not exposed. An advisor that has not run yet names
  the tool and command to run it.

- **Runtime Insights leads with its verdict.** The panel opens on how many things this run lists to check and how many
  the default list leaves out. Findings are one list filtered by theme,
  whose rows open in place; the comparison and change impact, the resource profiler, and the run's coverage and check
  limits move to tabs of their own ([Runtime Insights](docs/features/overview.md#runtime-insights),
  [#1328](https://github.com/jdubois/boot-ui/pull/1328)).
- **Quarkus 3.40.1 LTS.** Updated the Quarkus compatibility platform to the latest LTS micro release and kept the
  RabbitMQ metadata test fixture compatible with the managed SmallRye API.

- **Tabs that look like tabs, in every theme.** Every panel tab strip now shares one component, with muted labels
  instead of link-blue text, arrow-key navigation, and a selected tab drawn in each theme's own idiom.

- **A shorter Runtime Insights default list.** Some kinds move to the panels showing the same evidence or appear only
  on request, each row saying where, and five wording and attribution bugs are fixed
  ([Runtime Insights](docs/features/overview.md#runtime-insights)).
- **Eight Maven Central artifacts instead of thirteen; one Spring Boot starter for Spring MVC and WebFlux.** BootUI
  2.0 publishes `bootui-core`, `bootui-engine`, `bootui-ui`, `bootui-spring-boot-starter`, `bootui-quarkus`,
  `bootui-quarkus-deployment`, `bootui-cli`, and `bootui-agent`. To migrate:
  - **Spring WebFlux:** replace `bootui-spring-boot-starter-reactive` with `bootui-spring-boot-starter`.
  - **Spring MVC:** nothing changes, as long as the application declares its own `spring-boot-starter-web` (or
    `spring-boot-starter-webmvc`), as Spring MVC applications do. The starter no longer brings a web stack; an
    application that had none of its own starts no web server, and BootUI stays off.
  - **WebFlux applications that relied on BootUI's servlet starter for Tomcat:** these now start as REACTIVE on Netty,
    which is what `spring-boot-starter-webflux` alone gives them.
  - **A direct `bootui-spring-autoconfigure` dependency:** depend on `bootui-spring-boot-starter`. The auto-configuration
    moved into it, keeping its `io.github.jdubois.bootui.autoconfigure` packages.
  - **A direct `bootui-client` dependency:** depend on `bootui-cli`. The client keeps its
    `io.github.jdubois.bootui.client` package and stays dependency-free: picocli is an optional dependency of
    `bootui-cli`. The runnable CLI is still the shaded `bootui-cli-<version>-all.jar`, which is what JBang and the
    installers use.
  - **`bootui-parent` and `bootui-quarkus-parent`:** no longer published, and nothing to change. Every published POM
    is now flattened: no parent, every dependency version resolved.

  The starter's build now fails if a servlet or reactive web-server artifact reaches its dependencies. The release
  stages its Central bundle as a local repository and runs the consumer smoke tests against it before the release tag
  is created, and again before upload. The smoke tests cover Spring MVC on Tomcat, WebFlux on Netty with no Servlet API
  on the classpath, the CLI and its client without picocli, Quarkus, and the agent ([Setup](docs/SETUP.md),
  [Spring WebFlux](docs/setup/webflux.md), [Command line](docs/CLI.md#building-on-it)).

- **Runtime Insights lists less noise by default.** The panel, `get_runtime_insights`, and `bootui insights list` now
  show a default list, and every observation carries `listed` and, when left out, `unlistedReason`. A route's time
  breakdown is listed only when prominent: a warm median of 20 ms or more, authorization taking 20 % of its time, or a
  median of 50 authorization decisions a request, which replaces the planned authorization-cost check; a route with two
  to four warm requests is listed as not enough evidence when their median reaches 100 ms. Exception hotspots lists
  groups behind a 5xx, a failed run or message, a redirect, or not seen in the previous run, and collapses those seen
  only behind 4xx responses, and those caught in runs or messages that completed, into one counted row each. SQL after
  the handler drops a statement Repeated SELECTs already lists from the same call site. Framework warnings leaves out
  `WARN` messages without a specific check and counts, in a **No request** row, the framework `ERROR` events that
  carried no request id, except those a container wrote on a failed request's thread just after it ended. Garbage
  collection and heap rows are reached from the Memory panel, which counts them and opens the **Memory** theme with
  every row. **Show all routes**, a search, a deep link, and the agent query `all` (formerly a plain text search), a
  kind, or a route still reach every row, which says why it was left out ([Runtime
  Insights](docs/features/overview.md#runtime-insights), PLAN-v2 M4-19).
- **The documentation site and the Docker Hub sample images follow the released major.** Every branch declares its
  release line in `.github/release-line`, and `pages.yml` and `docker-publish.yml` publish a branch only when its line
  has a release on Maven Central and no newer major does, so merging `v2` into `main` publishes no 2.0 site or image
  before 2.0.0 is out. The Release workflow releases only versions of the branch's own line, so `main` after the merge
  cannot release 1.x and a `1.x` maintenance branch cannot release 2.0.0 (a branch containing the 2.0-only `bootui-agent`
  must declare at least line 2); it releases only from `main` or an `N.x`
  maintenance branch, and fails when its documentation run skipped the deploy. `rehearse_v2_merge.py` rehearses the merge
  on a candidate that is never published, and [Releasing 2.0](docs/V2-RELEASE.md) is the runbook for the merge and the
  `1.x` branch (PLAN-v2 M4-23, D40).
- **Less runtime-journal work on request threads.** A recorded statement, REST client call, or cache access now only
  selects its application frames on the application thread; the journal's dispatcher formats them, with the same
  `Class.method(File.java:42)` text and masking as before. An offer to the journal takes one lock instead of three,
  checks the queue's reserved share and inserts atomically, and no longer wakes the dispatcher for every event: the
  dispatcher drains in batches, recording an event at most about a millisecond later, or one timer tick on systems
  with a coarser timer (about 15.6 ms on Windows by default). A burst that fills half the queue's routine share ends
  that pause at once, so a small `queue-capacity` does not drop events while the dispatcher waits. On the Spring MVC
  sample under load, the offer path falls from about 1.3 % to 0.2 % of CPU samples and the dispatcher from about
  8.4 % to 3.5 %. An event offered after the run ended is now counted as dropped rather than accepted, even when a
  listener keeps the dispatcher from stopping. A frame whose class is redefined, by the BootUI agent or a hot swap,
  while its event waits for the dispatcher reads `(Unknown Source)` (PLAN-v2 M4-18d).
- **A Java agent sidebar group.** Java Agent, Code Paths, and Code Inventory now share a **Java agent** group between
  Diagnostics and Developer tools, with Java Agent first as the setup and status entry point. Without the agent
  attached, Code Paths and Code Inventory stay in that group, dimmed, with their unavailable reason as the tooltip,
  instead of moving into the collapsed *Disabled / unavailable* group; a panel turned off with
  `bootui.panels.<panel-id>.enabled=false` still moves there. Their documentation moves to the
  [Java Agent](docs/features/java-agent.md) page.
- **Code Paths counts a late fragment's request once.** A code-paths fragment arriving after its request's tree was
  merged into its route, and no longer kept, now amends that route's executed methods instead of opening a second,
  partial tree that counted the request twice; one for a tree only an exemplar still keeps amends its route too
  (PLAN-v2 M5-7a).
- **`bootui.agent.sensors` rejects unknown sensor ids.** The default sensor set is now `executors`, `inventory`,
  `code-paths`, `processes`, `network`, and `blocking`, while `threads`, `files`, and `environment` are opt-in. The Side
  Effects sensors this version does not ship (`thread-activity`, `thread-locals`, `resources`, `security-sinks`)
  are accepted with a warning and reported not available. Any other id now fails the application's start, on Spring and
  Quarkus alike, while the BootUI agent is attached, with an error naming the accepted ids.

- **Durable Live Activity history is journal-rendered.** With
  `bootui.activity.persistence.enabled=true`, persisted rows now contain the journal's `MASKED` view rather than
  polling panel buffers. They no longer retain principals, exception or log messages, or email subjects. **Migration:**
  use the bounded live panels when those details are needed; durable history retains safe summaries and metadata
  ([Live Activity](docs/features/overview.md#durable-history), PLAN-v2 §8).

- **Durable Live Activity history is journal-rendered.** With
  `bootui.activity.persistence.enabled=true`, persisted rows now contain the journal's `MASKED` view rather than
  polling panel buffers. They no longer retain principals, exception or log messages, or email subjects. **Migration:**
  use the bounded live panels when those details are needed; durable history retains safe summaries and metadata
  ([Live Activity](docs/features/overview.md#durable-history), PLAN-v2 §8).

- **Faster, quieter CI builds.** The per-extension Quarkus integration-test modules and the three Spring Playwright
  suites now run on parallel runners instead of competing with the coverage build or running back to back. Surefire
  and Failsafe write each test class's console output to `*-output.txt` files, uploaded as the `test-output`
  artifacts, instead of the build log, and Vitest prints a coverage summary rather than its per-file table.

- **The sidebar's pinned top is now Home: Scorecard, Live Activity, and Runtime Insights.** The Overview panel is
  renamed **Scorecard**, which is what it shows, at `#/scorecard`; `#/overview` and the root still land there. Its
  `overview` panel id, `bootui.panels.overview.*` properties, `GET /bootui/api/overview`, and the `get_overview` tool
  are unchanged. GitHub moves to the Developer tools group ([Home](docs/features/overview.md),
  [GitHub](docs/features/developer-tools.md#github)).

- **The MySQL panel reads every Oracle MySQL version instead of only 8.4.** It previously skipped every server outside
  the 8.4 line, so a MySQL 9.7 database showed "No supported MySQL JDBC datasource was found". Oracle MySQL 8.4 LTS and
  9.7 LTS are now the tested lines, and CI runs the Spring and Quarkus MySQL live suites against both `mysql:8.4.6` and
  `mysql:9.7.2`. Other Oracle MySQL versions, such as 8.0 or Innovation releases, are read with an informational
  "not a tested server line" diagnostic, and any section the server cannot answer reports its own reason. Other
  compatible flavors are skipped before any statistics query instead of failing the read
  ([MySQL](docs/features/database.md#mysql)).
- **Maven Central releases ship an empty placeholder `-javadoc.jar` instead of generated Javadoc.** Central requires
  the file but not its content, and BootUI's public surface is its HTTP, MCP, and CLI contract rather than a Java API;
  `-sources.jar` files are still published for IDE navigation. This shrinks uploads and release build time.
- **REST API advisor audit: three noisy rules retired, two severities recalibrated.** `RAPI-VALID-005`
  (Idempotency-Key), `RAPI-DTO-004` (response DTO setters), and `RAPI-ERR-002` (`throws Exception`) now always return
  `SKIPPED`; their IDs and dismissals are kept. `RAPI-RESP-006` drops from HIGH to MEDIUM because servers already strip
  204 content, and `RAPI-VER-002` drops from LOW to INFO and now recommends method-level `consumes`. `RAPI-MAP-002` no
  longer reports identical Spring mappings, which Spring rejects at startup and so only appear for inactive profile
  alternatives, while still reporting partial overlaps that fail at request time. Rule names now match the catalogue
  and learn-more links point at specific sources. The catalogue has 60 rule IDs, 53 of which can emit ([#1168](https://github.com/jdubois/boot-ui/pull/1168)).
- **Spring advisor audit against Spring Boot 4.1.1 and Spring Framework 7.0.9.** Four rules are added:
  SPRING-CONFIG-007 (LOW) flags Boot's deprecated `spring-boot-jackson2` auto-configuration, scheduled for removal in
  Boot 4.3; SPRING-CONFIG-008 (INFO) reminds you to remove `spring-boot-properties-migrator` once migration is done;
  SPRING-PERF-007 (INFO) reviews virtual threads on JDK 21–23, before JEP 491 removed `synchronized` pinning; and
  SPRING-WEB-008 (LOW) flags an unlimited servlet multipart request size read from Boot's `DispatcherServlet`
  registration. SPRING-CONFIG-001 (lazy initialization for large contexts) is retired because nearly every real
  application exceeded its threshold and Boot advises against enabling lazy initialization by default; its ID stays
  reserved. SPRING-CONFIG-003 now also reports the remaining verified Boot 4.0/4.1 removals, including OTLP
  logging/tracing, OpenTelemetry, Brave, Zipkin and Wavefront keys, RabbitMQ `retry.max-attempts`, Kafka
  `backoff.random`, `spring.jackson.parser`/`generator`, and template-engine `*.enabled` switches. SPRING-WIRING-007
  no longer claims Framework 7.0 deprecates `RestTemplate` (the deprecation lands in 7.1). The advisor now ships 41
  rules ([Spring checks](docs/SPRING-CHECKS.md), [#1164](https://github.com/jdubois/boot-ui/pull/1164)).
- **CRaC readiness advisor audit.** Two checks are added: `CRAC-POOL-005` reports refresh-time database access
  (Flyway, Liquibase, Boot schema initializers, `spring.sql.init.mode=always`, or Hibernate boot metadata access and
  schema management) next to a non-in-memory Hikari pool, which leaves connections open at a
  `spring.context.checkpoint=onRefresh` checkpoint because the Hikari lifecycle has not started yet; it runs only when
  the `org.crac` API or onRefresh is present, and never displays the JDBC URL. `CRAC-NET-002` reports host-name and
  network-interface lookups retained by static initializers. `CRAC-SCHED-001` now also finds programmatic
  `scheduleAtFixedRate` calls and `addFixedRateTask` registrations, `CRAC-RANDOM-001` is `HIGH` only for explicit
  SecureRandom seeding (`MEDIUM` for generator fields) and covers `SplittableRandom`, `CRAC-SECRET-001` no longer
  reports credential-named JPA entity columns, `CRAC-POOL-002` covers Kafka, Lettuce, Jedis, and Netty event-loop
  clients, and `CRAC-CACHE-001` explains expiry across restore precisely
  ([CRaC readiness checks](docs/CRAC-READINESS-CHECKS.md), [#1170](https://github.com/jdubois/boot-ui/pull/1170)).
- **Spring Security advisor audited against Spring Security 7.1.1.** Spring Security 7's passkey (`webAuthn()`),
  one-time-token and SAML 2.0 login filters are now recognized framework filters and browser-login credentials on
  Spring MVC, so those chains are assessed by the CSRF, framing, CSP and session checks instead of being left
  incomplete; WebFlux one-time-token login is recognized the same way. `SEC-SESSION-001` now reports session-backed
  passkey login, whose 7.1 configurer applies no session-authentication strategy (no session-id or CSRF-token rotation
  at login), with its own message and an `ObjectPostProcessor` remediation. New HIGH, production-only rules flag plain
  HTTP opaque-token introspection on Spring MVC (`SEC-OAUTH-005`, parity with `SEC-RXF-OAUTH2-004`) and plain HTTP
  OAuth2 client provider authorization, token, JWK-set and user-info endpoints on both stacks (`SEC-OAUTH-006`,
  `SEC-RXF-OAUTH2-005`; the WebFlux catalogue now has 26 rules). `SEC-CORS-003` and `SEC-OAUTH-001`, which could only
  pass or skip, are retired. `SEC-SESSION-004` now reviews explicit `SameSite=None` instead of skipping every unset
  value; `SEC-SESSION-002` no longer flags production apps with direct TLS; `SEC-OAUTH-004` is production-only like its
  reactive twin; `SEC-HEAD-002` drops from HIGH to MEDIUM and `SEC-HEAD-007` rises from LOW to MEDIUM to match WebFlux;
  the WebFlux framing and CSP reviews (`SEC-RXF-HEAD-002`, `SEC-RXF-HEAD-004`) no longer flag bearer-only API
  chains; and `SEC-CONFIG-005` ignores the `spring.web.error.include-*=always` development defaults DevTools adds, so
  it no longer reports three MEDIUM findings on every DevTools run while an application value is still reported ([Security checks](docs/SECURITY-CHECKS.md), [#1173](https://github.com/jdubois/boot-ui/pull/1173)).
- **Hibernate advisor audit against Hibernate ORM 7.** Effective factory settings are now read from the factory's own
  options and SQL statement logger, so settings the application never configured no longer leave `HIB-CONFIG-003`,
  `-006`, `-009`, `-013`, `-017`, `-019`, and `-020` without evidence and the scan `PARTIAL`. Three rules are added:
  `HIB-MAP-023` (MEDIUM) for `Set` element collections of embeddables without `equals`/`hashCode`, which Hibernate
  rewrites on every flush; `HIB-MAP-024` (LOW) for `@Lob` on PostgreSQL, which stores `oid` large objects; and
  `HIB-ENTITY-010` (INFO) for timestamp `@Version` attributes. `HIB-CONFIG-001` is retired in favour of the Spring
  advisor's `SPRING-JPA-001`, `HIB-MAP-021` is retired because ORM 7 removed `@Where`, and the earlier removal of
  `HIB-MAP-017` is now documented. `HIB-CONFIG-016` reports the disabled pagination guard once at INFO instead of
  repeating `HIB-FETCH-003`'s queries at HIGH, `HIB-FETCH-005` drops to LOW and skips JDBC locators, `HIB-MAP-014`
  drops to LOW, `HIB-CONFIG-013` only applies to types bound through the JVM time zone, and stale learn-more links now
  point at the current guides. The catalog has 72 active rules ([Hibernate checks](docs/HIBERNATE-CHECKS.md),
  [#1172](https://github.com/jdubois/boot-ui/pull/1172)).
- **Architecture advisor catalog audit.** Three rules are retired and their IDs reserved: `ARCH-CODE-005`
  (`printStackTrace` into an explicit writer, mostly the legitimate `StringWriter` idiom), `ARCH-CODE-011` (the
  `Interface` name suffix), and `ARCH-SPRING-005` (default-package stereotypes, which a scan can never import).
  `ARCH-SPRING-011` is now HIGH, because Spring Framework 7 throws `IllegalArgumentException` on every call of an
  `@Async` method with another return type, and it no longer judges private, static, or final methods of an `@Async`
  class. `ARCH-CODE-007` is now MEDIUM, `ARCH-SPRING-002` LOW, and `ARCH-CODE-010` INFO. `ARCH-CODE-016` stays MEDIUM on
  Spring and is LOW on Quarkus, where `@Inject` field injection is idiomatic, and `ARCH-CODE-003` no longer runs on
  Quarkus, where `java.util.logging` is a built-in logging API. The self-invocation, proxyability, and lifecycle-callback
  rules now also cover Spring Framework 7 `@Retryable` and `@ConcurrencyLimit`, Spring Retry, and method security
  annotations such as `@PreAuthorize`, whose self-invocation skips the authorization check
  ([#1165](https://github.com/jdubois/boot-ui/pull/1165)).
- **Quarkus advisor audit: client-proxy field rule, production bind logging, fewer false positives.** A second audit
  against Quarkus 3.33 and CDI 4.1 retires `QA-CDI-001` and adds `QA-CDI-004` (MEDIUM): a public instance field on any
  normal-scoped bean — application, request, session or custom scope — is a CDI definition error that ArC tolerates,
  and access through an injected reference reaches the shared client proxy rather than the current instance. Final
  atomics and concurrent collections are no longer exempt there, and `QA-CDI-002` now covers singleton REST resources
  only. New `QA-CFG-005` (HIGH) reports build-time Hibernate bind-parameter logging that a production build would
  package. `QA-CFG-004` also detects the deprecated `database.generation.create-schemas` and `halt-on-error` keys and
  names each replacement. An explicit `quarkus.http.enable-compression=false` now suppresses `QA-WEB-001`,
  `QA-WEB-002` drops from MEDIUM to LOW, and the compression and shutdown rules prefer a visible `%prod.` declaration,
  fixing a `QA-WEB-004` false positive in development mode; they now report incomplete production coverage there
  like the other production rules. The advisor has 14 rules
  ([Quarkus checks](docs/QUARKUS-ADVISOR-CHECKS.md#second-audit-disposition), [#1167](https://github.com/jdubois/boot-ui/pull/1167)).
- **Vulnerabilities scores CVSS v4.0 and prefers it over CVSS v3.** Advisories carrying a CVSS v4.0 vector now get a
  numeric score from a port of FIRST's reference calculator, verified against it for every Base metric combination, and
  scored as published, so GitHub's frequent `E:U` Threat metric applies. When an advisory carries both versions, the v4
  score decides the severity, as it does for GitHub's own label: in a live OSV.dev sample, the v3 band overstated
  GitHub's severity for 6 of 21 dual-vector records. Some findings therefore drop a severity band, and v3 and v4 numbers
  are never compared. Same behavior on Spring MVC, Spring WebFlux, and Quarkus
  ([Vulnerabilities checks](docs/VULNERABILITIES-CHECKS.md#severity-applicable-assessments-cvss-v4-preferred-over-v3),
  [#1163](https://github.com/jdubois/boot-ui/pull/1163)).
- **Vulnerabilities reports malicious packages as CRITICAL.** An OpenSSF Malicious Packages advisory (`MAL-` ID), which
  OSV.dev serves for Maven packages, used to read as `UNKNOWN` with no score penalty. It is now `CRITICAL`, without a
  synthesized CVSS score, and its details lead with removal guidance
  ([Malicious-package advisories](docs/VULNERABILITIES-CHECKS.md#malicious-package-advisories),
  [#1163](https://github.com/jdubois/boot-ui/pull/1163)).
- **Memory advisor audit: fewer, more reliable findings.** The advisor now evaluates 32 rules. Five noisy rules are
  retired and their IDs are never reused: `MEM-HEAP-007` (committed heap above usage, which flagged normal GC headroom
  and every equal `-Xms`/`-Xmx`), `MEM-FOOTPRINT-004` (host swap, not attributable to the JVM), `MEM-POOL-006` (JIT
  tier flags such as IntelliJ's `-XX:TieredStopAtLevel=1`), `MEM-THREAD-003` (peak versus current threads), and
  `MEM-CONTENT-004` (arrays at half the heap, the normal shape of a Java heap). `MEM-HEAP-004` now reports the classic
  `-Xmx32g`, which already disables compressed oops, using the live `MaxHeapSize`, `ObjectAlignmentInBytes`, and
  `UseCompressedOops` options. `MEM-GC-006` no longer reports a ZGC or Shenandoah concurrent cycle as a long GC event.
  `MEM-POOL-002` evaluates the whole code cache and the combined compiled-method segments instead of one segment that
  HotSpot can fall back from. `MEM-FOOTPRINT-002` thread-stack reservations drop to LOW, and `MEM-POOL-003` rises to
  MEDIUM when `-XX:+DisableExplicitGC` disables the `System.gc()` that java.nio needs to reclaim direct buffers. GC
  filler objects (JDK 19+) are excluded from the class histogram. New INFO rule `MEM-GC-008` notes non-generational
  ZGC on JDK 21-23, where generational ZGC is available
  ([Memory checks](docs/MEMORY-CHECKS.md#complete-rule-audit-and-current-behavior),
  [#1162](https://github.com/jdubois/boot-ui/pull/1162)).
- **GraalVM advisor: October 2026 audit (30 checks).** The native-image readiness advisor was re-audited against the
  GraalVM for JDK 25 feature releases (through 25.4), Spring Framework 7.0.9, Spring Boot 4.1.1, and Spring Cloud
  Commons, with every new or removed rule critiqued by three reviewer models. `GRAAL-REFLECT-003` (deep reflection) and
  `GRAAL-REFLECT-004` (member annotation access) are retired because neither needs metadata of its own. Five checks are
  added: `GRAAL-REFLECT-006` (application types bound with Jackson or Spring's HTTP clients in a method body),
  `GRAAL-JDK-003` (`finalize()` cleanup that never runs natively), `SPRING-AOT-006` (explicit-argument `getBean`),
  `SPRING-AOT-007` (registry post-processors replayed at run time), and `SPRING-AOT-008` (`@RefreshScope`).
  `GRAAL-REFLECT-001` now covers Spring's `ReflectionUtils`, `ClassUtils`, and `BeanUtils` facades, `GRAAL-RES-001`
  covers `ClassPathResource` and resource pattern lookups, `SPRING-AOT-003` covers `@ConditionalOnCloudPlatform` and
  `@ConditionalOnThreading`, and `GRAAL-JMX-001` no longer flags `ManagementFactory.getPlatformMBeanServer()` but
  reports MBean registration, JMX proxies, and remote connectors instead
  ([GraalVM readiness checks](docs/GRAALVM-READINESS-CHECKS.md#october-2026-audit),
  [#1171](https://github.com/jdubois/boot-ui/pull/1171)).
- **Quarkus Security advisor audit (45 rules).** Three new rules: `QS-TLS-006` flags legacy TLS protocol versions in
  HTTP SSL or TLS registry lists, `QS-OIDC-005` flags OIDC web-app tenants that disable session token encryption, and
  `QS-PROXY-001` flags forwarded headers trusted from any address. `QS-AUTH-007` and `QS-AUTH-013` now review
  production declarations, so `%dev`/`%test`-only embedded users are no longer reported. `QS-SESSION-001` is lowered
  to MEDIUM. `QS-CFG-001` now also catches committed symmetric keys and inline private keys. A sole `/.*/` CORS
  origin no longer reports `QS-CORS-002`, because Quarkus treats it as the wildcard origin with credentials
  defaulting to `false`. Every rule links to a rule-specific section of the Quarkus 3.33 guides
  ([#1161](https://github.com/jdubois/boot-ui/pull/1161), [Quarkus security checks](docs/QUARKUS-CHECKS.md)).
- **One request slow threshold on every stack.** `bootui.activity.request-slow-threshold-ms` (default 1,000 ms) is now
  honored by Spring WebFlux and Quarkus as well as Spring MVC. It sets the `SLOW` severity of Live Activity `REQUEST`
  and `SCHEDULED` entries and decides which exchanges are kept longer. Spring WebFlux and Quarkus previously used a
  fixed 500 ms, so by default an entry that took 500–999 ms is no longer flagged `SLOW` there. A value of `0` now
  disables slow classification on every stack; Spring MVC previously flagged every request as slow at `0`.
- **BootUI's own requests no longer take Spring HTTP exchange slots.** While `bootui.monitoring.exclude-self` is on,
  BootUI's Spring recording filter no longer records BootUI's own requests into BootUI's repository, instead of
  recording them and hiding them when the panel is read, as Quarkus already did. The check uses the decoded path below
  the servlet context path or WebFlux base path and never the query string. Console polling no longer evicts
  application exchanges, `hiddenSelf` now reads `0` on Spring as on Quarkus, and Actuator's `httpexchanges` endpoint,
  when backed by BootUI's repository, no longer lists them.
- **Every adapter builds request profiles with one shared engine assembler.** Spring MVC, Spring WebFlux, and Quarkus
  now serve the profile through `ExecutionProfileAssembler`, so identical evidence produces an identical profile. Each
  signal attaches to at most one request: a trace id shared by two captured requests, or a serving thread or time
  window two requests could equally claim, now leaves the signal out of both profiles and counts it in the notes,
  instead of showing it in both. On Spring MVC, exceptions keep their method, path, and window match, within which a
  trace id now settles which request threw them; on Quarkus, a disabled SQL Trace, Exceptions, or Security Logs panel
  no longer contributes to request profiles, as on Spring.
- **One slowest-request KPI for every stack.** Live Activity's p50/p95 latency and slowest request are now computed once
  in the shared engine, so Spring MVC, Spring WebFlux, and Quarkus report the same figures for the same traffic. The
  slowest request is labelled with its resolved route and links to that route's row in HTTP Exchanges, and the latency
  card states how many requests it covers. Spring MVC now computes these over every retained exchange rather than the
  newest `bootui.activity.max-entries`, and Spring WebFlux and Quarkus now report a 0 ms slowest request instead of
  none. SQL Trace, Live Activity, and route rankings share one percentile helper; no existing SQL Trace figure changes.
- **Route labels are the same whichever source resolved them.** SQL Trace route attribution now renders a Spring
  framework template the way it renders a declared one, so `/orders/{id:[0-9]+}` reads `/orders/{id}`, while a wildcard
  such as `/**` is kept as declared. A variable's pattern may now contain `?` or `/` without truncating the route.
  When declared mappings are ambiguous, a masked path now also masks every segment they mark as a parameter, and a
  brace-delimited segment on a real request is masked rather than trusted as template syntax.
  On Quarkus, declared JAX-RS routes are now matched under `quarkus.http.root-path` and `quarkus.rest.path`, so SQL
  Trace attributes requests to their declared route instead of a masked path when the application has a root path.
- **Quarkus 3.33.3.3.** The Quarkus extension, integration tests, and sample app move to Quarkus 3.33.3.3, the
  newest micro release of the 3.33 LTS stream.
- **Dependencies and build tooling updated**, including Vue 3.5.43 in the bundled console, the Quarkus LangChain4j BOM
  1.13.3 in the Quarkus sample app, GraalVM Native Build Tools 1.1.14, Vitest 5.0.1, jsdom 30.1.1, Prettier 3.9.8, and
  the patched `undici` 7.30.0 and `brace-expansion` transitive dependencies.
- **The Pentesting advisor no longer duplicates Quarkus Security rules and catches weaker CSPs** (77 checks, down
  from 79). `PT-A05-070` (Quarkus CORS configuration) and `PT-A05-072` (Quarkus TLS with plaintext HTTP) are retired
  because the Security panel's `QS-CORS-001`/`QS-CORS-002` and `QS-TLS-001` already review that configuration on every
  Quarkus application; `PT-A05-072` also ignored the `client-auth=required` default. `PT-A07-006` now reviews Spring
  issuer URIs only, leaving `quarkus.oidc.auth-server-url` to `QS-TLS-004`, and Quarkus A07 coverage reads `HANDOFF`.
  The synthetic CORS preflight still exercises Quarkus's global CORS filter. `PT-A05-060` now reports plain `data:`,
  `http:`, or `https:` script sources (MEDIUM) and an enforced CSP that restricts no scripts, such as a
  `frame-ancestors`-only policy (LOW). `PT-A05-043` is MEDIUM only when the management listener binds more broadly
  than a narrowed `server.address`, which Spring Boot does not inherit, and LOW otherwise. `PT-A05-011` rates an
  unversioned `Server` header INFO ([#1166](https://github.com/jdubois/boot-ui/pull/1166),
  [Pentesting checks](docs/PENTEST-CHECKS.md#pentesting-advisor-audit-2026)).

### Removed

These removals ship with BootUI 2.0.0, from the `v2` branch ([PLAN-v2.md](docs/PLAN-v2.md) §4.3, M4-16).

- **`io.github.jdubois.bootui.spi.TraceIdProvider` is removed; use `CorrelationContextProvider`.** Every recorder and
  capture point now reads the trace id of the work it records from the adapter's `CorrelationContextProvider`, which
  also carries BootUI's request and execution ids, so the separate `setTraceIdProvider(...)` setters on
  `SqlTraceRecorder`, `RestClientTraceRecorder`, `CacheActivityRecorder`, `FaultToleranceEventRecorder`, `EmailStore`,
  and `EmailCaptureService` are gone too. **Migration:** an application or extension that installed its own trace id
  source calls `setCorrelationContextProvider(...)` instead, returning, for example,
  `BootUiCorrelation.current().withTrace(traceId, spanId)`. With no provider installed, recorders keep reading the
  SLF4J MDC `traceId` key, as before; OpenTelemetry on Spring WebFlux and Quarkus is wired by BootUI itself.
- **The Live Activity persistence poller is removed, together with `bootui.activity.persistence.capture-interval`.**
  Durable history (`bootui.activity.persistence.enabled=true`) is now written only by the runtime journal's subscriber,
  which stores every recorded batch once instead of re-reading the panel buffers every 2 seconds and missing entries
  above about 100 events per second. It does so whatever `bootui.activity.feed-source` the panel reads. **Migration:**
  remove `bootui.activity.persistence.capture-interval`, which is now ignored, and keep the runtime journal enabled
  (the default): with `bootui.runtime-journal.enabled=false`, persistence logs a warning and writes nothing
  ([Runtime journal](docs/PROPERTIES.md#runtime-journal)).

### Fixed

- **A task its handler waited for is no longer badged "after response".** With the BootUI agent, Live Activity's
  **after response** badge and the request profile's **Handoffs** compared the task's run end with the response, and
  the JDK releases a waiting handler before that run returns, so on virtual threads about one waited-for `FutureTask`
  in twenty under load read as finishing after its response. Both now use the task body's own completion, as Runtime
  Insights does, plus I/O, a failure, or 50 ms of work its result-publication tail had after the response. A handler
  released from inside the task's body, as by `DeferredResult.setResult`, can still race it
  ([Java Agent](docs/features/java-agent.md#accepted-limits)).
- **Malformed MCP envelopes answer the same on every stack.** A `null`, numeric, or object `method` or tool name, a
  repeated `MCP-Protocol-Version`, and a version header sent with an oversized or batch body now get the same
  documented client error on Spring and Quarkus; `MCP-Protocol-Version: 2026-07-28` without `_meta` is now `-32602`
  with the request id rather than `-32600` with a `null` one ([#1340](https://github.com/jdubois/boot-ui/issues/1340)).
- **Links between MCP and CLI results.** `get_runtime_impact` resolves a bare method name such as `applyDiscount`
  (`AMBIGUOUS` with each declaring class when several do), names the real overloads when the asked parameters match
  none, and names every route beyond the 8 it lists per list; `get_code_inventory` matches method names. Side Effects
  captures an event-loop connect a concurrent REST client call names: the journal's newest-first order made it index
  only one event per refresh, on all three stacks. Live Activity `EXCEPTION` entries carry `exceptionGroupId`, which
  `get_exception_detail` takes. A `get_runtime_insights` route query lists that route first and follows it up;
  `get_side_effects` matches a request id; `get_live_memory` and `get_jvm_tuning` return their own panel's part.
- **The runtime journal records the sample applications' logs.** Its Spring appender skipped every logger under
  `io.github.jdubois.bootui`, the samples' included; it now skips only BootUI's own packages, as on Quarkus.
- **Side Effects sensors no longer hide each other's records.** A connect a file system provider makes inside a file
  operation now records, and one sensor's many distinct paths or frames no longer leave another's targets unknown
  ([Java Agent](docs/features/java-agent.md#side-effects), M5-5d).
- **The `caught-exceptions` sensor stays opt-in.** On the caught benchmark route its own share measured 2.9 % then
  5.0 % (15 pairs each), over its 3 % budget once; the cumulative overhead with it was 5.9 %
  ([Java Agent](docs/features/java-agent.md#the-caught-exceptions-sensor)).
- **The `files` sensor stays opt-in.** On the I/O benchmark route its own share is a 2.3 % median (15 pairs), but the
  default sensors plus `files` reach 10.6 % (9 pairs), over the 10 % budget ([Java Agent](docs/features/java-agent.md#the-files-sensor)).
- **Code Paths keeps recording on a thread after a deep stack overflow.** An application's runaway recursion through
  timed methods could overflow the stack a second time while the agent bridge was resetting the thread after the first
  overflow. The thread then stayed counted inside a call that had already returned. On a pooled thread that could stop
  every later request on it from recording, or leave an abandoned fragment collecting its calls. The next timed method
  to return on the thread now resets it again, and the dropped fragment is counted under `abandonedFragments`
  ([Java Agent](docs/features/java-agent.md#the-code-paths-sensor), PLAN-v2 M5-4a).
- **One framework warning per message, not per failed request.** Runtime Insights' `framework-warnings-by-route`
  grouped events by their exact message, so a framework that writes the request path and a per-request id into the
  message, as Quarkus's error handler does (`HTTP Request to /api/records failed, error id: …`), listed each failed
  request as a row of its own. Messages that differ only by a UUID, a long hexadecimal id, or a numeric path segment are
  now one group, quoted with `<id>` and `<n>` in place of those parts, on every stack; the row's id no longer changes
  with each run. ([PLAN-v2.md](docs/PLAN-v2.md) M4-20)

- **A Spring Modulith application starts with BootUI.** BootUI's application event multicaster, which records
  application events in the runtime journal, claimed the context's `applicationEventMulticaster` bean name before
  Spring Modulith's event publication registry, whose own definition then failed the application's start with a
  `BeanDefinitionOverrideException`. BootUI now installs it from an auto-configuration of its own, ordered after Spring
  Modulith's and every other auto-configuration, and backs off from any multicaster the application or a library
  defines. Then it records no application event, and says so: `transactional-listener-skipped` and
  `after-commit-writes` report `UNAVAILABLE` with the reason, Runtime Insights, change impact, and the run comparison
  name it among their limitations, and the run's comparability facts leave the `app-event` source out, on Spring MVC
  and Spring WebFlux ([Runtime Insights](docs/features/overview.md#runtime-insights), PLAN-v2 §5.18, M4-8).
- **Runtime Insights no longer judges a request that lost events to eviction or a clear.** A request or execution that
  started before an event the runtime journal evicted, could not fit, or cleared (**Clear recording** while it ran) was
  projected with only the events it kept, so `proxy-bypass` reported a `@Cacheable` method as bypassed when the
  request's cache access was the event it lost. Such work is now left out whole, with a limitation counting it and
  each check that would have examined it saying so, on Spring MVC, Spring WebFlux, and Quarkus; the agent view no
  longer asks for traffic when requests were only left out. A partial check's reason no longer calls its counts a
  floor, since a dropped transaction or cache access can make a finding appear. A cross-observation counterexample harness now replays every
  observation kind's seeded case and counterexamples against every kind, with each event dropped in turn, the
  recording cleared and the ring overflowing at every point, and each stack, SQL capture, source, and panel missing;
  every kind passes it, including D29's four, which the default list therefore shows, and a container's `ERROR`
  written after a request left out this way is not counted in Framework warnings' **No request** row
  ([Runtime Insights](docs/features/overview.md#runtime-insights),
  PLAN-v2 §2.2, M4-18e).
- **A method probe reported active catches a class loaded right after.** A probe was marked active just before its
  transformer was registered, so a class its run loaded in that gap ran unprobed, and the probe waited for an
  invocation that had already happened. The transformer is now registered first, and its advice records nothing until
  the probe is active (PLAN-v2 §5.14, M5-8).
- **The BootUI agent skips the application's own Byte Buddy.** Packaging the agent relocated the `net.bytebuddy.`
  prefix in its exclusion list to the agent's shaded package, so the published jar excluded only its own copy of Byte
  Buddy and could instrument the one an application ships with Mockito or Hibernate. The prefix is now built at run time,
  and a test checks it in the packaged jar (PLAN-v2 §5.13).
- **Code Paths overhead under load.** With more than 512 request trees open, as under sustained load, the engine settled
  the eldest one tree at a time, reading the whole runtime journal once per request on BootUI's drain thread: 15 % of
  the process's CPU in a profile of the sample under the agent overhead benchmark's load. The eldest quarter now settle
  together, in one journal read: the agent overhead benchmark's median went from 16.0 % to 4.1 % with the default
  sensors on a four-processor CI runner, and the `code-paths` sensor stays on by default
  ([Java agent](docs/features/java-agent.md#the-code-paths-sensor), PLAN-v2 §5.13, M5-13).
- **Runtime Insights write evidence and plan accuracy.** On Quarkus, `safe-method-dml` labels Hibernate statements as
  preparations, separate from timed JDBC executions of the same SQL shape; the evidence and limitations no longer claim
  a prepared write ran. The v2 plan now describes persisted `METADATA_ONLY` reads and the in-progress Code Paths, Code
  Inventory, and agent evidence work accurately ([#1240](https://github.com/jdubois/boot-ui/pull/1240);
  PLAN-v2 §§5.5, 5.14, 5.15, 5.17, 8; FIN2-01–03).
- **Code Inventory after reload.** Work retaining an old application object across a DevTools restart or Quarkus
  live reload no longer marks the changed method in the new run executed, or attributes its first hit to the new
  run. Defining-loader tokens stay stable across retransformation, and hit flags belong to one run, so old advice
  cannot satisfy `changed-code-not-executed` or `verify_after_change`. Ignored reflection and serialization loaders no
  longer consume the bounded token pool, and dead loaders' slots are safely recycled. Capacity failures stay
  **not tracked**, rather than falsely **never executed** ([#1247](https://github.com/jdubois/boot-ui/pull/1247);
  M52-01; PLAN-v2 §5.15, §5.17).
- **Code Inventory and Code Paths honor a disabled HTTP Exchanges panel.** Code Inventory, its API, and
  `get_code_inventory` no longer show the first request and route that ran a method, and `changed-code-not-executed`
  names no route, while HTTP Exchanges is disabled; Code Paths, its API, `get_code_paths`, and the handler split of
  `route-time-breakdown` are unavailable with that reason, on Spring MVC, Spring WebFlux, and Quarkus.
- **Code Inventory no longer reports methods as removed when a class root could not be read.** A class directory or
  jar the scan cannot open or walk now counts as skipped, as a class file it cannot parse already did, and makes the
  scan partial (failed when nothing could be read) instead of complete.
- **Runtime Insights write attribution and remote calls.** Anonymous access reports Quarkus Hibernate SQL as an
  unverified preparation instead of a proven table write; hidden Hibernate evidence cannot promote it. Runtime model,
  change impact, and run comparison attribute DML writes only to exact lexical targets rather than read-side tables;
  older run summaries do not compare incompatible table edges. Transactions held across captured AI calls are detected
  alongside REST calls without double-counting a nested transport call
  ([Runtime Insights](docs/features/overview.md#runtime-insights); PLAN-v2 §§5.4, 5.5, 5.9; follow-up to #1230).
- **Clear recording and trace-only AI route attribution.** Runtime-journal offers now stamp and enqueue atomically
  against **Clear recording**, so an application event cannot be offered after a clear returns with the previous
  recording's generation and then disappear. AI calls imported with only a trace id now use the same bounded,
  ambiguity-aware request attribution for route child counts, time, and tokens as for runtime-model edges, including
  late-request reclaim without double counting (PLAN-v2 §5.2, M3-3c, M4-11).

- **Quarkus worker resource attribution.** A Quarkus REST worker or virtual thread whose response body outlives its
  chain — a `File` or `Path` response, which Quarkus streams after the chain is done — now stops being metered for
  the request as soon as Quarkus completes that request on it, instead of staying charged to it for the whole
  transfer. Whatever the thread did back in its pool in between — unrelated work, or a task an agent propagated for
  another request — is no longer added to the finished request's CPU time and allocation, and its
  `bootui.ExecutionSegment` interval no longer covers that window, so **Profile resources** stops joining those JFR
  samples and hot frames to the wrong route. An ordinary response was already attributed correctly, because the
  worker writes it itself and the request is taken inline on that thread. A suspended chain — a blocking method
  returning a `Uni` or a `CompletionStage`, a `Multi`, SSE — releases its worker at a point Quarkus 3.33 exposes no
  hook for, and stays attributed to that worker until the request is taken (PLAN-v2 §5.11, D17;
  [Runtime Insights](docs/features/overview.md#runtime-insights)).

- **Runtime Insights completeness and zero-ORM comparisons.** Drops of scheduled, messaging, and WebSocket
  completion events now mark observations that examine those executions partial, while disabled optional evidence
  does not. Collection and Code Inventory checks do not count unrelated execution drops.
  A drop refreshes cached coverage and findings even before another event is dispatched. Run comparison
  includes Hibernate flush counts changing to or from zero when both runs recorded the ORM source,
  with an explicit capture-listener caveat when a run recorded no sessions; legacy summaries keep the conservative
  event-presence fallback (follow-up to [#1222](https://github.com/jdubois/boot-ui/pull/1222),
  [#1225](https://github.com/jdubois/boot-ui/pull/1225), and
  [#1228](https://github.com/jdubois/boot-ui/pull/1228); PLAN-v2 §5.5, §5.8).

- **Java agent verification and early task publication.** Core executor and supported thread hooks must positively
  pass their self-tests: a timeout, interruption, or probe error without hook hits disables the sensor for that claim.
  `PROPAGATED` is unavailable while the executor self-test is pending or unverified. A task that publishes its own
  result just before its body returns now uses the same 2 ms response-clock slack as a nested promise, avoiding
  false `work-after-response` evidence ([#1233](https://github.com/jdubois/boot-ui/pull/1233),
  [#1223](https://github.com/jdubois/boot-ui/pull/1223); PLAN-v2 M5-2, D32).

- **Source-panel policy gaps.** While HTTP Exchanges is disabled, a request's journal profile (panel and
  `get_request_profile`) is unavailable, Runtime Insights lists no route as **Not exercised in this run**, and
  **Profile resources** lists no per-route row; each says why. On Quarkus, Live Activity no longer adds Security Logs
  principals, exception messages, email details, or buffered requests and SQL while their panel is disabled. Durable
  history no longer shows a stored principal under `METADATA_ONLY`, and its search no longer matches text masked or
  withheld on read, on Spring MVC, Spring WebFlux, and Quarkus ([Live Activity](docs/features/overview.md#durable-history), PLAN-v2 §8).

- **Work after the response.** Follow-up to [#1218](https://github.com/jdubois/boot-ui/pull/1218):
  task-body completion restores fast late-starting tasks and earlier SQL followed by long-running
  computation, without counting a waited-for task's delayed handoff close. Result-publication tails remain visible
  and I/O uses the actual response boundary. Promise-signalling runnables and explicitly early-completed fork/join
  tasks keep their own body-return markers (PLAN-v2 M5-2b, D32).

- **Runtime Insights error and connection evidence.** A recovered retry or fallback no longer hides unrelated errors
  in a successful request. Connections held together now use the known pool maximum and the corrected first possible
  hold-and-wait concurrency estimate. Exception checks follow captured subclasses and causes rather than only the
  top-level wrapper ([Runtime Insights](docs/features/overview.md#runtime-insights), PLAN-v2 §5.5).
- **Runtime Insights agent list.** `requests` counts completed HTTP exchanges only: zero is not proof the run was idle
  when an observation names a request or execution, retained scheduled runs or consumed messages, or evicted events
  say otherwise. A run-level observation with no exemplar, such as heap growth after one collection, does not. The
  default agent list includes latency rows and omits only an insufficient repeated SELECT under 50 ms of summed
  measured time that ran fewer than 10 times in any one request; a limitation names how many were left out, and
  `query=repeated-selects` returns them. A sufficient finding, including a local-database N+1, stays. Repeated-selects
  evidence names the phase and whether the repeats ran in a transaction, and says when the total is unmeasured or a
  parent result size was not recorded.

- **Runtime journal and persisted Live Activity bounds.** Oversized evidence no longer exceeds the configured
  byte budget; SQL events identify their named data source even with connection recording
  off; per-request SELECT tracking is capped and uses the same literal-free fingerprints for live and persisted N+1
  badges, replacing the least frequent shape when full so a later repeated SELECT remains detectable; and persisted
  activity pages scan past rows hidden by a disabled panel
  while keeping a continuation cursor (PLAN-v2 §5.2, §8; [Live Activity](docs/features/overview.md#durable-history);
  follow-up to #1216).

- **The Java agent's self-test checks every hook on its own, and its report matches what runs.** A thread pool's
  `addWorker` and work-queue keys, `CompletableFuture`'s supply and run stages, and platform and virtual thread runs
  are self-tested separately, so a missing hook no longer passes on a sibling's count. The panel says whether each
  sensor is active for this application's claim, and the `PROPAGATED` tier is withheld when the claim does not use
  `executors`. Sensors report their install, self-test, and cumulative install-and-release times; the **Class transformation** card
  sums them across every sensor. A request profile never attributes work at a tier it reports unavailable, and the
  `JAVA_TOOL_OPTIONS` snippet quotes a jar path that contains spaces
  ([Java Agent](docs/features/java-agent.md), PLAN-v2 §5.13).

- **Duplicate `X-Content-Type-Options` on streamed BootUI responses.** On Spring MVC with Spring Security, a host
  header writer racing the response commit (for example the log-tail SSE stream) could add `nosniff` twice. The
  security-headers response wrapper is now synchronized and drops identical repeated baseline values.
- **Runtime Insights and change impact stay truthful with sparse or restricted evidence.** Scheduled jobs and consumed
  messages can show observations without an HTTP request. Change impact counts route traffic across the whole run
  after journal eviction, narrows an explicitly named handler method to its own mappings, and excludes disabled
  source panels' evidence from its model and suggestions. Route-count overflow marks unclassified routes as
  undetermined, and disabled-source limitations appear only when relevant evidence was recorded
  ([#1217](https://github.com/jdubois/boot-ui/pull/1217); PLAN-v2 M4-18b).
- **Runtime Insights source-panel follow-ups.** Checks no longer report an empty evaluation after a unit they
  examine is hidden; only its disabled opening panel is named, and HTTP-only checks do not blame hidden jobs.
  Dropped HTTP events count once even when HTTP is a required source. Quarkus does not claim that an
  unverified prepared write executed when Hibernate evidence is hidden, and trace-only AI calls owned by hidden
  requests no longer survive as uncorrelated coverage ([#1217](https://github.com/jdubois/boot-ui/pull/1217)).
- **Runtime Insights after Clear recording.** Clearing the journal or freeing BootUI memory now refreshes the
  report and its evidence at once instead of serving the cleared events until a new one arrives, and no route's first
  post-clear request is labeled cold. The evidence table follows each auto-refresh of the open observation, and
  `gc-inflated-latency` leaves each route's cold first request out of its slowest tenth (PLAN-v2 §5.5, M3-3a, M4-3).

- **Runtime Insights and Live Activity UI.** Load failures in Change impact, Run comparison, Profile resources,
  Why-slow, and observation evidence show their message instead of a JSON object (including in the screen-reader
  status region). `work-after-response` observations appear under the Time chip, the command palette finds Runtime
  Insights by "what changed", "impact", and "compare", Live Activity's runtime-journal feed gains a **Run id** filter,
  and the request drawer no longer shows or copies the previous row's profile when a second row is opened while the
  first is still loading.

- **Runtime Insights tells an unavailable panel from a disabled one.** An observation whose evidence belongs to a panel
  this application cannot serve, such as Security Logs on a Quarkus application without
  `quarkus.security.events.enabled`, now names what would make it available instead of reporting the panel as disabled
  or the evidence as insufficient. The panel's evidence stays out of the projection either way
  ([Runtime Insights](docs/features/overview.md#runtime-insights), PLAN-v2 §5.5).

- **Quarkus reports application-event publications as unrecorded.** Quarkus records which observers ran but not who
  fired the event, so a change impact that reaches code only through an event now says so in its limitations, rather
  than implying the Spring adapters' `PUBLISHES` edge exists there ([Quarkus support](docs/QUARKUS-SUPPORT.md),
  PLAN-v2 §5.18).

- **A cleared correlation scope holds on Quarkus.** Work run deliberately outside a request, such as a managed task
  taken from a snapshot with no request, is no longer re-correlated by the Vert.x duplicated context it happens to run
  on, and closing any correlation scope restores the request the thread was being metered for instead of stopping its
  measurement (PLAN-v2 §5.1).

- **`bootui.runtime-insights.ai-token-threshold` is validated on Quarkus.** A zero, negative, or unreadable value now
  fails at startup with the same message as on Spring, instead of being silently replaced by the default
  ([Properties](docs/PROPERTIES.md), PLAN-v2 §5.5).

- **Retained request and execution profiles.** Live Activity displays the runtime-journal timeline even after an
  HTTP exchange leaves the shorter buffer. `get_request_profile` and `bootui request-profile` open journal requests,
  scheduled runs, and consumed-message executions first; their result names the selected source and falls back to the
  HTTP-exchange profile when necessary. Missing ids identify both retention windows (PLAN-v2 M2-9b, M3-7).

- **Runtime observation accuracy (OBS-01, OBS-02, OBS-08).** Proxy bypass no longer judges `@Cacheable(sync = true)`
  or condition-dependent cache methods as bypasses when Spring legitimately records no preceding cache access.
  Anonymous writes identify each captured DML target, including JDBC batch previews, not tables read by INSERT … SELECT,
  subqueries, or UPDATE … FROM; ambiguous multi-table forms stay visible as labelled lexical candidates, not proven
  writes. Truncated batch literals no longer hide later previews; truncation, uncertain comments, and DELETE … USING
  never produce exact write claims. Possible batch truncation is explicit. The anonymous-access documentation now describes intended public
  writes and unproven anonymity honestly
  ([Runtime Insights](docs/features/overview.md#runtime-insights), PLAN-v2 M4-12, M4-13).

- **Live Activity under durable storage no longer offers a source it ignores or an empty-state it contradicts.** The
  **Recorded by** selector is withheld, with the same note as the journal-only filters, while persisted history serves
  the feed, and no `source` is sent, because stored rows are always journal-rendered. A persisted page whose rows are all
  hidden now reads "No visible rows on this page" beside **Load older activity** instead of "No activity recorded yet".
  The prompt inventory now names all four MCP prompts including `verify_after_change`, the runtime journal page says the
  BootUI agent propagates raw executors and `CompletableFuture`, and PLAN-v2 §8 and M4-18 match the shipped panel gating
  and review fixes ([Live Activity](docs/features/overview.md#durable-history), PLAN-v2 §8, M4-18).
- **Code Inventory and Code Paths honor a disabled HTTP Exchanges panel.** Code Inventory, its API, and
  `get_code_inventory` no longer show the first request and route that ran a method, and `changed-code-not-executed`
  names no route, while HTTP Exchanges is disabled; Code Paths, its API, `get_code_paths`, and the handler split of
  `route-time-breakdown` are unavailable with that reason, on Spring MVC, Spring WebFlux, and Quarkus.
- **Code Inventory no longer reports methods as removed when a class root could not be read.** A class directory or
  jar the scan cannot open or walk now counts as skipped, as a class file it cannot parse already did, and makes the
  scan partial (failed when nothing could be read) instead of complete.
- **Runtime Insights write attribution and remote calls.** Anonymous access reports Quarkus Hibernate SQL as an
  unverified preparation instead of a proven table write; hidden Hibernate evidence cannot promote it. Runtime model,
  change impact, and run comparison attribute DML writes only to exact lexical targets rather than read-side tables;
  older run summaries do not compare incompatible table edges. Transactions held across captured AI calls are detected
  alongside REST calls without double-counting a nested transport call
  ([Runtime Insights](docs/features/overview.md#runtime-insights); PLAN-v2 §§5.4, 5.5, 5.9; follow-up to #1230).
- **Clear recording and trace-only AI route attribution.** Runtime-journal offers now stamp and enqueue atomically
  against **Clear recording**, so an application event cannot be offered after a clear returns with the previous
  recording's generation and then disappear. AI calls imported with only a trace id now use the same bounded,
  ambiguity-aware request attribution for route child counts, time, and tokens as for runtime-model edges, including
  late-request reclaim without double counting (PLAN-v2 §5.2, M3-3c, M4-11). ([#1244](https://github.com/jdubois/boot-ui/pull/1244))

- **Quarkus worker resource attribution.** A Quarkus REST worker or virtual thread whose response body outlives its
  chain — a `File` or `Path` response, which Quarkus streams after the chain is done — now stops being metered for
  the request as soon as Quarkus completes that request on it, instead of staying charged to it for the whole
  transfer. Whatever the thread did back in its pool in between — unrelated work, or a task an agent propagated for
  another request — is no longer added to the finished request's CPU time and allocation, and its
  `bootui.ExecutionSegment` interval no longer covers that window, so **Profile resources** stops joining those JFR
  samples and hot frames to the wrong route. An ordinary response was already attributed correctly, because the
  worker writes it itself and the request is taken inline on that thread. A suspended chain — a blocking method
  returning a `Uni` or a `CompletionStage`, a `Multi`, SSE — releases its worker at a point Quarkus 3.33 exposes no
  hook for, and stays attributed to that worker until the request is taken (PLAN-v2 §5.11, D17;
  [Runtime Insights](docs/features/overview.md#runtime-insights)). ([#1239](https://github.com/jdubois/boot-ui/pull/1239))

- **Runtime Insights completeness and zero-ORM comparisons.** Drops of scheduled, messaging, and WebSocket
  completion events now mark observations that examine those executions partial, while disabled optional evidence
  does not. Collection and Code Inventory checks do not count unrelated execution drops.
  A drop refreshes cached coverage and findings even before another event is dispatched. Run comparison
  includes Hibernate flush counts changing to or from zero when both runs recorded the ORM source,
  with an explicit capture-listener caveat when a run recorded no sessions; legacy summaries keep the conservative
  event-presence fallback (follow-up to [#1222](https://github.com/jdubois/boot-ui/pull/1222),
  [#1225](https://github.com/jdubois/boot-ui/pull/1225), and
  [#1228](https://github.com/jdubois/boot-ui/pull/1228); PLAN-v2 §5.5, §5.8).

- **Java agent verification and early task publication.** Core executor and supported thread hooks must positively
  pass their self-tests: a timeout, interruption, or probe error without hook hits disables the sensor for that claim.
  `PROPAGATED` is unavailable while the executor self-test is pending or unverified. A task that publishes its own
  result just before its body returns now uses the same 2 ms response-clock slack as a nested promise, avoiding
  false `work-after-response` evidence ([#1233](https://github.com/jdubois/boot-ui/pull/1233),
  [#1223](https://github.com/jdubois/boot-ui/pull/1223); PLAN-v2 M5-2, D32).

- **Source-panel policy gaps.** While HTTP Exchanges is disabled, a request's journal profile (panel and
  `get_request_profile`) is unavailable, Runtime Insights lists no route as **Not exercised in this run**, and
  **Profile resources** lists no per-route row; each says why. On Quarkus, Live Activity no longer adds Security Logs
  principals, exception messages, email details, or buffered requests and SQL while their panel is disabled. Durable
  history no longer shows a stored principal under `METADATA_ONLY`, and its search no longer matches text masked or
  withheld on read, on Spring MVC, Spring WebFlux, and Quarkus ([Live Activity](docs/features/overview.md#durable-history), PLAN-v2 §8). ([#1237](https://github.com/jdubois/boot-ui/pull/1237))

- **Work after the response.** Follow-up to [#1218](https://github.com/jdubois/boot-ui/pull/1218):
  task-body completion restores fast late-starting tasks and earlier SQL followed by long-running
  computation, without counting a waited-for task's delayed handoff close. Result-publication tails remain visible
  and I/O uses the actual response boundary. Promise-signalling runnables and explicitly early-completed fork/join
  tasks keep their own body-return markers (PLAN-v2 M5-2b, D32).

- **Runtime Insights error and connection evidence.** A recovered retry or fallback no longer hides unrelated errors
  in a successful request. Connections held together now use the known pool maximum and the corrected first possible
  hold-and-wait concurrency estimate. Exception checks follow captured subclasses and causes rather than only the
  top-level wrapper ([Runtime Insights](docs/features/overview.md#runtime-insights), PLAN-v2 §5.5). ([#1234](https://github.com/jdubois/boot-ui/pull/1234))
- **Runtime Insights agent list.** `requests` counts completed HTTP exchanges only: zero is not proof the run was idle
  when an observation names a request or execution, retained scheduled runs or consumed messages, or evicted events
  say otherwise. A run-level observation with no exemplar, such as heap growth after one collection, does not. The
  default agent list includes latency rows and omits only an insufficient repeated SELECT under 50 ms of summed
  measured time that ran fewer than 10 times in any one request; a limitation names how many were left out, and
  `query=repeated-selects` returns them. A sufficient finding, including a local-database N+1, stays. Repeated-selects
  evidence names the phase and whether the repeats ran in a transaction, and says when the total is unmeasured or a
  parent result size was not recorded. ([#1229](https://github.com/jdubois/boot-ui/pull/1229))

- **Runtime journal and persisted Live Activity bounds.** Oversized evidence no longer exceeds the configured
  byte budget; SQL events identify their named data source even with connection recording
  off; per-request SELECT tracking is capped and uses the same literal-free fingerprints for live and persisted N+1
  badges, replacing the least frequent shape when full so a later repeated SELECT remains detectable; and persisted
  activity pages scan past rows hidden by a disabled panel
  while keeping a continuation cursor (PLAN-v2 §5.2, §8; [Live Activity](docs/features/overview.md#durable-history);
  follow-up to #1216).

- **The Java agent's self-test checks every hook on its own, and its report matches what runs.** A thread pool's
  `addWorker` and work-queue keys, `CompletableFuture`'s supply and run stages, and platform and virtual thread runs
  are self-tested separately, so a missing hook no longer passes on a sibling's count. The panel says whether each
  sensor is active for this application's claim, and the `PROPAGATED` tier is withheld when the claim does not use
  `executors`. Sensors report their install, self-test, and cumulative install-and-release times; the **Class transformation** card
  sums them across every sensor. A request profile never attributes work at a tier it reports unavailable, and the
  `JAVA_TOOL_OPTIONS` snippet quotes a jar path that contains spaces
  ([Java Agent](docs/features/java-agent.md), PLAN-v2 §5.13). ([#1233](https://github.com/jdubois/boot-ui/pull/1233), [#1243](https://github.com/jdubois/boot-ui/pull/1243))

- **Duplicate `X-Content-Type-Options` on streamed BootUI responses.** On Spring MVC with Spring Security, a host
  header writer racing the response commit (for example the log-tail SSE stream) could add `nosniff` twice. The
  security-headers response wrapper is now synchronized and drops identical repeated baseline values. ([#1232](https://github.com/jdubois/boot-ui/pull/1232))
- **Runtime Insights and change impact stay truthful with sparse or restricted evidence.** Scheduled jobs and consumed
  messages can show observations without an HTTP request. Change impact counts route traffic across the whole run
  after journal eviction, narrows an explicitly named handler method to its own mappings, and excludes disabled
  source panels' evidence from its model and suggestions. Route-count overflow marks unclassified routes as
  undetermined, and disabled-source limitations appear only when relevant evidence was recorded
  ([#1217](https://github.com/jdubois/boot-ui/pull/1217); PLAN-v2 M4-18b).
- **Runtime Insights source-panel follow-ups.** Checks no longer report an empty evaluation after a unit they
  examine is hidden; only its disabled opening panel is named, and HTTP-only checks do not blame hidden jobs.
  Dropped HTTP events count once even when HTTP is a required source. Quarkus does not claim that an
  unverified prepared write executed when Hibernate evidence is hidden, and trace-only AI calls owned by hidden
  requests no longer survive as uncorrelated coverage ([#1217](https://github.com/jdubois/boot-ui/pull/1217)).
- **Runtime Insights after Clear recording.** Clearing the journal or freeing BootUI memory now refreshes the
  report and its evidence at once instead of serving the cleared events until a new one arrives, and no route's first
  post-clear request is labeled cold. The evidence table follows each auto-refresh of the open observation, and
  `gc-inflated-latency` leaves each route's cold first request out of its slowest tenth (PLAN-v2 §5.5, M3-3a, M4-3). ([#1228](https://github.com/jdubois/boot-ui/pull/1228))

- **Runtime Insights and Live Activity UI.** Load failures in Change impact, Run comparison, Profile resources,
  Why-slow, and observation evidence show their message instead of a JSON object (including in the screen-reader
  status region). `work-after-response` observations appear under the Time chip, the command palette finds Runtime
  Insights by "what changed", "impact", and "compare", Live Activity's runtime-journal feed gains a **Run id** filter,
  and the request drawer no longer shows or copies the previous row's profile when a second row is opened while the
  first is still loading. ([#1227](https://github.com/jdubois/boot-ui/pull/1227))

- **Runtime Insights tells an unavailable panel from a disabled one.** An observation whose evidence belongs to a panel
  this application cannot serve, such as Security Logs on a Quarkus application without
  `quarkus.security.events.enabled`, now names what would make it available instead of reporting the panel as disabled
  or the evidence as insufficient. The panel's evidence stays out of the projection either way
  ([Runtime Insights](docs/features/overview.md#runtime-insights), PLAN-v2 §5.5). ([#1224](https://github.com/jdubois/boot-ui/pull/1224))

- **Quarkus reports application-event publications as unrecorded.** Quarkus records which observers ran but not who
  fired the event, so a change impact that reaches code only through an event now says so in its limitations, rather
  than implying the Spring adapters' `PUBLISHES` edge exists there ([Quarkus support](docs/QUARKUS-SUPPORT.md),
  PLAN-v2 §5.18). ([#1224](https://github.com/jdubois/boot-ui/pull/1224))

- **A cleared correlation scope holds on Quarkus.** Work run deliberately outside a request, such as a managed task
  taken from a snapshot with no request, is no longer re-correlated by the Vert.x duplicated context it happens to run
  on, and closing any correlation scope restores the request the thread was being metered for instead of stopping its
  measurement (PLAN-v2 §5.1). ([#1224](https://github.com/jdubois/boot-ui/pull/1224))

- **`bootui.runtime-insights.ai-token-threshold` is validated on Quarkus.** A zero, negative, or unreadable value now
  fails at startup with the same message as on Spring, instead of being silently replaced by the default
  ([Properties](docs/PROPERTIES.md), PLAN-v2 §5.5). ([#1224](https://github.com/jdubois/boot-ui/pull/1224))

- **Retained request and execution profiles.** Live Activity displays the runtime-journal timeline even after an
  HTTP exchange leaves the shorter buffer. `get_request_profile` and `bootui request-profile` open journal requests,
  scheduled runs, and consumed-message executions first; their result names the selected source and falls back to the
  HTTP-exchange profile when necessary. Missing ids identify both retention windows (PLAN-v2 M2-9b, M3-7).

- **Runtime observation accuracy (OBS-01, OBS-02, OBS-08).** Proxy bypass no longer judges `@Cacheable(sync = true)`
  or condition-dependent cache methods as bypasses when Spring legitimately records no preceding cache access.
  Anonymous writes identify each captured DML target, including JDBC batch previews, not tables read by INSERT … SELECT,
  subqueries, or UPDATE … FROM; ambiguous multi-table forms stay visible as labelled lexical candidates, not proven
  writes. Truncated batch literals no longer hide later previews; truncation, uncertain comments, and DELETE … USING
  never produce exact write claims. Possible batch truncation is explicit. The anonymous-access documentation now describes intended public
  writes and unproven anonymity honestly
  ([Runtime Insights](docs/features/overview.md#runtime-insights), PLAN-v2 M4-12, M4-13). ([#1230](https://github.com/jdubois/boot-ui/pull/1230))

- **Late runtime-journal events keep their request attribution.** AI exports, managed-executor work, SQL, exceptions,
  connection releases, authorization decisions, and ORM sessions that finish after the HTTP response now update the
  completed request's route aggregates without counting the request twice. The completed-request attribution ledger is
  bounded and reports expiry explicitly. The application's own GenAI spans imported through the OTLP receiver now
  reach the journal even though the receiver runs as BootUI work, while BootUI's own traces and other services' spans
  in the aggregator topology stay out. Trace-only AI edges now use the same unique request-window rule as Live Activity
  and request profiles, including events received before their HTTP anchor and ambiguous traces shared by overlapping
  requests; a call that no request of its trace spans is counted apart and is not reported as a comparison limitation
  ([#1235](https://github.com/jdubois/boot-ui/pull/1235);
  PLAN-v2 M2-2, M3-3c, M3-9, M4-1).
- **Java agent claim handoffs preserve request ownership.** Overlapping submissions of the same task across restarts
  stay unowned rather than taking a newer claim's snapshot. Immediate reclaim cancels queued executor/thread sensor
  removal or reinstalls the sensor after an in-flight reset, restoring thread subclasses even when the new claim
  names different packages. Rejected direct fork/join tasks, including already-completed tasks, and failed
  `CompletableFuture` thread-per-task starts release their snapshots without treating `invoke`'s accepted task failure
  as a rejection; executor skip counters stop when the sensor is off
  ([Java Agent](docs/features/java-agent.md#claims-and-lifecycle),
  [#1213](https://github.com/jdubois/boot-ui/pull/1213); AGT-01, AGT-02, AGT-04, AGT-09).

- **Profile resources joins segments closed by another thread.** A request segment still open when its request was
  taken from another thread, as on Quarkus where the response closes a worker's segment from the event loop, now commits
  its JFR event with its own thread's id, so the worker's CPU and allocation samples join the route instead of counting
  as outside any request. ([#1211](https://github.com/jdubois/boot-ui/pull/1211))
- **`work-after-response` no longer reports a task its handler waited for.** The handler resumes as soon as the task
  sets its result, before the agent closes the task's handoff, so under load that handoff could end just after the
  response. Now only SQL, REST, and message work that ended at least two milliseconds after the response started is
  counted, which absorbs the millisecond precision of recorded event starts. A task's failure still counts by its own
  end ([#1218](https://github.com/jdubois/boot-ui/pull/1218)).

- **Runtime Insights times AI calls once and reports what it could not count.** `route-time-breakdown` no longer
  subtracts an AI call's time from the handler when its model HTTP call was already counted as REST client or SQL time:
  calls reported by Spring AI or Quarkus LangChain4j carry their monotonic completion and are placed on the request's
  clock as **AI calls**, and tool and retrieval operations, which wrap application code, stay in the handler. A call
  known only from a GenAI span is placed by its wall-clock start, with a limitation, and adds only its time inside the
  handler that no placed call covers, so a query made before it no longer shrinks its AI time.
  `transaction-across-remote-call` reports a method once one of its remote calls took 20 ms or more, naming its median
  and slowest call, so a slow call among fast ones is no longer dropped. A method whose calls were all faster is not
  shown, not even as **Needs more traffic**, and the check's reason counts it.
  `lazy-sql-after-handler` counts requests whose response-phase SQL cannot be placed against their transactions apart,
  with a limitation and a check reason, instead of dropping them, and `split-transaction-writes` names its uncounted
  requests in its check reason too. `ai-usage-by-route` reports the tier its calls were actually linked by, and
  mentions trace-id linking only for calls recovered from GenAI spans
  ([#1219](https://github.com/jdubois/boot-ui/pull/1219)).

- **Spring WebFlux requests report their GraphQL operation and authentication time again.** The reactive correlation
  filter never began a request's phase markers, so the shared GraphQL operation and Spring Security authentication
  observation handlers had nothing to record into on WebFlux: Live Activity and Runtime Insights showed every GraphQL
  request as the plain `/graphql` route instead of one route per operation, and `route-time-breakdown` reported no
  authentication time. The filter now begins the request's marker timeline before the rest of the chain is assembled
  and ends it when the chain terminates, including cancellation, and passes what was recorded to the journal. WebFlux
  still marks no handler or response phase, so those offsets stay unknown rather than guessed, and the breakdown names
  the authentication time out of the request's unattributed time: a WebFlux route is insufficient only when neither a
  recorded call nor authentication time names any of its time
  ([#1214](https://github.com/jdubois/boot-ui/pull/1214)).

- **Run comparison reports comparable work, not changes in instrumentation.** SQL, REST, AI, cache, exception, and
  runtime-model edge changes require their sources in both runs. Scheduled jobs and consumed messages compare beside
  routes, disappeared fingerprints are listed when bounded evidence permits it, and no eligible work reports
  `INSUFFICIENT`, never `COMPARED` or an `EVALUATED` check. Eligibility follows each check's unit: heap-growth checks
  run when they examine collections, even with zero requests, and stable heaps explain what was examined. The UI
  labels insufficient checks **Not enough evidence**. Allocation uses the median; summary codec v10 reads v8/v9
  without inventing missing medians or execution history. Restart cost requires adjacent in-memory restarts, and
  Quarkus explicitly reports that its lifecycle hook supplies no complete reload total. History that cannot survive
  reload reports `UNAVAILABLE` without a usable baseline. `bootui insights compare` and its MCP tool default to the
  previous run, including an idle one. Comparison also honors source-panel policy: disabled panels' facts, root
  executions, and edges are hidden with an explicit limitation, each messaging broker is gated independently, and
  hidden HTTP totals are labelled hidden rather than zero traffic. SQL groups are literal-free display shapes under
  every exposure mode, including in kept runs and the baseline file; legacy fingerprints are sanitized on read,
  and masking-indistinguishable groups retain summed counts and histograms
  ([#1222](https://github.com/jdubois/boot-ui/pull/1222),
  [Run comparison](docs/PLAN-v2.md#58-run-comparison--runtime-insights--delivered), CMP-02/04/05/06/07, M4-18b, C15-1).

- **Runtime Insights no longer reports what it could not see.** From the 2.0 validation run
  ([report](docs/V2-VALIDATION-REPORT.md)): `route-time-breakdown` stops calling time "application code" when a request
  reached no handler BootUI marks, such as an Actuator or `/q/` endpoint or a request the security filters answered with
  401 or 403: such requests are left out of their route's phases, a route made mostly of them is insufficient with its
  recorded calls, and on WebFlux a route with no recorded call is insufficient instead of one unattributed span.
  Kafka sends, timed until the broker's asynchronous acknowledgement, are no longer counted as **Message sends**; only
  RabbitMQ and JMS sends are. Checks that read SQL report the new `UNAVAILABLE` status with the reason where this
  application's SQL is not recorded, as with R2DBC, instead of `EVALUATED` with nothing found, on Spring MVC, Spring
  WebFlux, and Quarkus. **Not exercised in this run** keeps the routes of applications in `io.quarkus.*` packages,
  lists Spring WebFlux routes, and says when the declared routes could not be read. `lazy-sql-after-handler` names a
  query a view ran while rendering, such as through a Thymeleaf formatter, and advises loading it in the handler, with
  the application frame as the call site; frames of applications in `org.springframework.samples` or
  `io.quarkus.sample` count as application code. The `errors-behind-2xx` sentence reads "2 requests whose transaction
  rolled back", and insufficient findings are labelled **Not enough evidence** (PLAN-v2 M4-18a).
- **Live Activity says when nothing has been recorded yet.** An empty feed with no filter, search, or toggle narrowing
  it said "No activity matches the current filters"; it now says no activity is recorded yet and how to produce some,
  keeping the filter message for a feed a filter narrowed. `orm-auto-flush` applies its threshold per request, as the
  plan sets it, so one request that auto-flushed three times or more, or for a fifth of its ORM time, reports its route
  instead of waiting for a second one ([report](docs/V2-VALIDATION-REPORT.md), PLAN-v2 M4-18c).
- **The Mappings panel lists Spring WebFlux routes.** The Actuator-backed provider read only Spring MVC's
  `dispatcherServlets`, so a WebFlux application showed no mapping and, without OpenTelemetry, grouped its requests by
  masked paths. It now also reads WebFlux's `dispatcherHandlers`: annotated controllers, and functional routes whose
  predicate names one method and one path ([WebFlux support](docs/WEBFLUX-SUPPORT.md)).
- **Quarkus HTTP and exception capture can no longer fail a request after its response.** When a worker or virtual
  thread ended the response, the HTTP exchange capture read the response headers while the event loop could still be
  changing them. The read intermittently threw `NullPointerException` or `NoSuchElementException`, and Quarkus then
  logged an ERROR for the application's URL that the Exceptions panel and Live Activity recorded as an application
  failure. Off the event loop, the capture now copies the response headers just before Vert.x writes them. Both the
  HTTP exchange and exception capture filters now catch their own failures and log a warning under BootUI's own logger,
  which the Exceptions panel ignores ([#1203](https://github.com/jdubois/boot-ui/pull/1203)).
- **Quarkus apps with OpenTelemetry logs and Dev Services start again with BootUI.** Adding `bootui-quarkus` to an
  application that enables `quarkus.otel.logs.enabled` and starts Compose or datasource Dev Services stopped dev and
  test mode with a build-step `Cycle detected` error, because BootUI fed the Dev Services results into the CDI bean
  container, which the OpenTelemetry log handler needs before logging is set up. BootUI now records the Dev Services
  snapshot without touching the bean container; the Dev Services panel shows the same services and stays unavailable
  when none started ([Dev Services on Quarkus](docs/QUARKUS-SUPPORT.md),
  [#1204](https://github.com/jdubois/boot-ui/pull/1204)).
- **`ARCH-SPRING-004` no longer reports a self-call that only joins the caller's transaction.** A method that already
  runs in a transaction, declared on the method or the class, can call a `@Transactional` method of the same bean
  whose `REQUIRED`, `SUPPORTS` or `MANDATORY` propagation would only join that transaction. That call is no longer
  reported at HIGH. A private helper counts as transactional when every caller in its class is. The call stays
  reported when the caller may run without a transaction, the callee starts or suspends a transaction, the transaction
  manager, rollback rules, isolation or timeout differ, the callee also carries another proxy annotation, or the call
  is written inside a lambda or a `try` block ([Architecture checks](docs/ARCHITECTURE-CHECKS.md#arch-spring-004---beans-should-not-self-invoke-their-own-proxied-methods),
  [#1176](https://github.com/jdubois/boot-ui/issues/1176)).
- **DB-HIB-007 no longer reports "enforcement is unknown" for ordinary PostgreSQL foreign keys.** Enforcement was
  only recorded for `NOT VALID` constraints, so every validated foreign key matching a `@ManyToOne` produced a
  diagnostic and left the Database advisor scan `PARTIAL`. A foreign key absent from a complete, untruncated
  `NOT VALID` catalog read is now known to be validated and enforced; a failed or truncated read still leaves it
  unknown ([#1174](https://github.com/jdubois/boot-ui/issues/1174)).
- **Vulnerabilities no longer scans test-only libraries listed in a CycloneDX SBOM.** The CycloneDX Gradle plugin
  lists test-classpath libraries by default, marked `cdx:maven:package:test=true`; Spring MVC and WebFlux took them as
  application dependencies, so a test-only `freemarker` or a newer test-only `jackson-databind` was reported vulnerable
  although no such JAR shipped. Components marked that way, or with CycloneDX `scope: "excluded"`, and the components
  nested in them, are now left out of the inventory unless the archive census finds their JAR on the classpath, so a
  mislabeled SBOM still cannot hide a shipped library ([#1177](https://github.com/jdubois/boot-ui/issues/1177)).
- **ARCH-SPRING-001 no longer reports Kotlin constructor injection as field injection.** Kotlin copies an annotation
  such as `@Value` or `@Autowired` written on a primary-constructor property onto the backing field as well, so
  `class Foo(@Value("\${key}") private val key: String)` was reported as field injection. A field in a Kotlin class
  is now skipped when a constructor parameter of the same type carries the identical annotation; `@Autowired lateinit
  var` and annotated class-body properties are still reported
  ([Architecture checks](docs/ARCHITECTURE-CHECKS.md#arch-spring-001---classes-should-not-use-field-injection),
  [#1175](https://github.com/jdubois/boot-ui/issues/1175)).
- **The REST API advisor reads Quarkus REST `@ResponseStatus` and `@ResponseHeader`.** A `@POST @ResponseStatus(201)`
  creation method is no longer reported as using the default status, and a declared `Location` or `Retry-After`
  header satisfies `RAPI-RESP-008` and `RAPI-ERR-007`. Versioned `/v3/...` API handlers are no longer mistaken for
  springdoc's `/v3/api-docs` and excluded from the versioning rules ([#1168](https://github.com/jdubois/boot-ui/pull/1168)).
- **The Spring advisor no longer penalizes DevTools' development defaults or valid enum spellings.** While a DevTools
  restart is active, DevTools sets `spring.web.error.include-message`, `include-binding-errors` and
  `include-stacktrace` to `always`, which made SPRING-WEB-004 report three MEDIUM findings on every IDE run. Those
  defaults are now ignored like BootUI's own Actuator defaults; values the application configures are still reported.
  SPRING-WEB-004 and SPRING-MGMT-003 also accept every spelling Boot's lenient enum binding accepts, such as
  `ON_PARAM` or `whenauthorized`, instead of reporting an analysis error
  ([#1164](https://github.com/jdubois/boot-ui/pull/1164)).
- **Fewer Architecture advisor false positives.** `ARCH-CODE-013` ignores classes compiled into `target/test-classes`
  or `build/classes/*/test`, which are on the classpath under `spring-boot:test-run` or `bootTestRun`.
  `ARCH-SPRING-008` no longer reports services throwing `ResponseStatusException` or other web exception types.
  `ARCH-CODE-015` no longer asks `@Bean` or CDI producer holders and composed stereotypes such as `@AutoConfiguration` to
  become final utility classes. `ARCH-MOD-001` reports each internal-package access with its own description and source
  line instead of repeating one class-level line, and `ARCH-SPRING-022` now says that Quarkus 3 also ignores
  `javax.transaction.Transactional` ([#1165](https://github.com/jdubois/boot-ui/pull/1165)).

- **Database advisor false positives and hidden findings.** DB-SCHEMA-001 no longer reports the one-row identifier
  tables Hibernate (`<entity>_seq` with a single `next_val` column, the MySQL default for `GenerationType.AUTO`) and
  Spring Batch (`BATCH_*_SEQ`) generate without a primary key. DB-SCHEMA-002 no longer lets an unrelated GIN, partial
  or generic-JDBC index on the same table turn every foreign key into an unknown result. DB-PG-002 treats a sequence
  that was never read, on a role allowed to read it, as unused rather than unknown, so a fresh development database no
  longer scans `PARTIAL`. Learn-more links now point to MySQL 8.4, the PostgreSQL primary/foreign-key docs and the
  Jakarta Persistence 3.2 specification instead of blog posts and Wikipedia
  ([Database checks](docs/DATABASE-ADVISOR-CHECKS.md), [#1169](https://github.com/jdubois/boot-ui/pull/1169)).

- **Quarkus Vulnerabilities coverage is no longer reported complete when the dependency model is missing or damaged.**
  A missing or blank build-time model, a malformed entry, or a runtime JAR coordinate the build step could not encode
  now reports `UNAVAILABLE` coverage instead of `COMPLETE`, so the Known-findings score is qualified rather than
  presented as covering the whole application ([#1163](https://github.com/jdubois/boot-ui/pull/1163)).

- **Live Activity durable persistence stores a failed or slow entry once, including a slow `4xx` request.**
  Persistence remembers the entries it stored in a bounded window. An entry that newer entries pushed out of Spring
  MVC's capped stream and that came back later, for example once `bootui.free-on-idle` released captured SQL, could be
  stored a second time. Failed and slow entries are now remembered in a second window per kind of entry, which routine
  entries and other kinds never displace, so a failing scheduled job cannot make persistence forget a failed request.
  Requests, statements, and REST calls are recognized by the rule and threshold of the failure-preserving buffer that
  keeps them: `5xx` and slow requests, failed and slow statements, and failed, `4xx`/`5xx`, and slow REST calls. A `4xx`
  request that reached `bootui.activity.request-slow-threshold-ms`, shown as `WARN`, therefore counts as slow, and on
  Spring MVC, Spring WebFlux, and Quarkus the configured threshold drives both the buffer and persistence. Severities
  are unchanged
  ([Failure-preserving retention](docs/features/diagnostics.md#failure-preserving-retention)).
- **Log Tail and Dev Services container logs follow the value-exposure policy.** Log messages were returned exactly
  as captured on every surface, and Spring's Dev Services container logs verbatim, so a logged password assignment was
  shown in full under the default `MASKED` mode. Both now apply the rule exception messages already follow, through
  one shared engine helper: secret-like `key=value` and `key: value` assignments are masked under `MASKED`, text is
  omitted under `METADATA_ONLY`, and it is verbatim only under `FULL` or with `bootui.mask-secrets=false`. Log Tail
  applies it when a line is read, so the recent snapshot, the SSE stream and its replayed backlog, `get_log_tail`, and
  `bootui logs tail` are covered on Spring MVC, Spring WebFlux, and Quarkus, and a runtime exposure change applies to
  retained lines and open streams without a restart. Container logs are masked before the tail is cut and are not read
  at all under `METADATA_ONLY`. `LogLineDto.message` and `DevServiceLogReport.logs` are now nullable, and the additive
  `messageOmitted` and `logsOmitted` flags let the Log Tail and Dev Services panels say a message was omitted by policy
  instead of showing an empty line. Exception messages are unchanged.
- **Log Tail streams no longer do exposure or encoding work on application logging threads.** Spring WebFlux and
  Quarkus now hand each captured line to dedicated delivery threads, as Spring MVC already did. A line logged on one of
  those threads is never captured, and WebFlux serializes each line there rather than leaving it to Spring's encoder,
  so a stream can no longer feed its own log output, such as framework debug logging, back to itself. Like Spring MVC,
  a WebFlux or Quarkus client that falls 1,000 lines behind is disconnected and reconnects, instead of buffering
  without bound, and a stream always releases its slot and subscription, even when its delivery task is rejected.
- **An invalid `bootui.expose-values` or `bootui.mask-secrets` value is reported once rather than on every read, on
  Spring and Quarkus.** On Quarkus an unrecognized `bootui.mask-secrets` value such as a typo now keeps masking on,
  instead of being converted to `false`, and `bootui.expose-values=metadata-only` is accepted for `METADATA_ONLY`, as
  Spring's relaxed binding already did.
- **SQL Trace and REST Client show call sites for the sample apps.** BootUI skipped the whole
  `io.github.jdubois.bootui` namespace when looking for the application frame that issued a statement or an
  outbound call. Because the Spring MVC, Spring WebFlux, and Quarkus sample apps live under it, their call sites were
  always empty, including in statement rankings, N+1 groups, and Live Activity. Only BootUI's own module packages are
  now skipped, and a test fails if a new BootUI package is added without being classified.
- **Live Activity durable persistence works on MySQL and Oracle.** On MySQL, every read used the SQL-standard
  `OFFSET … FETCH FIRST` row limit, which MySQL rejects, so the Live Activity panel and `GET /bootui/api/activity`
  failed once persistence was on, while rows kept piling up unread. On Oracle, the table could never be created,
  because Oracle has no `BIGINT` type. The store now detects the database once and uses `LIMIT` on MySQL and MariaDB
  and `NUMBER(19)` columns on Oracle. Other databases keep the same SQL, so existing tables need no migration. The
  **Use the existing datasource** switch now also checks that the table can be read before it switches, so a
  database that rejects the query is reported as a failed switch instead of breaking the panel. This applies to
  Spring MVC, Spring WebFlux, and Quarkus
  ([#1142](https://github.com/jdubois/boot-ui/issues/1142)).
- **Spring MVC Log Tail streams no longer throw on a worker thread when a client disconnects or the application
  stops.** When the servlet container had already failed the async request, the stream worker still tried to
  complete the `SseEmitter`. Tomcat rejected that with an uncaught `IllegalStateException`, and the session could
  stay registered. The container's completion, timeout, or error callback now cancels any pending completion, and a
  concurrent rejection no longer prevents the session from being released.

### Security

- **Every runtime-journal source now follows its panel's policy.** The sources 2.0 added — `authorization`, `orm`,
  `websocket`, and `agent.executors` — and `connection` were owned by no panel, so their evidence was still recorded
  and served through Live Activity, request profiles, Runtime Insights, and the MCP tools and CLI commands over them
  while the panel that publishes it (Security Logs, Hibernate, WebSockets, Java Agent, SQL Trace) was disabled. Runtime
  Insights additionally ignored `security`, `cache`, `messaging`, `scheduled`, and `mail`. One mapping,
  `JournalSourcePanels`, now names the owning panel of every source for all three surfaces on Spring MVC, Spring
  WebFlux, and Quarkus, exhaustively, so a new source cannot be added without declaring its panel. A disabled panel's
  events are left out of the Runtime Insights projection, every observation reading that source is `NOT_APPLICABLE`
  with the panel named, and one reading it as optional evidence says the evidence is not counted — including when only
  one broker's panel (`kafka`, `rabbitmq`, `jms`) is disabled. A unit of work is left out whole when the panel owning
  the event that opens it is disabled, since that event names the route, destination, and status the panel publishes.
  One projection reads each panel's state once, so a panel toggled while it runs cannot make a recorded source read as
  absent. The running agent handoffs Live Activity synthesizes now require the Java Agent panel, and an observation
  that treats a source as optional evidence reads "recorded **and** visible", so a disabled panel can no longer read as
  proof that nothing happened and produce a false finding.

- **The AI Framework chat detail now follows the value-exposure policy.** `GET /bootui/api/ai/chats/{spanId}` returned
  the chat span's attributes and events verbatim in every mode, so captured prompts, completions, input and output
  messages, sensitive attributes, and `exception.message` and `exception.stacktrace` text were shown raw even under the
  default `MASKED`. Every chat detail read now applies the live `bootui.expose-values` / `bootui.mask-secrets` policy
  through the same rule as the Traces detail: content is scrubbed of secret-like assignments under `MASKED`, omitted as
  `null` under `METADATA_ONLY`, and verbatim only under `FULL`, while keys, types, token counts, models, and timings are
  unchanged. Tool call arguments and results (`gen_ai.tool.call.*`, `spring.ai.tool.call.*`), vector query content and
  returned documents (`db.vector.query.content`, `db.vector.query.response.documents`), and indexed
  `gen_ai.prompt.*` / `gen_ai.completion.*` content are now treated as free-form text on the Traces detail and request
  profile too. Applies on Spring MVC, Spring WebFlux, and Quarkus, including after a runtime change of the mode
  ([AI Framework value exposure](docs/features/services.md#ai-framework-value-exposure),
  [#1210](https://github.com/jdubois/boot-ui/pull/1210)).
- **Journal-rendered SQL and log text now follows the value-exposure policy.** Live Activity rows, KPI strip,
  request journal profiles, and Runtime Insights sentences and evidence rendered from the runtime journal showed SQL
  literals, concatenated log messages, and `;name=value` path parameters as recorded in every mode, through the UI, the
  REST API, `get_live_activity`, `get_runtime_insights`, `get_runtime_insight`, and their `bootui` CLI commands, and
  the opt-in `bootui_activity` history stored them raw. Every read now applies the live `bootui.expose-values` /
  `bootui.mask-secrets` policy, so a change from `FULL` to `MASKED` or `METADATA_ONLY` applies to the next read and the
  Runtime Insights cache is keyed on it: SQL is shown as its literal-free shape, log messages and path parameters are
  masked, and `METADATA_ONLY` omits log messages. Durable history is written at least as masked as `MASKED`, and stored
  rows, including those written before this change, are masked again under the live mode on read, keeping their
  structural label but dropping free text under `METADATA_ONLY`, and are read only while the panel that owns them is
  enabled. Runtime Insights quote a statement's literal-free shape, never its grouping fingerprint, which kept MySQL
  double-quoted strings and a truncated dollar quote verbatim. Applies on Spring MVC, Spring WebFlux, and Quarkus
  ([Live Activity safety](docs/features/overview.md#safety-and-limits), PLAN-v2 §8,
  [#1216](https://github.com/jdubois/boot-ui/pull/1216)).
- **Trace data now follows the value-exposure policy.** `GET /bootui/api/traces/{id}`, the trace embedded in the
  per-request profile (`GET /bootui/api/activity/request/{id}`), and their `get_request_profile` MCP tool and
  `bootui request-profile` projections returned span status messages, `exception.message` and `exception.stacktrace`
  event attributes, URLs, and header values verbatim in every mode, so a secret in an exception message that the
  Exceptions panel masked was still shown raw. Spans are still stored as captured, and every read now applies the live
  `bootui.expose-values` / `bootui.mask-secrets` policy: free-form text uses the exception message rule (masked under
  `MASKED`, omitted as `null` under `METADATA_ONLY`), URLs use the HTTP Exchanges URI rule, sensitive header and
  attribute values are masked, and bound parameter and header values are omitted under `METADATA_ONLY`. Keys, types,
  names, ids, and timings are unchanged, and `FULL` shows values verbatim, except URL user-info, which BootUI never
  shows. Applies on Spring MVC, Spring WebFlux, and Quarkus, including after a runtime change of the mode
  ([Trace value exposure](docs/features/diagnostics.md#trace-value-exposure),
  [#1205](https://github.com/jdubois/boot-ui/pull/1205)).
- **Log, exception, and container-log masking now covers the credential after an authorization scheme.** Under the
  default `bootui.expose-values=MASKED`, the shared rule masked only the first word after a secret-like key, so
  `Authorization: Bearer <token>` hid the word `Bearer` and showed the token. The credential is now masked and the
  key and scheme stay visible: `Authorization: Bearer ******`, `"authorization": "Basic ******"`, or
  `Proxy-Authorization: Digest ******`, including every parameter of a Digest, OAuth, or AWS signature credential and
  every value of the `Authorization=[Basic ..., Bearer ...]` and `Authorization:"Bearer ..."` forms that header maps
  print. After an `authorization` key, a scheme BootUI does not recognize is masked together with its credential, and
  after any other secret-like key a scheme is always masked together with its credential. A credential after a bare
  `Bearer`, `Basic`, `Negotiate`, or `NTLM` is masked even with no key before it when its shape shows it is one, as in
  `sending Bearer ******`, while prose such as `missing Bearer token` or `Basic auth is enabled` is unchanged. This
  changes Log Tail messages on every surface, exception messages, and Spring Dev Services container logs alike, on
  Spring MVC, Spring WebFlux, and Quarkus. Any other secret-like key whose value does not start with a scheme is
  masked exactly as before, and `METADATA_ONLY`, `FULL`, and `bootui.mask-secrets=false` are unchanged
  ([Log message exposure](docs/features/diagnostics.md#log-message-exposure), follows
  [#1150](https://github.com/jdubois/boot-ui/pull/1150)).

## [1.20.0] - 2026-10-05

BootUI 1.20.0 makes request evidence easier to hand to agents and easier to trust in the UI. It adds richer request
profiles, route rankings, code locations, broader database support, advisor audits, stronger vulnerability reporting,
and stricter value exposure.

### Added

- **Agent-ready request profiles and Copy for AI.** MCP, CLI, REST, Live Activity, and Exceptions now export the same
  masked profile, with Copy for AI and REST client/cache evidence included
  ([#1192](https://github.com/jdubois/boot-ui/pull/1192), [#1148](https://github.com/jdubois/boot-ui/pull/1148)).
- **Route rankings and finding locations.** HTTP Exchanges ranks retained routes by traffic, latency, and errors, while
  advisor findings show copyable source locations ([#1152](https://github.com/jdubois/boot-ui/pull/1152),
  [#1149](https://github.com/jdubois/boot-ui/pull/1149)).
- **Failure-preserving retention.** HTTP Exchanges, SQL Trace, REST Client, and durable Live Activity persistence now
  reserve capacity for failed and slow records by default ([#1153](https://github.com/jdubois/boot-ui/pull/1153),
  [#1154](https://github.com/jdubois/boot-ui/pull/1154)).
- **MySQL and MariaDB reach.** The MySQL panel now reads every Oracle MySQL version and best-effort MariaDB behind MySQL
  Connector/J ([#1191](https://github.com/jdubois/boot-ui/pull/1191),
  [#1194](https://github.com/jdubois/boot-ui/pull/1194)).
- **New REST API, Architecture, and Database rules.** Runtime-breaking handlers, ignored injection/lifecycle
  annotations, and schema or Hibernate mapping risks gained checks
  ([#1168](https://github.com/jdubois/boot-ui/pull/1168), [#1165](https://github.com/jdubois/boot-ui/pull/1165),
  [#1169](https://github.com/jdubois/boot-ui/pull/1169)).

### Changed

- **Advisor catalog audits.** Spring, CRaC, Hibernate, GraalVM, Security, Quarkus, Memory, and Pentesting audits added
  checks, retired noisy rules with IDs preserved, and recalibrated severities
  ([#1164](https://github.com/jdubois/boot-ui/pull/1164), [#1170](https://github.com/jdubois/boot-ui/pull/1170),
  [#1172](https://github.com/jdubois/boot-ui/pull/1172), [#1171](https://github.com/jdubois/boot-ui/pull/1171),
  [#1173](https://github.com/jdubois/boot-ui/pull/1173), [#1167](https://github.com/jdubois/boot-ui/pull/1167),
  [#1162](https://github.com/jdubois/boot-ui/pull/1162), [#1166](https://github.com/jdubois/boot-ui/pull/1166)).
- **Vulnerabilities severity.** CVSS v4.0 now drives severity when present, and OpenSSF malicious-package advisories are
  CRITICAL with removal guidance ([#1163](https://github.com/jdubois/boot-ui/pull/1163)).
- **Shared request defaults.** WebFlux and Quarkus now honor the 1,000 ms slow threshold, `0` disables slow everywhere,
  and Spring stops recording BootUI's own requests while self-exclusion is on
  ([#1153](https://github.com/jdubois/boot-ui/pull/1153)).
- **Consistent profile and route evidence.** All adapters use the shared profile assembler, one slowest-request KPI, and
  consistent route labels ([#1148](https://github.com/jdubois/boot-ui/pull/1148),
  [#1152](https://github.com/jdubois/boot-ui/pull/1152)).
- **Release artifacts and dependencies.** Maven Central releases publish placeholder Javadoc jars, Quarkus moves to
  3.33.3.3, and notable frontend/build dependencies were refreshed
  ([#1190](https://github.com/jdubois/boot-ui/pull/1190), [#1135](https://github.com/jdubois/boot-ui/pull/1135)).

### Fixed

- **Quarkus runtime safety.** HTTP and exception capture can no longer fail requests after response end, and
  OpenTelemetry logs with Dev Services start again ([#1203](https://github.com/jdubois/boot-ui/pull/1203),
  [#1204](https://github.com/jdubois/boot-ui/pull/1204)).
- **Advisor and vulnerability accuracy.** Fixes reduce false positives, ignore test-only SBOM libraries, and avoid
  overclaiming damaged Quarkus dependency models ([#1178](https://github.com/jdubois/boot-ui/pull/1178),
  [#1179](https://github.com/jdubois/boot-ui/pull/1179), [#1180](https://github.com/jdubois/boot-ui/pull/1180),
  [#1181](https://github.com/jdubois/boot-ui/pull/1181), [#1163](https://github.com/jdubois/boot-ui/pull/1163),
  [#1164](https://github.com/jdubois/boot-ui/pull/1164), [#1168](https://github.com/jdubois/boot-ui/pull/1168),
  [#1169](https://github.com/jdubois/boot-ui/pull/1169)).
- **Persistence and diagnostics reliability.** Live Activity persistence works on MySQL and Oracle, sample-app call
  sites appear, and Log Tail streams release resources correctly ([#1144](https://github.com/jdubois/boot-ui/pull/1144),
  [#1143](https://github.com/jdubois/boot-ui/pull/1143), [#1120](https://github.com/jdubois/boot-ui/pull/1120),
  [#1150](https://github.com/jdubois/boot-ui/pull/1150)).

### Security

- **Value-exposure policy.** AI chat details, traces, request profiles, Log Tail, and Dev Services logs now apply the
  live exposure policy instead of exposing raw captured values ([#1210](https://github.com/jdubois/boot-ui/pull/1210),
  [#1205](https://github.com/jdubois/boot-ui/pull/1205), [#1150](https://github.com/jdubois/boot-ui/pull/1150)).
- **Authorization masking.** Log, exception, and container-log masking now hides credentials after Authorization-like
  schemes while preserving harmless scheme names ([#1150](https://github.com/jdubois/boot-ui/pull/1150)).

## [1.19.0] - 2026-09-25

Maintenance release focused on safer, more accurate diagnostics: JVM values are masked consistently, SQL timings gain
microsecond precision, advisor partial scans explain missing evidence, and several false positives are reduced.

### Changed

- **Advisor severity declarations match emitted results.** HIB-QUERY-007 and SEC-CORS-006 now declare the severities
  they already emitted, so catalog metadata aligns with findings and scores stay unchanged
  ([#1098](https://github.com/jdubois/boot-ui/pull/1098), [#1100](https://github.com/jdubois/boot-ui/issues/1100)).

### Fixed

- **JVM argument secrets are masked.** JVM Tuning and Live Memory now apply the exposure policy across Spring MVC,
  WebFlux, Quarkus, REST, MCP, and CLI output, including OnError commands and METADATA_ONLY JVM options
  ([#1113](https://github.com/jdubois/boot-ui/issues/1113)).
- **Hibernate Advisor partial scans are explainable.** Reports now include bounded diagnostics and coverage notes, and
  the Hibernate and Database panels show missing evidence without hiding affected rules
  ([#1086](https://github.com/jdubois/boot-ui/issues/1086)).
- **SQL Trace keeps sub-millisecond work visible.** Statement durations and aggregates now use microseconds, while the
  slow-query threshold keeps its millisecond semantics ([#1093](https://github.com/jdubois/boot-ui/issues/1093)).
- **Generated-code and Spring-executor checks are quieter.** OpenAPI `ApiUtil` and Spring-owned executors no longer
  produce misleading findings ([#1085](https://github.com/jdubois/boot-ui/issues/1085),
  [#1083](https://github.com/jdubois/boot-ui/issues/1083)).
- **Hibernate and database advisor unknowns are reduced.** STRING enums, default joins, PostgreSQL unique indexes,
  duplicate/special indexes, UUID foreign keys, and named index unknowns are assessed more accurately
  ([#1090](https://github.com/jdubois/boot-ui/issues/1090), [#1087](https://github.com/jdubois/boot-ui/issues/1087),
  [#1088](https://github.com/jdubois/boot-ui/issues/1088), [#1092](https://github.com/jdubois/boot-ui/issues/1092),
  [#1089](https://github.com/jdubois/boot-ui/issues/1089), [#1091](https://github.com/jdubois/boot-ui/issues/1091)).
- **Vulnerability coverage recognizes first-party Spring archives.** Application module JARs, exploded layer indexes,
  and `spring-boot-jarmode-tools` no longer make coverage look incomplete when identifiable locally
  ([#1084](https://github.com/jdubois/boot-ui/issues/1084)).

## [1.18.0] - 2026-09-21

Feature release adding PostgreSQL and MySQL operational diagnostics, retained advisor violation pages, and a Claude Code
plugin, with more accurate advisor checks, Pentesting dismissals, and Spring Boot archive coverage.

### Added

- **PostgreSQL and MySQL operational diagnostics.** Read-only panels and REST/MCP/CLI reports cover vital signs,
  sessions, statements, indexes, tables, replication, settings, partial evidence, comparisons, and a MySQL sample
  profile ([#1026](https://github.com/jdubois/boot-ui/pull/1026),
  [#1059](https://github.com/jdubois/boot-ui/pull/1059)).
- **Paginated advisor violation details.** Advisors expose retained per-rule details in the UI, REST, MCP, and CLI;
  retained details default to 10,000 per advisor, pages default to 100, and truncation is explicit
  ([#1037](https://github.com/jdubois/boot-ui/pull/1037)).
- **Claude Code plugin.** `/plugin install bootui@bootui` installs the BootUI skill and local MCP server registration,
  with `BOOTUI_MCP_URL` available to override the default endpoint
  ([#1068](https://github.com/jdubois/boot-ui/pull/1068)).

### Changed

- **Generated classes and database row caps are handled more clearly.** ARCH-CODE rules skip only positively identified
  generated sources, PostgreSQL limits have documented defaults, and PostgreSQL/MySQL cap-only results use local labels
  ([#1042](https://github.com/jdubois/boot-ui/pull/1042), [#1044](https://github.com/jdubois/boot-ui/pull/1044),
  [#1046](https://github.com/jdubois/boot-ui/pull/1046), [#1059](https://github.com/jdubois/boot-ui/pull/1059)).

### Fixed

- **Pentesting honors persisted dismissals.** Dismissed findings no longer affect active totals, severity bars, or
  Overview penalties across UI, REST, MCP, and CLI, and dismissal state survives restart
  ([#1040](https://github.com/jdubois/boot-ui/pull/1040), [#1041](https://github.com/jdubois/boot-ui/pull/1041)).
- **Advisor false positives are reduced.** Architecture, Hibernate, and Database checks better recognize repository
  transactions, date conversions, thread factories, bulk-update version maintenance, and view metadata
  ([#1039](https://github.com/jdubois/boot-ui/pull/1039), [#1038](https://github.com/jdubois/boot-ui/pull/1038),
  [#1043](https://github.com/jdubois/boot-ui/pull/1043), [#1023](https://github.com/jdubois/boot-ui/pull/1023),
  [#1035](https://github.com/jdubois/boot-ui/pull/1035)).
- **Runtime diagnostics report more honest state.** Vulnerability coverage for extracted Spring Boot archives, MySQL
  instrumentation and datasource discovery, and WebFlux HTTP/1.x rejections avoid incomplete coverage or stranded
  requests ([#1036](https://github.com/jdubois/boot-ui/pull/1036),
  [#1059](https://github.com/jdubois/boot-ui/pull/1059)).

## [1.17.0] - 2026-09-10

Feature release focused on evidence-led diagnostics and agent-guided assessment. It adds an application-assessment MCP
prompt, safer MCP client setup, a Hibernate bulk-update check, and broad advisor accuracy and evidence-quality fixes.

### Added

- **Hibernate detects bulk updates that skip optimistic-locking versions.** The new check reviews observed Spring Data
  JPQL/HQL updates on versioned entities and stays inapplicable when repository metadata is unavailable
  ([#1019](https://github.com/jdubois/boot-ui/pull/1019)).
- **Agents can request an application assessment before changing code.** The BootUI skill, MCP prompt, panel copy and
  guide collect bounded evidence, propose prioritized actions, and stop for approval before fixes
  ([#981](https://github.com/jdubois/boot-ui/pull/981), [#990](https://github.com/jdubois/boot-ui/pull/990)).
- **MCP setup now matches common clients.** The MCP Server panel and docs show VS Code, Claude Code, Cursor and generic
  snippets, plus an explicit bearer-header switch for agents outside loopback
  ([#928](https://github.com/jdubois/boot-ui/issues/928)).

### Changed

- **Advisor scores now reflect usable known findings instead of assumed coverage.** Partial evidence, scan notes,
  not-applicable scopes, GitHub security counts and overview badges now avoid fake passes or fake zeros
  ([#954](https://github.com/jdubois/boot-ui/issues/954), [#989](https://github.com/jdubois/boot-ui/issues/989)).
- **Advisor audits separate evidence gaps from clean results.** Database, Quarkus, GraalVM, Vulnerabilities, Pentesting
  and Security checks were corrected, with unsupported or low-signal rules retired where noted
  ([#977](https://github.com/jdubois/boot-ui/issues/977), [#959](https://github.com/jdubois/boot-ui/issues/959),
  [#958](https://github.com/jdubois/boot-ui/issues/958), [#978](https://github.com/jdubois/boot-ui/issues/978),
  [#961](https://github.com/jdubois/boot-ui/issues/961), [#965](https://github.com/jdubois/boot-ui/issues/965)).
- **More advisor catalogs preserve uncertainty instead of guessing.** Hibernate, Spring, REST API, Memory and JVM Tuning
  fixes keep contracts stable while retiring or skipping unsupported checks where the audit did
  ([#964](https://github.com/jdubois/boot-ui/issues/964), [#969](https://github.com/jdubois/boot-ui/issues/969),
  [#962](https://github.com/jdubois/boot-ui/issues/962), [#956](https://github.com/jdubois/boot-ui/issues/956),
  [#955](https://github.com/jdubois/boot-ui/issues/955)).
- **Configuration search and override suggestions understand relaxed property names.** Dotted, kebab-case and
  environment spellings match consistently, while the picker suggests bindable dotted keys
  ([#940](https://github.com/jdubois/boot-ui/issues/940), [#939](https://github.com/jdubois/boot-ui/issues/939),
  [#945](https://github.com/jdubois/boot-ui/issues/945)).
- **Console state persistence is documented for image rebuilds.** The Docker guidance explains how overrides and
  dismissed findings can survive rebuilds through `bootui.overrides-file`
  ([#930](https://github.com/jdubois/boot-ui/discussions/930)).

### Fixed

- **Overview and browser coverage handle incomplete scans honestly.** Cached reports from panels and agents are found
  without rescanning, stale `NOT_SCANNED` data is cleared, and partial reports keep findings visible
  ([#986](https://github.com/jdubois/boot-ui/issues/986), [#983](https://github.com/jdubois/boot-ui/issues/983)).
- **Panel navigation recovers after backend rebuilds.** Missing lazy assets now offer an explicit reload that preserves
  route intent and warns before discarding unsaved input ([#985](https://github.com/jdubois/boot-ui/issues/985)).
- **Spring evidence handling is less noisy.** Native scheduling observability, BootUI-wrapped caches, OSIV absence and
  not-applicable vendor checks no longer count as missing applicable evidence
  ([#989](https://github.com/jdubois/boot-ui/issues/989)).
- **Architecture, CRaC, Kotlin and Modulith checks avoid framework-language false positives.** Valid logger, scheduling,
  lifecycle, default-argument, `lateinit`, nested-exception and post-commit listener shapes are recognized
  ([#957](https://github.com/jdubois/boot-ui/issues/957), [#960](https://github.com/jdubois/boot-ui/issues/960),
  [#925](https://github.com/jdubois/boot-ui/issues/925), [#926](https://github.com/jdubois/boot-ui/issues/926)).
- **Security and pentest findings stop blaming unverified or BootUI-owned defaults.** Actuator protection is not
  inferred from filter descriptions, and BootUI's health-details default is ignored as host configuration
  ([#922](https://github.com/jdubois/boot-ui/issues/922), [#965](https://github.com/jdubois/boot-ui/issues/965), #923).
- **Wrapped datasources are discovered consistently.** Database Advisor and SQL Trace inspect pools behind Spring
  datasource proxies and report unreadable wrappers instead of going blind
  ([#924](https://github.com/jdubois/boot-ui/issues/924)).

## [1.16.0] - 2026-09-03

Feature release that takes BootUI diagnostics to terminals and CI through a new CLI, dependency-free client, and plain
REST command endpoint. It also fixes MCP error reporting, Spring DevTools status, and a Spring path-filter bypass.

### Added

- **A `bootui` CLI now exposes every diagnostic as a command.** The Maven Central/JBang tool renders tables or JSON,
  supports URL/token/timeout options and exits distinctly for transport, usage, disabled and read-only outcomes.
- **A command endpoint enabled by default projects MCP tools onto REST.** All three stacks expose live discovery and
  invocation behind existing local, host, CSRF, token, panel-policy, concurrency and timeout guards; `bootui-client`
  reuses it.
- **The engine catalog now drives the CLI and Command Line panel.** Users can see install commands, live endpoint
  metrics, refused commands and the current command catalog while the panel remains read-only.

### Changed

- **The DevTools panel is now Spring DevTools.** Only the display name changes; the panel id, route, properties and API
  contract stay the same, and Quarkus remains not applicable.

### Fixed

- **MCP and Spring DevTools failures degrade cleanly.** Refused MCP tool 4xx calls now report in-band client errors, and
  uninitialised Restarter status reads report restart unavailability instead of failing.

### Security

- **Spring safety filters match encoded and matrix-parameter BootUI paths.** Spring MVC and WebFlux now resolve paths
  like their handlers, so loopback, host, DNS-rebinding, CSRF, token, panel and security-header guards cannot be
  bypassed.

## [1.15.0] - 2026-08-27

Feature release adding Fault Tolerance and WebSockets panels, richer Metrics, SQL Trace, REST API, Cache, and theme
experiences, plus important masking, activation, Host-header, and input-bounding fixes.

### Added

- **Fault Tolerance and WebSockets panels.** All three stacks report declared fault-tolerance policies, bounded metadata
  events, WebSocket endpoints, live connections, STOMP subscriptions, and recent frame metadata without payload capture.
- **Metrics and Cache explain their sources.** Metrics gain provenance, grouped explanations, and filters, while Cache
  rows disclose implementation-described tiers and comparable native hit ratios without fabricating unavailable data.
- **HTTP and SQL diagnostics are easier to act on.** HTTP Exchanges can copy a safe cURL template, SQL Trace ranks
  retained statements by route, and DB-RUNTIME-001 flags changing SQL shapes as bounded evidence.
- **REST API error contracts are catalogued.** Declared handlers and Quarkus mappers appear in a pageable view, retained
  exceptions can link to exact handlers, and three evidence-based error-contract advisor rules were added.
- **Theme picker adds five opt-in skins.** Graphite, Minimal, Cyberpunk, France, and Windows 95 join light and dark,
  with explicit choice only, reduced-motion handling, and contrast gates (#878).

### Changed

- **Panel presentation, frontend loading, and docs were refined.** Shared surfaces, deferred chunks, site search,
  focused feature/setup pages, searchable catalogues, rebuilt navigation, and consent-only analytics were added (#875,
  #876).

### Fixed

- **Theme, REST API, and Quarkus diagnostics render correctly.** Theme menus are opaque, REST API load failures show the
  real reason, and custom Quarkus BootUI mounts stay out of HTTP Exchanges, Live Activity, and Exceptions (#857).
- **HTTP Probe input is bounded before forwarding.** Method, path, body, header count, names, values, and total header
  size are checked in UTF-8 bytes on all three adapters, with canonical 400 errors for over-limit input (#860).
- **Core DTO collections are defensively copied.** Published reports stay stable for readers and keep deterministic
  Jackson 3/Jackson 2 JSON ordering (#855).

### Security

- **Deactivated Spring MVC and WebFlux apps no longer serve the packaged console.** The reserved BootUI namespace now
  returns 404 for static assets just like Quarkus, including encoded and relocatable-path variants (#856).
- **URI credentials and REST-client transport errors are sanitized.** User-info is always removed, sensitive query,
  matrix, and fragment values follow policy, and nested credential URLs are masked in free-text errors (#858).
- **Malformed Host headers and opaque Origins fail closed.** Strict authority parsing rejects smuggled or invalid Host
  values, and serialized `Origin: null` is treated as no concrete host on state-changing requests (#859).
- **Malformed HTTP Probe input cannot exceed its byte budget.** Unpaired surrogates are charged at the worst-case UTF-8
  replacement size so invalid input cannot bypass the configured limits.

## [1.14.1] - 2026-08-20

Patch release that restores Spring native and Quarkus Docker startup, fixes the native-image build bootstrap on AMD64,
and strengthens release verification against the artifacts consumers actually download.

### Fixed

- **The packaged BootUI shell is no longer reachable on Spring MVC or Spring WebFlux when BootUI is inactive.**
  Deactivating BootUI (a `prod`/`production` profile, `bootui.enabled=OFF`, an invalid `bootui.enabled` value, or simply
  no enabling profile) already unwired every BootUI route, but `bootui-ui` ships the compiled console at
  `META-INF/resources/bootui/`, one of Spring Boot's default static-resource locations, so `GET /bootui/index.html` and
  every asset under it still answered `200` with an empty shell. A new `BootUiShellGuardAutoConfiguration`, gated by the
  exact negation of the activation condition and by the presence of the packaged shell, answers `404` for the reserved
  `/bootui` namespace on both stacks, matching the Quarkus adapter's production shell guard. It matches the decoded
  request path (so `/%62ootui/index.html` and matrix-parameter spellings cannot slip through) and follows relocated
  static handling (`spring.mvc.servlet.path`, `spring.mvc.static-path-pattern`, `spring.webflux.static-path-pattern`),
  under which the same bundle otherwise surfaced at, for example, `/app/static/bootui/index.html`. Requests outside the
  reserved `/bootui` namespace are passed through untouched; a host application that served its own routes there while
  shipping BootUI now receives `404` for them while BootUI is inactive (#856).
- **Spring native images can start with the sample application's Hibernate second-level JCache configuration.**
  Runtime hints now retain the reflectively created `JCacheRegionFactory` constructor together with Caffeine's
  `application.conf` and default `reference.conf` cache configuration resources (#832, #835).
- **Docker image publishing works across the supported sample configurations.** Native AMD64 builds fetch and verify the
  pinned Maven Wrapper JAR before invoking the wrapper, Quarkus Docker startup accepts the intentionally pinned
  Flyway-compatible H2 driver, and the Spring Docker-profile Live Activity check recognizes its PostgreSQL datasource
  instead of requiring the development-only H2 URL (#828, #833, #834).
- **Maven Central smoke tests now exercise standalone Spring MVC and WebFlux consumer projects.** They can no longer pass
  by resolving unpublished reactor sample modules instead of the released starter artifacts (#827).
- **The MCP concurrency-limit test no longer fails intermittently on JDK 25** while preserving the production capacity
  guard (#829).

## [1.14.0] - 2026-08-18

Feature release headlined by Database advisor, Transactions, Hibernate Statistics, Live Activity's Live flow map, and
complete MCP access to BootUI's safely exposable features.

### Added

- **Database diagnostics expanded.** Database advisor, Transactions, and Hibernate Statistics add bounded database,
  transaction, and ORM visibility with honest unavailable states (#760, #761, #781, #795, #807).
- **Animated Live flow map.** Live Activity turns existing HTTP, JDBC, REST client, cache, Kafka, and RabbitMQ evidence
  into an accessible dependency map without new probes (#777, #784).
- **Complete capability-aware MCP reads.** MCP exposes every safely passive panel read, cached advisor report, bounded
  diagnostic control, strict schema, and runtime counter (#776, #791).

### Changed

- **Advisor accuracy was audited broadly.** Architecture, Spring, Quarkus, Hibernate, Security, REST API, JVM Tuning,
  Memory, Vulnerabilities, GraalVM, CRaC, Pentesting, and Database use stronger evidence (#743–#755, #775, #782, #795,
  #806, #807).
- **Advisor scores count every finding.** Panels and Overview apply severity weights per concrete finding, not only once
  per violated rule (#803).
- **SSE health and auto-refresh share one model.** Live Activity, Exceptions, SQL Trace, Security Logs, REST Client, and
  Transactions show calm paused, retrying, and unavailable states (#757, #783).
- **The console uses one Calm Control Room system.** All panel routes, the shell, and Hibernate Statistics received
  consistent hierarchy, responsive states, focus treatment, and reduced-motion support (#812, #813, #816).

### Fixed

- **Quarkus Security advisor matches Quarkus 3.33 LTS.** Effective auth, TLS, CORS, roles, management, literal-secret,
  password, and hostname-verification behavior is modeled more accurately; the obsolete unsigned-JWT rule was retired
  (#755).
- **WebFlux avoids event-loop blocking.** BootUI work in applicable WebFlux handlers is offloaded without changing the
  shared API contract (#726).
- **Bounded data stays bounded.** Metrics, SQL previews, email bodies, telemetry snapshots, scanner concurrency, and
  vulnerability severity parsing no longer leak stale, partial, or misleading state (#720, #727, #732, #742, #771,
  #780).
- **Shared UI resilience improved.** Data panels, navigation, keyboard behavior, responsive layouts, sidebar scrolling,
  cgroup discovery, and Dev Services classification handle edge cases correctly (#721, #722, #730, #735, #737, #772,
  #815, #817).

## [1.13.1] - 2026-08-07

Patch release that restores accurate scanner scores with customized Jackson mappers and removes Quarkus split-package
warnings during application augmentation.

### Fixed

- **Overview scanner scores no longer show a false `100 / Good` result when a Spring host application enables Jackson
  polymorphic typing.** BootUI API responses now use a path-scoped clean Jackson 3 serializer on both Spring MVC and
  WebFlux without changing host endpoint serialization, while malformed severity summaries surface as scanner errors
  instead of silently receiving a perfect score (#724).
- **Quarkus applications no longer report split-package warnings for BootUI runtime packages.** Same-package white-box
  tests now live in the Quarkus runtime module instead of an integration-test application archive, keeping augmentation
  output clean without changing runtime behavior (#719).

## [1.13.0] - 2026-08-06

Feature release headlined by Beans dependency graph mode, reactive security and REST Client coverage, messaging
diagnostics, configurable mounts, and another accessibility hardening pass.

### Added

- **Beans dependency graph.** The panel adds a bounded, keyboard-accessible graph with list fallback and Quarkus
  fidelity notes (#656).
- **RabbitMQ and JMS diagnostics.** RabbitMQ covers all runtimes; JMS covers Spring MVC/WebFlux without payload or
  arbitrary-header retention (#655, #660).
- **Reactive security and REST Client coverage.** WebFlux security plus WebFlux/Quarkus REST Client metadata feed shared
  panels and Live Activity (#654, #657, #658, #663).

### Changed

- **Custom UI and API mounts work end to end.** `bootui.path` and `bootui.api-path` move the full surface together, fail
  invalid paths clearly, and do not leave legacy `/bootui` exposed (#662).
- **SSE-backed panels recover predictably.** Live Activity, Exceptions, SQL Trace, Security Logs, and REST Client show
  calm reconnect states, avoid duplicate streams, and retry cleanly (#661).
- **State-changing UI actions require confirmation.** Thread dumps, unsafe HTTP Probe methods, and configuration writes
  use the branded confirm flow with duplicate-submit protection (#692).
- **Panel naming and Quarkus baselines were updated.** AI Usage became AI Framework, and Quarkus modules align on
  Quarkus 3.33.3.1 LTS through the shared parent (#691, #701).

### Fixed

- **Accessibility and stale-request defects were corrected.** Labels, navigation, command palette semantics, keyboard
  operation, contrast, filtering, refreshes, pagination, and route changes now behave consistently (#659, #694–#698).
- **Slow Log Tail subscribers cannot block Spring MVC logging.** SSE delivery uses bounded per-subscriber queues and
  disconnects only the overloaded browser (#693).
- **Custom mounts and browser storage fail safely.** Application-root links respect configured roots, and UI preferences
  fall back to safe in-memory state when storage is unavailable (#699, #700).

### Security

- **MCP tool-call failures no longer leak internals.** Unexpected `tools/call` errors return the standard detail-free
  JSON-RPC error while logging details server-side (#705).
- **Vulnerable docs and frontend dependencies were patched.** `immutable`, `linkify-it`, `fast-uri`, and affected
  `brace-expansion` lines were updated outside BootUI's shipped runtime (#625–#627, #676, #678–#679, 4c895203).

## [1.12.0] - 2026-07-12

Security and hardening release that adds authenticated remote API access, brings the MCP Server to Spring WebFlux,
enforces bounded outbound HTTP responses, and completes another accuracy pass across every advisor.

### Added

- **Authenticated non-loopback API access on all three adapters.** Every non-loopback `/bootui/api/**` request now
  requires a bearer token, while loopback use remains frictionless. BootUI generates and logs a 256-bit token once when
  remote access is enabled without a configured `bootui.authentication.token`; callers can send it in the
  `Authorization` header, and the browser can exchange it for an HttpOnly, `SameSite=Strict` cookie scoped to the BootUI
  API (#608).
- **MCP Server support on Spring WebFlux**, with a reactive tool catalog over the reactive Live Activity, Exceptions,
  Security Logs, SQL Trace, and Log Tail controllers. The shared MCP core now validates protocol versions and JSON-RPC
  envelopes, handles notifications and lifecycle methods correctly, bounds payload size and concurrent calls, and is
  covered by the same conformance suite on Spring MVC, Spring WebFlux, and Quarkus. WebFlux advertises every applicable
  tool except `security_scan`, whose backing advisor is not yet ported (#611, #612).
- **A distributable BootUI agent skill** for GitHub Copilot and other skill-aware coding agents, covering installation,
  runtime inspection, advisor scans, verification, and MCP setup (#611).
- **Shared security-header policy** across Spring MVC, Spring WebFlux, and Quarkus, including CSP,
  `X-Content-Type-Options`, `X-Frame-Options`, and HSTS where applicable (#582).
- **Focused JDK 21 and 25 compatibility CI**, alongside the existing Java 17 build/release baseline (#579).

### Changed

- **All outbound HTTP integrations now enforce response-size budgets.** HTTP Probe returns a clear truncation signal;
  pentesting probes, GraalVM metadata, OSV/EPSS, and GitHub clients reject oversized responses rather than buffering
  unbounded remote content (#568).
- **The shared API conformance suite now covers nested and action endpoints**, canonical access-denied responses,
  pagination/filtering, scanner lifecycles, trace operations, and MCP behavior on every adapter. Backend panel metadata is
  centralized in one validated catalog and checked against UI routes, manifests, guarded API prefixes, and
  `docs/FEATURES.md` (#581, #583).
- **Another full advisor accuracy pass** corrected false positives, stale platform assumptions, severity/rationale gaps,
  and runtime heuristics across Architecture, Spring/Quarkus application, Hibernate, Memory, Security, REST API,
  Pentesting, Vulnerabilities, GraalVM, and CRaC. New coverage includes REST response-contract checks, proxy-aware
  architecture rules, current JVM/container memory heuristics, additional security configuration evidence, stricter
  version comparison and OSV result handling, and expanded GraalVM/CRaC runtime checks (#597–#606).
- **Release verification now smoke-tests all published distributions from Maven Central** — Spring MVC, Spring WebFlux,
  and Quarkus — before committing and tagging a prepared release (#576).
- **Dependencies updated**, including Quarkus 3.37.2, Spring Kafka 4.1.0, the Quarkus LangChain4j BOM, Vite, Prettier,
  vue-tsc, Playwright, VuePress, Sharp, and the patched `js-yaml` 3.15.0 transitive dependency.

### Fixed

- **REST Client panel now reports unavailable until a client is actually instrumented (Spring MVC).** The panel's
  `/bootui/api/panels` availability previously only checked that the internal `RestClientTraceRecorder` bean existed
  — but that bean is registered unconditionally (it also backs Live Activity), so the check was always true and the
  panel showed up in the sidebar even for applications with no `RestClient`, `RestTemplate`, or `WebClient` ever
  built. Availability now mirrors the recorder's own "has anything been instrumented yet" signal, the same pattern
  Kafka/Email/Cache already use (#560).
- **CRaC Docker publish smoke tests** now grant the capabilities CRIU needs to restore a checkpointed process and use the
  corrected generated-image startup path (#607).
- **WebFlux MCP availability and conformance expectations** now match the implemented reactive server, and nested
  endpoint conformance checks no longer misclassify valid endpoint families (#612).
- **Release pushes retry safely after a non-fast-forward update**, rebasing the release-only version commit before
  recreating the tag instead of leaving a successfully published version untagged (#559).

## [1.11.0] - 2026-07-09

Feature release headlined by three new dev-loop panels — **Email**, **REST Client Trace**, and **Kafka** — and
**Live Activity growing from 4 to 9 merged signal types**. Also ships a ~2.4x faster Maven build.

### Added

- **Email panel** — captures outgoing application email (recipients, subject, HTML/text body, attachments) for local
  inspection, mirroring Laravel Telescope's mail watcher. Ships on both Spring and Quarkus; content is revealed by
  default, with an opt-in `bootui.email.mask-content` flag for teams routing real PII through a shared dev
  environment (#538).
- **REST Client Trace panel** — captures outbound `RestClient`/`RestTemplate`/`WebClient` calls (method, host, path,
  status, duration), with slow-call and "chatty" (repeated-call) detection. Spring servlet adapter only for now
  (#544).
- **Kafka panel** — a dedicated, filterable view over producer/consumer activity: direction, topic, partition,
  offset, key hash, duration, and success/failure, with the message value/payload never captured. Ships on both
  Spring and Quarkus (#550).
- **Live Activity grows from 4 to 9 merged signal types**, adding cache accesses, scheduled-task runs, Kafka
  producer/consumer activity, outbound REST client calls, and captured email to the existing request/SQL/exception/
  security feed — each nested under its correlated request. Kafka and scheduled-task capture are also new on
  Quarkus, and cache capture is also new on Spring WebFlux (#538, #541, #542, #543, #544).

### Changed

- **Email and REST Client Trace moved into the Services group** in the sidebar, alongside Scheduled Tasks and Cache.
- **~2.4x faster full Maven build** (5:09 → ~2:10 min on a 10-core machine) by enabling reactor (`-T 1C`) and
  Surefire test parallelism, after fixing two latent Quarkus concurrency races that made parallel builds flaky
  (#545).

### Fixed

- **Hardened Kafka and Scheduled Tasks activity capture to fail open on errors**, so a capture-side bug can no
  longer disrupt a real Kafka send/consume or scheduled-task run.

## [1.10.0] - 2026-07-07

Feature release headlined by Spring WebFlux support as BootUI's third first-class adapter, OpenTelemetry span
enrichment, and the next advisor audit pass. It also corrects Quarkus loopback-enforcement reporting.

### Added

- **Spring WebFlux support.** A new reactive starter serves the shared engine, Vue UI, and API contract on Netty with
  the same safety floor as Spring MVC; unsupported panels report clear reasons (#523, #526, #536).
- **Reactive trace correlation.** WebFlux HTTP, SQL, exception, and security capture stamps active OpenTelemetry trace
  ids, with BootUI contributing an overridable Reactor context-propagation default (#523, #526, #536).
- **OpenTelemetry span enrichment.** BootUI can stamp service identity plus SQL and exception depth attributes on spans,
  and the Traces drawer surfaces those enriched cross-service traces (#525).
- **Advisor coverage grew.** Spring Security added default-user and cookie-prefix checks, and Hibernate added
  composite-id, bind-logging, and natural-id checks (#511, #522).

### Changed

- **The advisor audit reached the remaining rule sets.** Architecture, Memory, REST API, Pentesting, Vulnerabilities,
  Spring, GraalVM, CRaC, Security, Hibernate, and Quarkus gained fixes and targeted checks (#509–#520, #522).
- **Obsolete or duplicate advisor logic was removed.** The audit retired duplicate Architecture checks, stale Spring
  Security claims, and dead Quarkus Security rules while documenting replacements (#509, #519, #520, #522).
- **BootUI self-traffic classification is shared.** Capture and transform paths agree on configured `bootui.path`
  instead of hardcoding `/bootui` in capture (#508).
- **Sample apps have dedicated ports.** Spring MVC, WebFlux, and Quarkus samples now run on 8080, 8081, and 8082 so they
  can run side by side.
- **Sample-app integration tests run Docker-free on H2.** The tests no longer require a PostgreSQL Testcontainer (#506).
- **Dependencies were refreshed.** Quarkus, PostgreSQL JDBC, Vite, Vitest, vue-tsc, and Prettier moved to current lines
  (#528–#533).

### Fixed

- **Quarkus overview now reports loopback enforcement accurately.** `activation.localhostOnly` mirrors Spring semantics
  and no longer warns that Quarkus reads lack the shared `LocalhostGuard` policy.
- **Quarkus Security advisor matches current Quarkus behavior.** Dead checks were removed, JWT algorithm, GraphQL, CORS,
  secret, TLS, management, authz, messaging, and session logic were corrected, and new checks were added (#520).
- **Quarkus application advisor rules now fire and rank correctly.** Scheduling, REST-client timeout, reactive JDBC, Dev
  Services, schema generation, CDI, virtual threads, profiles, and new checks were corrected (#520).

## [1.9.0] - 2026-07-03

Feature release headlined by optional durable JDBC persistence for Live Activity, Exceptions triage, SQL call-site
capture, Quarkus parity work, and a full advisor audit. It also fixes a Quarkus XSS vulnerability.

### Added

- **Live Activity can persist to JDBC.** The default remains the in-memory ring buffer, but operators can switch to
  masked, instance-namespaced JDBC persistence with buffered writes and self-capture protection (#504).
- **Exceptions gained triage state.** Exception groups can be Open, Acknowledged, or Resolved; resolved regressions
  reopen automatically with a badge, while acknowledged groups stay acknowledged (#499).
- **SQL call sites are visible.** SQL Trace and Live Activity show application call sites, and rows surface suspected
  N+1 patterns without opening the drawer (#500).
- **Vulnerabilities can be dismissed and restored.** Dismissals survive patch-version bumps for the same vulnerable
  package and are excluded from rollups while remaining visible (#485).
- **Quarkus parity improved.** Security events, profile drill-down, JAX-RS exception mapping, and access gating now
  align with the Spring adapter's shared model (#489, #491, #496, #501).

### Changed

- **All nine advisor rule sets were audited.** Architecture, Spring, Security, Pentesting, Hibernate, REST API, Memory,
  Vulnerabilities, and Quarkus Security received bug fixes, false-positive reductions, and new checks (#479–#487).
- **Maven Central publishing was trimmed.** Demo/test modules and unnecessary checksum fanout were removed from releases
  to reduce the publishing footprint (#477, #493).

### Fixed

- **Quarkus XSS vulnerability fixed.** The shell `<base href>` now comes from static root-path configuration rather than
  attacker-influenced request URI data (#503).
- **Quarkus production builds no longer expose the shell.** The entire `/bootui` surface returns 404 in
  `LaunchMode.NORMAL`, matching the already-dark API behavior (#497).
- **Quarkus Exceptions and Live Activity keep HTTP context.** Method, path, handler, and richer request details now win
  the capture race (#492, #495).
- **Quarkus panel filtering and badges are accurate.** Beans and Mappings no longer hide similarly-prefixed application
  classes, and Security Logs use Quarkus event names for badge colors (#498).
- **Quarkus docs and release guards were corrected.** Panel availability claims, javadocs, and stale Quarkus version
  references were fixed, with release checks preventing future stale versions (#476, #478, #488).

## [1.8.0] - 2026-07-01

Feature release headlined by Quarkus support, Quarkus-native advisors, shared conformance, and the breaking renames
needed for the Quarkus port.

### Added

- **Quarkus support.** The new `bootui-quarkus` extension serves the same Vue UI, API contract, and shared engine as
  Spring Boot, activates only in dev/test, and reports unsupported panels clearly (#467).
- **Quarkus-native Security advisor.** The shared security panel reviews Elytron/OIDC auth, Quarkus HTTP permissions,
  TLS, CORS, and role annotations on Quarkus (#467, #472).
- **Shared HTTP conformance.** `bootui-conformance` pins the manifest, available-panel JSON reads, and cross-site write
  rejection across Spring and Quarkus (#467).
- **Quarkus sample and tests.** A reference sample app plus Docker-free Quarkus integration and conformance suites
  demonstrate and gate the adapter (#467).

### Changed

- **Quarkus advisors were hardened before release.** Application and Security advisors gained parity fixes plus
  Quarkus-specific production, JWT, header, form-auth, management, gRPC, GraphQL, and messaging checks (#472).
- **Dependencies were refreshed.** Vue, Spotless, PostgreSQL JDBC, and Prettier moved to current lines (#468–#471).
- **Spring Cache is now Cache.** The route and API moved to `/cache` and `/bootui/api/cache`; the old browser route
  redirects, but the old API path and `bootui.panels.spring-cache.*` keys were removed.
- **Spring adapter modules were renamed.** `bootui-autoconfigure` became `bootui-spring-autoconfigure`; direct Maven
  users must update that artifact id, while starter users need no change.
- **`bootui-spi` was merged into `bootui-engine`.** The `bootui-spi` Maven coordinate no longer exists, but the
  `io.github.jdubois.bootui.spi.*` package remains in the engine for import compatibility.

## [1.7.0] - 2026-06-29

Feature release headlined by **richer Dependabot insight in the GitHub panel** and **filterable readiness concerns on the
GraalVM and CRaC advisors**, alongside a console-wide accessibility, motion, and design-token polish and a fix for a
trailing-slash redirect loop.

### Added

- **Dependabot alert details in the GitHub panel security drawer.** The drawer previously showed only an open-alert count
  plus a privacy note. It now lists the bounded set of open Dependabot alerts with their package, ecosystem, severity,
  advisory ID, summary, affected range, and fixed version — non-secret advisory metadata. Code scanning and secret
  scanning stay count-only and never inline secret values or vulnerable code snippets (#464).
- **Concern filtering on the GraalVM and CRaC advisor panels.** Both panels listed every readiness concern with no way to
  narrow them. A shared filter toolbar (severity chips, category dropdown, free-text search) now appears once a scan finds
  concerns, with a live count and a no-match empty state, keeping the two advisors uniform (#463).

### Changed

- **Console-wide accessibility, motion, and design-token polish.** A design pass verified WCAG 2.1 AA contrast in both
  light and dark themes (including caution body text), put a visible branded focus ring on every interactive control,
  calmed elevation and motion (flattened card shadows, hover-lift only on interactive cards, honors
  `prefers-reduced-motion`), and replaced native `window.confirm()` on one-click mutations with a branded confirmation
  dialog. `DESIGN.md` and its sidecar are now the documented source of truth for the radius, severity-color, and chart
  palettes (#459).
- **Bumped the toolchain:** build plugins to latest (#462), Testcontainers to 2.0.5 (#461), and Node to 24.18.0 / npm to
  11.17.0 (#460).
- **Clarified the front-end hot-reload workflow** in the contributor and agent docs so UI iteration uses the Vite dev
  server (`:5173/bootui/`) rather than the pre-built Maven-served console (#457).

### Fixed

- **`/bootui` no longer redirects to `/bootui/`, fixing a "localhost redirected you too many times"
  (`ERR_TOO_MANY_REDIRECTS`) loop behind a trailing-slash–stripping filter or proxy.** BootUI used to
  answer `GET /bootui` with a `302` to the canonical `/bootui/` so its relatively-referenced Vue assets
  and `fetch('api/...')` calls would resolve. Spring Framework 6.1+/Boot 4 dropped trailing-slash URL
  matching, so a host application that restores it with `UrlHandlerFilter.trailingSlashHandler("/**")
  .wrapRequest()` (a standard Boot 4 idiom) rewrites `/bootui/` back to `/bootui` ahead of the
  dispatcher — turning BootUI's redirect into an infinite loop. The same happens behind any proxy or
  filter that strips trailing slashes. BootUI now serves the SPA shell at **both** `/bootui` and
  `/bootui/` and injects a runtime `<base href="{contextPath}/bootui/">` so assets, API calls, and lazy
  chunks resolve regardless of the trailing slash, with no redirect to loop on. Host
  `server.servlet.context-path` support (#332) is preserved because the base href is computed
  per-request (#456).

## [1.6.0] - 2026-06-25

Feature release headlined by **idle memory reclamation** — BootUI now releases its live diagnostic buffers and pauses
recording while the console sits idle — alongside a fix for a YAML activation gotcha that could silently disable BootUI
and a restore of the Spring Security panel's collapsible sections.

### Added

- **Idle memory reclamation for the live diagnostic buffers.** BootUI fills several bounded in-memory buffers from the
  host application's own traffic — ingested OTLP traces/spans, the SQL trace, and the request/security correlation
  windows. In development that traffic keeps flowing even when nobody has the console open, so the buffers sat at their
  steady-state size for no observable benefit. Once the console has been idle for `bootui.free-on-idle.timeout`
  (default `5m`), BootUI now releases those buffers' retained data and pauses recording into them, then refills them from
  live traffic on the next console request. A `ConsoleActivityFilter` registered just after the safety filters marks the
  console active on any trusted-local `/bootui` request (UI load, API poll, or stream open), so an open console never
  reclaims while a genuinely unused one does. The Exceptions and Log Tail buffers are deliberately retained so a recent
  error stays visible when the console is opened, the idle gate is kept separate from any user-facing pause toggle so
  resuming never overrides an explicit pause, and the whole behavior is dev-only and fully disabled with
  `bootui.free-on-idle.enabled=false` (#452).
- **Ecosystem page on the documentation site** ([`docs/WORKS-WITH.md`](docs/WORKS-WITH.md)) that tells the shared-Java
  workflow story across BootUI, [Coffilot](https://www.julien-dubois.com/coffilot/), and
  [Dr JSkill](https://www.julien-dubois.com/dr-jskill/), reachable from a new "Ecosystem" navbar entry (#432).

### Fixed

- **`bootui.enabled: ON` / `OFF` in YAML no longer silently disables BootUI.** YAML parses the unquoted `ON` as the
  boolean `true`, which reached `BootUiActivationCondition` as the string `"true"`, was rejected as an invalid value, and
  turned BootUI off — so `/bootui` fell through to the static-resource handler and 404'd with
  `NoResourceFoundException`. The activation condition now normalizes boolean-ish values (`TRUE`/`YES` → `ON`,
  `FALSE`/`NO` → `OFF`), matching Spring's relaxed enum binding used for BootUI's other `Mode` properties; unknown values
  still fail closed (#447, #448).
- **Restored the collapsible accordion in the Spring Security panel.** Bootstrap's collapse JavaScript was never
  imported, so clicking the panel's accordion section headers did nothing; BootUI now bundles `bootstrap/js/dist/collapse`
  and the sections expand and collapse again (#431).
- **Corrected the heading-anchor scroll offset on the documentation site** so deep links and in-page anchors no longer
  land with the target heading hidden behind the fixed navbar (#426).

## [1.5.2] - 2026-06-17

Patch release fixing a startup crash for applications that contribute their own `HttpExchangeRepository`, polishing two
panels (Metrics measurement spacing and the Flyway sidebar icon), and adding a guide for driving BootUI from local AI
coding agents.

### Added

- **AI agents guide** ([`docs/AI-AGENTS.md`](docs/AI-AGENTS.md)) covering how to drive BootUI from local AI coding agents
  over the Model Context Protocol (MCP): connecting an agent to BootUI's MCP server so it can consult the running
  application before proposing a fix and verify it afterwards, a worked Hibernate-findings example, and how BootUI pairs
  with [Coffilot](https://github.com/jdubois/coffilot) to build, run, and scan an app from the GitHub Copilot App's side
  panel (#423).

### Fixed

- **BootUI no longer crashes applications that contribute their own `HttpExchangeRepository`.** BootUI registers a
  fallback in-memory `HttpExchangeRepository` guarded by `@ConditionalOnMissingBean`, but runs `@AutoConfigureBefore` the
  standard HTTP-exchange auto-configurations. When the host application supplied its own repository from a configuration
  ordered after BootUI (for example its own auto-configuration), the condition could not see it yet, so BootUI created
  its fallback as well — leaving two repositories that broke single-bean injection into BootUI's recording filter and
  Spring Boot's own `httpExchangesEndpoint`, crashing the context at startup. BootUI now reconciles the repositories in a
  `BeanFactoryPostProcessor` that runs after every bean definition is registered (regardless of ordering) and before any
  bean is instantiated, dropping its fallback whenever another `HttpExchangeRepository` is present so exactly one remains
  — the application's own — which BootUI's filter and HTTP Exchanges panel then use transparently (#422).
- **Corrected the Flyway panel icon** in the sidebar and panel header so it no longer reuses an unrelated glyph (#412).
- **Restored the spacing between the statistic label and value** in the Metrics panel's per-sample measurements, which
  had run together without a gap (#410).

## [1.5.1] - 2026-06-15

Patch release with two fixes: BootUI's live panels no longer hold the JVM open until the configured shutdown timeout
(a graceful-shutdown regression introduced by 1.5.0's move to Server-Sent Events), and BootUI no longer crashes
Spring Cloud Config applications during the bootstrap phase.

### Fixed

- **BootUI's live panels no longer delay graceful shutdown.** The Server-Sent Events panels (Live Activity, Exceptions,
  SQL Trace, Security Logs, Log Tail, Copilot, Claude Code) open an `SseEmitter` with no timeout, which counts as an
  active request. Their emitter cleanup ran from a bean-destruction (`@PreDestroy`) hook — too late, because Spring Boot
  4's default graceful shutdown waits for in-flight requests *before* beans are destroyed, so every JVM stop blocked
  until the `spring.lifecycle.timeout-per-shutdown-phase` timeout (30s by default). BootUI now completes these streams on
  `ContextClosedEvent`, which fires before the web server's graceful-shutdown lifecycle, so the application stops
  promptly again.
- **BootUI no longer breaks Spring Cloud bootstrap startup.** When a host application used Spring Cloud Config / the
  legacy bootstrap context (`spring-cloud-starter-bootstrap`) with BootUI active (for example under the `dev` profile),
  the application crashed at startup with
  `MissingWebServerFactoryBeanException: No qualifying bean of type 'ServletWebServerFactory' available`. BootUI's
  command-line support forces a servlet web type so the console can be served, but it was also applying that to Spring
  Cloud's transient, non-web **bootstrap** application context, which has no `ServletWebServerFactory`. BootUI now
  detects the bootstrap context (via Spring Cloud's `"bootstrap"` marker property source) and leaves it untouched, while
  still forcing the servlet web type on the main application.

## [1.5.0] - 2026-06-15

Feature release headlined by a new **Live Activity** panel — a diagnostics "home base" that merges BootUI's already
captured signals into one reverse-chronological stream and adds a Symfony-style per-request profiler — and a move to
**Server-Sent Events** for the event-driven panels so they update the moment something happens instead of polling on a
timer. It also adds keyboard shortcuts and number-key navigation to the command palette, a fourth Ahead-of-Time
sample-app Docker image (Spring AOT + JDK AOT cache), simplifies the Overview panel, upgrades the sample app to Spring AI
2.0.0 GA, and quiets the tracing libraries' DEBUG noise.

### Added

- **Live Activity panel** — a new Overview panel that is the diagnostics home base: one reverse-chronological stream of
  everything the application just did plus a per-request profiler. It adds no new instrumentation, instead reusing the
  controllers and DTOs that back the HTTP Exchanges, SQL Trace, Exceptions, and Security Logs panels, so every value is
  already masked, self-filtered, and bounded. The stream merges `REQUEST`, `SQL`, `EXCEPTION`, and `SECURITY` entries
  with a colour-coded severity, a latency heat scale, a requests-over-time sparkline, a KPI strip (requests/min, error
  rate, p50/p95 latency, SQL rate, slowest endpoint, active exceptions, health, heap), and client-side filters that
  persist in the browser. Correlated SQL, exceptions, and security events are nested chronologically under the request
  that produced them — pinned by trace id, by the request's serving thread, or by method/path — and the per-request
  profiler correlates one request's signals with a tiered join that degrades gracefully and labels approximate matches
  rather than fabricating links, flags likely N+1 access patterns, and offers a **Copy profile** action. The merged feed
  is pushed over **Server-Sent Events** (`GET /bootui/api/activity/stream`) and can be paused and resumed. The panel is
  read-only and inherits BootUI's full safety model. Configurable under `bootui.activity.*` (#388).
- **Server-Sent Events live updates** for the Exceptions, SQL Trace, and Security Logs panels. Each panel subscribes to a
  per-panel `/stream` endpoint and re-fetches the moment a signal is captured (or the buffer is cleared or
  paused/resumed) instead of polling on a fixed interval. The push carries no data — masking, truncation, and
  value-exposure rules still apply through the regular endpoint — bursts are coalesced into a single refresh, the stream
  is closed when the auto-refresh toggle is off or the tab is hidden, and the panels fall back to their initial load when
  Server-Sent Events are unavailable (#386).
- **Command palette keyboard shortcuts and number-key navigation** — each panel now has a two-letter shortcut that the
  palette matches and displays, and the unfiltered palette can be navigated with the number keys `1`–`9` (#381).
- **AOT-optimized sample-app Docker image** (`Dockerfile-aot`, `docker-compose-aot.yml`, published as
  `jdubois/bootui-sample-app-aot`) — a fourth startup-optimization option that combines Spring AOT processing with a JDK
  AOT (Ahead-of-Time) cache on the plain JVM image for significantly faster startup, documented in
  [`docs/TRY-SAMPLE-APP.md`](docs/TRY-SAMPLE-APP.md) (#383, #387).
- Sample-app **action-lab buttons** that exercise more BootUI panels for richer demos and integration coverage (#389).
- New `bootui.activity.*` configuration properties, catalogued in [`docs/PROPERTIES.md`](docs/PROPERTIES.md).

### Changed

- **Simplified the Overview panel** by removing the live Health and Memory cards. The panel now opens with the hero
  banner and quick links and leads straight into the on-demand security & health scoring dashboard; the dedicated Health,
  Live Memory, and Heap Dump panels (also reachable from the Live Activity KPI strip) remain the home for that detail
  (#396).
- **Quieted the tracing libraries' DEBUG noise at full sampling.** Because BootUI raises
  `management.tracing.sampling.probability` to `1.0` for local development, the OpenTelemetry SDK and Micrometer Tracing
  span/propagation code runs on every request and floods the console with low-value lines when the host's root logger is
  at `DEBUG`. BootUI now pins `logging.level.io.opentelemetry` and `logging.level.io.micrometer.tracing` to `INFO` as
  overridable defaults while the Traces panel is active; set either key yourself to opt back in (#394).
- Upgraded the sample app to **Spring AI 2.0.0 GA** (from `2.0.0-RC1`) (#378).
- Bumped `esbuild` from `0.28.0` to `0.28.1` (#380).

### Fixed

- **`NoSuchMethodError` opening the Configuration or MCP Server panel on older Jackson 3** — `ConfigMetadataCatalog` now
  binds to the stable `ObjectMapper.treeToValue(TreeNode, Class)` overload instead of `treeToValue(JsonNode, Class)`,
  which was only added in jackson-databind 3.1. Host applications whose classpath resolves an earlier Jackson 3 (for
  example 3.0.x dragged in by a transitive dependency) no longer crash `ConfigController` start-up with
  `java.lang.NoSuchMethodError: 'java.lang.Object tools.jackson.databind.ObjectMapper.treeToValue(...)'` (#384).
- **SQL Trace no longer fails under Spring Boot DevTools' class-loader split.** When DevTools loads the application on its
  restart class loader, the data source's own loader (the base loader, where the driver/pool jar lives) cannot see
  BootUI's `SqlTracedDataSource` marker, so creating the JDBC tracing proxy threw. BootUI now defines the proxy with its
  own class loader — a descendant of the data source's that can see both the marker and the JDK's JDBC interfaces — so
  SQL tracing keeps working during DevTools-powered development (#395).
- Updated the Claude Code panel icon (#392).
- Fixed the documentation site's home-page `<title>` tag (#382) and a duplicate `@vuepress/plugin-markdown-tab` warning
  during the docs build (#379).

## [1.4.0] - 2026-06-12

Feature release headlined by three new panels — a **SQL Trace** panel that records executed SQL through a hand-written
JDBC proxy and flags slow queries and likely N+1 access patterns, an **Exceptions** diagnostics panel that captures and
groups runtime exceptions, and an opt-in, local-only **MCP server** (with a Developer Tools panel) that exposes BootUI's
advisors and read-only diagnostics to local AI coding agents. It also sharpens the Traces panel, strengthens the CRaC
advisor, fixes two GraalVM native-image issues in the starter, hardens the sample app's JVM and native Docker images to
zero OS-package CVEs, shrinks the packaged UI, and polishes the console shell.

### Added

- **SQL Trace panel** — a new Database panel showing the SQL statements the application recently executed, captured by a
  hand-written JDBC tracing proxy built on the JDK's own dynamic-proxy support (no third-party database-proxy library).
  It transparently wraps each `DataSource` and records SQL text, statement/category, wall-clock duration, affected rows,
  batch size, connection, thread, and failures into a bounded in-memory ring buffer; groups identical statements; flags
  likely **N+1** access patterns and slow queries; and offers local-only Pause/Resume and Clear actions. Parameter
  capture is off by default and masked when enabled, wrapping fails open, and the JDK proxies are registered for GraalVM
  native images. Configurable under `bootui.sql-trace.*` (#359).
- **Exceptions panel** — a new Diagnostics panel that captures, groups, and surfaces exceptions thrown by the host
  application. Capture uses two observe-only sources (a `HandlerExceptionResolver` for MVC handler exceptions with
  request context, and a Logback root appender for anything logged with a throwable), deduplicated by `Throwable`
  identity and grouped by a SHA-256 fingerprint of the class and top stack frames, with bounded recent occurrences and
  the cause chain. Messages are masked, request paths are captured without query strings, and the clear action honors
  read-only. Configurable under `bootui.exceptions.*` (#358).
- **MCP server for AI agents** — BootUI can optionally expose its advisors and read-only diagnostics to local AI coding
  agents (GitHub Copilot, Claude Code) through an opt-in, local-only
  [Model Context Protocol](https://modelcontextprotocol.io) server. It is a hand-rolled JSON-RPC 2.0 endpoint at
  `POST /bootui/api/mcp`, disabled by default (`bootui.mcp.enabled=OFF`), that reuses the existing controllers and DTOs
  so every tool returns the same masked, bounded shape as the REST API — advisor scans as action tools, plus diagnostics
  and core-context read tools — and inherits BootUI's full safety model. A new **MCP Server** panel (top of Developer
  Tools) documents the exposed tools, shows connection details and a copyable client-configuration JSON, and toggles the
  server on or off at runtime, overriding the configured mode. Configurable under `bootui.mcp.*` (#368, #370).
- Three new **CRaC readiness checks** — `CRAC-FILE-001` (direct file-handle opens), `CRAC-CACHE-001` (live
  `CacheManager` beans), and `CRAC-CONFIG-001` (static initializers capturing env/properties) — plus broadened
  `CRAC-RES-001`, `CRAC-SECRET-001`, and `CRAC-POOL-001` coverage, all catalogued in
  [`docs/CRAC-READINESS-CHECKS.md`](docs/CRAC-READINESS-CHECKS.md) (#355).
- New `bootui.sql-trace.*`, `bootui.exceptions.*`, and `bootui.mcp.*` configuration properties, all catalogued in
  [`docs/PROPERTIES.md`](docs/PROPERTIES.md).
- A brand favicon for the console UI, the sample app, and the VuePress documentation site (#364).

### Changed

- **Traces panel now shows the HTTP request path** a trace served (falling back to the root span name when no path
  attribute is present) instead of labelling traces with generic root spans, and **fully excludes BootUI's own
  traffic**: self-span filtering is now trace-level, so once any span identifies a trace as BootUI's own the whole trace
  — and sibling spans exported in other OTLP batches — is dropped. Applied to both the in-process span exporter and the
  `/bootui/api/otlp` receiver (#375).
- **Polished the console shell** — collapsed-sidebar hover flyouts, a mobile overlay drawer and responsive topbar, a
  pulsing "live" dot on the auto-refresh toggle, recently visited panels floated to the top of the command palette, and
  human-readable byte sizes in Health details (#362).
- **Shrank the packaged UI JAR** by subsetting the Bootstrap Icons font and CSS to only the icons BootUI uses (#363).
- **Hardened the sample app's JVM Docker image** (published as `jdubois/bootui-sample-app`) to carry no known OS-package
  CVEs. Its runtime stage now uses Google's distroless glibc base (`gcr.io/distroless/base-debian12:nonroot`, the same
  base `Dockerfile-native` uses) instead of Alpine, which removes the vulnerable `openssl` (`libssl3`/`libcrypto3`) and
  `busybox` packages a scanner previously flagged. The `jlink` runtime is now assembled on the glibc JDK to match the
  base; because distroless ships no shell, JVM flags moved from a `sh -c $JAVA_OPTS` entrypoint to `JAVA_TOOL_OPTIONS`,
  the entrypoint is exec-form, and the Docker `HEALTHCHECK` was dropped (probe `/actuator/health` from your orchestrator
  instead, as the native image already documents) (#365).
- **Hardened the sample app's GraalVM native Docker image** by building a mostly-static binary and switching the runtime
  stage to `gcr.io/distroless/base-debian12:nonroot`, taking it (and the BootUI-generated `Dockerfile-native`) to zero
  CVEs; the curl-based `HEALTHCHECK` was dropped (#356).
- **Shrank the sample app's JVM Docker image from 739MB to 338MB** by exploding the repackaged Spring Boot jar into
  layers, assembling a curated `jlink` runtime, and using a minimal Alpine final stage (#361).
- Refactored the sample app into clean feature packages for clearer demos and integration tests (#371).

### Fixed

- **GraalVM native image: the Mappings panel no longer fails.** `GET /bootui/api/mappings` (the compatibility
  endpoint that returns Actuator's raw mappings descriptor) threw a `MissingReflectionRegistrationError` in a native
  image because Jackson reflectively instantiates the array forms of Actuator's nested `MediaTypeExpressionDescription`
  / `NameValueExpressionDescription` types while serializing them. BootUI's `RuntimeHints` now register those types and
  their array forms, so consumers of the starter no longer need to declare the hints themselves (#367).
- **GraalVM native image: BootUI no longer self-reports as "Disabled" while running.** In a native image the activation
  condition is frozen at AOT build time, but the `BootUiActivation` bean recomputed it against the live runtime
  environment (where the build-time `dev` profile / `bootui.enabled=ON` no longer apply), so the Overview panel and the
  startup log claimed BootUI was disabled even though it was serving. When running AOT-generated artifacts, BootUI now
  trusts the frozen build-time decision and reports the accurate enabled state (#367).
- Exempted the `/bootui/api/mcp` endpoint from Spring Security's SPA CSRF token so non-browser MCP clients (e.g. VS
  Code) can connect without a token, while `LocalhostOnlyFilter`'s loopback, `Host` allow-list, and cross-site
  defenses still apply (#370).
- Fixed VuePress documentation anchor links landing on the wrong section (#374).

## [1.3.0] - 2026-06-11

Feature release headlined by two new GraalVM/CRaC capabilities — a new **CRaC (Coordinated Restore at Checkpoint)
readiness panel** and **GraalVM reachability-metadata** support (repository lookup plus an install-into-source-tree
action) — alongside an upgrade to **Spring Boot 4.1.0** and a second wave of advisor improvements (Memory, REST API,
GraalVM) authored with Anthropic's new **Claude Fable 5** model, on top of the 1.2.0 hardening pass. It also makes BootUI
reachable from inside containers through narrow, fail-closed opt-ins (`bootui.trusted-proxies` and
`bootui.trust-container-gateway`), runs the sample app Docker-free by default, and adds a cross-platform CI build matrix.

### Added

- **CRaC readiness panel** — a new Runtime panel that reviews the host application's
  [Coordinated Restore at Checkpoint](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
  readiness. It reports whether the `org.crac` API is on the classpath, whether the running JVM is a CRaC-capable JDK
  (detected via the real CRaC implementation rather than the no-op shim), whether `spring.context.checkpoint=onRefresh`
  is set, and any `-XX:CRaCCheckpointTo` / `-XX:CRaCRestoreFrom` JVM arguments. It scans the host application's own
  classes against a curated set of `CRaC-*` checks (including the `CRAC-POOL-001` connection-pool readiness check) for
  constructs that complicate checkpoint/restore, and generates ready-to-use container assets — a multi-stage
  `Dockerfile-crac` plus a `checkpoint-and-run.sh` entrypoint. The full catalogue lives in
  [`docs/CRAC-READINESS-CHECKS.md`](docs/CRAC-READINESS-CHECKS.md) (#322).
- **GraalVM reachability-metadata lookup** — the GraalVM panel queries the reachability-metadata repository for the
  host's dependencies and adds an _install into source tree_ action that writes the metadata into the project; the
  "Include dependencies" toggle now defaults to on (#324, #331).
- A second wave of advisor rules on top of the 1.2.0 hardening pass: **7 new GraalVM checks** (plus the new
  `GRAAL-FFM-001` Foreign Function & Memory check, replacing the AWT check), **7 new Memory rules**, and **9 new REST API
  rules**, all catalogued in the refreshed `docs/*-CHECKS.md`. This wave of advisor work was authored with Anthropic's
  new Claude Fable 5 model.
- **`bootui.trusted-proxies`** — an opt-in list of source IP ranges (CIDR notation, e.g. `172.16.0.0/12`) trusted in
  addition to loopback by the safety filter. Lets local Docker-bridge callers reach BootUI without the blunt
  `bootui.allow-non-localhost=true`: it relaxes only the source-address check and keeps the `Host` allow-list
  (DNS-rebinding) and cross-site write (CSRF) protections in force. Pair it with `bootui.allowed-hosts` for the hostname
  the browser uses.
- **`bootui.trust-container-gateway`** (`OFF` / `AUTO` / `ON`, default `OFF`) — a one-flag opt-in to trust the
  auto-detected container gateway as a single `/32`, so BootUI can be reached inside a container with a published port
  without knowing the subnet or setting a broad `bootui.trusted-proxies` CIDR. Detection covers both the Linux Docker
  Engine bridge gateway (from `/proc/net/route`) and the Docker Desktop gateway (via the `gateway.docker.internal` DNS
  name). Like `bootui.trusted-proxies`, it relaxes only the source-address check and keeps the `Host` allow-list and
  CSRF protections in force.
- **Cross-platform CI** — a build workflow matrix that runs the full build on Linux, Windows, and macOS.
- A "Docker container access" section in [`docs/SETUP.md`](docs/SETUP.md) covering the trusted-proxies and
  container-gateway options.

### Changed

- **Upgraded to Spring Boot 4.1.0** (from 4.0.x), including aligning the Hibernate advisor end-to-end tests with
  Hibernate 7.4 collection-fetch behavior (#326).
- Marked the **JVM Tuning, GraalVM, Architecture, REST API, and CRaC** panels unavailable in GraalVM native images,
  where their underlying JVM/bytecode analysis cannot run. The startup-timeline buffer is now installed for AOT images
  even when BootUI is inactive, so timeline data is captured if BootUI is later enabled.
- **`bootui-sample-app` now runs Docker-free by default** — the `dev` Spring profile (the default via
  `spring.profiles.default=dev`) swaps the Docker Compose PostgreSQL, Redis, and Ollama services for an in-memory H2
  database, a simple in-memory cache, and disabled Spring AI, so a bare `spring-boot:run` (and the Playwright e2e suite)
  starts offline with no Docker engine. A new `docker` profile (`-Dspring-boot.run.profiles=docker`) restores the full
  Docker experience (PostgreSQL, Redis, Ollama, and the `qwen2.5:0.5b` chat model).
- **"Try the sample app" now uses the published Docker image** — [`docs/TRY-SAMPLE-APP.md`](docs/TRY-SAMPLE-APP.md) runs
  `jdubois/bootui-sample-app` (the JVM image), with `docker run` command lines for the CRaC
  (`jdubois/bootui-sample-app-crac`) and GraalVM native (`jdubois/bootui-sample-app-native`) images too. The
  `scripts/run-sample.sh` and `scripts/run-sample.ps1` helper scripts, which cloned and built the repository locally,
  were removed.
- The sample app's three Dockerfiles (JVM, CRaC, and native) now default to the `dev` profile with Flyway/Liquibase
  disabled for fast startup, and the JVM and CRaC images set explicit JVM tuning flags.
- Shared a single source-tree writer and build-system detection routine across the GraalVM and CRaC config generators.

### Fixed

- BootUI now loads correctly when the host application sets a non-root `server.servlet.context-path` (#332).
- Fixed the generated `Dockerfile-native` Maven build for plain Spring Boot applications (#325).
- Normalized install display paths to forward slashes on Windows so the setup snippets render correctly.
- Removed the ArchUnit gate from CRaC availability — only running in a native image disables the panel.
- Fixed broken documentation links and aligned the VuePress navbar logo with the sidebar toggle.

## [1.2.0] - 2026-06-09

Feature release headlined by a **sweeping hardening pass across all eight rule-based advisors** — recalibrated
severities, far fewer false positives and negatives, and a wave of new high-signal checks — backed by the new ability to
**dismiss and restore advisor findings**. It also makes the console load even when the host disables Spring's
static-resource mappings, and fixes scheduled-task and sample-app native-image regressions.

### Added

- **New high-signal advisor checks** added across the rule advisors during the hardening pass, including Architecture
  (`ARCH-SPRING-017`, `ARCH-SPRING-018`, `ARCH-SPRING-019`, `ARCH-SPRING-021`, `ARCH-MOD-001`), REST API (`RAPI-MAP-008`,
  `RAPI-RESP-008`, `RAPI-VER-005`), and GraalVM (`GRAAL-CLASSGEN-001`, `GRAAL-INIT-002`, `GRAAL-SER-002`,
  `GRAAL-SCAN-001`, `SPRING-AOT-001`, `SPRING-AOT-002`, plus new class-generation, classpath-scanning, and Spring-AOT
  categories), with further new Spring, Hibernate, Memory, Security, and Pentesting rules catalogued in the refreshed
  `docs/*-CHECKS.md`.
- Added `CRITICAL` severity to the Architecture, REST API, Spring, Hibernate, Security, Pentesting, and GraalVM advisors;
  official "learn more" links to the Architecture, Pentesting, and GraalVM panels; and a muted analysis-error channel on
  the Architecture, Spring, Security, and Memory panels that surfaces rules which throw during evaluation.
- **Dismiss / restore advisor findings** — every finding surfaced by the seven Overview-scored rule advisors
  (Architecture, REST API, Spring, Hibernate, Memory, Security, Pentesting) now carries a _Dismiss_ button. Dismissed
  rules collapse into a "Dismissed rules" list at the bottom of the panel and are excluded from the panel's finding
  count, severity bars, the panel's own advisor score, and the weighted Overview score; they can be restored at any time.
  Dismissals are keyed by the globally unique rule IDs, applied server-side, and persisted under the `dismissedRules`
  node of a developer-local `.bootui/boot-ui.yml` file (next to the runtime overrides file), so they survive restarts and
  stay consistent between each panel and the Overview dashboard.
- Per-advisor **0–100 score** now shown on each of those advisor panels (100 minus the weighted finding penalty), always
  matching the value the Overview dashboard computes for that advisor, rendered through a shared `AdvisorScoreCard`.

### Changed

- **Hardened every rule-based advisor (Phases 0–8)** — Architecture, REST API, Spring, Hibernate, Memory, Security,
  Pentesting, and GraalVM — with context- and profile-aware dynamic severity, recalibrated thresholds, canonical Spring
  Boot 4 property names, and fewer false positives/negatives. The matching `docs/*-CHECKS.md` catalogues were refreshed.
- Reworked Memory heap-pressure detection to measure pressure from a post-GC dual snapshot and to track GC-overhead
  trend across scans rather than within a single forced GC.
- Extracted shared UI building blocks (`FlashBanner`, `SpinnerButton`, `ReadOnlyNotice`, `AdvisorScoreCard`) and a shared
  `AgentSessionController` base for the Copilot and Claude Code panels.
- Changed the Spring panel icon from a lightbulb to a leaf.
- Restructured the documentation site: install the starter via a dedicated dev Maven/Gradle profile, render the
  Maven/Gradle setup as VuePress tabs, reordered the setup sections, and removed the README badges.

### Fixed

- Serve the BootUI console assets even when the host sets `spring.web.resources.add-mappings=false`: a dedicated
  `WebMvcConfigurer` maps `/bootui/**` to the bundled SPA without re-exposing the host's own static resources, and
  disabled static-resource mappings are now logged at `WARN` with a troubleshooting note (#291).
- Scheduled Tasks panel no longer returns HTTP 500 when the host application registers its own `ScheduledTaskHolder`
  bean; tasks are aggregated across every holder so programmatically registered timers appear alongside `@Scheduled`
  tasks (#288).
- Fixed the sample app's native-image smoke test for its new `@Inheritance(TABLE_PER_CLASS)` and `UUID` `@Id` demo
  entities by registering reflection hints for the `UnionSubclassEntityPersister` constructor and the `UUID[]` multi-id
  loader array in the sample app's native-hints configuration.
- Fixed a Liquibase connection leak and a brittle cache type check flagged by static analysis, and stabilized the
  Hibernate advisor end-to-end tests against cached scan state and duplicated paged collection-fetch detail rendering.

## [1.1.0] - 2026-06-07

Feature release that introduces a dedicated **Advisors** workspace with three new rule-based panels (Spring, REST API,
Memory), expands every existing advisor catalogue, lets the console run from non-web applications, and reorganizes the
runtime memory panels — while hardening the safety filter and correcting the Actuator-defaults precedence.

### Added

- **Spring panel** — new advisor that inspects the running application for Spring and Spring Boot 4 best-practice issues,
  shipping 31 curated rules documented in `docs/SPRING-CHECKS.md`.
- **REST API panel** — new advisor that audits controller/handler mappings against 36 curated REST design rules,
  documented in `docs/REST-API-CHECKS.md`.
- **Memory panel** — new advisor with 22 heap, native, GC, and finalizer checks documented in `docs/MEMORY-CHECKS.md`.
- **Live Memory** runtime panel showing live JVM memory-pool usage, split out from the previous runtime Memory panel.
- Support for serving the BootUI console from **non-web (command-line) Spring Boot applications**, not just servlet web
  apps.
- GitHub panel **open-issues drawer** that lists open repository issues with bounded refreshes.
- Five new Spring Security checks — BCrypt work-factor floor (`SEC-AUTH-006`), Referrer-Policy and Permissions-Policy
  headers (`SEC-HEAD-005`/`SEC-HEAD-006`), concurrent session control (`SEC-SESSION-007`), and HTTPS enforcement in
  production (`SEC-CONFIG-006`), documented in `docs/SECURITY-CHECKS.md`.
- Expanded advisor coverage across the board: GraalVM readiness grows from 5 to 12 checks, plus new Architecture,
  Hibernate, and Memory rules, all reflected in their `docs/*-CHECKS.md` catalogues.

### Changed

- Grouped the rule-based panels under a dedicated **Advisors** navigation group (Architecture, REST API, Spring,
  Hibernate, Memory, Security) alongside Pentesting and Vulnerabilities, and wired the new advisors into the Overview
  security & health scoring dashboard.
- Renamed panels for clearer URLs and class names: dropped the "Advisor" suffix from the rule-based panels, renamed
  Tuning Advisor to **JVM Tuning** and Dependencies to **Vulnerabilities**, and split the runtime Memory panel into
  **Live Memory** and **JVM Tuning**. Legacy routes (`/security-advisor`, `/hibernate-advisor`, `/tuning-advisor`,
  `/pentest`, `/dependencies`, `/rest-advisor`, `/spring-advisor`, `/memory-advisor`, `/profiles`) redirect to the new
  paths.
- Aligned endpoint, controller, and DTO naming for Log Tail, HTTP Probe, Database Connection Pools, and Profile Diff,
  and fixed the Database Connection Pools component pluralization.
- Standardized the empty/unavailable panel state behind a shared `UnavailableState` component and consolidated shared
  time/number formatting helpers across views.
- Renamed the advisor catalogue docs from `*-ADVISOR-CHECKS.md` to `*-CHECKS.md`, closed rule-ID numbering gaps so every
  sequence is continuous, corrected drifted Hibernate doc entries (`HIB-CONFIG-016`, `HIB-CONFIG-017`, `HIB-MAP-018`,
  `HIB-MAP-019`), and rewrote `docs/REST-API-CHECKS.md` in the shared `### ID - Title` format. Renumbered rule IDs:
  `MEM-GC-002` → `MEM-GC-001`, `MEM-GC-003` → `MEM-GC-002`, `RAPI-VALID-003` → `RAPI-VALID-002`, `RAPI-VALID-004` →
  `RAPI-VALID-003`, and `HIB-FETCH-007` → `HIB-FETCH-006`.
- Gave AI Usage, Copilot, and Claude Code their own documentation sections and synced `docs/FEATURES.md`,
  `docs/PROPERTIES.md`, the README feature table, and the screenshots with the Advisors regrouping.
- Scoped the Maven Central release secrets to a protected `maven-central` GitHub environment.

### Fixed

- BootUI's Actuator defaults are now contributed as true lowest-priority defaults so a host application's
  `EnvironmentPostProcessor` settings always win (#246).
- Hardened the localhost-only safety filter and added value-based secret masking for browser-visible property values.
- Fixed dark-mode contrast on Bootstrap contextual utilities and on the GitHub quota metric cards.
- Made the developer tooling more robust: `run-sample.sh` no longer fails on macOS Bash 3.2, the getting-started scripts
  can target `main` or one of the last five tags, the Maven offline setup primes `spring-boot-maven-plugin`, and the
  Copilot dev server self-heals on a cold worktree `.m2`.
- Fixed documentation-site build failures (GitHub Pages Node 24 configuration, backticked angle-bracket type fragments,
  and the Hibernate advisor image) and pinned the Ollama Docker Compose port to stabilize the e2e startup.

## [1.0.0] - 2026-06-05

First stable BootUI release, focused on promoting the current local developer-console surface to `1.0.0`, adding the
Spring Security Advisor, and publishing the redesigned documentation site.

### Added

- Security Advisor panel with explicit Spring Security hardening checks for authentication, authorization, CSRF, sessions,
  headers, CORS, method security, actuator exposure, OAuth2 resource-server validation, and security configuration hygiene,
  plus the `docs/SECURITY-ADVISOR-CHECKS.md` rule catalogue.
- Overview security & health scoring dashboard that can run the available Architecture, Hibernate Advisor, Security
  Advisor, Vulnerabilities, Pentesting, and GitHub scanners individually or together.
- VuePress documentation site, GitHub Pages workflow, repository documentation, and setup/sample-app pages for the public
  docs at `julien-dubois.com/boot-ui`.

### Changed

- Copilot and Claude Code dashboards now emphasize input/output token usage charts while retaining event and failure
  views for sanitized local agent activity.
- Refreshed release-facing screenshots for the 1.0 surface, including Overview, Security Advisor, Copilot, and Claude Code,
  and verified the screenshot set against the routed panel list.
- Updated the implementation roadmap so completed 1.0 work is separated from the next workstream for trace/log/request
  correlation, bean graph visualization, and an e-mail viewer.

### Fixed

- Detected proxied Hikari data sources in the Database Connection Pools panel.
- Corrected Spring Modulith Flyway migration reporting so module-specific history tables remain visible and read-only.
- Fixed VuePress markdown links, homepage setup navigation, GitHub Pages Node 24 configuration, and sample quick-start
  script UI bundling.

## [0.5.1] - 2026-06-04

Patch release focused on preserving BootUI startup in applications that do not include Spring Security Core while
keeping Security Logs support available when Spring Security authentication events are present.

### Fixed

- Guarded BootUI's auto-configured Spring Security audit event repository behind Spring Security authentication event
  classes so applications without `spring-security-core` no longer fail at startup.

## [0.5.0] - 2026-06-04

Fifth BootUI release, focused on repository context, servlet session inspection, database migration/advisor tooling, and
release-facing documentation for the expanded 0.5.0 panel surface.

### Added

- GitHub dashboard panel under Overview, with local repository detection, bounded refreshes for pull requests, issues,
  latest GitHub Actions executions, dynamic rate-limit/quota drawers, security signals, and Copilot usage report metadata.
- HTTP Sessions panel backed by embedded Tomcat session metadata, with masked session identifiers and attributes by
  default plus confirmation-gated clear/destroy actions.
- Database navigation group with read-mostly Flyway and Liquibase panels, including migration/change-set inventory and
  confirmation-gated `migrate`, `clean`, and `update` actions.
- Hibernate Advisor panel with explicit Hibernate/JPA mapping, configuration, caching, and repository-query checks, plus
  the `docs/HIBERNATE-CHECKS.md` rule catalogue.
- Auto-configured an in-memory Spring Boot `AuditEventRepository` for Security Logs when BootUI is active, audit events are
  enabled, and the host app has not provided its own repository.
- Sample-app quick-start scripts for macOS/Linux and Windows PowerShell.

### Changed

- Standardized panel auto-refresh controls and visibility-aware refresh behaviour across live panels.
- Updated the GitHub Actions drawer to show latest execution details and count only the latest run per workflow when
  reporting workflow failures.
- Expanded the sample app with Flyway/Liquibase schemas, richer Hibernate/JPA sample mappings, HTTP session data, security
  events, and release screenshots for the current sidebar surface.
- Reworked the implementation roadmap so already-shipped database/security/runtime panels moved out of the plan and the
  next workstream focuses on trace/log/request correlation, bean graph visualization, and an e-mail viewer.

### Fixed

- Corrected AI Usage telemetry KPIs and summary calculations.
- Matched the exact BootUI root paths in BootUI's highest-priority Spring Security chain so host SPA fallback filters do
  not intercept `/bootui` before BootUI can redirect to its console.
- Tightened security diagnostics, value exposure handling, panel availability wiring, and release documentation for the
  0.5.0 panel surface.

## [0.4.0] - 2026-06-03

Fourth BootUI release, focused on new local runtime/security diagnostics, native-image readiness tooling, and the
current grouped sidebar surface.

### Added

- GraalVM native-image readiness panel with on-demand host-application checks for reflection, dynamic proxies, resources,
  serialization, native access, dependency reachability metadata, and a reviewable `reachability-metadata.json` scaffold.
- Threads panel backed by in-process `ThreadMXBean` snapshots, with state counts, deadlock detection, virtual-thread
  context, server-side filtering/paging, stack expansion, and confirmation-gated raw dump download.
- HTTP Exchanges panel for recent inbound application requests, including bounded recording, server-side filtering,
  masked headers/query data, trace identifiers, and drawer-style request/response details.
- Security Logs panel and Security navigation group for recent Spring Boot audit events, bounded retention, masking,
  filters, and visibility-aware auto-refresh.
- Weekly GraalVM native-image build workflow and native-image sample-app Docker assets/readiness documentation.

### Changed

- Renamed the Cache surface to Spring Cache across the route metadata, docs, and release-facing screenshots.
- Updated HTTP Exchanges and Security Logs to use standard visibility-aware auto-refresh instead of manual refresh
  buttons.
- Updated the next-feature roadmap so already-shipped panels moved out of the plan and the next workstream is focused on
  migrations, trace/log/request correlation, and bean graph visualization.
- Bumped build and dependency plumbing, including Spring AI 2.0.0-M8, the GraalVM native build tools plugin, Sonatype
  Central publishing plugin, Maven plugins, `actions/checkout`, and the frontend/API error-handling utilities.

### Fixed

- Added Spring AOT runtime hints and sample native-image wiring so BootUI resources, DTOs, heap-dump/security reflective
  calls, and Maven metadata survive native-image builds.
- Updated feature documentation, sample-app walkthroughs, security policy, Playwright docs, and screenshots for the
  current sidebar grouping and full 0.4.0 panel surface.
- Completed Java/frontend audit follow-ups around nullability, shared helpers, frontend API normalization, and duplicate
  utility removal.

## [0.3.0] - 2026-06-02

Third BootUI release, focused on the new JVM Tuning Advisor, Java 17 baseline, stronger AI telemetry guidance, and
release-facing documentation/screenshots for the updated menu surface.

### Added

- Tuning Advisor panel split out from Memory, with fixed bare-metal JVM options, percentage-based Kubernetes
  `JAVA_TOOL_OPTIONS`, optional Burstable request sizing, Actuator probe YAML, and virtual-thread sizing guidance based on
  the running JVM context.
- LangChain4j support in the AI Usage panel. BootUI now detects Spring AI and/or LangChain4j, shows the selected
  framework with header badges, and offers side-by-side Spring AI and LangChain4j telemetry setup guides explaining the
  dependency and configuration each needs to emit GenAI spans (including optional prompt/completion content capture).
- Health panel setup guidance: a disabled state with guidance when no Actuator `HealthEndpoint` is available, and
  guidance (without changing reported statuses) when a health tree contains only Spring Boot's default indicators.
- Frontend test coverage for the shared auto-refresh and refresh-state utilities, the Health view, and the panel header
  component.

### Changed

- ArchUnit is now bundled transitively through `bootui-spring-boot-starter`, so the Architecture panel works out of the
  box without an extra application dependency; the sample app's redundant direct dependency was removed.
- Architecture checks were expanded with additional coding-practice and Spring proxy/stereotype heuristics.
- Pentesting checks now align their local-only hygiene catalogue with OWASP Top 10 2025.
- Release preparation (Maven module versions, README install snippet, release commit, and tag) and Maven Central
  publishing are unified into a single `Release` workflow, replacing the separate `Prepare Release` workflow.
- Lowered the build baseline from Java 25 to Java 17, updating the Maven compiler release, the CI build matrix, and
  CodeQL analysis.
- The Data menu item is now Spring Data, and the database pool view is consistently named Database Connection Pools.
- AI Usage content-capture guidance and documentation now cover LangChain4j alongside Spring AI.
- Copilot and Claude Code agent panels now share the standard panel refresh behavior.

### Fixed

- Removed auto-refresh flicker by showing panel skeletons only on first load and sharing refresh state across panels.
- Normalized frontend errors when the backend is offline or unavailable.
- Removed duplicated `formatDuration`/`formatTime` helpers in the Traces panel in favor of the shared format utilities.
- Updated the footer GitHub link, release docs, and regenerated feature screenshots for the current sidebar menu.

## [0.2.0] - 2026-06-01

Second BootUI release, focused on local security diagnostics, three new local-only diagnostics panels — Architecture
(ArchUnit), Heap Dump (value-free class histogram), and Database Connection Pools — and safer defaults around
host-application security, plus release/documentation hardening for the full visible panel surface.

### Added

- Architecture panel that runs a curated, zero-config ArchUnit ruleset against the host application's own classes for
  package-cycle, coding-practice, and Spring-stereotype hygiene, with an on-demand scan and the latest report.
- Heap Dump panel that captures local JVM heap dumps on demand and analyzes a value-free class histogram, including a
  `max-classes` memory cap plus big-objects and collection-bloat smart filters. Raw `.hprof` download stays disabled by
  default because dumps contain plaintext secrets.
- Database Connection Pools panel that surfaces read-only database pool sizing, masked JDBC metadata, and a live
  active/idle/total/pending saturation chart, failing closed when pool support is unavailable.
- Pentesting panel with explicit, local-only OWASP-aligned hygiene checks for security headers, CORS behavior, cookie
  flags, verbose errors, Spring Security wiring, actuator exposure, DevTools, H2 console, and risky configuration values.
- BootUI Spring Security integration that keeps `/bootui/**` and `/bootui/api/**` reachable in local applications using
  Spring Security while preserving the localhost-only safety filter.
- Automatic local application trace capture so Traces and AI Usage can populate from the host app without requiring a
  separate local OTLP exporter setup.
- CI test report publishing for Maven/JUnit and Playwright runs.
- Playwright end-to-end coverage for the Pentesting panel.

### Changed

- Monitoring panels now hide BootUI's own beans, mappings, loggers, metrics, traces, and related runtime data by default
  through `bootui.monitoring.exclude-self=true`.
- Request-driven BootUI controllers and agent session stores are lazy-loaded, and agent session parsing is bounded to
  avoid unnecessary startup work.
- Vulnerability findings are sorted by severity/importance first, with stable ordering inside each severity group.
- The app shell, panel headers, skeleton states, auto-refresh controls, and command palette/navigation were polished for a
  faster and more consistent UI.
- Architecture, Pentesting, and Vulnerabilities now share clearer scan status messaging.
- Startup Timeline configuration, panel read-only controls, application property reference docs, pentest catalogue docs,
  feature docs, screenshots, and E2E documentation were reconciled with the implemented `0.2.0` behavior.
- Refreshed and reorganized `SECURITY.md`.
- Regenerated feature screenshots and extended the docs screenshot script to cover the Architecture, Heap Dump, and
  Database Connection Pools panels.

### Fixed

- Fixed sample-app and BootUI security audit findings, including enabling CSRF protection in the sample app.
- Fixed hidden-BootUI-internals assumptions in Beans E2E coverage after self-data filtering became the default.
- Removed duplicated panel headings and restored Overview/Metrics heading behavior.
- Fixed BootUI navigation controls, including theme persistence and command palette shortcut behavior.
- Fixed the Claude Code sidebar icon.
- Added registry-level coverage so global read-only mode is checked for every action-capable panel.

## [0.1.0] - 2026-05-29

First final BootUI release. This promotes the alpha line to the final `0.1.x` coordinate while keeping the local-only,
developer-console safety model and the full visible panel surface.

### Added

- Copilot and Claude Code panels for sanitized local activity dashboards, including session summaries, activity trends,
  tool and model usage, failures, and bounded live refresh behavior.
- Vitest, Vue Test Utils, and jsdom coverage for reusable frontend behavior, wired into the Maven test phase.

### Changed

- README, feature documentation, the release plan, and generated feature screenshots are aligned with the final `0.1.0`
  panel surface and install coordinates.
- Beans, Conditions, Mappings, Configuration, and Loggers use bounded server-side filtering and pagination for
  high-cardinality applications.
- The full visible route set is promoted from the supported alpha surface to the supported `0.1.0` release surface.

### Fixed

- Dev Services discovery and controls handle prototype-scoped Testcontainers beans, stopped containers, null log output,
  restart failures, metadata-only connection detail masking, and abstract bean definitions more defensively.

## [0.1.0-alpha.5] - 2026-05-27

Latest tagged alpha with the expanded panel surface, telemetry features, and release hardening.

### Added

- Backend test coverage for `BootUiProperties` binding, additional activation rules
  (devtools activation, custom disabled profiles, invalid `bootui.enabled` failing closed),
  controller mappings and DTO serialization for every `/bootui/api/**` endpoint,
  Config controller HTTP CRUD with masking modes and restart warnings, logger level
  mutation/clear, broader secret masking coverage, and panel edge cases
  (Data, Scheduled, HTTP Probe, Log Tail, Profile Diff, Security, Metrics, DevTools,
  Dev Services, Memory).
- `CHANGELOG.md` and a sample-app walkthrough at `bootui-sample-app/README.md`.
- Spring Cache panel for cache managers, known caches, safe local sizes, Micrometer cache metrics,
  cache annotations, and confirmation-gated clear actions.
- Embedded OTLP/HTTP trace receiver at `/bootui/api/otlp/v1/traces`, plus Traces and AI Usage panels
  for local trace waterfalls, Spring AI observations, token usage, tool calls, and bounded in-memory
  telemetry.
- Optional panel availability metadata so the sidebar can dim panels whose backing classpath,
  Actuator endpoint, or local infrastructure is unavailable.
- GitHub project links in the UI and sample-app AI prompt helpers for exercising telemetry locally.

### Changed

- Documentation reconciled with the implemented `AUTO|ON|OFF` activation model,
  persisted runtime overrides, plain-JavaScript Vue 3 frontend, and the full
  visible panel set as supported alpha functionality.
- Dev Services, Vulnerabilities, Traces, and AI Usage now have stronger empty/disabled states,
  bounded data handling, and focused Playwright coverage.
- Vulnerability scan results are retained in memory after an explicit scan so the panel can keep
  showing the latest local results.
- Sample app PostgreSQL JDBC driver updated to 42.7.11.
- Repository formatting checks and release documentation now cover the alpha release workflow,
  README version synchronization, and Maven Central signing constraints.

### Fixed

- Corrected the sample Redis service port mapping used by the Cache panel tests.
- Fixed GitHub code scanning workflow permissions and an incomplete string escaping/encoding finding.

### Security

- Enabled CSRF protection in the sample app.

## [0.1.0-alpha.4] - 2026

First successful Maven Central publication of the alpha line.

### Fixed

- Source-less modules (`bootui-ui`, `bootui-spring-boot-starter`) now attach an
  empty `javadoc.jar` at the `package` phase so the release-profile `gpg-sign`
  binding (running at `verify`) signs it. Without this, Sonatype Central
  rejected the deployment.

## [0.1.0-alpha.3] - 2026

Release attempt blocked by missing javadoc signatures (see alpha.4 fix).

## [0.1.0-alpha.2] - 2026

Initial public alpha attempt. Sonatype Central deployment failed; subsequent
attempts re-used the GAV coordinate and required version bumps because
Sonatype consumes a coordinate even on failure.

## [0.1.0-alpha.1] - 2026

First tagged BootUI alpha. Highlights of the harden-all-visible-panels scope:

### Added

- Spring Boot 4 starter (`bootui-spring-boot-starter`) and auto-configuration
  (`bootui-autoconfigure`) packaged with a Vue 3 / Vite UI shell served from
  `/bootui` and `/bootui/api/**`.
- `bootui.enabled=AUTO|ON|OFF` activation model with profile-based enablement
  (`dev`, `local`) and disablement (`prod`, `production`); fail-closed on
  invalid values; auto-activation when Spring Boot DevTools is on the classpath.
- Localhost-only safety filter with explicit `bootui.allow-non-localhost` opt-out.
- Secret-masking for browser-visible property names and values, with three
  exposure modes: `MASKED` (default), `METADATA_ONLY`, and `FULL`.
- Runtime configuration overrides persisted to
  `.bootui/application-bootui.properties` and applied at high precedence on the
  next start; restart/rebind caveats surfaced for every override mutation.
- Internal Actuator bridge that returns stable BootUI DTOs even when the
  underlying Actuator endpoint or Spring module is absent.
- Panels: Overview, Beans, Conditions, Configuration, Mappings, Health, Loggers,
  Startup Timeline, JVM Memory (with suggested options), Spring Data,
  Scheduled Tasks, HTTP Probe (loopback-only), Log Tail, Profile Diff,
  Spring Security, Micrometer Metrics, Dependency inventory + OSV vulnerability
  scan, Spring Boot DevTools reload/restart, and Dev Services for Docker Compose,
  Testcontainers beans, and service connection metadata.
- Sample application (`bootui-sample-app`) and a Playwright end-to-end suite
  exercising every visible browser route.

### Notes

- Spring Boot 3.x support, Gradle plugin, CLI, extension SPI, hosted features,
  request history, distributed tracing, multi-service orchestration, and live
  Docker Compose lifecycle control are intentionally out of scope for the alpha.

[Unreleased]: https://github.com/jdubois/boot-ui/compare/v1.20.0...HEAD
[1.20.0]: https://github.com/jdubois/boot-ui/compare/v1.19.0...v1.20.0
[1.19.0]: https://github.com/jdubois/boot-ui/compare/v1.18.0...v1.19.0
[1.18.0]: https://github.com/jdubois/boot-ui/compare/v1.17.0...v1.18.0
[1.17.0]: https://github.com/jdubois/boot-ui/compare/v1.16.0...v1.17.0
[1.16.0]: https://github.com/jdubois/boot-ui/compare/v1.15.0...v1.16.0
[1.15.0]: https://github.com/jdubois/boot-ui/compare/v1.14.1...v1.15.0
[1.14.1]: https://github.com/jdubois/boot-ui/compare/v1.14.0...v1.14.1
[1.14.0]: https://github.com/jdubois/boot-ui/compare/v1.13.1...v1.14.0
[1.13.1]: https://github.com/jdubois/boot-ui/compare/v1.13.0...v1.13.1
[1.13.0]: https://github.com/jdubois/boot-ui/compare/v1.12.0...v1.13.0
[1.12.0]: https://github.com/jdubois/boot-ui/compare/v1.11.0...v1.12.0
[1.11.0]: https://github.com/jdubois/boot-ui/compare/v1.10.0...v1.11.0
[1.10.0]: https://github.com/jdubois/boot-ui/compare/v1.9.0...v1.10.0
[1.9.0]: https://github.com/jdubois/boot-ui/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/jdubois/boot-ui/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/jdubois/boot-ui/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/jdubois/boot-ui/compare/v1.5.2...v1.6.0
[1.5.2]: https://github.com/jdubois/boot-ui/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/jdubois/boot-ui/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/jdubois/boot-ui/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/jdubois/boot-ui/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/jdubois/boot-ui/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/jdubois/boot-ui/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/jdubois/boot-ui/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/jdubois/boot-ui/compare/v0.5.1...v1.0.0
[0.5.1]: https://github.com/jdubois/boot-ui/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/jdubois/boot-ui/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/jdubois/boot-ui/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/jdubois/boot-ui/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/jdubois/boot-ui/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/jdubois/boot-ui/compare/v0.1.0-alpha.5...v0.1.0
[0.1.0-alpha.5]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.5
[0.1.0-alpha.4]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.4
[0.1.0-alpha.3]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.3
[0.1.0-alpha.2]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.2
[0.1.0-alpha.1]: https://github.com/jdubois/boot-ui/commit/6ad9e3371c1c92d82597400ffa9063b7746bafe7
