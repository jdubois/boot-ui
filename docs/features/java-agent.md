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
  The JVM splits `JAVA_TOOL_OPTIONS` on whitespace, so a jar path with spaces or quotes is quoted inside the value and
  the value is single-quoted for the shell: `JAVA_TOOL_OPTIONS='"-javaagent:/my agents/bootui-agent.jar"' ...`.
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
the sensor is installed and self-tested again afterward. This applies to both `executors` and `threads`.
A transformer stays installed across claims, so the sensor row's **This claim** column says whether this application's
armed claim uses it: a sensor missing from the claim's `bootui.agent.sensors` reads `inactive`, and an inactive
`executors` sensor propagates nothing. Each threads
transformer retains its own package history for restoration: reclaiming with different packages cannot leave advice
on subclasses from the previous claim. The replacement transformer uses the new claim's packages.

## The executors sensor

A claim asks for the sensors in `bootui.agent.sensors`: `executors`, [`inventory`](#the-inventory-sensor), and
[`code-paths`](#the-code-paths-sensor), the defaults, and the opt-in [`threads`](#the-threads-sensor). The agent installs each one once, on its own thread, then
self-tests its hooks with private pools. BootUI offers the `PROPAGATED` tier only after every core executor hook passes;
an installed transformer alone is not verification. Advice may run while the asynchronous probe is pending, but BootUI
does not advertise propagation as available then. The sensor row
shows its state (`installing`, `testing`, `installed`, `self-test-failed`, `failed`, or `off`), how long the last install (including its
retransformation of loaded classes) and its self-test each took, the self-test's result, how many JDK types it
instrumented, how many loaded classes it retransformed and how long all its installs and releases took, and the types
that failed to transform. The **Class transformation** card sums every sensor's transformed, retransformed, failed, and
skipped classes and its install and release time since the JVM started: the time is aggregate work, not a wall-clock
interval, because the sensors install one after another and also retransform when a claim is released. Its state is
`off` once every sensor is released. A sensor that finished installing but is still `testing` counts as installed in
this class-transformation summary, not as retransformation still running. The summary reads `failed` when a sensor's
behavioral self-test fails, even if the executor transformer remains installed with propagation disabled.
Without a sensor, the
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
| `ThreadPoolExecutor.addWorker` | receives tasks | a task that starts a new `ThreadPoolExecutor` worker |
| `ThreadPoolExecutor.queue` | receives tasks | a task `ThreadPoolExecutor.execute` or `submit` puts on the work queue |
| `ScheduledThreadPoolExecutor` | receives tasks | one-shot delayed tasks; periodic tasks are never propagated |
| `ForkJoinPool` | receives tasks | `execute`, `submit`, and `invoke` of a root task |
| `ForkJoinTask adapters` | receives tasks | the pool's `Runnable` and `Callable` adapters |
| `ForkJoinTask.fork` | receives tasks | a `fork()` from a thread outside the pool |
| `DelayScheduler` | receives tasks | JDK 25+ delayed tasks, such as `CompletableFuture.delayedExecutor` |
| `CompletableFuture.ThreadPerTaskExecutor` | receives tasks | the thread-per-task fallback of `CompletableFuture` (JDK 17 to 25) |
| `ThreadPoolExecutor.runWorker` | runs tasks | every task a `ThreadPoolExecutor` runs |
| `ForkJoinTask.doExec` | runs tasks | every task a `ForkJoinPool` runs |
| `CompletableFuture.AsyncSupply` | runs tasks | `supplyAsync` stages, whose outcome it reads |
| `CompletableFuture.AsyncRun` | runs tasks | `runAsync` stages, whose outcome it reads |

The hooks table shows, for each one, whether this JDK has its type, whether the agent instrumented it, its self-test
result (`passed`, `failed`, `not-exercised`, `unsupported`, or `not-run`), and how many tasks it received or ran. Each
hook passes only on its own count: a thread pool's `addWorker` and `queue` keys and `CompletableFuture`'s supply and run
stages are tested separately, so one working sibling cannot hide a missing hook. A self-test failure of a
`ThreadPoolExecutor` or `ForkJoinTask.doExec` hook disables propagation for that claim, including a timeout,
interruption, or probe error with no positive hook hits. Detailed step outcomes remain in the report: an inconclusive
core hook is not a pass. The panel says why; the next
claim tests again. A failed `CompletableFuture.AsyncSupply` or `AsyncRun` hook disables nothing: a pool's run hook
then propagates that kind of stage itself, without reading its outcome; a stage `CompletableFuture` runs on its own
thread-per-task fallback has no other run hook, so it is counted as never applied. The hooks are verified on JDK 17, 21, 25, 26, and 27; on any other JDK the
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
message, or failed. A task is after the response when its body ends after its request's response started: a normal
JDK body whose handler waited for its result ended before, and a request that marked no response, as when its handler failed, is compared with its
end; on Spring WebFlux, which marks no request phases, the request's end stands in for its response.
For plain `Runnable` tasks the agent marks the body's return, before closing the handoff.
Completing a promise inside that body is not its return: the body can keep computing after the handler answers.
This boundary includes a decorator's code and synchronous completion callbacks the runnable invokes; a handler
waiting for a hidden future or promise does not necessarily wait for the submitted runnable's whole body.
If that nested publication or a task's own early result publication preceded the response, body-return evidence uses
2 ms of clock slack to avoid counting
a near-response decorator return as a long continuation. A response already present at the first nested publication
is a causal ordering fact, so fast bodies already running after the response do not need that slack.
For JDK `FutureTask`, fork/join tasks, and asynchronous `CompletableFuture` stages, the agent marks body completion
before normal JDK result publication releases waiters. If the body explicitly publishes its own result early, its
later return remains the body boundary, including a `CountedCompleter` whose `exec()` returns false. An externally
completed or cancelled task whose computation is skipped does not acquire a body-return marker.
A body that ends after the response counts all of its
attributed SQL, REST, and message evidence, including an earlier write followed by computation and a fast task that
started after the response. A waited-for body is not reported merely because its handoff closes late.
The full handoff lifetime remains visible: `FutureTask.done()` and synchronous dependent stages can still do real
work after result publication. A failure observed escaping the task's result-publication tail is timed separately
from a body failure stored in its future, using the same 2 ms clock slack as unconfirmed late I/O; the escaping
failure's class takes precedence if both body and tail fail.
When the body ended before the response, or its completion could not be confirmed, only I/O ending at least two milliseconds past the
actual response boundary counts. With no retained request timeline, the request's end is the conservative boundary;
pure computation after earlier I/O cannot be distinguished from closure bookkeeping without a body marker.
Explicitly publishing a failure early preserves that outcome's original response ordering, separately from the
body's later return when the publication hook is observed. Self-cancellation and `CompletableFuture` operations
using `internalComplete` (`completeExceptionally`, `cancel`, and `obtrude*`) have no early-publication hook; their body
or early-failure timing can remain unconfirmed. Latches and foreign futures do not define a submitted runnable's
return either.
That observation and the request profile's `PROPAGATED` tier apply only while the agent is attached
and armed for the application, its `executors` sensor is installed with a passed core-hook self-test and is not disabled, and BootUI
attached its handoffs to the claim; otherwise they say which of these is missing.

## The threads sensor

`bootui.agent.sensors=executors,threads` adds the `threads` sensor, which carries a request into the threads its
application code starts, with `new Thread(...).start()`, a virtual thread, or
`Executors.newVirtualThreadPerTaskExecutor()`. It is off by default because `java.lang.Thread` is the riskiest JDK
class to retransform; it installs and self-tests on its own, with a platform thread and, from JDK 21, a virtual thread.
A failed or inconclusive self-test of a present key or apply hook stops only this sensor, at once, then removes its
transformer; timeouts, interruptions, and probe errors do not verify a hook without positive hits. The deliberately
unexercised application-subclass hook and unsupported virtual-thread features do not prevent a pass, including preview
virtual threads disabled on JDK 19/20. If the JVM refuses removal, the sensor
stays stopped and its state reads `self-test-failed (release-failed)`.

| Hook | Role | JDK type |
| --- | --- | --- |
| `Thread.start` | starts threads | `Thread.start()`, and on JDK 21+ the `start(ThreadContainer)` executors use |
| `VirtualThread.start` | starts threads | JDK 21+ virtual threads |
| `Thread.run` | runs threads | a platform thread's task, through `Thread.run` (JDK 17) or `Thread.runWith` (JDK 21+) |
| `VirtualThread.run` | runs threads | JDK 21+ a virtual thread's task, through `Thread.runWith`, self-tested on its own |
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

## The inventory sensor

The `inventory` sensor, on by default, records which application methods ran in this run and which jars and class
directories loaded classes. The [Code Inventory](diagnostics.md#code-inventory) panel reads it (changed methods since the
previous run, executed and never-executed code, dependency use); the Java Agent panel shows the sensor's row, its hooks,
and its counters.

| Hook | Role | What it covers |
| --- | --- | --- |
| `method entry` | records first calls | every non-abstract, non-native method and constructor of the claimed packages' classes |
| `class load` | counts loaded classes | every class definition with a code source, outside the JDK, BootUI, and Byte Buddy |

**Executed methods.** The agent adds one entry check to each instrumented method: a method that already ran in this
run costs one array read and volatile reads. Its first call in a run marks it executed and, when the call belongs to
a request or another BootUI execution, records the request id, the route, and the time. A first call with nothing to
attribute, as at startup, is marked executed without a record. Each claim starts a new run, so a DevTools restart or a
Quarkus live reload counts executions afresh, while a method keeps the same id for the agent's lifetime, in every class
loader that defines its class. While nothing records (the claim disarmed, or the sensor stopped), a method's first call
still marks it, without a record, so it never stays on the slower first-call path; a claim that does not ask for the
sensor removes its instrumentation.

Execution also checks a primitive defining-loader token. Each claim admits its calling thread's context class loader
and ancestors, and loaders first seen defining new classes beneath them; retained sibling loaders from earlier runs
stay ineligible, even if another agent retransforms their classes. Classes the new context loader defined before claiming
become eligible at the claim without retransformation. Old application objects and their tasks cannot mark a changed method executed
in the replacement run. Hit flags belong to the run, so a hit racing a restart or the byte epoch wrapping cannot
write into the replacement run's flags. Applications claiming with an unrelated or null context loader must supply
the application loader as their thread context loader to admit its already-defined classes.
The claim enumerates the JVM's loaded classes to identify pre-existing loaders before admitting fresh definitions;
an old loader defining a lazy class after an inventory-off run cannot become a new-run loader that way. A new loader
appearing after that snapshot may be admitted even when installation first encounters its class already loaded.
Known loader provenance never changes when an empty ancestor later defines its first class.

Instrumented packages only grow while the sensor is installed: a package a refine added stays instrumented when a later
claim asks only for its base packages, as after a DevTools restart, so its classes are instrumented in the new class
loader too, and a release restores every class instrumented. Each claim and refine also retransforms the claimed
packages' loaded classes that were never instrumented in their class loader.

Never instrumented: static initializers, methods whose names start with `$` (such as JaCoCo's `$jacocoInit`),
abstract and native methods, BootUI's own classes, the agent and Byte Buddy, Spring's CGLIB and AOT proxies (`$$` in
the name), ArC's generated `_Subclass`, `_ClientProxy`, and `_Bean` classes, Hibernate and Mockito proxies, synthetic
classes, and classes loaded from a test root (`test-classes`, or Gradle's `build/classes/<language>/test`). Lambda
bodies, bridge methods, default and static interface methods, and records' methods are instrumented. A method counts as
**tracked** only once its class was transformed; a class that fails to transform, such as one with a method near the
JVM's 64 KB code limit, is named in the sensor row and its methods are not tracked. A class instrumented only after it
loaded (retransformed when the sensor installed or a refine added its package) may have run before, unseen: its methods
are marked **late** for that run, so the Code Inventory panel can say they ran before instrumentation rather than that
they never ran. From the next run on, they are tracked from the start.

**Class loads.** A second transformer, which never changes a class, counts the classes each code source (a jar or a
class directory, keyed by its location) defines in this run, with the time of its first class and, for that first
class, the route that loaded it. Classes already loaded when the sensor installs are counted once, as loaded before the
claim; redefinitions are ignored; and classes BootUI loads for its own scans and checks are not counted.

**Self-test.** The sensor installs once, off the claiming thread, then calls a bundled probe class whose advice must
reach the bridge. A failure stops only this sensor at once, then removes its transformers; if the JVM refuses that, it
stays stopped for good.

**Transport and limits.** Records travel through the agent's bounded ring, `bootui.agent.ring-capacity` records of 64
bytes (65,536 by default, 4 MB; the first claim in a JVM sizes it). The ring never blocks: a record that does not fit is
dropped and counted, and since the executed flags are kept apart, a dropped record loses only the first request, route,
and time, never the fact that the method ran. Routes are interned per run, at most 16,384 of them; past that they are
recorded as unknown. At most 262,144 methods and 4,096 code sources are tracked for the agent's lifetime; past that,
methods are left uninstrumented and counted.
Defining-loader tokens use weak identity keys in the isolated agent, never held by the bootstrap bridge. Only loaders
defining claimed types, the claim's context loader, and their ancestors receive tokens; observing ignored classes does
not consume capacity. The 16,383 non-bootstrap slots are reusable after a phantom reference proves a loader has died
and cannot be resurrected. At capacity, affected definitions cannot record execution and remain **not tracked** with a
reason, even in subsequent runs, until instrumented with a valid token in a newer run. Within a run, an untracked copy
keeps the method uncertain even if another copy was instrumented successfully. The sensor counts `definitionOverflow`
and the inventory reports a limitation: missing tracking evidence is unknown, never confidently **never executed**.

| Counter | What it counts |
| --- | --- |
| Methods tracked | Application methods instrumented, whose executions the sensor sees. |
| Executed this run | Tracked methods that ran at least once since this run claimed the agent. |
| Over the method limit | Methods left uninstrumented because the method limit was reached. |
| Transform failures | Application classes that failed to transform. |
| Code sources | Jars and class directories that defined at least one class. |
| Records dropped | Records dropped because the ring was full. |
| Records lost | Records whose writer never finished them: skipped after 50 drains and a second, or found half-written. |
| Strings over the limit | Routes recorded as unknown because the run's string table was full. |

In the report, the sensor's `inventory` object carries them as `methodsTracked`, `executedThisRun`, `methodOverflow`,
`transformFailures`, `codeSources`, `ringDropped`, `ringLost`, and `internOverflow`, with `disabledReason` when the
sensor stopped recording; its hooks' `fired` counts are first calls and counted class loads. Other sensors' `inventory`
is `null`, as the inventory sensor's `executors` is.

Accepted limits: calls made before BootUI claims the agent (typically the main class's, or a restarted context's
startup code before its claim) are not seen, though in the agent's first run the classes already loaded are marked
late; a method HotSwapped without a restart keeps its flag; and Mockito's inline mock maker dispatches a stubbed call before the
sensor's check, so a stubbed method does not count as executed, while a spy's real call does. The `inventory` sensor
shares its transformer with the [`code-paths` sensor](#the-code-paths-sensor).

## The code-paths sensor

The `code-paths` sensor, on by default, times the application's bean methods per request: for each request, a call
tree of the public and protected methods of its beans, merged by caller and method, with each node's calls, total time,
time in its callees, and the request phase it entered in (filters, handler, or response). It is the evidence behind
the [Code Paths](diagnostics.md#code-paths) panel, `get_code_paths`, and `route-time-breakdown`'s handler split; the
engine keeps the request and route trees in memory for the current run, and the Java Agent panel shows the sensor's row,
its hook, and its counters.

| Hook | Role | What it covers |
| --- | --- | --- |
| `bean methods` | times bean methods per request | the public and protected instance methods of the application's bean classes |

**What is timed.** The bean classes come from the adapter: on Spring, the user class of every bean in the
application's packages, read when the context refreshes (a singleton's target class through any AOP proxy, else the
bean definition's type, CGLIB subclasses resolved to their user class); on Quarkus, the application archive's classes
with a bean-defining annotation (`@ApplicationScoped`, `@RequestScoped`, `@SessionScoped`, `@Dependent`, `@Singleton`,
`@Startup`) or `@Path` or `@Provider`, read at build time and sent with the static-init claim. The agent keeps them as a
union across claims, so a DevTools restart's or a live reload's classes get their timing as they load, and a refine
that names a bean class already loaded retransforms it. Never timed: constructors, static initializers, static methods
(such as Panache's), private and package-private methods, `$`-prefixed, synthetic, and bridge methods, `equals`,
`hashCode`, and `toString`, record accessors, configuration-properties holders (a class annotated
`@ConfigurationProperties`, or created by a `@Bean` method annotated so), interfaces, lambdas, and everything the
[inventory sensor](#the-inventory-sensor) never instruments. A framework or library method appears only
as time of the bean method that called it.

**One transformer.** The `inventory` and `code-paths` sensors share one transformer of the application's classes, with
one advice per sensor: a claim asking for one of them applies only its advice, a claim asking for another set
retransforms the classes whose advice changes, and a claim asking for neither removes the transformer and restores every
class. Each advice has its own self-test; the code paths' calls a bundled probe whose methods must see their entry
counted and their exit popped, both on return and on a thrown exception. A failed self-test removes only that sensor's
advice. A class whose transformation fails with the code paths' advice, as a method that advice pushes past the JVM's
64 KB code limit, is never given it again and is retransformed with the inventory's advice alone, so it keeps its
inventory. Loaded classes are retransformed in batches of 64, a batch the JVM rejects split down to the class it
rejects.

**On the application thread.** The advice is one call into the agent's bridge at entry and one at exit. A thread's
first timed call captures its owner once, through the same correlation BootUI's executor propagation uses: a request,
or a task an executor runs for one, which the tree keeps apart as an asynchronous child of its request. A call with no
owner, as at startup or in a scheduled job, only counts depth, so its callees never capture again. With an owner, the
thread borrows a tree from a pool of 256 and records into it until the outermost call returns, or, around a Spring MVC
request's scope and a Quarkus request's event-loop routing, until the adapter closes the scope, so application filters
outside BootUI's own filter are recorded apart; a scope opened again inside it for the same request, as a Vert.x
reroute, is part of it, and a scope in which no bean method ran hands nothing over. The tree is then handed to BootUI as
one fragment of at most 512 nodes:
calls deeper than 32 levels stay in their level-32 ancestor's time, and once 480 nodes are used, each caller's further
methods share one **Other** node. A Quarkus blocking resource method runs on a worker thread, whose fragment starts at
its outermost bean call; its nodes record the phase the request's filters last marked on that worker, which is cleared
when the worker's fragment ends and when the response has been written, so the worker's next work never inherits it.

**Adaptive exclusion.** BootUI sums each method's calls and time from the fragments, with the time those fragments
recorded, not the wall time between them, so a request a minute that calls a cheap mapper a million times is judged on
what it did. A method is judged once its fragments recorded 100 ms, which one long request can do alone, and otherwise
every second if it was called at least 5,000 times; one called more than 50,000 times a second of that recorded time,
with a mean under 2 µs, such as a getter in a loop, is no longer timed for the rest of the run, and its time stays in
its caller. A new run times it again.

**Bounds and failures.** Nothing on the application thread blocks or allocates per call: an empty pool drops the
fragment, a full queue (4 MB of fragments) drops it, and both are counted. Every entry point of the bridge catches its
own errors and resets the thread's state, and after 100 internal errors the sensor switches itself off for the JVM's
life, which its row says. A `StackOverflowError` or `OutOfMemoryError` thrown inside the bridge, as an application's
runaway recursion through timed methods, resets the thread's state too but is the application's, never counted toward
that limit. A thread that dies inside a request's scope keeps its tree only until the next run, whose pool starts from
the free trees. On the machine this was measured on, a timed call costs about 110 ns, most of it the two
`System.nanoTime()` reads (43 ns each there); a call with no owner about 26 ns, and an excluded one about 10 ns.

| Counter | What it counts |
| --- | --- |
| Fragments recorded | Call trees of requests' bean methods handed to BootUI, one per thread. |
| Fragments dropped | Fragments not recorded because every tree of the pool was in use. |
| Queue full | Fragments dropped because the fragment queue was full. |
| Calls in no node | Calls deeper than 32 levels or past a fragment's node budget, whose time stays in their caller. |
| Bytes waiting | Fragments waiting for BootUI to read them. |
| Methods excluded | Methods no longer timed in this run by the adaptive exclusion. |
| Internal errors | Errors of the sensor itself; after 100 it switches itself off. |

In the report, the sensor's `codePaths` object carries them as `fragmentsFlushed`, `fragmentsDropped`, `queueDropped`,
`callsDropped`, `queueBytes`, `excludedMethods`, and `errors`, with `disabledReason` when the sensor stopped recording;
its hook's `fired` count is fragments, not calls, since the advice keeps no global counter. Other sensors' `codePaths`
is `null`. The shared transformer's counters are on the `inventory` row while its advice applies, and on the
`code-paths` row otherwise.

**On BootUI's side.** BootUI's drain thread reads the fragments every 100 ms and merges each request's into its
request tree, which settles about two seconds after its last fragment, when the engine looks up its request's exchange
and stamped calls in the runtime journal. Under sustained load, once 512 younger request trees are open, the eldest 128
settle together, with one journal read for all of them. Settling them one at a time read the whole journal once per
request: in a profile of the sample under the agent overhead benchmark's load, that was 15 % of the process's CPU,
against about 0.25 % for the advice on the application threads.

**Debuggers.** The agent's bridge, which the advice calls, carries no line numbers or local variable tables, only its
source file names, so stepping into an instrumented method in IntelliJ IDEA, Eclipse, or any JDI debugger steps over
the advice's calls into BootUI and stops in the application's method, as a forked JDI test verifies; an error the bridge
reports shows its file but no line number. Two calls also reach BootUI's correlation capture, which is regular
application-class-path code: the first timed call on a thread with no request scope open, as an executor task or a
scheduled job, and the first call of each method in a run, which the inventory sensor records with its owner. A debugger
stepping into one of those can stop there, unless `io.github.jdubois.bootui.*` is in its step filters.

Accepted limits: Spring WebFlux and reactive Quarkus endpoints time the assembly of their pipeline on the thread that
builds it, not its execution; a request's handler phase cannot yet be split by these trees (that comes with the Code
Paths panel); calls made before BootUI claims the agent are not seen; and an agent jar from before this sensor leaves
it unavailable while the other sensors keep working.

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
| `bootui.agent.sensors` | `executors`, `inventory`, `code-paths` | The sensors this application asks for: `executors`, `inventory`, and `code-paths`, and the opt-in `threads`. |
| `bootui.agent.executors.skip-tasks` | BootUI's, Micrometer's, and Spring's propagating wrappers, `jdk.internal.`, `sun.`, `com.zaxxer.hikari.`, `com.github.benmanes.caffeine.` | Task class-name prefixes never propagated. |
| `bootui.agent.executors.skip-threads` | `vert.x-`, `bootui-` | Worker thread-name prefixes never propagated to; on Spring, Reactor's `parallel-`, `boundedElastic-`, and `single-` are added when Reactor's automatic context propagation is on. |
| `bootui.agent.executors.max-handoff` | `5m` | The handoff window: a task belongs to its request when it starts no later than this after the request ended, its work is attributed until this long after it started, and it is published `capped` when it runs longer. |
| `bootui.agent.ring-capacity` | `65536` | The records the agent's transport ring holds, clamped to 1,024–4,194,304 and rounded up to a power of two; the first claim in a JVM sizes it. |

On Quarkus these are build-time properties, read when the application is built (augmented); dev mode rebuilds when
they change.
