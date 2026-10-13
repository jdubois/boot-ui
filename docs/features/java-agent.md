# Java Agent

The Java Agent panel shows whether the optional BootUI `-javaagent` is attached to the current JVM, whether this
application has claimed it, and how to attach it when it is missing. Its sensor switches are available on Spring MVC,
Spring WebFlux, and Quarkus.

The panel opens the sidebar's **Instrumentation** group, the setup and status entry point for the panels that read the
agent's sensors: [Code Paths](#code-paths), [Code Inventory](#code-inventory), and [Side Effects](#side-effects).
Without the agent those three stay in the group, dimmed, with the reason they are unavailable.

The agent is a development-time helper. It carries a request's correlation through the JDK's executors, so work handed to
a raw thread pool or `CompletableFuture` is owned by the request that handed it over, and it feeds the sensors below. It is
local-only, exports nothing, and stays dormant until BootUI claims it. An application that starts without `-javaagent`
behaves exactly as before and reports `NOT_ATTACHED` with setup snippets.

While the agent is not attached, the panel opens on **What the Java agent adds**, then **Attach the agent**: download the
jar if it is missing, add `-javaagent` where the application starts, and restart. In every other state the agent's own
diagnosis comes first and the setup snippets close the panel.

## Attaching the agent

Add the published `bootui-agent` jar to the JVM with an explicit `-javaagent:` option. The setup card points at the
expected local Maven repository path:

```text
~/.m2/repository/com/julien-dubois/bootui/bootui-agent/<version>/bootui-agent-<version>.jar
```

When the jar is missing, the first tab, **Download the agent (curl)**, fetches it from Maven Central and needs no Maven:

```bash
curl -fL --create-dirs -o ~/.m2/repository/com/julien-dubois/bootui/bootui-agent/<version>/bootui-agent-<version>.jar \
  https://repo1.maven.org/maven2/com/julien-dubois/bootui/bootui-agent/<version>/bootui-agent-<version>.jar
```

**Download the agent** does the same with `mvn dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:<version>`,
and two Gradle tabs add a `downloadBootuiAgent` task. These resolve through your own proxy and mirror settings.

The panel detects `pom.xml`, `build.gradle`, or `build.gradle.kts` and lists the matching snippets first:

- Spring Boot Maven plugin `<agents>`, and Gradle Kotlin and Groovy `bootRun { jvmArgs(...) }`.
- Quarkus dev mode: `./mvnw quarkus:dev -Djvm.args="-javaagent:..."`.
- Surefire/Failsafe `<argLine>@{argLine} -javaagent:...</argLine>`, which keeps JaCoCo's own `argLine`.
- IntelliJ IDEA VM options.
- `JAVA_TOOL_OPTIONS="-javaagent:..." ./mvnw spring-boot:run`, scoped to one command. Never export it in a shell: every
  JVM started there would load the agent.

Every snippet has a **Copy** button. The agent is not for production JVMs or AOT/native-image runs.

::: details Snippet ids and local repository

Snippet ids are stable: `curl-download`, `maven-download`, `gradle-kotlin-download`, `gradle-groovy-download`,
`maven-plugin`, `gradle-kotlin`, `gradle-groovy`, `quarkus-dev`, `surefire`, `intellij`, and `java-tool-options`.

If the application's JVM has `-Dmaven.repo.local=...`, that repository is honored. When the application knows its local
repository, the download command includes `-Dmaven.repo.local=<absolute path>`, so the jar and the setup card's path stay
aligned.

:::

## Status states

`GET /bootui/api/java-agent`, `get_agent_status`, and `bootui agent status` return the same `JavaAgentReport` the panel
shows (`state`, `reason`, versions, `jarPath`, `claim`, `heldBy`, `sensors`, `toggles`, `counters`, `warnings`, `setup`).

| State | Meaning |
| --- | --- |
| `NOT_ATTACHED` | The JVM started without `-javaagent`. |
| `DORMANT` | The agent is present, but this application has not claimed it, for example `bootui.agent.enabled=false`. |
| `ARMED` | This application owns an armed claim. |
| `HELD` | Another application in the same JVM owns the agent; `heldBy` names it. |
| `DISARMED` | This run ended its claim. |
| `UNAVAILABLE` | The agent did not start, the protocol or version differs, or the runtime cannot use it. |
| `FAILED` | The agent rejected or failed this application's claim. |
| `DISABLED` | Agent support is disabled, including Quarkus production mode and a Spring application forced on in a disabled profile such as `prod`. |

An agent only works with BootUI of its own version: a different version or protocol is `UNAVAILABLE` (the reason names both
versions). Attach the jar of the version BootUI reports.

## Switching sensors at run time

The `threads`, `environment`, `thread-activity`, `thread-locals`, `security-sinks`, and `files` sensors can be switched on
and off for the running application without a restart, from the panel's **Runtime switches** card or from each sensor's
section in [Side Effects](#side-effects). Each switch shows the configured value from `bootui.agent.sensors`, an
**Overridden** badge when it differs, the sensor's state, and why it is off or on by default. Every other sensor is
installed with the claim only.

`POST /bootui/api/java-agent/sensors/{id}` with `{"enabled": true}` or `{"enabled": false}` switches one sensor and returns
the updated report. It is the panel's only action, so `bootui.panels.java-agent.read-only` and `bootui.read-only` refuse it
with the canonical 403, and it carries the usual localhost, Host, and cross-site write protections. MCP provides
`enable_agent_sensor` and `disable_agent_sensor`, and the CLI `bootui agent sensor enable|disable <id>`; agents must obtain
the user's approval first.

A switch is a runtime override, never written to a file. It lasts for the application's slot, so DevTools restarts and
Quarkus live reloads keep it, and a full JVM restart forgets it. A test run never inherits a dev switch.

::: details What each switch does and when it fails

- `threads` installs and self-tests on, and restores `java.lang.Thread` off.
- `files` and `environment` stop recording at once, then reinstall the transformer that `processes`, `network`, and
  `blocking` share and self-test them again; one whose core hook fails stays off for the JVM's life. Runtime Insights'
  run comparison leaves the compared sensors out of a run in which one of these was switched.
- `thread-activity` and `security-sinks` have a transformer of their own. `thread-locals` transforms nothing.
- `security-sinks` switches its [JDK checks](#jdk-checks) and request-value matching together, but never turns matching on
  by itself; switching it off wipes the values held for running requests.
- Another sensor id or a body without `enabled` answers 400. A 409 means the agent is not attached or not armed, an older
  agent predates switches, the claim changed meanwhile, or the sensor already failed its self-test in this JVM.

:::

## Claims and lifecycle

Spring claims the agent as soon as BootUI's activation is resolved, with the main application class's package and
`bootui.agent.packages`, refines the claim with the auto-configuration packages when the context refreshes, disarms on
close or startup failure, and releases it when BootUI is inactive or `bootui.agent.enabled=false`, so the agent removes its
transformers. Quarkus claims from a `STATIC_INIT` recorder in dev and test launch modes; production launch mode never
claims and reports `DISABLED` with reason `Quarkus production mode`.

When `bootui.enabled=ON` forces BootUI on despite an active `bootui.disabled-profiles` entry such as `prod`, Spring
releases the agent and the panel reports `DISABLED`. Only `bootui.agent.allow-in-disabled-profiles=true` claims it there.
The `bootui.agent.*` properties are build-time on Quarkus.

`bootui.agent.mode=auto` resolves to `test` under JUnit, TestNG, Cucumber, Spring Boot test, or Quarkus test launch mode,
otherwise to `dev`. DevTools restarts and Quarkus live reloads replace the same application slot.

::: details Claim details

- Classes loaded before the claim are instrumented when the sensors install and marked late for that run.
- The first claim in a JVM installs the sensors off the claiming thread; a DevTools restart finds them installed.
- A `SpringApplication` run inside another one's, such as Spring Cloud's bootstrap context, neither claims nor releases.
- Sensor removal runs off the caller's thread. A new claim supersedes a queued release. If restoring the application
  classes fails, the affected sensors stay disabled in that JVM.
- A sensor row's **This claim** column says whether the armed claim uses an installed transformer; a sensor missing from
  `bootui.agent.sensors` reads `inactive`.
- The engine looks up the bridge only from the bootstrap class loader, so an application-classpath copy is ignored.

:::

## The executors sensor

A claim asks for the sensors in `bootui.agent.sensors`. Setting it replaces the list; it does not append to the defaults.
Each sensor installs once, on its own thread, then self-tests its hooks. BootUI offers the `PROPAGATED` correlation tier only
after every core executor hook passes.

| Sensor | Enabling property | Purpose |
| --- | --- | --- |
| `executors` | default | Carries a request's correlation into executor work (this section). |
| `inventory` | default | [Records which application methods ran and which jars loaded classes](#the-inventory-sensor). |
| `code-paths` | default | [Times application bean methods per request](#the-code-paths-sensor). |
| `processes` | default | [Records the processes the application starts](#the-processes-sensor), by command name. |
| `network` | default | [Records connects, datagram sends, and name lookups](#the-network-sensor). |
| `files` | default | [Records files opened, deleted, moved, and copied](#the-files-sensor), as path patterns. |
| `blocking` | default | [Reports blocking calls started on event loops](#the-blocking-sensor). |
| `resources` | default | [Reports streams, channels, and sockets left open or never closed](#the-resources-sensor). |
| `threads` | `bootui.agent.sensors=...,threads` | [Carries a request into threads the application starts](#the-threads-sensor). |
| `environment` | `bootui.agent.sensors=...,environment` | [Records environment variable and system property names read](#the-environment-sensor). |
| `thread-activity` | `bootui.agent.sensors=...,thread-activity` | [Records threads started and executors created or left running](#the-thread-activity-sensor). |
| `thread-locals` | `bootui.agent.sensors=...,thread-locals` | [Finds thread locals left set on pooled threads](#the-thread-locals-sensor). |
| `caught-exceptions` | `bootui.agent.sensors=...,caught-exceptions` | [Reports exceptions application code catches and rethrows](#the-caught-exceptions-sensor). |
| `security-sinks` | `bootui.agent.sensors=...,security-sinks` | [Checks risky JDK calls and request input reaching sinks](#the-security-sinks-sensor). |

Each sensor row shows its state (`installing`, `testing`, `installed`, `self-test-failed`, `failed`, or `off`), install and
self-test time, hooks, and the types that failed to transform. The **Class transformation** card sums every sensor's
transformed, retransformed, failed, and skipped classes and its install and release time since the JVM started.

Where an executor receives a task, the agent asks BootUI for the submitting thread's correlation (request, execution, trace,
and span ids, and the route, as strings and numbers only, never a transaction, a data source, or an application object).
Where the executor runs the task, BootUI opens that request again on the worker as a child execution `async-…`. The task's
SQL, REST calls, messages, and exceptions are then owned by the request, and each task becomes one `agent.executors` event
in the runtime journal. Live Activity nests it under its request as an `ASYNC` entry, and the request profile lists it under
**Handoffs**.

Work still running after its request answered shows in Live Activity as an `ASYNC` entry with an **after response** badge.
Runtime Insights reports it as `work-after-response` when it ran SQL, called a REST service, sent a message, or failed.

::: details Hooks and self-test

| Hook | Role | JDK type |
| --- | --- | --- |
| `ThreadPoolExecutor.addWorker`, `.queue` | receives tasks | a task that starts a worker or is put on the work queue |
| `ScheduledThreadPoolExecutor` | receives tasks | one-shot delayed tasks; periodic tasks are never propagated |
| `ForkJoinPool`, `ForkJoinTask adapters`, `ForkJoinTask.fork` | receives tasks | root submissions and a `fork()` from outside the pool |
| `DelayScheduler`, `CompletableFuture.ThreadPerTaskExecutor` | receives tasks | JDK 25+ delayed tasks; the `CompletableFuture` thread-per-task fallback |
| `ThreadPoolExecutor.runWorker`, `ForkJoinTask.doExec` | runs tasks | every task those pools run |
| `CompletableFuture.AsyncSupply`, `AsyncRun` | runs tasks | `supplyAsync` and `runAsync` stages, whose outcome they read |

The panel shows per hook whether the type exists, whether it was instrumented, its self-test result (`passed`, `failed`,
`not-exercised`, `unsupported`, or `not-run`), and how many tasks it received or ran. A failed or inconclusive self-test of
a `ThreadPoolExecutor` or `ForkJoinTask.doExec` hook disables propagation for that claim; the next claim tests again. A
failed `AsyncSupply` or `AsyncRun` hook disables nothing: the pool's run hook propagates that stage without reading its
outcome. Hooks are verified on JDK 17, 21, 25, 26, and 27; on any other JDK the report warns that the self-test decides.

Nothing is opened when the worker already works for that request, for BootUI's own work, on `bootui-` threads, or when no
request or execution owns the work. Propagated work is not metered as its request's own CPU time and allocation.

:::

### How a task is propagated

Only root submissions and a `fork()` from outside a pool are propagated. Threads a request starts itself are propagated
only by the opt-in [threads sensor](#the-threads-sensor).

::: details Why a task is after the response

A task is after the response when its body ends after its request's response started. A normal JDK body whose handler
waited for its result ended before it. A request that marked no response is compared with its end; on WebFlux, which marks
no request phases, the request's end stands in for the response.

For a body that ended before the response, the badge and the request profile still mark the task when its tail ran SQL, a
REST call, or a message at least 2 ms past the response, failed after it, or ran at least 50 ms past it. Runtime Insights'
`work-after-response` reports only I/O or failures after the response, so a task badged for computation alone is not an
insight. These observations apply only while the agent is armed, the `executors` sensor passed its core-hook self-test, and
BootUI attached its handoffs to the claim; otherwise the panel says which is missing.

:::

### Counters

::: details Counter table

| Counter | What it counts |
| --- | --- |
| Pending | Tasks received from owned work that have not run yet. |
| Never applied | Received tasks that never reached an instrumented run point, such as tasks of pools whose workers started before the claim. |
| Ambiguous | Tasks submitted more than once by different owners, left unowned. |
| Stale | Tasks received under an earlier claim, never reopened after a restart. |
| Refused | Snapshots the bridge refused because they held more than strings and numbers. |
| Over the limit | Owned submissions refused a snapshot because a retention cap was full, left unowned. |
| Skipped | Virtual-thread continuations, periodic tasks, wrappers (`skip-tasks`), and workers (`skip-threads`). |
| Failed tasks | Propagated tasks that ended with an exception. |

Once the executors sensor has counted overflow, the panel warns that a shared task object may have run with another
request's context.

:::

### Accepted limits

::: details Limits of task propagation

- Parallel-stream subtasks run by other workers stay unowned.
- Pools whose workers started before the first claim never apply their tasks.
- A task submitted when the bridge holds 32,768 entries runs unowned, and entries of earlier claims count until they run or
  are released.
- A task submitted before a DevTools restart or a Quarkus live reload that ends after it is never reopened.
- A task object pending across claim generations stays ambiguous and unowned until its pending count drains.
- One window, `bootui.agent.executors.max-handoff` (5 minutes), bounds a handoff: later work is not attributed to the
  request, and a task that ends later is published `capped`.

:::

## The threads sensor

The opt-in `threads` sensor carries a request into the threads its application code starts, with `new Thread(...).start()`,
a virtual thread, or `Executors.newVirtualThreadPerTaskExecutor()`. Enable it with `bootui.agent.sensors=executors,threads`
(include the other defaults you want), or switch it on at run time. It is off by default because `java.lang.Thread` is the
riskiest JDK class to retransform. A failed self-test stops only this sensor.

::: details Which threads are propagated, and hooks

A thread is propagated only when application code starts it: the first caller of `start()` outside the JDK must belong to
the claimed packages. Threads started by a library or framework, pool workers, and a `Thread` subclass whose `run()` is
outside the claimed packages (such as `java.util.Timer`'s) never inherit the request. A background thread the application
starts lazily from a request carries that request until it ends, bounded by the handoff window. Its counters are those of
the executors sensor, plus **Library threads skipped** and **Pool workers skipped**.

| Hook | Role | JDK type |
| --- | --- | --- |
| `Thread.start`, `VirtualThread.start` | starts threads | `Thread.start()` (and `start(ThreadContainer)` on JDK 21+), JDK 21+ virtual threads |
| `Thread.run`, `VirtualThread.run` | runs threads | a thread's task, through `Thread.run` (JDK 17) or `Thread.runWith` (JDK 21+) |
| `Thread subclass run` | runs threads | `run()` of a `Thread` subclass in the claimed packages |

On JDK 21 and later the task's call inside `Thread.runWith` is replaced, never wrapped, so scoped-value bindings that a
`StructuredTaskScope` subtask inherits stay visible.

:::

## The inventory sensor

The `inventory` sensor, on by default, records which application methods ran in this run and which jars and class
directories loaded classes. The [Code Inventory](#code-inventory) panel reads it.

::: details How it records, and what is never instrumented

Each instrumented method gets one entry check. Its first call in a run marks it executed and, when a request or another
BootUI execution owns the call, records the request id, route, and time. Each claim starts a new run, so a DevTools restart
or Quarkus live reload counts afresh. A second transformer, which never changes a class, counts the classes each code source
loads in this run, with the route that loaded the first one.

Never instrumented: static initializers, `$`-prefixed methods, abstract and native methods, BootUI's own classes, the agent,
proxies (Spring CGLIB and AOT, ArC, Hibernate, Mockito), synthetic classes, and classes loaded from a test root. A method
counts as **tracked** only once its class was transformed. A class instrumented only after it loaded may have run before,
unseen: its methods are marked **late** for that run.

Accepted limits: calls made before BootUI claims the agent are not seen, a method [HotSwapped](#hotswap) without a restart
keeps its flag, and Mockito's inline mock maker dispatches a stubbed call before the sensor's check. The sensor shares its
transformer with the [`code-paths` sensor](#the-code-paths-sensor).

| Counter | What it counts |
| --- | --- |
| Methods tracked | Application methods instrumented, whose executions the sensor sees. |
| Executed this run | Tracked methods that ran at least once since this run claimed the agent. |
| Over the method limit | Methods left uninstrumented because the method limit was reached. |
| Transform failures | Application classes that failed to transform. |
| Code sources | Jars and class directories that defined at least one class. |
| Records dropped, lost | Records dropped because the ring was full, or never finished by their writer. |
| Strings over the limit | Routes recorded as unknown because the run's string table was full. |

- Records travel through a bounded ring of `bootui.agent.ring-capacity` records (65,536 by default) that never blocks. A
  dropped record loses only the first request, route, and time, never the fact that the method ran.
- At most 262,144 methods and 4,096 code sources are tracked for the agent's lifetime.
- Each claim admits its context class loader and ancestors, so old objects from earlier runs cannot mark a changed method
  executed. An application claiming with an unrelated or null context loader must supply the application loader as its
  thread context loader.
- For the Vulnerabilities panel's [runtime reach](advisors.md#runtime-reach), each jar keeps a bounded set of hashes of the
  class names it defined.
- Hooks: `method entry` (every non-abstract, non-native method and constructor of the claimed packages) and `class load`.
  The report carries the counters as `methodsTracked`, `executedThisRun`, `methodOverflow`, `transformFailures`,
  `codeSources`, `ringDropped`, `ringLost`, and `internOverflow`. Missing tracking evidence is unknown, never confidently
  **never executed**.

:::

## The code-paths sensor

The `code-paths` sensor, on by default, times the application's bean methods per request: a call tree of the public and
protected methods of its beans, merged by caller and method, with each node's calls, total time, time in its callees, and the
request phase it entered in (filters, handler, or response). It is the evidence behind the [Code Paths](#code-paths) panel,
`get_code_paths`, and `route-time-breakdown`'s handler split.

::: details Which methods are timed, and counters

The bean classes come from the adapter: on Spring, the user class of every bean in the application's packages; on Quarkus,
classes with a bean-defining annotation, `@Path`, or `@Provider`. Never timed: constructors, static initializers, static and
private methods, synthetic and bridge methods, `equals`, `hashCode`, `toString`, record accessors, interfaces, lambdas, and
everything the inventory sensor never instruments. A framework or library method appears only as time of the bean method
that called it.

A method called more than 50,000 times a second with a mean under 2 µs, such as a getter in a loop, is no longer timed for
the rest of the run, and its time stays in its caller. After 100 internal errors the sensor switches itself off for the JVM's
life.

Accepted limits: Spring WebFlux and reactive Quarkus endpoints time the assembly of their pipeline on the thread that builds
it, not its execution, so these trees do not split the handler phase. Calls made before BootUI claims the agent are not seen.

| Counter | What it counts |
| --- | --- |
| Fragments recorded | Call trees of requests' bean methods handed to BootUI, one per thread. |
| Fragments dropped | Fragments not recorded because every tree of the pool was in use. |
| Queue full | Fragments dropped because the fragment queue was full. |
| Calls in no node | Calls deeper than 32 levels or past a fragment's node budget, whose time stays in their caller. |
| Bytes waiting | Fragments waiting for BootUI to read them. |
| Methods excluded | Methods no longer timed in this run by the adaptive exclusion. |
| Internal errors | Errors of the sensor itself. |

- `inventory` and `code-paths` share one transformer with one advice and self-test each. A failed self-test removes only
  that sensor's advice.
- A request's tree is handed to BootUI as one fragment of at most 512 nodes; calls deeper than 32 levels stay in their
  ancestor's time, and past 480 nodes each caller's further methods share one **Other** node.
- Nothing on the application thread blocks or allocates per call: an empty pool or a full queue (4 MB) drops the fragment
  and counts it. BootUI's drain thread reads fragments every 100 ms and a request tree settles about two seconds later.
- The report carries the counters as `fragmentsFlushed`, `fragmentsDropped`, `queueDropped`, `callsDropped`, `queueBytes`,
  `excludedMethods`, and `errors`, with `disabledReason` when the sensor stopped. The hook is `bean methods`.
- The agent's bridge carries no line numbers, so stepping into an instrumented method in a debugger steps over the advice.
  A debugger stepping into BootUI's correlation capture can stop there, unless `io.github.jdubois.bootui.*` is in its step
  filters.

:::

## The processes sensor

The `processes` sensor, on by default, records the processes application code starts, for the
[Side Effects](#side-effects) panel, `get_side_effects`, and `bootui side-effects`. It records the command name only,
never its arguments or environment, then whether the process started and, when the JVM reports it, the exit status and
lifetime. It hooks `ProcessBuilder.start()`, `ProcessBuilder.startPipeline(...)`, and `Runtime.exec(...)`.

The command label is sanitized before it leaves the agent: a started process records the executable's file name, and a
failed start records the first element cut at its first whitespace or `=`, so `/bin/sh -c secret` records only `sh`. Only
letters, digits, `.`, `_`, `+`, and `-` are kept, up to 128 characters.

::: details Self-test, exit watching, and limits shared by the side-effect sensors

- The self-test starts a deliberately invalid command that the JDK rejects before spawning anything. A failed self-test
  disables the sensor.
- Completion is watched with `Process.onExit()`, for at most 1,024 processes at once.
- BootUI's own process starts and `bootui-` threads are ignored.
- A start names its owner: a request, an execution no request owns (a scheduled run, a consumed message, a WebSocket
  message), or neither.
- All side-effect sensors share a ring of 1,024 records and a string table of 8,192 strings a run. A full ring drops and
  counts records instead of blocking. After 100 internal errors a sensor switches off for the JVM's life.
- The sensor's coverage reads `recording` when this application's claim includes it, otherwise `not-claimed`,
  `not-available`, or `failed` with the reason.

:::

## The network sensor

The `network` sensor, on by default, records what the application does on the network, for the
[Side Effects](#side-effects) panel's **Network** tab: the hosts and ports it connects to, the datagrams it sends, and the
host names the JVM resolves, each with the client recognized from the calling frames. It never reads a byte sent or
received, a URL's path or query, or a header.

::: details Target handling and capture rules

A target is the remote's host string and port, never resolved, with anything up to an `@` dropped. At most 1,024 distinct
targets are kept per run; the others share `(other hosts)`. BootUI's own work is never recorded. Loopback connections to
databases, brokers, and containers are.

A connection or datagram is **not captured by any panel** when no visible panel shows its work. JDBC, messaging, and mail
connections are captured by SQL Trace, the broker's panel, or Email. Any other connection is captured by REST Client Trace
when a call of the same request names its host and port. Infrastructure clients (OpenTelemetry and Zipkin exporters, metrics
registries, DNS, Testcontainers, DevTools) are marked `infrastructure`. `get_side_effects` with `query` `not captured` lists
the uncaptured rows.

Known limits: asynchronous socket channels, connected datagram writes, and native code are not seen, and a lookup the JVM's
cache answered is not counted.

| Hook | Role | What it covers |
| --- | --- | --- |
| `Socket.connect` | core | every blocking `Socket` connect, socket factories, and TLS sockets |
| `SocketChannel.connect` | core | Netty, Vert.x, Kafka, gRPC, the JDK `HttpClient`, and Unix-domain sockets |
| `SocketChannel.blockingConnect`, `.finishConnect` | optional | `socket().connect(...)`, and a non-blocking connect's outcome |
| `DatagramChannel.send`, `DatagramSocket.send` | optional | datagram sends, address and port only |
| `InetAddress.lookup` | optional | name lookups the JVM's address cache misses |

- A hook that fails its self-test is left out and listed under `hooksLeftOut`; a failing core hook disables only this sensor.
- The client is recognized from frames and thread families, then from a configured exporter endpoint, then from a
  well-known port. A REST client call is matched to a connection within a few seconds.
- The runtime model gains observed `OPENS` edges from routes and jobs to `HOST` nodes keyed `host:port`.

:::

## The files sensor

The `files` sensor, on by default, records the files application code opens, deletes, moves, and copies, as path patterns,
never their contents, for the [Side Effects](#side-effects) panel, `get_side_effects`, and `bootui side-effects`. Leave it
out of `bootui.agent.sensors`, or [switch it off at run time](#switching-sensors-at-run-time), to stop recording.

::: details Path patterns, hooks, and limits

The agent turns a path into a pattern before anything leaves the hook, so a raw path and the user name in it never reach
BootUI. The working directory becomes `.`, the temporary directory `$TMPDIR`, and the home `~`. Tokens, UUIDs, hex and
alphanumeric ids, and digit runs become `{token}`, `{uuid}`, `{hex}`, `{id}`, and `{n}`, so `report-2026-10-05.csv` in
`target/reports` is `./target/reports/report-{n}-{n}-{n}.csv`. The engine also masks segments its secret detector recognizes.

Class files, jars, and files a class loader reads are counted in buckets, never recorded per route. Rows carry where they
came from (**application**, **library**, **logging**, **class path**, or **JDK**).

| Hook | Records |
| --- | --- |
| `FileInputStream.open`, `FileOutputStream.open` | a read, a write (core) |
| `RandomAccessFile.open`, `FileChannel.open` | a read or a write by mode or options |
| `Files.newByteChannel`, `newInputStream`, `newOutputStream` | a read or a write; `readAllBytes`, `readString`, and `lines` reach them |
| `Files.delete`, `deleteIfExists`, `move`, `copy` | a delete, a move, a copy |

- `File.delete`, `File.renameTo`, `File.createNewFile`, `AsynchronousFileChannel`, memory-mapped access, and native code are
  not seen.
- One operation records once. A generation keeps at most 3,000 distinct path patterns; beyond that the target is
  `(too many distinct paths)`.

:::

## The environment sensor

The opt-in `environment` sensor records the names of the environment variables and system properties application code reads
directly, never their values. It hooks `System.getenv` and `System.getProperty`, passing only the name, never the default.
Add `environment` to `bootui.agent.sensors`, or [switch it on at run time](#switching-sensors-at-run-time).

A row's count is the requests and threads that read a name. Reads made by the JDK and by configuration frameworks resolving
their own properties are not recorded. A name the secret detector recognizes is masked. It stays off by default because it
adds cost to every `System.getProperty` call ([Overhead](#overhead)).

## The security-sinks sensor

The opt-in `security-sinks` sensor, enabled with `bootui.agent.sensors=...,security-sinks` or
[its run-time switch](#switching-sensors-at-run-time), runs its [JDK checks](#jdk-checks). With
`bootui.agent.security-sinks.request-values=true` as well, it also checks whether the value of one of the current
request's query or path parameters appears **unchanged** in SQL text, a command, a file path, or an outbound URL. The
property does nothing without the sensor.

::: details Rows, sinks, and what is held

Each match is a row of the Side Effects **Security sinks** tab and of `get_side_effects`' `security-sinks` query, worded as
a fact, never as a vulnerability. A row names the sink, the parameter's name, the call site, and the sink's text with the
value **redacted** to `{name}`. It never holds the value.

| Sink | Where it is checked |
| --- | --- |
| SQL text | Where SQL Trace's JDBC capture records the statement; every literal stays masked |
| Command | The `processes` sensor's hook; the row shows the file name and the argument's index, never an argument |
| File path | The `files` sensor's hooks, so only with `files` claimed |
| Outbound URL | Where the REST client panel records the call; scheme, host, port, redacted path, and query keys only |

Form values, headers, and bodies are never held. The values live in memory only while a request runs, are removed when its
response completes, and are wiped on a new claim, a restart, a release, or when the sensor is switched off. They are never
persisted, logged, or displayed.

- The holder keeps at most 128 requests and 32 values of 4 to 256 characters each. A missed end is swept after 60 seconds.
- Matching is bounded per request. After the budget, later sinks are not checked and a `files` or `processes` row says
  `(not kept: not checked for request input)`.
- A value that is a number, `true`/`false`, or outside an SQL literal may be a word the text always holds. Such a match is
  shown only once a second request produced a different raw text with the same redacted text.
- A JDK `HttpClient` call is checked only when it goes through a REST client BootUI records.
- **Clear recording** clears the rows.

:::

### JDK checks

::: details What the JDK checks record

With `security-sinks` in `bootui.agent.sensors`, whatever `request-values` says, the sensor also hooks the JDK's security
APIs and records three kinds of facts as Security sinks rows, each worded as what was seen:

| Check | Recorded |
| --- | --- |
| Deserialization without a filter | The outermost `ObjectInputStream.readObject()` of a stream with no `ObjectInputFilter`, with the first class read and the call site |
| Weak algorithms | `MessageDigest.getInstance` and `Cipher.getInstance` for MD5, MD2, SHA-1, DES, DESede, RC4, and block ciphers in ECB mode, with who asked |
| Trust managers and hostname verifiers | A trust manager in the application's packages, a library trust-all, or a default the application installed through `SSLContext.init` or `HttpsURLConnection` |

A caller inside the JDK asking for SHA-1 or MD5 itself is the JDK's own use, counted and never shown. A group whose core
hook fails is switched off alone.

Library trust managers that accept every certificate are recognized by exact class name: Netty's
`InsecureTrustManagerFactory`, Vert.x's `TrustAllTrustManager`, and Apache HttpClient's `TrustAllStrategy` and
`TrustSelfSignedStrategy`. Not detected: any other library's trust-all and Netty's OpenSSL provider.

Not checked: `KeyGenerator`, `Signature`, `Mac`, `SecureRandom`, PBE algorithms, and a library's own default verifier or
factory. Without `jdk.unsupported`, the deserialization group is off.

:::

## The blocking sensor

The `blocking` sensor, on by default, reports `Thread.sleep`, `TimeUnit.sleep`, `Object.wait`, `LockSupport.park`, and the
[network](#the-network-sensor) and [files](#the-files-sensor) sensors' blocking operations **started** on an event loop, for
the [Side Effects](#side-effects) panel's **Blocking** tab. Like BlockHound, it watches blocking methods on threads that
must not block; unlike BlockHound, it reports and never throws.

::: details Event loops, hooks, and call-site rewriting

Event loops are Reactor Netty's on Spring WebFlux (and on a `WebClient`'s loops in Spring MVC) and Vert.x's on Quarkus. On a
servlet container the tab is `not-applicable`. Reactor's `parallel`, `single`, and `boundedElastic` schedulers, Vert.x
workers, and virtual threads are never event loops. A park shorter than 1 ms is only counted.

| Hook | Role |
| --- | --- |
| `LockSupport.park` | records every public `park`, `parkNanos`, and `parkUntil` |
| `Thread.sleep call sites`, `Object.wait call sites` | record sleeps and waits called from the application's classes |
| `network and file operations` | no hook of its own: operations the `network` or `files` sensor records on an event loop |

`Thread.sleep` and `Object.wait` are native, so the agent rewrites their **call sites** in the application's classes into
calls that call the original and record around it. A stack trace through a rewritten call shows one extra frame,
`io.github.jdubois.bootui.agent.bridge.Blocking.sleep` or `waitOn`; exceptions and the interrupt flag are unchanged. The
sensor does not see a library's own `sleep`, `Thread.join`, or blocking on a loop before its first request.

:::

## The thread-activity sensor

The opt-in `thread-activity` sensor records the threads the application starts and the executors it creates, per route or
thread family and call site, for the [Side Effects](#side-effects) panel's **Threads and leaks** tab: how many a request
starts, the threads and executors it **left running** when it ended, and the executors reclaimed without a shutdown. It
never records a thread-local, a task, or anything a thread holds. It differs from [`threads`](#the-threads-sensor), which
carries a request's context into threads; the two can run together. Add it to `bootui.agent.sensors`, or switch it on at
run time.

::: details Origins, hooks, and limits

A row's origin is `application`, `library`, or `jdk`. A thread still alive, or an executor not shut down, 250 ms after the
response completed is reported once as left running. Threads and executors are tracked weakly, so the sensor never keeps
them alive.

- Hooks: `Thread.start` and `VirtualThread.start` (starts), `ThreadPoolExecutor.<init>`, `ForkJoinPool.<init>`, and
  `ThreadPerTaskExecutor.<init>` (creations), and their `shutdown` methods. The common pool is never recorded.
- At most 1,024 threads and 1,024 executors are tracked. Beans created as singletons are never tracked.
- It does not see threads created through classes that bypass the JDK's, such as Netty's own thread-per-task executor.
- A core hook that fails its self-test disables the sensor alone; JDK 21 hooks are `unsupported` on JDK 17.

:::

## The thread-locals sensor

The opt-in `thread-locals` sensor finds the thread locals a request or a job **left set** on its pooled platform thread, for
the [Side Effects](#side-effects) panel's **Threads and leaks** tab: a value the next request on that thread inherits, such
as a tenant, a user, or a security context. It hooks no `ThreadLocal` method and transforms no class: it snapshots which
thread locals hold a value when a scope opens and scans again when it closes. It reads each entry's key and whether its
value is `null`, **never the value** and never a `toString()`.

::: details What is dropped, scopes, and caps

A row's target is the static field that holds the thread local, such as `com.example.TenantContext.CURRENT`. Frameworks
that set and clear their own thread locals (Spring's request and transaction holders, MDCs, Micrometer, OpenTelemetry,
Jackson, Netty) are dropped and counted. Event loops, virtual threads, and scopes no request or job owns are not scanned.
Add `thread-locals` to `bootui.agent.sensors`, or [switch it on at run time](#switching-sensors-at-run-time).

A scope opens around a Spring MVC request, a request's task on a pool worker, Spring WebFlux work on `boundedElastic`, a
Quarkus blocking resource method or managed executor task, and a scheduled run.

- To read the maps, the sensor asks `Instrumentation.redefineModule` to open `java.lang` to the agent's own module only,
  never to the application. Without the opening the sensor reports itself unavailable; a claim never fails.
- A table larger than 16,384 slots or with more than 4,096 thread locals set is skipped and counted. At most 16 leftovers a
  scope are reported.

:::

## The resources sensor

The `resources` sensor, on by default, reports the streams, channels, and sockets a request's or a job's work opened and
left open past its request, or never closed before the garbage collector reclaimed them, for the
[Side Effects](#side-effects) panel's **Threads and leaks** tab. It never records a byte read or written: a row's target is
the path pattern or the host and port the [`files`](#the-files-sensor) and [`network`](#the-network-sensor) rows show.

::: details Counts, hooks, and guards

It has no open hook of its own: the `files` and `network` sensors hand it what they opened. A resource still open 250 ms
after its request ended is counted **Open after request**, and one closed later **Closed after request**; both are often an
intended hand-off, such as a connection pool's sockets. A resource the collector finds unreachable while never closed is
**Reclaimed without close()**, the leak, and the only count a run comparison keys on. Leave it out of
`bootui.agent.sensors` to turn it off.

- Close hooks: `FileInputStream`, `FileOutputStream`, and `RandomAccessFile` close, `FileChannelImpl.implCloseChannel`,
  `Socket.close`, and `AbstractSelectableChannel.implCloseChannel`.
- Only the JDK's exact channel and socket classes are tracked, at most 1,024 at a time, each as a weak reference. Nothing
  captures the resource, its class, or its class loader.
- A resource kind is tracked only while its close hook passed its self-test.

:::

## The caught-exceptions sensor

The opt-in `caught-exceptions` sensor, enabled with `bootui.agent.sensors=...,caught-exceptions`, reports the exceptions
application code catches and which of them are thrown again. It records events in the runtime journal's
`agent.caught-exceptions` source, owned by the Exceptions panel, whose
[**Caught in application code**](diagnostics.md#caught-in-application-code) section reads what became of each one. There is
no runtime switch for it. It stays off by default because its overhead does not yet fit the default sensors' budget.

::: details Hooks and bounds

It records the caught exception's class, the handler's site (class, method, line, and declared types), its owner, and its
thread. It never records a message, stack trace, or field. Should an application class fail to load with a `VerifyError`
naming `CaughtExceptions`, remove `caught-exceptions` from `bootui.agent.sensors` and report the class.

- Hooks: `handler entry` (each exception handler that names a type, in the claimed packages) and `exceptional exit` (each
  method with such a handler, except constructors). `finally` and `synchronized` handlers are left alone.
- Caught exceptions stay pending for at most 60 seconds. A site caught more than 16 times for one owner on a thread is only
  counted after that.
- The sensor registers at most 16,384 handlers for the JVM's life; the Exceptions panel says when some were left out.
- The inserted code runs only when an exception is caught or leaves a method that catches some.

:::

## HotSwap

A debugger's HotSwap, such as IntelliJ IDEA's **Reload Changed Classes**, passes the edited bytes through the agent's
transformer, so the `inventory` and `code-paths` advice is applied again. The edited method keeps being tracked and timed. A
HotSwapped method keeps its executed flag for the run, and its first request and route stay those of the call before the
edit.

::: details HotSwap limits

- A schema change (an added or removed method or field) is refused by the JVM unless it supports enhanced redefinition, as
  the JetBrains Runtime does.
- On JDK 17, a release or a claim that adds or removes a sensor reverts a HotSwap made since the class loaded. Apply it
  again, or restart.
- Code Inventory compares a HotSwapped method with the previous run only after the next restart or live reload.

:::

## Overhead

The agent adds measurable but bounded overhead, which CI measures by comparing the sample application with and without it.
Each sensor's default follows its measured increment: at most 3 % over the other default sensors, and at most 10 %
cumulative against no agent. A sensor that does not fit stays opt-in (`threads`, `environment`, `thread-activity`,
`thread-locals`, `caught-exceptions`, and `security-sinks`).

The agent is a development-time tool. To lower its cost, leave a sensor out of `bootui.agent.sensors` or
[switch it off at run time](#switching-sensors-at-run-time).

## Coexistence and class data sharing

The BootUI agent coexists with the OpenTelemetry Java agent and with JaCoCo. Put JaCoCo's Surefire/Failsafe placeholder
first and append BootUI with `@{argLine} -javaagent:...` so coverage keeps working.

Because the agent appends itself to the bootstrap class path, HotSpot prints this expected warning:

```text
Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

It means CDS, AppCDS, and AOT caches no longer apply outside boot-loader classes for that JVM. This is why the agent should
not be placed on production or AOT-cached JVM launches.

## Agent evidence outside the journal

::: details Visibility, clearing, exports, and memory

Code Paths' trees, Code Inventory's first calls, and Side Effects' rows are kept in bounded stores of the run, not as
runtime journal events. The same rules apply to them as to journal events:

- **Panel visibility.** While a store's panel or [HTTP Exchanges](diagnostics.md#http-exchanges) is disabled, the panel, its
  MCP tool, its CLI command, and the Runtime Insights observations that read it say so and show nothing of its evidence.
- **Clear recording.** **Clear recording** and **Free BootUI memory** drop this evidence too. Counts since the claim and
  which methods executed are kept.
- **Exports.** Panels, **Export JSON**, **Copy for AI**, MCP tools, and the CLI carry only method keys, route templates (or
  masked observed paths), request ids, times, and counts.
- **Memory.** The journal status reports the stores' estimated bytes as **Agent evidence**, against
  `bootui.runtime-journal.agent-evidence-max-bytes` (about 68 MB by default).

:::

## Privacy and dependency inventory

The agent is local-only. It does not export telemetry, open a network connection, or record anything until a running
BootUI instance claims it. The dependency catalog, the Vulnerabilities panel, and the GraalVM readiness dependency scan
ignore the BootUI agent jar as an application library when its manifest contains `BootUI-Agent-Protocol`.

## Configuration

See [BootUI properties](../PROPERTIES.md#java-agent) for the full list.

| Property | Default | Purpose |
| --- | --- | --- |
| `bootui.agent.enabled` | `true` | Claim the agent when it is attached. |
| `bootui.agent.allow-in-disabled-profiles` | `false` | Spring only: claim the agent even when `bootui.enabled=ON` forces BootUI on in a disabled profile such as `prod`. |
| `bootui.agent.packages` | empty | Extra application package prefixes; the adapter-discovered packages are always included. |
| `bootui.agent.mode` | `auto` | `auto`, `dev`, or `test`. |
| `bootui.agent.sensors` | `executors`, `inventory`, `code-paths`, `processes`, `network`, `files`, `blocking`, `resources` | Replaces the requested sensor list, empty for none. The opt-in `threads`, `environment`, `thread-activity`, `thread-locals`, `caught-exceptions`, and `security-sinks` are also accepted. Any other id fails the start while the agent is attached. |
| `bootui.agent.security-sinks.request-values` | `false` | With `security-sinks`, holds query and path parameter values transiently in memory so a sink can be checked for one appearing verbatim. Never persisted, logged, or displayed. Read at startup. |
| `bootui.agent.executors.max-handoff` | `5m` | The handoff window: a task belongs to its request when it starts no later than this after the request ended, and it is published `capped` when it runs longer. |
| `bootui.agent.ring-capacity` | `65536` | The records the agent's transport ring holds, clamped to 1,024–4,194,304 and rounded up to a power of two. |

::: details More executor properties

| Property | Default | Purpose |
| --- | --- | --- |
| `bootui.agent.executors.skip-tasks` | BootUI's, Micrometer's, and Spring's propagating wrappers, `jdk.internal.`, `sun.`, `java.lang.ProcessHandleImpl`, `com.zaxxer.hikari.`, `com.github.benmanes.caffeine.` | Task class-name prefixes never propagated. |
| `bootui.agent.executors.skip-threads` | `vert.x-`, `bootui-` | Worker thread-name prefixes never propagated to; on Spring, Reactor's `parallel-`, `boundedElastic-`, and `single-` are added when Reactor's automatic context propagation is on. |

:::

On Quarkus these are build-time properties, read when the application is built; dev mode rebuilds when they change.

## Code Paths

The Code Paths panel names the application methods a route spends its time in: it turns "handler 80 ms" into
"`SlowPricingService.quote` 55 ms", across the route's warm requests, without tracing, spans, or a profiler session. It
needs the agent's [`code-paths` sensor](#the-code-paths-sensor), on by default once the agent is attached. Without it the
panel is unavailable with the Java Agent panel's reason, and `get_code_paths` answers `available: false`. Reads change
nothing on Spring MVC, Spring WebFlux, and Quarkus; its one action is a [method probe](#method-probes), which the panel's
read-only policy refuses.

- **Routes**: ranked by warm median, slowest first, with warm requests, median, 95th percentile, and the top method by self
  time. A route marked **assembly only** ran its handler on an event loop or returned a reactive result, so its tree times
  the assembly, not the work that ran later.
- **Opening a route** shows its tree. The URL keeps the route (`?route=`) and the selected method (`?method=`).
- **The route's tree** shows each method under its caller: calls, total and self time per request, an approximate median
  (≈), and its share of the handler's time. The **hot path** is marked, and **Collapse to the hot path** folds every other
  branch. Work an executor ran for the request is marked **async**.
- **Calls under methods**: the SQL statements, REST client calls, cache accesses, and AI calls each method issued.
- **Selecting a method** opens its detail: callers, the routes that reach it, and **Probe this method**
  ([method probes](#method-probes)).
- **Beans at runtime**: the calls between beans observed in this run, beside the dependencies the beans declare. A filter
  keeps declared dependencies **not called in this run**: never "unused", since an untaken path may still call it.
- **Excluded methods**: methods the sensor stopped timing because they were called more than 50,000 times a second under
  2 µs each.

A method's self time is its time outside its recorded child methods, so a JDK, framework, or library method shows only as its
caller's self time. Route trees cover the current run only and are keyed by the routes [HTTP Exchanges](diagnostics.md#http-exchanges)
owns: while that panel is disabled, Code Paths is unavailable with that reason.

Two Runtime Insights observations use it: `route-time-breakdown` splits a route's handler time into its top five methods, and
`repeated-selects` names the method that issued the repeated statements.

::: details API, tree building, and bounds

| Path (all `GET`) | Returns |
| --- | --- |
| `/bootui/api/code-paths` | The sensor's status, the routes with a tree and their top methods, the excluded methods, and the limitations |
| `/bootui/api/code-paths/route?route=` | One route's tree, paged by `depth`, `offset`, and `limit` |
| `/bootui/api/code-paths/requests/{requestId}` | One request's tree while the run keeps it |
| `/bootui/api/code-paths/beans` | The observed calls between beans and the declared dependencies |

`get_code_paths` and `bootui code paths` return at most `limit` (10) routes matching `query`, slowest first. The
`diagnose_runtime_issue` MCP prompt points to it for a slow route's handler.

- The engine merges a request's fragments into a request tree, then, about two seconds after its last fragment, into the
  **route tree**, with a log2 histogram from which the approximate median and 95th percentile are read.
- A node is its caller, its method, and the request phase it was entered in, so response-write time never counts as handler
  time.
- Route trees are bounded: 2,000 nodes a route (one **Other** node per parent and phase past the budget), 100,000 nodes and
  500 routes across the run.
- The SQL, REST client, cache, and AI recorders take the stamp of the innermost instrumented method open on their thread, so
  each call is counted under the node that issued it. A call recorded on another thread carries no stamp.
- **Assembly only** applies to a Spring MVC handler that started async processing, every Spring WebFlux handler, and a
  Quarkus resource method on the event loop or returning `Uni`, `Multi`, `CompletionStage`, or a publisher.
- The runtime model gains an observed `INVOKES` edge between two beans per call pair. Change impact never walks it.
- Trees are kept under the [agent evidence contract](#agent-evidence-outside-the-journal).

:::

### Method probes

A method probe answers "did this method run, how long did it take, and for which request?" for one application method,
without a debugger, a breakpoint, or a log line. **Probe this method**, on a method selected in a route's tree, or **Probe
in Code Paths**, on a method Code Inventory lists as changed, asks for a confirmation, then the agent retransforms that one
method and records its next **20 invocations**, for at most **60 seconds**, **five probes at once**. Each invocation shows
its duration, thread kind, request id (a link to Live Activity), outcome (returned, or the exception type it threw), and
calling frame. By default a probe records **metadata only**: never an argument, a return value, or a field, in any exposure
mode. With **Record argument and return shapes** checked before the start, it also records the
[shapes](#argument-and-return-shapes) of the first nine arguments and the return value.

The **Method probes** card lists the run's probes, newest first, with their state and why each ended. A probe ends with its
run (a DevTools restart, a Quarkus live reload, or BootUI disabled). For a method returning `Mono`, `Flux`,
`CompletionStage`, `Uni`, or another reactive type, the probe is marked **assembly only**.

Starting and stopping probes are refused while BootUI or the panel is read-only; a running probe still ends by itself.
Starting is exposed in the browser, API, MCP, and CLI; stopping is browser and API only.

::: details Probe API, agents, and bounds

| Method and path | Does |
| --- | --- |
| `GET /bootui/api/code-paths/probes` | The run's probes with their recorded invocations, the bounds, and the limitations |
| `POST /bootui/api/code-paths/probes` | Starts a probe on `{"method": "com.example.PriceService#quote(I)J"}`, with `"recordShapes": true` for shapes: 400 for a method that cannot be probed, 409 when refused |
| `GET /bootui/api/code-paths/probes/{id}` | One probe; 404 when this run has none |
| `POST /bootui/api/code-paths/probes/{id}/stop`, `DELETE /bootui/api/code-paths/probes/{id}` | Stops a probe |

AI agents start one with `start_method_probe` (`bootui probe start <method>`) and read it with `get_method_probe` (`bootui
probe show <id>`), only after the user's separate approval; see
[Did this method run, and how?](../AI-AGENTS.md#did-this-method-run-and-how). Agents start metadata-only probes and never
see a shape.

- The probe's own advice counts invocations and checks the window, so a probe never records past its bound even if removing
  its instrumentation is slow or fails.
- A probe takes a method the inventory or code-paths sensor instrumented, in the application's packages, by
  `binary.Class#name` with its descriptor when the method is overloaded.
- The run's last 25 probes, with at most 20 invocations each, are kept under the
  [agent evidence contract](#agent-evidence-outside-the-journal).

:::

#### Argument and return shapes

::: details Shape table and exposure

A shape says what an argument or the return value looked like without saying what it was: its runtime type, whether it was
`null`, and, for a short list of JDK types, a size, a length, or a presence. It never shows a value.

| Value | Shape | Example |
| --- | --- | --- |
| `null` | null | `null` |
| A primitive | its declared type, never its value | `int` |
| `String` | its type; its length under `FULL` exposure | `String`, or `String (12 chars)` |
| Exact JDK lists, sets, and maps | its type and size | `ArrayList (size 3)` |
| An array | its type and length | `int[] (length 4)` |
| `Optional` and its primitive variants | present or empty | `Optional (present)` |
| An enum constant | its enum; the constant's name under `FULL` exposure | `Level`, or `Level.HIGH` |
| Anything else | its runtime type only | `Card`, `PersistentBag`, `Integer` |

No application code runs: the agent never calls `toString()`, `hashCode()`, `equals()`, a getter, an iterator, or the
`size()` of an application or Hibernate collection, and a lazy Hibernate collection is never initialized.

`bootui.expose-values` decides what the panel shows, live: under `METADATA_ONLY`, no shape (and a probe cannot be started
with them); under `MASKED`, the default, types, nullness, sizes, and presence; under `FULL` (or `MASKED` with
`bootui.mask-secrets=false`), also string lengths, array lengths, and enum constant names. MCP, the CLI, and exports never
return a shape, whatever the exposure.

:::

## Code Inventory

The Code Inventory panel answers the question a developer or an agent asks right after an edit: **did the code I changed
actually run?** It needs the agent's [`inventory` sensor](#the-inventory-sensor), on by default once the agent is attached.
Without it the panel is unavailable with the Java Agent panel's reason, and `get_code_inventory` answers
`available: false`. It is view-only on Spring MVC, Spring WebFlux, and Quarkus.

Its header states the run and **N of M application methods executed**. Three tabs follow:

- **Changed since the previous run**: the methods whose code changed, and those added, since the previous DevTools restart or
  Quarkus live reload, without git. Each says whether it executed in this run, with the first request and route that ran it
  (a link to Live Activity). Methods not executed come first.
- **Application code**: the claimed packages, then their classes and methods, with executed, never-executed, and
  not-tracked counts, filterable to *never executed*.
- **Dependencies**: the application's declared dependencies matched to the jars the agent saw define classes. A declared jar
  with no class loaded is **not loaded in this run**, never "unused": a route this run did not exercise may load it.

A method counts as executed or never executed only when the agent instrumented its class in this run, or when its class has
not loaded in this run at all. Any other method is **not tracked**, with its reason, and is never counted either way.
Methods called before BootUI claimed the agent are not seen. The first request and route belong to
[HTTP Exchanges](diagnostics.md#http-exchanges): while that panel is disabled, reads leave them out with the reason.

::: details API and how changes are detected

| Path (all `GET`) | Returns |
| --- | --- |
| `/bootui/api/code-inventory` | The run, the scan, the method, change, and dependency counts, and the limitations |
| `/bootui/api/code-inventory/changes` | The changed and added methods, not executed first |
| `/bootui/api/code-inventory/methods` | Methods filtered by `package`, `class`, and `status` (`executed`, `never-executed`, `not-tracked`, `generated`) |
| `/bootui/api/code-inventory/dependencies` | The dependency use, declared jars not loaded first, filtered by `status` |

`get_code_inventory` and `bootui code inventory` return the counts first, then at most `limit` (25) rows of `query`:
`changed` (the default), `never-executed`, `not-tracked`, `executed`, `dependencies`, or a package, class, or method name.
The `verify_after_change` MCP prompt starts from it, and Runtime Insights reports a changed method no request executed as
`changed-code-not-executed`.

- At each run, BootUI scans the application's own class files in the claimed packages, off the request path (test roots
  excluded), and hashes each method over its resolved instructions, never line numbers or other debug attributes. A
  recompilation with other debug settings or a lambda only renumbered hashes the same; a changed literal, method reference,
  or `@GetMapping` value does not.
- The scan is bounded by `bootui.code-inventory.max-classes` (20,000) and `bootui.code-inventory.scan-timeout` (30
  seconds). Past either, it is partial and says so, and unreadable methods are never reported as removed.
- The previous run's hashes are kept across restarts in the same JVM, at most 1 MB a run per application. A full JVM
  restart keeps no previous run.
- Which methods executed comes from the agent's hit flags, exactly; the first request, route, and time come from records a
  full ring may drop (counted, and said).
- A [HotSwap](#hotswap) shows in **Changed** only after the next restart or live reload.
- **Clear recording** drops every first request and route recorded before it; which methods executed still covers the whole
  run.

:::

## Side Effects

The Side Effects panel shows what application code starts outside the JVM or touches through agent sensors, grouped by
route, background work, startup, or thread family. It needs the [BootUI agent](#attaching-the-agent) attached and armed for
the application. Without that, the panel is unavailable with the Java Agent panel's reason. It is view-only on Spring MVC,
Spring WebFlux, and Quarkus, except that a switchable sensor's section carries its
[runtime switch](#switching-sensors-at-run-time).

The panel has one tab per sensor group:

| Tab | Sensors | State |
| --- | --- | --- |
| Network | `network` | Connects, datagram sends, and name lookups ([network](#the-network-sensor)). |
| Files and processes | `files`, `processes` | Both record by default; `files` can be switched at run time. |
| Environment | `environment` | Records when opted in or switched on; otherwise `not-claimed`. |
| Threads and leaks | `thread-activity`, `thread-locals`, `resources` | `resources` records by default; the other two record when opted in or switched on. |
| Blocking | `blocking` | Records on Spring WebFlux and Quarkus; `not-applicable` on Spring MVC until a WebClient's event loop is registered. |
| Security sinks | `security-sinks` | JDK checks when opted in; request input reaching sinks also needs `bootui.agent.security-sinks.request-values=true`. |

::: details Attribution, API, and row details

Each row is attributed to the request route from HTTP Exchanges, then the execution no request owns (`scheduled
ReportJob.run`, `consumed kafka orders`, `websocket /ws`), then startup, then a normalized thread family such as
`pool-{n}-thread-{n}`. The call site is the first application frame, rendered as `Class#method`. Rows link to Live Activity
through up to three exemplar request ids. While HTTP Exchanges is disabled, route rows merge under
`(route hidden: HTTP Exchanges is disabled)`.

Rows show what each sensor is allowed to record, never more: process rows show the command name, not arguments; file rows
show a path pattern, not contents; environment rows show a name, not a value. Network rows carry how the connection is
captured: `captured` with the panel's id, `not-captured`, or `infrastructure`.

Runtime Insights' [run comparison](runtime-insights.md) reads these rows too: under **Outside the JVM** it lists the hosts,
file patterns, processes, and variable names a route, a job, or startup uses now and did not in the previous run, or no
longer uses.

Side Effects is an agent evidence store: **Clear recording** and **Free BootUI memory** clear its rows, and the panel then
says **The recording was cleared**. When the panel is disabled, the API, MCP tool, and CLI show only
**The Side Effects panel is disabled.**

| Path (all `GET`) | Returns |
| --- | --- |
| `/bootui/api/side-effects` | Every sensor with its tab, state, reason, rows, occurrences, dropped records, hooks, and limitations |
| `/bootui/api/side-effects/sensor?sensor=<id>&offset=&limit=` | One sensor's rows, most frequent first, paged; an unknown sensor id returns `400` with `{error}` |

`get_side_effects` and `bootui side-effects` return every sensor's coverage, then at most `limit` (20) rows matching
`query`: a sensor id such as `processes`, `network`, or `blocking`, `not captured` for the connections no panel shows, or
part of a route, target, client, or call site, most frequent first. The MCP tool is advertised only while the agent is armed.

- Process rows: starts, failed starts, completed exits, non-zero exits, last exit status, total and longest lifetime.
- Network rows: kind (`connect`, `datagram`, or `lookup`), client, capture state, attempts, failures, and times.
- Files rows: path pattern with its kind (`read`, `write`, `delete`, `move from`, `move to`, `copy from`, `copy to`),
  location, and origin. Class path, JDK, and logging rows are grouped apart in a collapsed table.
- Thread-activity rows: kind (`thread`, `virtual thread`, or `executor`), target, origin, how many were started or created,
  and how many were left running.
- Blocking rows: operation (`sleep`, `wait`, or `park`), event loop family, call site, and inside bean method, with the
  calls, interruptions, and how long they blocked the loop.
- Rows are capped at 500 per sensor and 2,000 per run; extra observations are counted in the sensor's **Other** row. These
  bounds shrink in proportion when `bootui.runtime-journal.agent-evidence-max-bytes` is set below its default. A request
  that never receives a route after 30 seconds is `(unknown route)`.
- A comparison shows absence as unknown, and **Partial**, for an owner whose work is still pending.

:::

::: details Sample application routes

The three samples seed routes that show each sensor with the agent attached, each with a counterexample:

- `GET /api/side-effects/java-version` starts `java -version` (a `java` process row); `GET /api/side-effects/runtime-version`
  answers from the running JVM and starts no process.
- `GET /api/side-effects/sdk-call` connects through an SDK-style client with its own `java.net.Socket`, which reads **Not
  captured by any panel**; `GET /api/side-effects/rest-call` goes through the recorded REST client and is never not captured.
- `GET /api/side-effects/report` writes a dated report under `target/bootui-side-effects` and reads the `sample.report.title`
  property (with `environment` opted in); `scratch` writes to `$TMPDIR` and `log` writes through a JDK logging handler.
- The WebFlux and Quarkus samples also seed `GET /api/side-effects/event-loop-sleep`, which sleeps 50 ms on an event loop,
  and `worker-sleep`, the same sleep on a worker thread, which shows no row.

:::
