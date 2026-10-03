# Java Agent

The Java Agent panel explains whether the optional BootUI `-javaagent` is attached to the current JVM, whether this
application has claimed it, and how to attach it when it is missing. The panel is in **Developer tools**, immediately
after **Command Line**, and is view-only on Spring MVC, Spring WebFlux, and Quarkus.

The agent is a development-time helper that propagates a request's correlation through the JDK's executors, so work an
application hands to a raw thread pool or `CompletableFuture` is owned by the request that handed it over. It is
local-only, exports nothing, and stays dormant until BootUI claims it. A Spring or Quarkus application that starts
without `-javaagent` behaves exactly as before and reports `NOT_ATTACHED` with setup snippets.

## Attaching the agent

Add the published `bootui-agent` jar to the JVM with an explicit `-javaagent:` option. When the agent is already
attached, BootUI reports the jar path from the agent itself. Otherwise the setup card points at the expected local Maven
repository path:

```text
~/.m2/repository/com/julien-dubois/bootui/bootui-agent/<version>/bootui-agent-<version>.jar
```

If the application was started with `-Dmaven.repo.local=...`, that repository is honored. When the jar is not present,
the first tab is **Download the agent**:

```bash
mvn dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:<version>
```

The panel detects `pom.xml`, `build.gradle`, or `build.gradle.kts` in the working directory and orders the matching
snippets first. Available snippets include:

- Spring Boot Maven plugin `<agents>` configuration.
- Gradle Kotlin and Groovy `bootRun { jvmArgs(...) }` examples for Spring.
- Quarkus dev mode: `./mvnw quarkus:dev -Djvm.args="-javaagent:..."`.
- Surefire/Failsafe `<argLine>@{argLine} -javaagent:...</argLine>`, which preserves JaCoCo's own `argLine`.
- IntelliJ IDEA VM options.
- `JAVA_TOOL_OPTIONS` scoped to one run command, such as `JAVA_TOOL_OPTIONS="-javaagent:..." ./mvnw spring-boot:run`.
  Never export it in a shell: every JVM started there (Maven, the Gradle daemon, IDE tooling) would load the agent and
  print HotSpot's class-data-sharing warning.

Every snippet tab has a **Copy** button. Snippet ids and labels are stable: `maven-download` (**Download the agent**),
`maven-plugin` (**Spring Boot Maven plugin**), `gradle-kotlin`, `gradle-groovy`, `quarkus-dev`, `surefire`,
`intellij`, and `java-tool-options`. The agent is not for production JVMs or AOT/native-image runs.

## Status states

`GET /bootui/api/java-agent`, `get_agent_status`, and `bootui agent status` return the same `JavaAgentReport` shown by
the panel. The report includes `state`, `reason`, `agentVersion`, `bootUiVersion`, `protocol`, `expectedProtocol`, `jdk`,
`loadMode`, `jarPath`, `startupMicros`, `claim`, `heldBy`, `sensors`, `retransformation`, `counters`, `messages`,
`warnings`, and `setup` (with `jarPath`, `jarFound`, `buildTool`, and `snippets`).

| State | Meaning |
| --- | --- |
| `NOT_ATTACHED` | The bridge is not on the bootstrap class path; the JVM started without `-javaagent`. |
| `DORMANT` | The bridge is present, but this application has not claimed it, for example because `bootui.agent.enabled=false`. |
| `ARMED` | This application owns an armed claim. |
| `HELD` | Another application in the same JVM owns the agent; `heldBy` names it. |
| `DISARMED` | This run ended its claim. |
| `UNAVAILABLE` | The bridge is present but the agent did not start, the protocol differs, or the runtime cannot use it. |
| `FAILED` | The agent rejected or failed this application's claim. |
| `DISABLED` | Agent support is disabled, including Quarkus production mode. |

A different agent version on the same protocol is shown as a warning. Protocol mismatches are unavailable rather than
best-effort.

## Claims and lifecycle

Spring claims the agent from `BootUiAgentClaimEnvironmentPostProcessor`, registered in `META-INF/spring.factories`, as
soon as BootUI's activation is resolved, with the main application class's package and `bootui.agent.packages`. The claim
is refined with the auto-configuration packages when the application context refreshes, disarmed on close or startup
failure, and released when BootUI is inactive or `bootui.agent.enabled=false`, so the agent removes its transformers.

Quarkus claims from a `STATIC_INIT` recorder in dev and test launch modes, refines on startup, and disarms on shutdown.
Production launch mode never claims the agent and reports `DISABLED` with reason `Quarkus production mode`.

`bootui.agent.mode=auto` resolves to `test` under JUnit, TestNG, Cucumber, Spring Boot test, or Quarkus test launch
mode; otherwise it resolves to `dev`. A dev application can take over from a test application, and DevTools restarts or
Quarkus live reloads replace the same application slot.

The engine looks up the bridge only from the bootstrap class loader, so an accidental application-classpath copy is
ignored.

Sensor removal runs off the caller's thread. A new claim supersedes a queued release; if removal has already begun,
the sensor is installed and self-tested again afterward. This applies to both `executors` and `threads`. Each threads
transformer retains its own package history for restoration: reclaiming with different packages cannot leave advice
on subclasses from the previous claim. The replacement transformer uses the new claim's packages.

## The executors sensor

A claim asks for the sensors in `bootui.agent.sensors`: `executors`, the default, and the opt-in
[`threads`](#the-threads-sensor). The agent installs each one once, on its own thread, then self-tests every hook with private pools before it propagates anything. The sensor row
shows its state (`installing`, `installed`, `failed`, or `off`), how long installing and the self-test took, the
self-test's result, how many JDK types it instrumented, and the types that failed to transform. Without a sensor, the
panel says:

> No sensor installed: the agent installs the sensors this application asks for when it claims the agent
> (bootui.agent.sensors).

### How a task is propagated

Where an executor *receives* a task (a key hook), the agent asks BootUI for the submitting thread's correlation: the
request and execution ids, the trace and span ids, and the route, as strings and numbers only, never a transaction, a
data source, or an application object. Where the executor *runs* the task (an apply hook), BootUI opens that request
again on the worker as a child execution, `async-…`, and closes it when the task ends. The child execution's work, its
SQL, REST calls, messages, and exceptions, is then owned by the request at the `PROPAGATED` correlation tier, and each
task becomes one `agent.executors` event in the runtime journal: Live Activity nests it under its request as an
`ASYNC` entry, and the request profile lists it under **Handoffs**.

Nothing is opened when the worker already works for that request (the caller ran the task itself, or a managed executor
already propagated it), for BootUI's own work, on `bootui-` threads, or when no request or execution owns the work. A
task handed over by a scheduled run or a consumed message keeps that execution's id. Propagated work is not metered as
its request's own CPU time and allocation: the request's resources stay those of its own threads, and each handoff
records its own allocated bytes (none on a virtual thread).

| Hook | Role | JDK type |
| --- | --- | --- |
| `ThreadPoolExecutor` | receives tasks | `ThreadPoolExecutor.execute`, `submit`, and `addWorker` |
| `ScheduledThreadPoolExecutor` | receives tasks | one-shot delayed tasks; periodic tasks are never propagated |
| `ForkJoinPool` | receives tasks | `execute`, `submit`, and `invoke` of a root task |
| `ForkJoinTask adapters` | receives tasks | the pool's `Runnable` and `Callable` adapters |
| `ForkJoinTask.fork` | receives tasks | a `fork()` from a thread outside the pool |
| `DelayScheduler` | receives tasks | JDK 25+ delayed tasks, such as `CompletableFuture.delayedExecutor` |
| `CompletableFuture.ThreadPerTaskExecutor` | receives tasks | the thread-per-task fallback of `CompletableFuture` (JDK 17 to 25) |
| `ThreadPoolExecutor.runWorker` | runs tasks | every task a `ThreadPoolExecutor` runs |
| `ForkJoinTask.doExec` | runs tasks | every task a `ForkJoinPool` runs |
| `CompletableFuture.Async` | runs tasks | `supplyAsync` and `runAsync` stages, whose outcome it reads |

The hooks table shows, for each one, whether this JDK has its type, whether the agent instrumented it, its self-test
result (`passed`, `failed`, `not-exercised`, `unsupported`, or `not-run`), and how many tasks it received or ran. A
self-test failure of a `ThreadPoolExecutor` or `ForkJoinTask.doExec` hook disables propagation for that claim, and the
panel says why; the next claim tests again. The hooks are verified on JDK 17, 21, 25, 26, and 27; on any other JDK the
report warns that the self-test decides.

### Counters

| Counter | What it counts |
| --- | --- |
| Pending | Tasks received from owned work that have not run yet. |
| Never applied | Received tasks that never reached an instrumented run point, such as tasks of pools whose workers started before the claim. |
| Ambiguous | Tasks submitted more than once by different owners, left unowned. |
| Stale | Tasks received under an earlier claim, never reopened after a restart. |
| Refused | Snapshots the bridge refused because they held more than strings and numbers. |
| Virtual threads skipped | Virtual-thread continuations, which keep their own context. |
| Periodic tasks skipped | Repeating scheduled tasks, which are never propagated. |
| Wrappers skipped | Tasks already carrying their context (`bootui.agent.executors.skip-tasks`). |
| Threads skipped | Workers whose executor propagates the context itself (`bootui.agent.executors.skip-threads`). |
| Failed tasks | Propagated tasks that ended with an exception. |

New submissions and skip counters are recorded only while the current armed claim asks for `executors` and that
sensor has not been disabled by its self-test. Pending entries are still drained while recording is off, without
reopening their snapshots. A handoff already opened before recording stopped is still closed and reports its outcome.

### Accepted limits

- Parallel-stream subtasks run by other workers stay unowned: only root submissions and a `fork()` from outside the pool
  are propagated.
- A task handed over by a draining or serializing executor carries the context of the thread that handed it over.
- Pools whose workers started before the first claim never apply their tasks; they are counted as never applied.
- A task submitted before a DevTools restart or a Quarkus live reload that ends after it is never reopened, and a task
  from the previous run that is still running when it ends is lost.
- If the same task object has pending submissions across claim generations, all overlapping submissions stay
  ambiguous and run unowned until their pending count drains, even when their owner ids match. A task retained after
  `shutdownNow`, `purge`, or a discard can therefore stay ambiguous if a removed submission never reaches a run or
  release hook; changing the generation never replaces its pending snapshot.
- A rejected direct fork/join root submission and a failed `CompletableFuture` thread-per-task start release their
  snapshots, including when the rejected fork/join task is already completed. Fork/join cleanup observes the pool's
  admission method, not the subsequent join: an application exception rethrown by `ForkJoinPool.invoke` after its task
  ran does not release another pending submission. A custom pool thread factory throwing after enqueueing remains an
  exceptional path where ownership may be lost.
- One window, `bootui.agent.executors.max-handoff` (5 minutes), bounds a handoff: a task belongs to its request when
  it started no later than that after the request ended (a later one is only counted in the request profile); its work
  recorded more than that after the task started is not attributed to the request; and a task that ends more than that
  after it started is published `capped`.
- SQL a propagated task runs is never stamped with its request's phase, so it is reported by `work-after-response`, not
  as lazy loading by `lazy-sql-after-handler`.
- Threads a request starts itself are propagated only by the opt-in [threads sensor](#the-threads-sensor).

Work still running after its request answered shows in Live Activity as a running `ASYNC` entry with an **after
response** badge, and Runtime Insights reports it as `work-after-response` when it ran SQL, called a REST service, sent a
message, or failed. A task is after the response when it ends after its request's response started: a task its
handler waited for ended before, and a request that marked no response, as when its handler failed, is compared with its
end; on Spring WebFlux, which marks no request phases, the request's end stands in for its response. Only the SQL, REST,
and message work that ended at least two milliseconds after the response started counts, or the task's failure: a waited-for
task releases its handler before its handoff closes, so that handoff may end just after the response although its work
ended before. That observation and the request profile's `PROPAGATED` tier apply only while the agent is attached
and armed for the application, its `executors` sensor is installed and not disabled by a failed self-test, and BootUI
attached its handoffs to the claim; otherwise they say which of these is missing.

## The threads sensor

`bootui.agent.sensors=executors,threads` adds the `threads` sensor, which carries a request into the threads its
application code starts, with `new Thread(...).start()`, a virtual thread, or
`Executors.newVirtualThreadPerTaskExecutor()`. It is off by default because `java.lang.Thread` is the riskiest JDK
class to retransform; it installs and self-tests on its own, with a platform thread and, from JDK 21, a virtual thread.
A failed self-test stops only this sensor, at once, then removes its transformer; if the JVM refuses that, the sensor
stays stopped and its state reads `self-test-failed (release-failed)`.

| Hook | Role | JDK type |
| --- | --- | --- |
| `Thread.start` | starts threads | `Thread.start()`, and on JDK 21+ the `start(ThreadContainer)` executors use |
| `VirtualThread.start` | starts threads | JDK 21+ virtual threads |
| `Thread.run` | runs threads | the thread's task, through `Thread.run` (JDK 17) or `Thread.runWith` (JDK 21+) |
| `Thread subclass run` | runs threads | `run()` of a `Thread` subclass in the claimed packages |

On JDK 21 and later the task's call inside `Thread.runWith` is replaced, never wrapped, so the scoped-value bindings a
`StructuredTaskScope` subtask inherits, which `runWith` keeps in its frame, stay visible in it.

A thread is propagated only when application code starts it: the first caller of `start()` outside the JDK must belong
to the claimed packages (`bootui.agent.packages` and the packages BootUI discovers). A thread a library or a framework
starts inside a request, such as a client's I/O thread, a pool filler built from an application thread factory, a
framework's dispatch to a virtual thread, or a context-propagating executor wrapper such as Micrometer's, never
inherits that request, even when its task is an application lambda; the wrapper carries the context itself. The
caller is looked up only inside owned work. A thread started inside `ThreadPoolExecutor.addWorker` and a fork/join
worker are pool workers and are never propagated: they would otherwise carry the request that created them for their
whole life. Nor is a platform thread whose class overrides `run()` outside the claimed packages, such as
`java.util.Timer`'s. `bootui-` threads are skipped, and the `bootui.agent.executors.skip-tasks` and `skip-threads`
prefixes are checked against the thread's task and name. A thread `CompletableFuture` starts for its own
thread-per-task fallback is left to the executors sensor, which reports the stage's outcome; Kotlin's `thread { }`
counts as its caller. Accepted limits: a background thread the application starts lazily from a request, such as a
polling loop, carries that request until it ends, the handoff window bounding what is attributed; and a stage of
`CompletableFuture.supplyAsync(..., executor)` on a virtual-thread-per-task executor is propagated by this sensor,
which cannot see the stage's failure, so its handoff ends successful.

Its counters are those of the executors sensor, over threads instead of tasks, plus:

| Counter | What it counts |
| --- | --- |
| Library threads skipped | Threads started inside owned work by code outside the claimed packages. |
| Pool workers skipped | Pool worker threads, never propagated. |

## Coexistence and class data sharing

The BootUI agent coexists with the OpenTelemetry Java agent and with JaCoCo. Put JaCoCo's Surefire/Failsafe placeholder
first and append BootUI with `@{argLine} -javaagent:...` so coverage keeps working.

Because the agent appends itself to the bootstrap class path, HotSpot prints:

```text
Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

That warning is expected. It means CDS, AppCDS, and AOT caches no longer apply outside boot-loader classes for that JVM.
This is why the agent is a development-time tool and should not be placed on production or AOT-cached JVM launches.

## Privacy and dependency inventory

The agent is local-only. It does not export telemetry, open a network connection, or record anything until a running
BootUI instance claims it. The dependency catalog and Vulnerabilities panel ignore the BootUI agent jar as an
application library when its manifest contains `BootUI-Agent-Protocol`.

## Configuration

See [BootUI properties](../PROPERTIES.md#java-agent) for:

| Property | Default | Purpose |
| --- | --- | --- |
| `bootui.agent.enabled` | `true` | Claim the agent when it is attached. |
| `bootui.agent.packages` | empty | Extra application package prefixes; the adapter-discovered packages are always included. |
| `bootui.agent.mode` | `auto` | `auto`, `dev`, or `test`. |
| `bootui.agent.sensors` | `executors` | The sensors this application asks for: `executors`, and the opt-in `threads`. |
| `bootui.agent.executors.skip-tasks` | BootUI's, Micrometer's, and Spring's propagating wrappers, `jdk.internal.`, `sun.`, `com.zaxxer.hikari.`, `com.github.benmanes.caffeine.` | Task class-name prefixes never propagated. |
| `bootui.agent.executors.skip-threads` | `vert.x-`, `bootui-` | Worker thread-name prefixes never propagated to; on Spring, Reactor's `parallel-`, `boundedElastic-`, and `single-` are added when Reactor's automatic context propagation is on. |
| `bootui.agent.executors.max-handoff` | `5m` | The handoff window: a task belongs to its request when it starts no later than this after the request ended, its work is attributed until this long after it started, and it is published `capped` when it runs longer. |

On Quarkus these are build-time properties, read when the application is built (augmented); dev mode rebuilds when
they change.
