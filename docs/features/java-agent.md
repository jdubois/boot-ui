# Java Agent

The Java Agent panel explains whether the optional BootUI `-javaagent` is attached to the current JVM, whether this
application has claimed it, and how to attach it when it is missing. It is view-only on Spring MVC, Spring WebFlux, and
Quarkus.

The panel opens the sidebar's **Instrumentation** group, the setup and status entry point for the panels that read the
agent's sensors: [Code Paths](#code-paths) and [Code Inventory](#code-inventory). Without the agent those two stay in
the group, dimmed, with the reason they are unavailable, so they can be found before the agent is attached; only
`bootui.panels.<panel-id>.enabled=false` moves them to *Disabled / unavailable*.

The agent is a development-time helper that propagates a request's correlation through the JDK's executors, so work an
application hands to a raw thread pool or `CompletableFuture` is owned by the request that handed it over. It is
local-only, exports nothing, and stays dormant until BootUI claims it. A Spring or Quarkus application that starts
without `-javaagent` behaves exactly as before and reports `NOT_ATTACHED` with setup snippets.

While the agent is not attached, the panel opens on its setup. Under the **Not attached** status, **Attach the agent**
lists the steps (download the jar when it is missing, add `-javaagent` where the application starts, and restart) above
the [snippet tabs](#attaching-the-agent). **What the Java agent adds** follows: what a Java agent is, how BootUI's stays
idle until the application claims it and records metadata rather than data, which features need it, what works
without it, and what it costs. The sections that describe an attached agent (versions and runtime facts, the claim,
opt-in sensors, sensors, class transformation, and counters) are left out until it is attached. In every other state,
`DORMANT`, `UNAVAILABLE`, and `DISABLED` included, the agent's own diagnosis comes first and the setup snippets close
the panel: the agent is already on the JVM or cannot be used, and the status reason names the fix.

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
`loadMode`, `jarPath`, `startupMicros`, `claim`, `heldBy`, `sensors`, `toggles`, `retransformation`, `counters`,
`messages`, `warnings`, and `setup` (with `jarPath`, `jarFound`, `buildTool`, and `snippets`).

| State | Meaning |
| --- | --- |
| `NOT_ATTACHED` | The bridge is not on the bootstrap class path; the JVM started without `-javaagent`. |
| `DORMANT` | The bridge is present, but this application has not claimed it, for example because `bootui.agent.enabled=false`. |
| `ARMED` | This application owns an armed claim. |
| `HELD` | Another application in the same JVM owns the agent; `heldBy` names it. |
| `DISARMED` | This run ended its claim. |
| `UNAVAILABLE` | The bridge is present but the agent did not start, the protocol differs, or the runtime cannot use it. |
| `FAILED` | The agent rejected or failed this application's claim. |
| `DISABLED` | Agent support is disabled, including Quarkus production mode and a Spring application forced on in a disabled profile such as `prod`. |

A different agent version on the same protocol is shown as a warning. Protocol mismatches are unavailable rather than
best-effort.

## Switching opt-in sensors at run time

The opt-in sensors, `threads`, `files`, `environment`, `thread-activity`, and `thread-locals`, can be switched on and off for the running application
without a restart, from the panel's **Opt-in sensors** card or from each opt-in sensor's section in
[Side Effects](#side-effects), the way the MCP Server panel switches MCP. Each switch shows the configured value from
`bootui.agent.sensors`, an **Overridden** badge when the switch differs from it, the sensor's state (installing,
self-testing, recording, or failed), and why the sensor is off by default. The opt-in `caught-exceptions` sensor is not
switched at run time: its visit of the application's classes is installed with the claim only.

| Sensor | Why it is opt-in |
| --- | --- |
| `threads` | It retransforms `java.lang.Thread`, the riskiest JDK class to instrument; a failed self-test leaves it off until the application restarts. |
| `files` | With the default sensors, the agent's overhead on the benchmark's I/O route measured about 10.6 %, over the 10 % budget. |
| `environment` | It advises `System.getProperty`, which frameworks call often: about 23–28 ns per read instead of 5–6 ns. |
| `thread-locals` | It scans the thread-local maps of every pooled request thread; it stays opt-in until its overhead is measured on more routes (about 0.5 % over the default sensors on the benchmark's route). |

`POST /bootui/api/java-agent/sensors/{id}` with `{"enabled": true}` or `{"enabled": false}` switches one and returns
the updated report. It is the panel's only action, so `bootui.panels.java-agent.read-only` and `bootui.read-only`
refuse it with the canonical 403, and it carries the same localhost, Host, and cross-site write protections as every
BootUI action. Another sensor id, or a body without `enabled`, answers 400; a switch the agent cannot make answers 409
with the reason: the agent is not attached or not armed for this application, an older agent predates switches, the
claim changed meanwhile, `threads` already failed in this run, or `files` or `environment` already failed its
self-test in this JVM. There is no MCP tool or CLI command for it, and only these sensors are ever switched: the
bridge refuses any other, the default sensors, `blocking`, and `caught-exceptions` included. The report lists `toggles`
only while this application's claim is armed.

The agent applies a switch to the running claim, keeping its generation: switching `threads` on installs and self-tests
it, and off restores `java.lang.Thread`. Switching `files` or `environment` stops their recording at once, then
reinstalls the side-effect transformer that `processes`, `network`, and `blocking` share with them, and runs the
self-test of every side-effect sensor the claim uses again: those sensors pause for the reinstall, and one whose core
hook fails that self-test stays off for the JVM's life, as at startup. After a switch, both panels read the sensors'
states again. Switching `thread-locals` transforms nothing: it enables or disables its scan, and a scope opened before
the switch is closed without a report. When the agent fails a switch the bridge already kept, the switch shows **Failed** with the agent's reason
rather than installing.

A switch is a runtime override, never written to any file. The bootstrap bridge keeps it for the application's slot
(`mode:application`), so every DevTools restart and Quarkus live reload claims again with it applied, and a full JVM
restart forgets it. A test run is another slot and never inherits a dev switch. A switch the configuration comes to
agree with, as after adding the sensor to `bootui.agent.sensors` and restarting, is dropped, so the configuration wins
again from then on.

## Claims and lifecycle

Spring claims the agent from `BootUiAgentClaimEnvironmentPostProcessor`, registered in `META-INF/spring.factories`, as
soon as BootUI's activation is resolved, with the main application class's package and `bootui.agent.packages`. The claim
is refined with the auto-configuration packages when the application context refreshes, disarmed on close or startup
failure, and released when BootUI is inactive or `bootui.agent.enabled=false`, so the agent removes its transformers.
A `SpringApplication` run inside another one's, as Spring Cloud's bootstrap context (or an environment carrying its
`bootstrap` property source), neither claims nor releases: it would otherwise claim with its own sources' packages, or,
resolving BootUI to disabled without the application's configuration, release the claim the application makes or keeps
armed across DevTools restarts.

When `bootui.enabled=ON` forces BootUI on despite an active `bootui.disabled-profiles` entry, such as `prod`, Spring
releases the agent instead of claiming it, logs why at `INFO`, and the panel reports `DISABLED` with that reason, as
Quarkus production mode does: the agent must never be attached to a production JVM. Only
`bootui.agent.allow-in-disabled-profiles=true` claims it there anyway.

Quarkus claims from a `STATIC_INIT` recorder in dev and test launch modes, refines on startup, and disarms on shutdown.
Production launch mode never claims the agent and reports `DISABLED` with reason `Quarkus production mode`. The
`bootui.agent.*` properties are build-time on Quarkus, so the panel reports the `bootui.agent.enabled` the build claimed
or released with, never a runtime value, which changes nothing.

**When the claim happens.** On Spring, the claim is made once the environment is prepared, before any bean is created.
In Spring PetClinic and the Spring sample, both started with DevTools on JDK 26, it came at about 0.6 to 0.75 s of JVM
uptime in each restart's thread. By then the restart class loader had loaded only the main class (and, in the sample, the
primary source and a listener its main method registers). On Quarkus, a static-init step makes the claim. In the Quarkus
sample on JDK 17, it came about 0.5 to 1 s after static init began. By then three application classes had loaded for
earlier static-init steps: a configuration mapping, an exception class, and an AI service interface, none of whose code
runs then. Classes loaded before the claim are instrumented when the sensors install, and marked late for that run.

**What the first claim costs.** The first claim in a JVM installs the sensors off the claiming thread. These figures were
measured on a shared 10-core laptop running other builds, at load averages of 21 to 35. They are upper bounds, not typical
values:

- In PetClinic, the agent's own start took about 25 ms.
- Installing the sensors, including retransforming the classes already loaded, took 0.63 to 0.80 s in total, within the
  1 s budget. The `executors` sensor's share of the retransformation was 0.59 to 0.73 s, and the `inventory` sensor's
  0.05 to 0.07 s.
- PetClinic's first start took a median of 6.3 s of JVM uptime with the agent claimed, against 5.4 s without it. A DevTools
  restart, whose claim finds the sensors installed, took 0.83 s against 0.70 s.
- The Quarkus sample's install took 1.1 to 1.5 s, but that was measured at load averages of 55 to 90, which also made its
  start times too noisy to compare.

**Across restarts and reloads.** Tests restart the Spring sample ten times with DevTools, and live-reload a minimal
Quarkus application ten times, with the agent claimed again at each run, then walk the heap from the agent (its classes'
statics, its instances, and its threads): no earlier run's class loader, the first included, is reachable from it. A
second walk of the same heap, which also starts from a thread the test names as an agent thread and makes keep the first
run, must find that run, so the walk is shown to catch such a hold. Earlier runs can stay in
the heap for reasons of their own, such as Spring Data's static type caches, Spring Boot's shutdown hook keeping the
first run's logging system, or a timer thread whose context class loader is the first Quarkus run's.

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

A claim asks for the sensors in `bootui.agent.sensors`: `executors`, [`inventory`](#the-inventory-sensor),
[`code-paths`](#the-code-paths-sensor), [`processes`](#the-processes-sensor), [`network`](#the-network-sensor),
[`blocking`](#the-blocking-sensor), and [`resources`](#the-resources-sensor), the defaults, and the opt-in
[`threads`](#the-threads-sensor), [`files`](#the-files-sensor), [`environment`](#the-environment-sensor),
[`thread-activity`](#the-thread-activity-sensor), [`thread-locals`](#the-thread-locals-sensor), and
[`caught-exceptions`](#the-caught-exceptions-sensor). The agent
installs each one once, on its own thread, then
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
- The **after response** badge orders a body's completion with the response only when the JDK's own result
  publication releases the handler. A handler released from inside the body, as by `DeferredResult.setResult` or a
  latch it waits on, can still answer before the body returns, so a body that ends within a few milliseconds of that
  release may be badged. So may a task whose own dependent stage writes the response, as Spring WebFlux's
  `Mono.fromFuture` does on the pool thread, when that write lasts 50 ms or more. A tail after a body that ended first,
  with neither late I/O nor a failure, is badged only from 50 ms past the response.
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
started after the response. A waited-for body is not reported merely because its handoff closes late, and neither
Live Activity's **after response** badge nor the request profile's **Handoffs** marks it so: the JDK releases the
waiting handler before the task's run returns, so its handoff can close just after the response started. After a body
that ended before the response, the badge and the profile still mark the task when its tail ran SQL, a REST call, or a
message at least 2 ms past the response, failed after it, or ran at least 50 ms past it (computation, logging, file
writes, sleeps, or mail in a `done()` callback or a dependent stage).
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
directories loaded classes. The [Code Inventory](#code-inventory) panel reads it (changed methods since the
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
claim; redefinitions are ignored; and classes BootUI loads for its own scans and checks are not counted. The recorder is
added before it walks the classes already loaded, and walks them twice, so no class defined meanwhile goes unseen; a
recorder removed and added again walks them again.

**Class names.** For the Vulnerabilities panel's [runtime reach](advisors.md#runtime-reach), each jar also keeps a
bounded set of 64-bit hashes of the class names it defined, with the run of each class's last load and whether BootUI's
own work loaded it: 8,192 names per jar and 65,536 in all, past which the jar's dropped names are counted. Lock-free and
of JDK types only, it pins no class loader. The bridge also reports the recorder's state and the packages of classes
defined without a code-source location (generated proxies and mocks aside), which keep reach from saying a dependency
did not load.

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
late; a method [HotSwapped](#hotswap) without a restart keeps its flag; and Mockito's inline mock maker dispatches a stubbed call before the
sensor's check, so a stubbed method does not count as executed, while a spy's real call does. The `inventory` sensor
shares its transformer with the [`code-paths` sensor](#the-code-paths-sensor).

## The code-paths sensor

The `code-paths` sensor, on by default, times the application's bean methods per request: for each request, a call
tree of the public and protected methods of its beans, merged by caller and method, with each node's calls, total time,
time in its callees, and the request phase it entered in (filters, handler, or response). It is the evidence behind
the [Code Paths](#code-paths) panel, `get_code_paths`, and `route-time-breakdown`'s handler split; the
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
that limit. When such an error strikes again while the bridge resets the thread, before it is done, the next timed
method to return on that thread resets it again, so the thread never stays counted inside a call it already left. A
thread that dies inside a request's scope keeps its tree only until the next run, whose pool starts from the free trees.
On the machine this was measured on, a timed call costs about 110 ns, most of it the two `System.nanoTime()` reads
(43 ns each there); a call with no owner about 26 ns, and an excluded one about 10 ns.

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

**On BootUI's side.** BootUI's drain thread reads the fragments every 100 ms and merges each request's into its request
tree, which settles about two seconds after its last fragment, when the engine looks up its request's exchange and
stamped calls in the runtime journal. Under sustained load, when more than 512 request trees are open (fewer under a
configured agent evidence bound), the eldest quarter settle together, with one journal read for all of them. Settling
them one at a time read the whole journal once per request: in a profile of the sample under the agent overhead
benchmark's load, that was 15 % of the process's CPU, against about 0.25 % for the advice on the application threads. On
a four-processor CI runner, the benchmark's median overhead went from 16.0 % to 4.1 % with the default sensors, and from
15.2 % to 5.5 % with `code-paths` alone, within the 10 % budget, so the sensor stays on by default.

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

## The processes sensor

The `processes` sensor, on by default, records the processes application code starts for the
[Side Effects](#side-effects) panel, `get_side_effects`, and `bootui side-effects`. It records the
command name only, never its arguments or environment, then records whether the process started and, when the JVM
reports it, the exit status and lifetime.

| Hook | Role | What it covers |
| --- | --- | --- |
| `ProcessBuilder.start` | records process starts | the private `start(Redirect[])` path reached by `ProcessBuilder.start()`, `ProcessBuilder.startPipeline(...)`, and `Runtime.exec(...)` |

The command label is sanitized before it leaves the agent. For a process that started, it is its executable's file
name: everything before the last `/` or `\` of the first command element is dropped, so a path with spaces, such as
`C:\Program Files\Java\bin\java.exe`, gives `java.exe`. For a start that failed, which may have been given a command and
its arguments or an environment assignment as one element, that element is first cut at its first whitespace or `=`:
`/bin/sh -c secret` records only `sh`, and an environment assignment keeps only the variable name. In both cases an
element that opens with a quote keeps only its quoted text, since Windows passes one element such as
`"C:\Tools\app.exe" --token x` verbatim to `CreateProcess`, which starts it: it records `app.exe`. Nothing past a quote
inside the element is kept either, since Windows also starts `C:\Tools\app.exe" --token "x` as `app.exe`. Only letters,
digits, `.`, `_`, `+`, and `-` are kept; other characters become `?`, and the result is capped at 128 characters. A start failure records the `IOException` outcome, but no arguments, environment variables, working
directory, or stream redirections.

The sensor's self-test starts a deliberately invalid command whose file name contains a NUL character. The JDK rejects
it before spawning anything; the test passes only when the hook fires. A failed self-test disables the sensor and
removes its transformer. JDK retransformation of `ProcessBuilder` is checked on JDK 17, 21, and the newest verified JDK,
and the Java Agent panel shows the hook and counters beside the other sensor rows.

A process start is published at once because the hook is rare. Completion is watched with `Process.onExit()`, whose JDK
stage runs on the common ForkJoin pool, then on the agent-owned `bootui-agent-process-exits` executor, so the application process and thread are not pinned. An exit is
attributed as its start was, carrying the start's time. At most 1,024 exits are watched at once; starts beyond that
still record that their exit was not watched. On Windows, each watched live process holds a JDK reaper thread. BootUI's own process starts, the agent's threads, BootUI threads whose
names start `bootui-`, and starts that happen while the agent is transforming a class are ignored. A start inside a
process or network hook on the same thread is not recorded; one inside a file operation is.

A start names its owner from the thread's owner slots, which adapter request scopes and executor handoffs push, or,
when no slot names one, by capturing BootUI's context on the thread: a request, an execution no request owns (a
scheduled run, a consumed message, a WebSocket message), or neither, when the row falls to startup or the thread's
family. On Spring WebFlux, the request scope's slot is not yet filled where Reactor restores BootUI's context on another
scheduler thread; `processes`, a rare hook, captures the context instead, as `files` does for an operation no slot owns.

The Side Effects bridge has its own ring of 1,024 records and string table of 8,192 strings a run. A full ring drops
and counts records instead of blocking application code. The table keeps room for every sensor: path patterns,
environment names, network targets, looked-up names, frames, and command names and thread families are each
guaranteed part of it (1,024, 512, 512, 512, 1,024, and 512 strings) and borrow beyond only while the others' unused
part stays free, so one sensor's many distinct strings never leave another's targets unknown. The names of threads no
owner names have 512 strings that never borrow; past them, a thread is named by its family. The agent's status counts what
each could not intern (`internRoomRefused`). The per-thread aggregation table is for the hotter side-effect sensors in later slices,
not for process starts. After 100 internal errors of its own recording, a side-effect sensor switches off for the
JVM's life, alone, and the report says why; 100 errors in the sensors' shared code switch them all off.

In the report, the sensor's side-effect coverage is `recording` when this application's armed claim includes it and the
bridge supports it, otherwise `not-claimed`, `not-available`, or `failed` with the reason. The Side Effects sensors not
shipped yet are listed as `not-available` with reason `Not available in this version.`


## The network sensor

The `network` sensor, on by default, records what the application does on the network for the
[Side Effects](#side-effects) panel's **Network** tab, `get_side_effects`, and `bootui side-effects`: the hosts and ports
it connects to, the datagrams it sends, and the host names the JVM resolves, each with the client recognized from the
calling frames and, for connections and datagrams, whether any panel shows that work. It never reads a byte sent or
received, a URL's path or query, or a header.

| Hook | Role | What it covers |
| --- | --- | --- |
| `Socket.connect` | core | `Socket.connect(SocketAddress, int)`, reached by every blocking `Socket` connect, `new Socket(host, port)`, socket factories, and `SSLSocketImpl` through `super` |
| `SocketChannel.connect` | core | `sun.nio.ch.SocketChannelImpl.connect(SocketAddress)`: Netty, Reactor Netty, Vert.x, Kafka, gRPC, the JDK `HttpClient`, and Unix-domain sockets. A non-blocking connect is recorded as pending |
| `SocketChannel.blockingConnect` | optional | `SocketChannel.socket().connect(...)`, which does not go through `Socket.connect` |
| `SocketChannel.finishConnect` | optional | a non-blocking connect's outcome and time, published with the connect's owner, target, and call site on whichever thread finishes it |
| `DatagramChannel.send` | optional | `sun.nio.ch.DatagramChannelImpl.send(ByteBuffer, SocketAddress)` |
| `DatagramSocket.send` | optional | `DatagramSocket.send(DatagramPacket)`: the packet's address and port only |
| `InetAddress.lookup` | optional | `InetAddress.getAddressesFromNameService`, reached only when the JVM's address cache misses a name, so its time is the name service's |

The hooks are delegating advice, as `processes`' is. Their JDK retransformation is checked on JDK 17, 21, and 26 by a
forked test that installs only this sensor and asserts each hook present, transformed, and passing its self-test, beside
the OpenTelemetry agent in both orders, JFR's socket events in both orders, and Mockito's inline mock maker mocking
`Socket` in both orders. Each self-test runs its hook without any I/O: connects and sends to an unresolved address, or a
Unix-domain address on a TCP channel, which the JDK refuses before touching the network, on a socket without a proxy so
no proxy selector is asked; a finish with no connect pending; a send on a closed, never-bound socket; and, on a helper
thread waited for at most 5 seconds, a lookup of a mixed-case spelling of `localhost`, which the JVM's case-sensitive
cache misses and the hosts file answers.

A hook that fails its self-test is left out for the JVM's life and its sensor keeps recording without it, listed under
`hooksLeftOut`; a failing core hook disables only its own sensor, and the transformer is reinstalled with the other
side-effect sensors, so `processes` keeps recording when `network` fails, and the other way round.

A target is the remote's host string and port, `InetSocketAddress.getHostString()`, which never resolves or
reverse-resolves, with anything up to an `@` dropped, IPv6 bracketed, characters other than letters, digits, and
`. _ - : [ ] / ~` replaced by `?`, and at most 128 characters; a Unix-domain socket is `unix:` and its path, the home
directory shown as `~`. Digits are kept, since an address and a port are the information. At most 1,024 distinct
targets, and apart from them 1,024 looked-up names, are kept per run; the others share `(other hosts)`, whose capture is
not known.

Connects and lookups are rare and published at once, with the first frame outside the socket plumbing (the JDK's
socket code, Netty, Vert.x's core, and Reactor's transport), or, when the stack holds one, the outermost frame of a
telemetry exporter, a metrics or log shipper, or container tooling, whose transport is itself an HTTP client; the first
frame outside the JDK; the first application frame; and the thread's family, read from a stack walk of at most 128
frames that stops at the thread's `run`. A thread's family drops a URL's user information, is cut at its first `?`,
`#`, or `@`, folds digit runs, replaces characters other than safe ones, and keeps at most 64 characters. Once a code-paths
stamp names the call site, a connect's and a lookup's frames are remembered per target, call site, and thread family,
and walked again only for a new one. A connect or a lookup takes its owner from the thread's slot, else captures it,
except on a Netty or Vert.x event loop, which never captures. Datagram sends are hot: their owner comes from the
thread's slot only, which an adapter's request scope fills even without code paths; the thread's last target is
reused when the same address is sent to again; the first send of a target from a call site and thread family is
published at once and its frames remembered, whichever request makes it, and the next ones are counted in the
thread's table and may lag until that thread's next send. A
non-blocking connect waiting for its finish is held weakly, swept after a minute, and forgotten when the sensor is
disabled. A network hook inside another network or process hook on the same thread does not record, so a
resolver's datagram inside a lookup is not counted twice; one inside a file operation does, so the connects of a
file system provider (an S3, GCS, or SFTP `Path`) or of the stream `Files.copy(InputStream, Path)` reads still show. BootUI's own work, BootUI's and the agent's threads, and
BootUI's own JDK `HttpClient`s, which run on a `bootui-http-N` executor, are never recorded. The JDK's own loopback pair
(`sun.nio.ch.PipeImpl`, a pipe or selector wake-up on Windows) is never recorded; loopback connections to databases,
brokers, and containers are, as they are what a developer runs locally.

The client is recognized in the engine, infrastructure first: by its frames (OpenTelemetry's exporters and SDK only,
never its instrumentation, agent, context, or API, which sit in the application's own stacks around its REST calls and
its DataSource), an OpenTelemetry exporter's thread (`BatchSpanProcessor`, `BatchLogRecordProcessor`,
`PeriodicMetricReader`), the host and port of an exporter endpoint the application configures
(`management.otlp.*`, `management.opentelemetry.*`, `management.zipkin.tracing.endpoint`, `otel.exporter.*`,
`quarkus.otel.exporter.otlp.*`, read once), or port 53 (DNS). Then from the frames; then, only when no frame names a
client, a well-known port (4317 and 4318 OTLP, 9411 Zipkin, 14250 and 14268 Jaeger, 3100 Loki, 8125 StatsD, 12201
GELF), so an application's own call to one stays its client's; then from the thread's family when a Netty event
loop's connect carries no frame of the library that asked for it: JDBC drivers, R2DBC, and Vert.x SQL clients; Kafka,
RabbitMQ, ActiveMQ, AMQP JMS, and IBM MQ clients; Jakarta Mail; the JDK `HttpClient` and `HttpURLConnection`, Apache
HttpClient, OkHttp, Jetty, Reactor Netty, Vert.x, Spring's and Quarkus's REST clients; Lettuce, Jedis, Redisson,
MongoDB, Cassandra, Elasticsearch, gRPC, and the AWS, Azure, and Google Cloud SDKs; and infrastructure clients: DNS
resolvers, OpenTelemetry and Zipkin exporters, metrics registries, log appenders, Spring Boot Docker Compose's and
Testcontainers' readiness checks, Testcontainers, docker-java, DevTools, and Dev Services.

A connection or a datagram is **not captured by any panel** when no visible panel shows its work:

- A JDBC, messaging, or mail client's connection is captured by SQL Trace, its broker's panel, or Email while that panel
  is available and enabled, decided on each read: BootUI then records that client's work, which a pool's connection
  carries later than its connect. A second DataSource BootUI does not wrap is not told apart from the wrapped one.
- Any other connection is captured by REST Client Trace when a REST client call of the same request or execution, or,
  for a connection no request or execution owns, one running at the same time (a second either side), names its host
  and port, or the host alone when the call named no port and the connection's is 80 or 443, or a configured proxy
  (`http.proxyHost`, `https.proxyHost`, `socksProxyHost`; a `ProxySelector`, an `HttpClient.Builder` proxy, a Reactor
  Netty proxy, or `HTTPS_PROXY` is not detected, so a call through one reads as not captured). A request's connection
  waits for that until 2 seconds after its request was named, once it ended, or after the connect when later; an
  execution's, which any of its events may name while it still runs, 60 seconds; unowned work's 10 seconds, or 60 for a
  recognized HTTP client, whose call is recorded once it completes; then it is not captured. A non-blocking connect's
  finish is decided as its connect was.
- Infrastructure clients are `infrastructure`: no panel is meant to show them.
- A name lookup is not a connection and has no capture.

These rows are Side Effects rows, not a Runtime Insights kind.
`get_side_effects` with `query` `not captured` lists them.

The runtime model gains observed `OPENS` edges from routes, scheduled jobs, and the one bean of an application call
site's class (never a library's frame) to
`HOST` nodes keyed `host:port`, hidden with the Side Effects panel (and route edges with HTTP Exchanges); change impact
never walks them.

Known limits: a non-blocking connect's time is known once it finishes; asynchronous socket channels, a connected
datagram channel's writes, and native code are not seen; a lookup the JVM's cache answered is not counted
(`networkaddress.cache.ttl`, 30 seconds by default); a connect a Netty or Vert.x event loop makes outside a request
scope names no owner; a BootUI `HttpClient`'s connect retried on its selector thread, or a
redirect it follows there, is recorded as the application's; a mocked `Socket` whose mock maker transformed `Socket`
before the agent is recorded as connected.

## The files sensor

The `files` sensor, opt-in, records the files application code opens, deletes, moves, and copies, as path
patterns, never their contents, for the [Side Effects](#side-effects) panel, `get_side_effects`, and
`bootui side-effects`.

| Hook | Records |
| --- | --- |
| `FileInputStream.open` | the private `open(String)` every `FileInputStream` constructor reaches: a read |
| `FileOutputStream.open` | the private `open(String, boolean)` every `FileOutputStream` constructor reaches, as `FileWriter` and `PrintWriter(String)` do: a write |
| `RandomAccessFile.open` | the private `open(String, int)`: a read for mode `r`, else a write |
| `Files.newByteChannel` | `(Path, Set, FileAttribute[])`, which its varargs overload, `readAllBytes`, `readString`, and `lines` reach: a write when the options hold `WRITE` or `APPEND`, else a read |
| `Files.newInputStream`, `Files.newOutputStream` | a read, a write; `newBufferedReader`, `write`, `writeString`, and `newBufferedWriter` reach them |
| `Files.delete`, `Files.deleteIfExists` | a delete |
| `Files.move` | the source as `move from` and the destination as `move to` |
| `Files.copy` | each path argument of its three overloads, as `copy from` and `copy to` |
| `FileChannel.open` | `(Path, Set, FileAttribute[])`, which its varargs overload reaches: read or write by its options |

A files hook records only outside every other side-effect hook on the thread, so `Files.newInputStream`, which
reaches `Files.newByteChannel` through the file system provider, records once, and the hosts file a name lookup reads
is not the application's. A path of another file system than the default one (a zip, `jar:`, `nested:`,
or `jrt:` path) is never turned into text.

The agent turns a path into a pattern before anything leaves the hook, so a raw path, and the user name in it, never
reaches BootUI's tables: a relative path is resolved against the working directory without touching the file system;
the working directory becomes `.`, the temporary directory `$TMPDIR`, and the home `~`, each as the JVM names it and in
its canonical form (macOS's `/var` and `/private/var`); another user's home becomes `/Users/*`, `/home/*`, or
`C:/Users/*` (also a UNC `//server/Users/*`, `//?/C:/Users/*`, `/var/home/*`, and `/export/home/*`); a JWT-like
segment becomes `{token}`, a UUID `{uuid}`, a run of 8 or more hexadecimal characters with a digit `{hex}`, an
alphanumeric run of 12 or more characters mixing letters and digits, or of 24 or more letters mixing upper and lower
case, `{id}`, and any other run of digits `{n}`; a shorter run of letters is kept. A
report written as `report-2026-10-05.csv` in the working directory's `target/reports` is
`./target/reports/report-{n}-{n}-{n}.csv`. The engine also masks, as `******` and whatever `bootui.expose-values`
says, a segment its secret detector recognizes (a JWT, a PEM key, an AWS key, a credential URL); a secret that matches
none of these rules and is shorter than them, such as a short all-lower-case token, is kept.

Class files, JAR, WAR, and JMOD files, paths in archive file systems, files under Java's home, and files under a
directory of the class path are counted in buckets, never recorded per route, interned, or walked, as is any other file
a class loader reads (a JDK class loader, a `ClassLoader` subclass, or Quarkus', Spring Boot's, JBoss Modules', or
Tomcat's), whose frame summary is walked once per call site first; so class loading costs
a counter. Other operations carry where they came from, from a frame summary walked once per path pattern and code-paths
method (or once per owner when no method is stamped): a JDK logging handler (`java.util.logging`) is **logging**; a
class loader, a module, a service loader, or a `jar:` URL is **class path**; no frame outside the JDK is **JDK**; the
first frame outside the JDK in Logback, Log4j, JBoss LogManager, SLF4J, tinylog, or Tomcat's access log valve is
**logging**, and in Spring Boot's loader, Quarkus' bootstrap, JBoss Modules, or Tomcat's class loader and scanner,
**class path**; otherwise the row is **application** when a frame in the application's packages called it, else
**library**. Rows of class path, the JDK, and logging are grouped apart. Each row's location is the working directory,
the temporary directory, the home, `system` (`/proc`, `/sys`, `/dev`), or elsewhere.

The self-test opens, deletes, moves, and copies paths under a directory that does not exist in the temporary
directory, so every hook runs and nothing is created. JDK retransformation of the hooked classes is checked on JDK 17,
21, and 26 (`FilesEnvironmentBehaviorsIT`). `FileInputStream.open` and `FileOutputStream.open` are its core hooks: one
that fails its self-test disables this sensor alone for the JVM's life; any other files hook that fails is left out,
listed under `hooksLeftOut`, and the sensor keeps recording. A generation keeps at most 3,000 distinct path patterns,
fewer when the string table keeps its room for the other sensors; beyond that a row's target is
`(too many distinct paths)`. Clear recording counts the quota again from zero, within that room.

The operation's own time (opening, deleting, moving, or copying) is recorded, not the reads and writes that follow.
`File.delete`, `File.renameTo`, `File.createNewFile`, `AsynchronousFileChannel`, memory-mapped access, and native code
are not seen.

`files` is opt-in because the agent's cumulative overhead with it is over the 10 % budget. On the agent overhead
benchmark's I/O route (one outbound connect and one file read per request), the CI job measured `files`' own share
against the default sensors at a median of 2.3 % over 15 pairs (pairs from −6.6 to 13.7 %). The default sensors plus
`files` measured 10.6 % against no agent over 9 pairs (pairs from 6.7 to 23.7 %). Add `files` to
`bootui.agent.sensors` to record it, or [switch it on at run time](#switching-opt-in-sensors-at-run-time).

## The environment sensor

The `environment` sensor, opt-in, records the names of the environment variables and system properties application
code reads directly, never their values:

| Hook | Records |
| --- | --- |
| `System.getenv` | `getenv(String)`, the variable's name |
| `System.getenvAll` | `getenv()`, as `(all variables)` |
| `System.getProperty` | `getProperty(String)` and `getProperty(String, String)`, the property's name; never the default |

The advice runs at the method's entry and passes only the name to the bridge. A read is recorded the first time a thread
reads the name for a request or an execution: on a thread no request scope owns, again once a second has passed. A
row's count is therefore the requests and threads that read it, not the calls. A read whose immediate caller, past
`System` and the `Boolean.getBoolean`, `Integer.getInteger`, and `Long.getLong` lookups, is a JDK class, such as an XML
or SSL factory looking up its own property, is not recorded, nor is a read whose immediate caller is a configuration
framework resolving its own properties (SmallRye Config, MicroProfile Config, Quarkus' configuration, Spring's
`Environment` and `SpringProperties`), which reads thousands of names at startup. Spring's `Environment` reads the whole maps once, so a
property it resolves from them is not seen, and `System.getProperties()` is not hooked. A generation keeps at most
1,000 distinct names; a name the secret detector recognizes (a JWT, a PEM key, an AWS key, a credential URL) is
masked.

`environment` is opt-in until its overhead is measured: with it recording, `System.getProperty` takes about 23 to
28 ns per call instead of 5 to 6 ns on JDK 17, 21, and 26 (`FilesEnvironmentBehaviorsIT`). Add `environment` to
`bootui.agent.sensors` to record it, or [switch it on at run time](#switching-opt-in-sensors-at-run-time). Its three hooks are core: one that fails its self-test disables the sensor alone.

## The security-sinks sensor

`bootui.agent.sensors=...,security-sinks` with `bootui.agent.security-sinks.request-values=true` checks whether
request input reaches a sink **unchanged**: whether the value of one of the current request's query or path parameters
appears verbatim in SQL text, a command, a file path, or an outbound URL. Both are opt-in (D37): the sensor does nothing
without the property, and the property does nothing without the sensor. Each match is a row of the Side Effects
**Security sinks** tab and of `get_side_effects`' `security-sinks` query (`request-input-in-sink`), worded as a fact,
never as a vulnerability:

> Request input reached this SQL text unchanged: the value of `name` appeared inside a literal. Check that it is bound
> as a parameter or escaped.

A row names the sink, the parameter's name, the call site, and the sink's text with the value **redacted** to
`{name}`. It never holds the value:

| Sink | Where it is checked | The row's target |
| --- | --- | --- |
| SQL text | Where SQL Trace's JDBC capture records the statement, on the thread that ran it. R2DBC is not captured | The statement with every literal masked, as SQL Trace's fingerprint masks it whatever the exposure, and `{name}` in the value's place; the row says whether it sat **inside** or **outside a literal** |
| Command | The `processes` sensor's `ProcessBuilder.start` hook, in each of the command's first 32 elements | The command's file name and the argument's index, `convert, argument 2`, never an argument |
| File path | The `files` sensor's hooks, so only with `files` claimed | The path pattern of the redacted path, `./reports/{name}.csv`; the `files` row names that pattern too |
| Outbound URL | Where the REST client panel records the call: `RestTemplate`, `RestClient`, `WebClient`, and the Quarkus REST client, on the thread that issues it, its host, decoded path, and decoded query parameters checked apart | The scheme, host, and port, the redacted path with numeric and UUID segments as `{id}`, and the query's keys only; never user information or the fragment, and no text when the value is in the host |

**How values are held.** The adapters hand the values to the agent's request value holder at the handler phase, only
while matching is on: Spring MVC parses the query string itself and reads the handler mapping's path variables, never
calling `getParameter*`, so a body is never read; Spring WebFlux takes the query parameters it already parsed and reads
the path variables once its handler mapping set them, never the form data; Quarkus takes the decoded query and the
matched path parameters. Form values, headers, and bodies are never held. The holder keeps at most 128 requests and 32
values of 4 to 256 characters each (on Spring MVC, BootUI's own decoding of the query string), and removes a request's values where its response
really completes: the filter's end, the async cycle's end, the WebFlux chain's end, or Quarkus' response end handler.
A missed end is swept after 60 seconds, and a new claim, a DevTools restart, a live reload, or a release wipes the
holder. The values are not part of BootUI's correlation context, so no executor snapshot copies them, and a task the
agent propagated, or any other request's work, is never matched; a task the request hands to a managed executor still
matches, until the response completes. Matching is bounded per request: at most 256 checks, 16 KB of text per check,
and 4 Mi character comparisons in all, each check costing its text's length times the held values' total length. An
identical text scanned whole that matched nothing, while the request's values are unchanged, is not checked again; one that
already matched is compared and redacted again, not reported twice, and not counted as a check, though its comparisons
count, so a statement repeated in a long loop can still reach the comparison budget. When a text was scanned only
in part, or held more matches than could be redacted, the row keeps no text. Once a request reached its budget, its
later sinks are not checked: a `files` or `processes` row then names its path or executable `(not kept: not checked for
request input)`, never the text, as it does for a path or executable longer than a check scans; the tab's limitations say when that happened. **Clear recording** clears the rows; the holder,
empty between requests, is not evidence.

**Overhead.** On the agent overhead job's sinks route (two query parameters, one SQL statement, and one file read per
request), matching added 2.6 %, −0.2 %, 2.9 %, 1.1 %, 0.5 %, and 2.2 % to the same sensors without it over six runs
(median of 15 pairs each), and the run with every sensor, `files` included, measured 10.9 %, 8.4 %, 10.8 %, 9.7 %, 13.8 %,
and 11.9 % against no agent, at the edge of the 10 % budget; matching stays opt-in. Two later runs on branches that
changed nothing matching runs measured 3.9 % [2.0, 6.5] and 4.9 % [1.3, 8.0], and their `files` A/Bs were elevated as
well: a median above 3 % fails about one clean run in six, so the build fails only when the median interval's lower
bound is above 3 % (D48). That rule still flags a regression whose interval sits above the budget; one near it, as
the 3.4 % [1.1, 4.2] a shared lock counter caused before #1296 merged, shows as FAIL in the report, not in the build.

**False positives.** A value that sits outside an SQL literal, inside a number or `true`/`false`, or that is itself a
number (digits, with an optional sign and decimal point, as `-33.8688`), may be a word the text always holds, as a
value equal to a column name. Such a match is shown only once a second request produced a different raw text with the
same redacted text, which shows the text varies with the value; until then the panel counts it as not
shown yet; a value repeated in requests with the same text confirms nothing. Any other match is shown from one
request, marked as seen in one request so far, including a value that crosses a literal's bounds, as one closing a
quote: the row then shows it outside any literal, every literal around it still masked. Only per-process keyed hashes of the raw and redacted texts are compared,
never the texts. Past the tab's row cap, a match not confirmed yet is counted, never shown in its Other row.

The sensor adds no hook of its own in this version: its deserialization, weak algorithm, and trust manager checks
follow (M5-6b2), and the `HttpClient` and `URL.openConnection` hooks are deferred, so a JDK `HttpClient` call is checked
only when it goes through a REST client BootUI records. With matching on, the tab's limitations show the holder's
counters: requests held, checks run, and what it skipped or could not keep; the sensor's reason names the sinks it
cannot check because their sensor is not claimed.

## The blocking sensor

The `blocking` sensor, on by default, reports `Thread.sleep`, `TimeUnit.sleep`, `Object.wait`, `LockSupport.park`, and
the [network](#the-network-sensor) and [files](#the-files-sensor) sensors' blocking operations **started** on an event
loop, for the [Side Effects](#side-effects) panel's **Blocking** tab, `get_side_effects`, and
`bootui side-effects`. Like BlockHound, it watches the JDK's blocking methods on threads that must not block; unlike
BlockHound, it reports and never throws.

The agent never decides what an event loop is: the adapter registers the threads it already classifies as event loops
for the runtime journal's thread kinds, each from the first request or response it handles there.

| Stack | Event loops registered |
| --- | --- |
| Spring WebFlux | Reactor Netty's (`reactor-http-nio-N`, `reactor-http-epoll-N`): Reactor `NonBlocking` threads that are Netty `FastThreadLocalThread`s, from the first request each serves |
| Spring MVC and WebFlux with a `WebClient` on Reactor Netty | the same loops, from the first client response each delivers, for a `WebClient` built from Spring Boot's `WebClient.Builder` while REST client tracing is on |
| Quarkus | Vert.x's (`vert.x-eventloop-thread-N`), from the first request each routes |
| Spring MVC, Spring WebFlux on a servlet container | none: a thread per request. The tab is `not-applicable` until a WebClient's loop is registered |

Reactor's `parallel` and `single` schedulers, `boundedElastic`, Vert.x workers, and virtual threads are never event
loops. Loops are kept in a table of at most 1,024 slots, keyed by thread id, held weakly, and stamped with the claim
generation, so a Quarkus live reload's run watches a loop again once it handles a request; a terminated loop's slot is
reused, and when the table is full the panel says that some loops were not registered. Netty's DNS resolver reads
`/etc/hosts` and `/etc/resolv.conf` on its first name resolution, which can show once as a file read on an event loop.

| Hook | Role | What it covers |
| --- | --- | --- |
| `LockSupport.park` | records parks | every public `LockSupport.park`, `parkNanos`, and `parkUntil` method, with and without a blocker: a contended lock, a `Future.get`, a blocking queue |
| `Thread.sleep call sites` | records sleeps | every `Thread.sleep(long)`, `Thread.sleep(long, int)`, `Thread.sleep(Duration)`, and `TimeUnit.sleep(long)` call in the application's classes |
| `Object.wait call sites` | records waits | every `Object.wait()`, `wait(long)`, and `wait(long, int)` call in the application's classes |
| `network and file operations` | records network and file operations | no hook of its own: a `Socket.connect`, a `SocketChannel` connect in blocking mode, a name lookup, or a `DatagramSocket` send the `network` sensor records, or a file open, delete, move, or copy the `files` sensor records, when it started on an event loop. Netty's non-blocking connect and its finish never block, and are never reported; class loading's reads are never reported; with the `network` or `files` sensor off, its operations are not either |

`Thread.sleep` and `Object.wait` are `native` on JDK 17, and retransformation cannot add the wrappers a native method
prefix needs; on later JDKs they end in native methods on platform threads, so `park` never sees them either. On every
JDK, the agent therefore rewrites their **call sites** in the application's classes (`bootui.agent.packages`, synthetic
classes and lambda bodies included, test roots and the classes the agent never instruments left out) into calls to the bridge's substitutes,
which call the original method and record around it. The rewrite is a visit of the transformer the inventory and
code-paths sensors share, so an application class is retransformed once per claim whatever the sensors. A stack trace
through a rewritten call shows one extra frame, `io.github.jdubois.bootui.agent.bridge.Blocking.sleep` or `waitOn`;
exceptions, the interrupt flag, and `IllegalMonitorStateException` are unchanged.

Off event loops, the `park` hook returns after one volatile read until an adapter registered a loop for the run, then
after one table lookup: about 2.4 ns per park measured by the bridge's opt-in benchmark
(`-Dbootui.agent.bench=true`), within its 10 ns budget, and 0.1 ns with the sensor off. On an event loop, a call
records its duration, its owner (request, execution, startup, or thread family), its code-paths stamp, and the first
frame outside the JDK and the first in the application's packages. A park shorter than 1 ms, such as a library handing
a lock over, is only counted (`shortParks`), unless it threw, as BlockHound's refusal does; sleeps and waits are always
recorded, and a zero or negative one never.
Records aggregate in the thread's table, as every side-effect record does.

Each hook is self-tested: the park hook parks the sensor's own thread with its permit already given, and a bundled
probe class's rewritten sleep and wait calls must reach the substitutes. A hook failing its self-test removes only its
sensor: the other side-effect sensors are installed again without it, and a later claim of the same sensors does not
try it again. When only the call-site hooks fail, parks are still reported and the panel says sleeps and waits are not. JDK retransformation of `LockSupport` and the
call-site rewrite are checked on JDK 17, 21, and the newest verified JDK, beside the OpenTelemetry agent in both orders,
and beside BlockHound installed before or after the claim, recording or throwing its error from inside the sleep: both
see the same sleep, BlockHound's error reaches the caller unchanged, and a later sleep is still recorded.

The sensor does not see a library's own `sleep` or `wait` (outside the application's packages), a sleep through a
method reference (`Thread::sleep`, an `invokedynamic`), `Thread.join`, a `sleep` qualified by a `Thread` subclass, or
blocking on a loop before it handled its first request.

## The thread-activity sensor

The opt-in `thread-activity` sensor records the threads the application starts and the executors it creates, per route
or thread family and call site, for the [Side Effects](#side-effects) panel's **Threads and leaks** tab,
`get_side_effects`, and `bootui side-effects`: how many a request starts, the threads and executors a request's
application code **left running** when it ended, and the executors shut down, or reclaimed by the collector without a
shutdown. It never records a thread-local, a task, or anything a thread or an executor holds. It is a different
sensor from [`threads`](#the-threads-sensor), which carries a request's context into the threads its work starts; the
two can run together.

| Hook | Role | What it covers |
| --- | --- | --- |
| `Thread.start` | records starts | `Thread.start()`, and `start(ThreadContainer)` from JDK 21, of every platform thread; core |
| `VirtualThread.start` | records starts | `VirtualThread.start(ThreadContainer)`, which `Thread.ofVirtual().start` and `Thread.startVirtualThread` reach, from JDK 21 |
| `ThreadPoolExecutor.addWorker` | pool mark | a thread a pool starts is its worker, recorded as the executor, never as a thread of its own; core |
| `ThreadPerTaskExecutor.start` | pool mark | the virtual or platform thread a thread-per-task executor starts for a task, from JDK 21 |
| `ThreadPoolExecutor.<init>` | records creations | the canonical constructor every other `ThreadPoolExecutor` and `ScheduledThreadPoolExecutor` constructor and `Executors` factory reaches; core |
| `ForkJoinPool.<init>` | records creations | the canonical public constructor, which `new ForkJoinPool(n)` and `Executors.newWorkStealingPool` reach; the common pool is never recorded |
| `ThreadPerTaskExecutor.<init>` | records creations | `Executors.newVirtualThreadPerTaskExecutor()` and `newThreadPerTaskExecutor`, from JDK 21 |
| `ThreadPoolExecutor.shutdown`, `.shutdownNow` | records shutdowns | of a tracked executor; core |
| `ForkJoinPool.shutdown`, `ThreadPerTaskExecutor.shutdown` | records shutdowns | `shutdown`, `shutdownNow`, and `close` |

The advice runs at the entry and exit of `start`, at the exit of the constructors, and at the entry of the shutdowns:
never on a thread's run path or its scoped values. The `executors` and `threads` sensors transform `Thread`,
`ThreadPoolExecutor`, and `ForkJoinPool` too, with their own transformers: the JVM applies both, and forked-JVM tests
claim the three sensors together, and `thread-activity` beside the OpenTelemetry agent in both orders, on JDK 17, 21,
and the newest verified JDK. A fork-join worker, the JDK's `DelayScheduler`, and a thread
whose starting frame is in `java.util.concurrent` (the per-task threads `CompletableFuture` falls back to when the
common pool has fewer than two threads) are pool workers too.

**Origin.** One bounded walk of at most 64 frames skips the threading API's own frames (`Thread`, its builders,
`java.util.Timer`, `kotlin.concurrent`, and for an executor its constructors and the `Executors` factories) to find who
started or created it, so a `java.util.Timer` is its caller's: the application's when the application created it. When
the creator is the JDK, as for an `HttpClient`, or the executors `CompletableFuture` and virtual threads create lazily,
the row's origin is `jdk`; when a singleton is being created, it is marked a singleton's. Neither is ever tracked. Otherwise the first frame outside the JDK decides, as for the `threads` sensor:
in the application's packages, `application`; else `library`, as a framework's pool, a client, a Hikari pool started
lazily inside a repository call, Tomcat's `AsyncContext.start`, or Spring's `@Async` executor. Library and JDK rows are
grouped apart in the panel. The JDK's own singletons (an innocuous thread, `process reaper`, `Keep-Alive-Timer`,
`Common-Cleaner`) are recorded as the JDK's without a walk.

**Left running.** A thread the application's code started for a request, and an executor it created for a request or
an execution, are tracked weakly, at most 1,024 threads and 1,024 executors at a time (the panel says when one was not
tracked): nothing the sensor holds keeps a thread, an executor, or a class loader alive. Each adapter tells the engine when a request's response is complete (Spring MVC once its async
context completed, Spring WebFlux when its chain terminates, Quarkus when the response body ended); while a tracked thread or executor waits for its request's end, the end is written into a lock-free ring, and
the agent's drain thread checks it 250 ms later (a Quarkus request whose connection closed before its response ended
never ends: what it started stops waiting after 10 minutes, counted), so a
thread still unwinding as the response completes is not reported: a thread still alive then, or an executor not shut
down, was so when the response was complete, and is reported once as **left running**. A thread started after its
request ended is not waited for, when that end was written; one whose request's end never comes stops waiting after
10 minutes, counted. A singleton's creation anywhere on the starting stack makes it a singleton's, never tracked: a
static initializer, as a lazy holder's first use inside a request, Spring's `DefaultSingletonBeanRegistry.getSingleton`,
as a `@Lazy` singleton or an `ObjectProvider` lookup creating its bean on first use, and ArC's shared contexts, which
create `@ApplicationScoped` and `@Singleton` beans on first use, again after each Quarkus live reload. Request-scoped and
prototype beans are created through neither, so what they start is still tracked. The walk reads at most 64 frames: a
singleton's creation deeper than that, under a long chain of interceptors or proxies, is not seen, and what the bean
starts inside a request is still reported left running. The panel says when threads were not
checked because their request's end never came or was lost, and when tracked threads or executors were dropped because
the sensor was switched off. An
executor's shutdown lands on its creation's row with its lifetime; one the collector reclaims without a shutdown, or
that the JDK's cleaner shuts down because nothing references it (`newSingleThreadExecutor`), is counted as reclaimed.

**Cost.** A request-owned platform thread start or an executor creation walks the stack once. A virtual thread start,
or a start no request owns, as a server starting a virtual thread per request, reuses the walk of the first start of
the same starting thread family, target, kind, and code-paths call site (at most 1,024 per run), so a row's call site
is its first start's when the code-paths sensor did not stamp it; a start no request owns is counted in that sighting
and published by the drain thread. The sensor is opt-in until a same-runner A/B of the agent's overhead benchmark on a
route that starts a thread and creates an executor per request shows its own median increment at most 3 % and the
cumulative median at most 10 % (the `agent-overhead-thread-activity` job of `build.yml`). The first run measured 11.5 %
for its own increment and 16.6 % cumulative, on a route that starts a thread and creates an executor on every request,
so it stays opt-in. Add `thread-activity` to `bootui.agent.sensors` to record it, or switch it on at run time from the
Java Agent or Side Effects panel, as `files` and `environment` are: it has its own transformer, so switching it
retransforms only `Thread` and the executors, and switching another side-effect sensor neither retransforms them nor
drops what it waits to check. A thread started or an executor created before it was switched on is not tracked.

Each hook is self-tested on the sensor's own thread: a platform thread started and joined, from JDK 21 a virtual thread,
a `ThreadPoolExecutor` running one task then shut down and another shut down at once, a `ForkJoinPool` shut down, and,
from JDK 21, a thread-per-task executor running one task then closed. A core hook that fails disables the sensor alone;
an optional hook that fails is left out, and when `ThreadPerTaskExecutor.start` is left out, so is
`VirtualThread.start`, or every task of a virtual-thread-per-task executor would read as a thread of its own. The hooks
of JDK 21 are `unsupported` on JDK 17.

The sensor does not see threads started or executors created through classes that bypass the JDK's (Netty's own
thread-per-task executor, JBoss Threads' `EnhancedQueueExecutor`, Tomcat's own `ThreadPoolExecutor` copy): their threads'
`Thread.start` is recorded as a library's. A subclass whose constructor throws after the canonical constructor returned
leaves a creation recorded. A thread that ends within 250 ms of its request's end is never reported left running.

## The thread-locals sensor

The opt-in `thread-locals` sensor finds the thread locals a request or a job **left set** on its pooled platform
thread, for the [Side Effects](#side-effects) panel's **Threads and leaks** tab, `get_side_effects`, and `bootui
side-effects`: a value the next request on that thread inherits, as a tenant, a user, or a security context left
behind. It hooks no `ThreadLocal` method and transforms no class. When a scope opens on a thread it takes a snapshot of
the thread's `threadLocals` and `inheritableThreadLocals` maps: which thread locals hold a value. When the scope closes
it scans them again, and a thread local with a value then that had none, or was absent, at the open is left set. A
`null` value counts as cleared, so `remove()` or `set(null)` in `finally` is never reported, and neither is a value set
before the scope opened. It reads each entry's key and whether its value is `null`, **never the value** and never a
`toString()`, and keeps no key past the scope's close.

| Scope | Opens | Closes |
| --- | --- | --- |
| A Spring MVC request | BootUI's request filter, on the request's pooled worker | after the application's filters, which have cleaned up |
| A request's task on a pool's own worker | the `executors` sensor reopens the request's context (`ThreadPoolExecutor` and fork-join workers; never a thread the application started itself) | when the task returns |
| Spring WebFlux work on `boundedElastic` | a Reactor schedule hook, around each task a scheduler runs, owned once Reactor's context propagation makes a request's context current inside it | when the task returns, after every context propagation accessor restored its value |
| A Quarkus blocking resource method | BootUI's outermost JAX-RS request filter, on the worker | its response filter |
| A Quarkus managed executor's task | SmallRye Context Propagation restores the request's context | when it ends |
| A scheduled run | Spring's observation scope or Quarkus' interceptor, inside the run's context | when the run returns |

Only the outermost scope on a thread scans. Event loops (Reactor Netty's, Vert.x's), whose assembly scopes never run
the request's blocking code, virtual threads, which are not pooled, asynchronous Spring MVC dispatches, and scopes no
request or job owns are not scanned: on Java 21 and later, with `spring.threads.virtual.enabled`, a Spring request runs
on a virtual thread and nothing is scanned, which the sensor's limitations count. A thread local is reported once per
pool thread until a scope clears it: a later request that sets the same thread local again is not, since it was
already set when that request began.

**What a row names.** Its target is the static field that holds the thread local, as
`com.example.TenantContext.CURRENT`, resolved on the engine's drain thread, never on a request's, within 20 ms a
second, outside the engine's lock: among the application's already-initialized classes, and a list of known frameworks' holder classes, one level
deep for their singletons (SLF4J's MDC adapter, Spring Security's strategy). It reads class files with ASM, so no field
type is loaded, and a field through a private lookup on its class, comparing identities only; it never initializes a
class, asking `jdk.internal.misc.Unsafe.shouldBeInitialized` first. When no static field holds it, the row names a hint
(a `withInitial` supplier's or an anonymous subclass's class) or the thread local's class, as `holder not resolved
(java.lang.ThreadLocal)`: a thread local in a bean's instance field, a library's, or the JDK's. There is no call site:
the value was set during the request. A thread local with an initial value (`ThreadLocal.withInitial` or an
`initialValue` override) is a per-thread cache filled by `get()`; it is shown, as `left set (with initial value)`,
only when its holder is in the application's packages. An inheritable one is `left set (inheritable)`.

**Exclusions.** BootUI's own thread locals, all `BootUiThreadLocal`s (an architecture test keeps them so), the agent's,
and those whose class the JDK defines (a read lock's hold counter, NIO's buffers) are skipped by class on the request's
thread. Frameworks that set and clear their thread locals themselves are dropped once their holder is resolved, or by
their own thread-local class when it is not (as Quarkus' anonymous `VertxMDC$1`), and counted per holder in the
sensor's limitations: Spring's `RequestContextHolder`, `LocaleContextHolder`, `TransactionSynchronizationManager`, and
`AopContext`, the SLF4J, Logback, Log4j 2, JBoss Log Manager, and Quarkus Vert.x MDCs, Micrometer's context,
observation, and tracing, OpenTelemetry's context and temporary buffers, Jackson's buffer recyclers, and Netty's
`InternalThreadLocalMap`. A dropped thread local is skipped by the bridge from then on. Spring Security's context is
never dropped: a security context leaking between requests is what this sensor is for (a row may be an empty context,
since `SecurityContextHolder.getContext()` sets one when it reads none).

**What the agent opens.** To read the maps, the sensor asks `Instrumentation.redefineModule` to open `java.lang` to the
agent's own module, the unnamed module of its isolated class loader, and to no other: never to the application, never
`--add-opens`. For the initialization check it also exports `jdk.internal.misc` to that module alone. Where the
export or `shouldBeInitialized` is missing on a JDK, the resolver falls back to Code Inventory: a class a method of
which ran is initialized, so only the application's classes are searched, and the sensor's limitations say so. Without
the opening, the sensor reports itself unavailable; a claim never fails.

**Cost and caps.** Two scans of a thread's maps per scanned scope, about 0.1 to 0.5 µs for the usual 16 to 64 slots; a
table larger than 16,384 slots or with more than 4,096 thread locals set is skipped, counted, at most 16 leftovers a
scope are reported, and the bridge remembers at most 1,024 thread locals per run, weakly. With the sensor off, a scope
costs one volatile read; on Spring WebFlux, while the agent is attached, each Reactor task also runs through a small
wrapper. The sensor is opt-in whatever its overhead: the `agent-overhead-thread-locals` job of
`build.yml` measures its own increment and the cumulative overhead on the default route: about 0.5 % over the default
sensors, and 7.0 % cumulative against the 10 % budget, in its first run. Add `thread-locals` to `bootui.agent.sensors` to
record it, or [switch it on at run time](#switching-opt-in-sensors-at-run-time).

Its self-test, on the sensor's own thread, opens a scope, leaves a plain, an inheritable, and a read `withInitial`
thread local set, removes one, sets one to `null`, and expects exactly the three left set, never one set before the
scope; then resolves the plain one to its static field. Its two pseudo-hooks, `ThreadLocalMap.scan` and
`ThreadLocal.holder`, report the result in the Java Agent panel.

## The resources sensor

The `resources` sensor, on by default (D47), reports the streams, channels, and sockets a request's or a job's work opened and left
open past its request, or never closed before the garbage collector reclaimed them, for the [Side
Effects](#side-effects) panel's **Threads and leaks** tab, `get_side_effects`, and `bootui side-effects`. It never
records a byte read or written: a row's target is the path pattern or the host and port the [`files`](#the-files-sensor)
and [`network`](#the-network-sensor) rows show.

**Opens.** It has no open hook of its own: the `files` and `network` sensors' hooks hand it the object they opened once
they recorded it, so it sees files only while `files` is on, and sockets while `network` is on (by default). It tracks a
`FileInputStream`, `FileOutputStream`, or `RandomAccessFile`, a `FileChannel` (`FileChannel.open`,
`Files.newByteChannel`, and the channel under `Files.newInputStream`, `newOutputStream`, and `lines`), a `Socket`
(plain or TLS), and a `SocketChannel`, of the JDK's exact channel and socket classes only, so a custom file system
provider's channel or a `Socket` subclass, which may release its descriptor elsewhere, is never tracked. Only an open
that a request or a job owns, with a frame of the application's packages on the stack, is tracked: the row's origin is
**Opened by the application** when the first frame outside the JDK (for a socket, the first frame outside the socket
plumbing, so the JDK's `HttpClient` is a library) is the application's, else **Opened by a library the application
called**. BootUI's and the agent's own work, work no request or job owns, class loading, and the JDK's own files are
never tracked.

| Hook | What it covers |
| --- | --- |
| `FileInputStream.close`, `FileOutputStream.close`, `RandomAccessFile.close` | a stream's close, its subclasses' `super.close()`, and its channel's close |
| `FileChannelImpl.implCloseChannel` | a file channel's close, and its close by the interruption of a thread blocked on it |
| `Socket.close` | a plain or TLS socket's close, and its streams' |
| `AbstractSelectableChannel.implCloseChannel` | a socket channel's close or interruption |
| `FileChannelImpl.setUninterruptible` | hands the channel a provider's `newInputStream` or `newOutputStream` opened to the files hook |

The close hooks run at the exit of the close, normal or not, and only mark the resource's entry closed: one read of a
counter for a kind with nothing tracked, else one identity lookup, never a lock. They are the sensor's own transformer,
so switching `files` or `environment` never removes them while a resource is tracked.

**Not a `Cleaner`** (D46). Each tracked resource is a weak reference with the open's owner, target, and frames, at most
1,024 at a time (the panel says when one was not tracked), polled by the agent's drain thread through a reference
queue, as the thread-activity sensor's executors are: a `Cleaner`'s phantom reference cannot be found again by identity
when the resource is closed, and each `Cleaner` starts a thread. Nothing captures the resource, its class, or its class
loader; a close never clears the reference, it sets a flag.

**Reports.** Each adapter's request end, the same one the thread-activity sensor hears, whether that sensor is on or
not, is checked 250 ms later: a resource of that request opened before its end and still open, by its own state read
through the JDK's final methods, is counted **Open after request**; once it is closed later, **Closed after request**.
Both are a hand-off, as a connection pool's sockets or a cache's file, and often intended. A resource the collector finds
unreachable while never closed is counted **Reclaimed without close()**, the leak, whether owned by a request or a job,
and is the only count a run comparison keys on. A resource closed before its request ended, as in try-with-resources or a
`finally`, is never reported. Each resource is counted once on its row (`count`, with its distinct `requests`).

**Guarding against false reports.** A resource kind is tracked only while its close hook is installed and passed its
self-test. Each
sweep also reads every tracked resource's own state: one closed for 30 seconds while its hook never said so is counted
as a missed close, and the collector's reclaims of its kind are no longer reported for the run: closes the hooks miss
systematically are detected and switch reclaims of that kind off, though a resource collected before the check sees
its missed close can still read as reclaimed. Switching the sensor off
forgets what it tracked before its close hooks are removed, and an open racing the switch is never kept. A weak
reference is cleared before finalization, so a reclaim means the resource became unreachable while still open. A TLS
socket's own state reads its TLS session, not its socket, so one another thread closed while it was still connecting
may later read as reclaimed without `close()`.

**Self-test**, on the sensor's own thread, never creating a file or touching the network: streams over an invalid file
descriptor and the channel of one, an unconnected socket, a socket channel opened and closed without I/O, and the JDK's
own `release` file opened read-only through `RandomAccessFile` and `Files.newInputStream`. A hook that fails leaves its
kind untracked; the sensor fails when no close hook passed. Forked-JVM tests run its behaviors on JDK 17, 21, and the
newest verified JDK, alone and beside the OpenTelemetry agent in both orders, with a library pool's socket and the JDK
`HttpClient`'s pool as counterexamples, never reported reclaimed.

**Cost.** The `agent-overhead-resources` job of `build.yml` measures it on the benchmark's I/O route (one socket
connected and closed and one file read per request), fifteen same-runner pairs each: its first run measured its own
median increment at -0.5 % over the default sensors (pairs -12.2 to 12.0 %), 0.3 % beside `files`, and the cumulative
median at 6.9 % (pairs 2.0 to 18.2 %), within the 3 % and 10 % budgets, so it is on by default (D47, an exception to
D37's opt-in rule). That job now fails CI when its own increment exceeds 3 %. It still prints the cumulative median,
for the record only, with its 95 % interval and what D48's rule would say: that figure is mostly the other default
sensors' overhead (10.7 % and 11.7 % on noisy runners later), which the `agent-overhead` job gates under D48. It tracks
sockets by default,
through `network`; file streams and channels need the opt-in `files` sensor, and the panel says so while `files` is off.
Like the other default sensors, it is not switched at run time: leave it out of `bootui.agent.sensors` to turn it off.

## The caught-exceptions sensor

`bootui.agent.sensors=executors,inventory,code-paths,processes,network,blocking,resources,caught-exceptions` adds the opt-in `caught-exceptions`
sensor, which reports the exceptions application code catches and which of them are thrown again. It records the
events in the runtime journal's `agent.caught-exceptions` source, owned by the Exceptions panel, whose
[**Caught in application code**](diagnostics.md#caught-in-application-code) section reads what became of each one.
It stays off by default until its overhead fits the default sensors' budget: CI measures its own share on a route
that catches one exception per request (at most 3 %) and the cumulative overhead with it (at most 10 %). The first run
measured 2.9 % and 5.9 % (15 pairs each); the second measured a 5.0 % own share, over its budget, so it stays opt-in.

| Hook | Role | What it covers |
| --- | --- | --- |
| `handler entry` | reports caught exceptions | each exception handler that names a type, in the classes of the claimed packages |
| `exceptional exit` | sees caught exceptions thrown again | each method with such a handler, except constructors |

**What is instrumented.** The sensor's visit joins the [inventory and code paths' transformer](#the-code-paths-sensor)
and applies to every class the inventory sensor would instrument, applied last, so it reads each class's own
exception tables. At the entry of each handler that names a type, after its frame and line number, it inserts one
call to the agent's bridge with the caught exception and the handler's site: straight-line code adding no branch
target, so the class's stack map frames stay valid and none is added. `finally` blocks and `synchronized` blocks'
catch-any handlers are left alone. To each method with such a handler but a constructor it appends one catch-any
handler covering the method's own code, after the method's own entries so theirs keep precedence: it tells the bridge
the exception is leaving the method and rethrows it, so an exception thrown again by the method or by a helper it
calls (`ExceptionUtils.rethrow`, Lombok's `@SneakyThrows`) is seen. That handler's frame lists `this` and the
declared parameters, as Byte Buddy's advice requires; a parameter the method stores a value of another verification
kind into is left unknown (`TOP`). Nothing computes frames or resolves a type inside the transformer, so no class is
loaded there. Classes older than Java 7 are left alone. A handler another agent's inlined advice added (it has no line
number in its first instructions while its method has some, catches exactly `Throwable`, and does not store it in a
named local), or that is also a jump target, is skipped at run time. The visit also reads each handler's own code,
without resolving a type: whether it never reads what it caught, prints its stack trace, restores the thread's
interrupt, passes it on as an error value (a call such as `completeExceptionally`, `Mono.error`, or `onError` taking a
`Throwable`), or ends by throwing on a straight line.

**What is recorded.** The caught exception's class, its handler's site (class, method, line, and declared types), its
owner (the request, a request's task, or an execution no request owns, as a scheduled run), its thread, and its
identity hash, which only joins a later throw to its catch inside BootUI; never its message, stack trace, or fields.
The identities of the exception and of up to 8 throwables of its cause and suppressed chain stay pending for at most
60 seconds, in a table of 4,096, and for 30 seconds after their request ended: a pending one leaving an instrumented
method by a throw, wrapped or not, or caught again by an instrumented handler under the same request, is recorded as
thrown, with the owner it was caught under. A handler that never reads what it caught keeps nothing pending. An entry evicted for room
is recorded as such, so its fate is never taken as known. On one thread, a site caught more than 16 times for one owner
is only counted after that, and the count is recorded when the thread's next caught exception has another owner.
Nothing is recorded without an owner, on BootUI's own threads or work, or before the sensor's self-test passed, which
runs again whenever the sensor is switched on again or reinstalled.

**Self-test and failure isolation.** A bundled probe class, loaded after the transformer installed, so the JVM verifies
the visit's output as it defines the class, holds the shapes the visit must keep valid (wide and reassigned parameters,
a constructor, a lambda, a multi-catch, a `finally`, try-with-resources) and a rethrow through a helper: both hooks must
fire. A failed self-test removes only this sensor's visit. A class whose transformation fails with it, or whose
retransformation the JVM rejects with it, never gets it again and is transformed again with the other sensors' visits.
A stress test defines every class of Spring Framework, Hibernate ORM, Jackson, Netty, Vert.x, Quarkus, and Kotlin's
standard library and coroutines (compiled by kotlinc) with and without the visit on JDK 17, 21, and 26: each class that
verifies without it verifies with it, also beneath an advice that checks every frame. CI runs it on a representative
subset of those jars; `-Dbootui.agent.verifier-stress=full` runs every one. Should an application class still
fail to load with a `VerifyError` naming `CaughtExceptions`, remove `caught-exceptions` from `bootui.agent.sensors`
and report the class.

**Cost.** None on the normal path: the inserted code runs only when an exception is caught, or leaves a method that
catches some, where a bridge call is added to the exception's own cost, allocating nothing. A logged or reported
throwable's identity marks are taken only while the sensor records.

## HotSwap

A debugger's HotSwap, as IntelliJ IDEA's **Reload Changed Classes** through JDI, or another agent's
`Instrumentation.redefineClasses`, hands the edited class's bytes to the agent's transformer like any redefinition: the
`inventory` and `code-paths` advice is applied again to the new bytes, with the same method ids, so the edited method
keeps being tracked and timed, and nothing is marked late or failed. A HotSwapped method keeps its executed flag for the
run, and its first request and route stay those of the call before the edit; the next run counts it afresh. A schema
change (an added or removed method or field) is refused by the JVM, unless it supports enhanced redefinition, as the
JetBrains Runtime does, and the class keeps running as it was, still instrumented. Since the agent's transformer runs
before the JVM decides, a method a HotSwap adds is not tracked until its class loads again, at the next restart or
reload. A release restores the HotSwapped
body without the advice. On JDK 17, a retransformation starts from the bytes the class was loaded with rather than the
HotSwapped ones (JDK-7124710, fixed in JDK 20): a release, or a claim that adds or removes a sensor, retransforms the
claimed classes and so reverts a HotSwap made since the class loaded, as any other agent's retransformation would; apply
it again, or restart. Code Inventory hashes the application's class files when a run starts, so a HotSwapped method
is compared with the previous run only after the next DevTools restart or Quarkus live reload, never on the HotSwap
itself. Forked-JVM tests redefine an instrumented bean class both ways, through JDI and through `Instrumentation`.

## Overhead

The `agent-overhead` jobs of `build.yml` measure the agent with the sample's executable jar, in pairs whose order
alternates. Each report gives each pair's throughput ratio, their median, and, since the median of 9 or 15 pairs moves
by several points from run to run on a shared runner, a distribution-free 95 % confidence interval of that median (the
4th lowest and highest of 15 pairs, the 2nd of 9). The default sensors' cumulative median on the I/O route only warns
above 10 %. Three checks fail a build: the blocking sensor's default, when its own increment's median is above 3 % or the
lower bound of the default route's cumulative median interval (9 pairs) is above 10 % (M5-5c, D48); request-value
matching's own increment on the sinks route, when the lower bound of its median's interval (15 pairs) is above 3 %
(M5-6b1, D48); and a sensor whose A/B is enforced while it is on by default, as `caught-exceptions` and
`thread-activity` would be. Every other A/B prints PASS or FAIL against its budget and fails only above 30 %.

The cumulative median varies by itself: across 33 CI runs between 2026-10-05 and 2026-10-07 it ranged from 3.6 % to
11.4 % on unchanged sensors, with a standard deviation of about 2 points, and its 95 % interval in a single run is about
6 points wide. In #1326's resources A/B, the cumulative medians of 10.7 % and 11.7 % had intervals of [7.2, 12.7] and
[4.2, 12.9] %, while `resources`' own increment was 1.4 % and −0.2 %. The runs before and after the thread-locals and
thread-activity follow-ups (#1299, #1323) averaged 7.6 % (14 runs) and 8.5 % (19 runs), a difference within noise
(t = 1.3). A same-machine leave-one-out A/B of each default sensor on the I/O route (15 pairs each) found no sensor
whose own increment's interval lies above zero: executors −4.2 %, inventory 1.3 %, code-paths 1.1 %, processes −4.3 %,
network −1.0 %, blocking −3.3 %, with the cumulative median at 2.2 % [−4.2, 7.9].

So a cumulative median just over 10 % in one run is not, alone, evidence that the default set grew. The rule (PLAN-v2
D48): a sensor's default follows its own increment's A/B, at most 3 %, and a cumulative check fails only when its
median interval's lower bound is above the 10 % budget. The blocking check applies it today; the opt-in sensors'
cumulative checks below still read the plain median until one of them is proposed for the defaults. A median that stays above 10 % across runs, with intervals
that still reach below it, is a reason to measure more pairs.

## Coexistence and class data sharing

The BootUI agent coexists with the OpenTelemetry Java agent and with JaCoCo. Put JaCoCo's Surefire/Failsafe placeholder
first and append BootUI with `@{argLine} -javaagent:...` so coverage keeps working.

Because the agent appends itself to the bootstrap class path, HotSpot prints:

```text
Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

That warning is expected. It means CDS, AppCDS, and AOT caches no longer apply outside boot-loader classes for that JVM.
This is why the agent is a development-time tool and should not be placed on production or AOT-cached JVM launches.

## Agent evidence outside the journal

Code Paths' request and route trees, Code Inventory's first calls, and Side Effects' rows are kept in bounded stores
of the run, not as runtime journal events. One engine contract, the agent evidence projection, applies to them what the
journal applies to its own events:

- **Panel visibility.** Each read resolves once whether the store's panel (Code Paths, Code Inventory, or Side
  Effects) and [HTTP Exchanges](diagnostics.md#http-exchanges), which owns requests and routes, are visible, and derives
  its answer, its cache key, and its reason from that one read. While the store's panel is disabled, its panel, its MCP
  tool, its CLI command, and the Runtime Insights observations that read it say so, and its evidence is not shown by
  those reads. A disabled Side Effects panel shows nothing and says `The Side Effects panel is disabled.` Without the agent,
  the agent's own reason comes first. When Code Paths is disabled, Side Effects rows lose their inside bean method and
  merge without it.
- **Clear recording.** The evidence is a listener of the runtime journal, so every clear of the journal, by **Clear
  recording** after its confirmation or by **Free BootUI memory**, drops it in the same step, under the journal's lock:
  every Code Paths tree recorded before the clear, the fragments still queued in the agent's ring included, Code
  Inventory's first requests and routes, and Side Effects rows. Side Effects records still in the agent whose first
  occurrence came before the clear are dropped by a watermark, and the panel then says `The recording was cleared`. A
  request whose Code Paths tree lost a fragment to the clear is left out whole, never shown partial. Counts since the
  claim, the adaptive exclusions, and which methods executed are kept.
- **Exports.** The panels, Runtime Insights' **Export JSON** and **Copy for AI**, the MCP tools, and the CLI carry only
  what these reads return: method keys, route templates (or masked observed paths), request ids, times, and counts.
  No surface serializes a store, and nothing of it is written to disk.
- **Memory.** The journal status reports the stores' estimated bytes as **Agent evidence**, beside the journal's own,
  against `bootui.runtime-journal.agent-evidence-max-bytes`: about 68 MB by default, the sum of the stores' fixed caps,
  now including Side Effects rows (about 5.3 MB). A smaller bound shrinks Code Paths' trees and Side Effects' rows and
  waiting records in proportion; Code Inventory's first calls, bounded by the agent's method limit, and method probes,
  bounded at 25 probes of 20 invocations, are only counted. Side Effects contributes `sideEffectRows` and
  `sideEffectsWaiting`. A disabled panel's store adds its bytes to the total without its own row's figures, and a store
  that records nothing for the application, as without the agent, is left out. The method names each store keeps beside its evidence are reported apart, and kept through a clear.

## Privacy and dependency inventory

The agent is local-only. It does not export telemetry, open a network connection, or record anything until a running
BootUI instance claims it. The dependency catalog, the Vulnerabilities panel, and the GraalVM readiness dependency scan
ignore the BootUI agent jar as an application library when its manifest contains `BootUI-Agent-Protocol`.

## Configuration

See [BootUI properties](../PROPERTIES.md#java-agent) for:

| Property | Default | Purpose |
| --- | --- | --- |
| `bootui.agent.enabled` | `true` | Claim the agent when it is attached. |
| `bootui.agent.allow-in-disabled-profiles` | `false` | Spring only: claim the agent even when `bootui.enabled=ON` forces BootUI on in a disabled profile such as `prod`. |
| `bootui.agent.packages` | empty | Extra application package prefixes; the adapter-discovered packages are always included. |
| `bootui.agent.mode` | `auto` | `auto`, `dev`, or `test`. |
| `bootui.agent.sensors` | `executors`, `inventory`, `code-paths`, `processes`, `network`, `blocking`, `resources` | The sensors this application asks for: `executors`, `inventory`, `code-paths`, `processes`, `network`, `blocking`, and `resources`, and the opt-in `threads`, `files`, `environment`, `thread-activity`, `thread-locals`, `caught-exceptions`, and `security-sinks`. Any other id fails the start while the agent is attached. |
| `bootui.agent.security-sinks.request-values` | `false` | With the `security-sinks` sensor, holds the current request's query and path parameter values while it runs, so a sink it reaches can be checked for one appearing verbatim ([the security-sinks sensor](#the-security-sinks-sensor)). Never stored, logged, or displayed. |
| `bootui.agent.executors.skip-tasks` | BootUI's, Micrometer's, and Spring's propagating wrappers, `jdk.internal.`, `sun.`, `java.lang.ProcessHandleImpl` (the JDK's process reaper), `com.zaxxer.hikari.`, `com.github.benmanes.caffeine.` | Task class-name prefixes never propagated. |
| `bootui.agent.executors.skip-threads` | `vert.x-`, `bootui-` | Worker thread-name prefixes never propagated to; on Spring, Reactor's `parallel-`, `boundedElastic-`, and `single-` are added when Reactor's automatic context propagation is on. |
| `bootui.agent.executors.max-handoff` | `5m` | The handoff window: a task belongs to its request when it starts no later than this after the request ended, its work is attributed until this long after it started, and it is published `capped` when it runs longer. |
| `bootui.agent.ring-capacity` | `65536` | The records the agent's transport ring holds, clamped to 1,024–4,194,304 and rounded up to a power of two; the first claim in a JVM sizes it. |

On Quarkus these are build-time properties, read when the application is built (augmented); dev mode rebuilds when
they change.

## Code Paths

The Code Paths panel names the application methods a route spends its time in: it turns "handler 80 ms" into
"`SlowPricingService.quote` 55 ms", across the route's warm requests, without tracing, spans, or a profiler session. It
needs the agent's [`code-paths` sensor](#the-code-paths-sensor), on by default once the agent is attached; without it
the panel is unavailable with the Java Agent panel's reason and a link to it, and every read and `get_code_paths` answer
`available: false` with that reason. The sidebar keeps it in the **Instrumentation** group, dimmed, with that reason. Its reads change nothing on Spring MVC, Spring WebFlux, and Quarkus; its one action
is a [method probe](#method-probes), which the panel's read-only policy refuses.

- **Routes**, ranked by their warm median: each route's warm requests, first recorded request, median and 95th
  percentile, and its top methods by self time. A route marked **assembly only** has a handler that ran on an event
  loop, returned a reactive or asynchronous result, or BootUI could not tell where its work ran, so its tree times the
  handler's assembly, not the work that ran later or elsewhere.
- **The selected route's tree** as an indented table: method, calls per request, total and self time per request, an
  approximate median (≈) per request that reached it, and its share of the handler's time in application methods (of the request's own time when no handler phase is known,
  as on WebFlux), with a share bar. Work an executor ran for the request is marked **async** and shown apart under the
  method that submitted it, never subtracted from it; a parent's methods past the tree's node budget are one **Other**
  node.
- **Calls under methods**: under each method, its SQL statements, REST client calls, cache accesses, and AI calls per
  request, with their time: the calls recorded while it was the innermost instrumented method open on their thread. A
  statement Hibernate flushes at commit runs after the `@Transactional` method returned, in the transaction interceptor
  around it, so it shows under the method that called the `@Transactional` one. Calls issued while no instrumented
  method was open, as in a filter or while the response is written, and calls recorded on another thread, as a
  streaming AI call's, show under no method; the limitations count each apart and say why.
- **Selecting a method** shows its callers within the tree, every route whose tree reaches it, and **Probe this
  method** ([method probes](#method-probes)).
- **Beans at runtime**, a tab beside the routes: the calls between beans observed in this run's route trees, with their
  counts, beside the dependencies the beans declare, as the Beans panel lists them. A filter keeps only the declared
  dependencies **not called in this run**, which is all a run can say: never "unused", since a path no request took or
  work outside a request may still call it. Calls come from each route's warm requests and its first request. A
  dependency is not called only when a call would have been observed: both beans' classes are instrumented and none of
  their methods was adaptively excluded; otherwise it is **not observable**, with why, as with a repository whose class
  a framework generates, and never counted as not called.
- **Excluded methods**: the methods the sensor stopped timing in this run, called more than 50,000 times a second under
  2 µs each, whose time stays in their callers.

How it works:

- The agent times the public and protected methods of the application's bean classes (Spring beans, ArC beans) and
  builds a per-thread fragment of each request's call tree; the engine merges a request's fragments into its request
  tree, then, about two seconds after its last fragment, or sooner under sustained load, when more than 512 request
  trees are open and the eldest quarter settle together, merges the settled tree into its **route tree**: per node, the
  requests that reached it, its calls, total and self time, and a log2 histogram of the time each request spent in it
  with its least and most, from which an approximate (≈) median and 95th percentile are read: interpolated within a
  bucket and clamped to that least and most. Each route's first recorded request, the first whose tree settled, is kept
  apart, as its time and request id only.
- Route trees are keyed by the routes and outcomes [HTTP Exchanges](diagnostics.md#http-exchanges) owns: while that panel is
  disabled, the panel, every read, `get_code_paths`, and the handler split are unavailable with that reason.
- A node is its caller, its method, and the request phase it was entered in: a helper called by the handler and again
  while the response is written is two nodes, so its response-write time never counts as handler time, whichever
  request reached it first.
- A request's tree joins its route tree once the request's exchange is recorded. A slow reactive response's tree can
  settle before its response completes: it waits for the exchange, looked up again with a back-off, up to the executor
  handoff window (five minutes), and only then is kept without a route.
- Route trees are bounded: 2,000 nodes a route with one **Other** node per parent and phase past the budget, 100,000
  nodes and 500 routes across the run. A call that finds no node left keeps its time in its caller's self time. Route
  trees cover the current run only: a DevTools restart or Quarkus live reload starts new ones.
- Request and route trees are kept outside the runtime journal, under the
  [agent evidence contract](#agent-evidence-outside-the-journal). While the Code Paths panel is disabled,
  every read, `get_code_paths`, Beans at runtime, the runtime model's observed calls, the handler split, and
  `repeated-selects`' issuing method say so and show nothing of it. Their estimated bytes show in Live Activity's journal
  status as **Agent evidence**; a smaller `bootui.runtime-journal.agent-evidence-max-bytes` shrinks the bounds above in
  proportion. **Clear recording** drops every request and route tree, and any fragment flushed before the clear, still
  queued or not: a request that lost a fragment to the clear is left out whole, never shown partial. The counts and the
  adaptive exclusions are kept, and the summary says the recording was cleared.
- A method's self time is its time outside its recorded child methods, so a JDK, framework, or library method shows
  only as its caller's self time, and so do the SQL, REST client, cache, and AI calls it waited on.
- **Call-site stamps.** Where the SQL, REST client, cache, and AI recorders already capture their application call site,
  on the thread that issued the call, they also take the agent's stamp of the innermost instrumented method open there:
  its fragment and node, packed into one number. When the request's tree settles, each stamped call is counted under the
  exact node that issued it, and the route tree sums them per node. A call recorded on another thread than the one that
  issued it carries no stamp and shows under no method: a streaming AI call, one received over OTLP, or a WebClient call
  subscribed on another thread (Spring's WebClient filter takes the stamp where the exchange is subscribed, which is the
  issuing thread when the caller blocks or subscribes in place; Quarkus's REST client, where its request filter runs). A
  call issued on the request's thread while no instrumented method was open, as in a filter, while the response is
  written, or in a commit after the outermost instrumented method returned, is counted apart. An executor's handoff
  carries the submitting method's stamp too, so its work shows under that method, and the thread's previous submitter
  comes back when the work is over, as for work a caller-runs executor ran in place.
- **Assembly only**: a handler that ran on an event loop, returned a reactive or asynchronous result, or whose work
  BootUI could not place. That is a Spring MVC handler that started async processing, every Spring WebFlux handler (the
  fragment covers the request's subscription on the assembling thread, up to its first asynchronous boundary; the
  WebFlux configuration marks every tree once, not each request), and a Quarkus resource method that runs on the event
  loop or returns `Uni`, `Multi`, `CompletionStage`, or a publisher. When Quarkus cannot tell which method ran, the
  request is marked assembly only rather than guessed.
- **Handler split.** With the sensor active, Runtime Insights' `route-time-breakdown` splits a route's **Handler, other
  work** into its top five handler-phase methods by own time, the rest as **Other handler time**, for every route whose
  tree is not assembly only. A method's own time is its self time minus the recorded calls stamped to it, which the
  breakdown already names as SQL, REST client, or AI time. Each method takes the share its own time per request has of
  the handler's other work (or of the handler's methods, when they add up to more), so the parts never exceed it. A
  recorded call without a stamp stays in the own time of the method that waited on it, and the observation says how
  many there were. Only handler-phase nodes count. Each method keeps its own row, labelled `Class.method`, with its
  parameter types when two overloads would share a label, and its package when two classes share a name.
- **Issuing method.** Runtime Insights' `repeated-selects` names the method that issued the repeated statements, such
  as a service method looping over a repository, beside SQL Trace's call site: the instrumented method that was
  innermost on the statements' thread, since a repository's own methods are not instrumented.

API, all `GET`:

| Path | Returns |
| --- | --- |
| `/bootui/api/code-paths` | The sensor's status, the routes with a tree ranked by warm median with their top methods, the excluded methods, and the limitations |
| `/bootui/api/code-paths/route?route=` | One route's tree, paged by `depth` (8 by default, at most 33), `offset`, and `limit` (200, at most 500), with each listed method's callers and the routes that reach it, and the route's exemplar request ids |
| `/bootui/api/code-paths/requests/{requestId}` | One request's tree while the run keeps it: its recent requests and each route's slowest and latest failed |
| `/bootui/api/code-paths/beans` | Beans at runtime: the observed calls between beans and the declared dependencies, observed first, with how many declared dependencies on a timed bean were not called in this run |

Each tree node carries `calls`: per kind (`SQL`, `REST`, `CACHE`, `AI`), the calls it issued per request and their
time, `null` for cache accesses, which have none. The runtime model gains an observed `INVOKES` edge between two beans
per call pair, with its count; change impact never walks it, since a call observed in one run is evidence of the paths
its requests took, not of what a change can reach. Change impact by method reads each route's own tree instead: every
route counts, per method, the requests whose tree ran it, at any depth and before its tree folds methods into Other
nodes, the first request and executor work included, at most 4,096 methods per route and 200,000 per run; a fragment
that arrives after its request's tree was merged amends its route rather than opening a second tree, and a route whose
trees may miss methods says so ([Change impact](overview.md#runtime-insights)).

`get_code_paths` and `bootui code paths` return at most `limit` (10) routes matching `query` (a route, or part of a
route or of a method its requests ran), slowest warm median first, each with its top methods; for a single route, its
method nodes with the most self time, each with its calls. A class or method query also finds a route only its first
request reached, which the warm tree keeps apart, and a limitation says so: that route has no method times until it
is sent again. The `diagnose_runtime_issue` MCP prompt points to it for a slow route's handler.

### Method probes

A method probe answers "did this method run, how long did it take, and for which request?" for one application method,
without a debugger, a breakpoint, or a log line. **Probe this method**, on a method selected in a route's tree, or
**Probe in Code Paths**, on a method Code Inventory lists as changed, asks for a confirmation, then the agent
retransforms that one method and records its next **20 invocations**, for at most **60 seconds**, **five probes at
once**:

- each invocation's duration, thread kind (platform or virtual), request id (a link to Live Activity), outcome
  (returned, or the type of the exception it threw), and calling frame: the first frame of the application's packages
  above the method, past proxies and interceptors, else the frame right above it;
- by default, **metadata only**: never an argument, a return value, or a field, in any exposure mode;
- with **Record argument and return shapes** checked before the start, also the **shapes** of the first nine arguments,
  taken at entry, and of the return value (see [Argument and return shapes](#argument-and-return-shapes)).

The **Method probes** card lists the run's probes, newest first, with their state (**starting**, **active**,
**waiting for its class** when this run has not loaded the class yet, **ending** while the agent removes its
instrumentation, **ended**, or **failed** with why), how many invocations each recorded, and why it ended: its
invocations, its window, a **Stop**, or the end of the run. The card refreshes every second only while a probe is live.

- **Bounds hold where the method runs.** The agent counts invocations and checks the window in the probe's own advice,
  so a probe never records past its bound even if removing its instrumentation is slow or fails; such a removal is
  reported on the probe. A probe ends with its run: a DevTools restart, a Quarkus live reload, or BootUI disabled. When
  the run ended with a restart or a reload, the previous run's copy of the class is not retransformed again: the probe's
  transformer is only removed, and the advice left in that copy records nothing until it is unloaded. While the panel
  is read-only, a running probe still ends by itself within its window.
- **The current run's code only.** A probe takes a method the agent's inventory or code-paths sensor instrumented, in the
  application's packages, by `binary.Class#name` with its descriptor when the method is overloaded. Only the copy of the
  class the current run's class loader defined is probed, never a previous run's still loaded; the inventory and
  code-paths instrumentation of the method stays as it was once the probe ends.
- **Reactive and asynchronous methods.** For a method returning `Mono`, `Flux`, `CompletionStage`, `Uni`, or another
  reactive or asynchronous type, the probe is marked **assembly only**: it times the result's assembly and sees only
  what the method throws itself; its request id is known only on the thread that captured it.
- **Evidence.** Probes and their invocations are kept under the
  [agent evidence contract](#agent-evidence-outside-the-journal), the run's last 25 probes with at most 20
  invocations each: hidden with the Code Paths panel, without request ids while HTTP Exchanges is disabled, counted in
  the journal status as **Agent evidence**, and **Clear recording** drops the probes that ended and every invocation
  recorded before it, keeping a live probe for what it records next.
- **Actions.** Starting and stopping probes are refused while BootUI or the panel is read-only
  (`bootui.read-only=true`, `bootui.panels.code-paths.read-only=true`), in the browser, the API, MCP, and the CLI.

| Method and path | Does |
| --- | --- |
| `GET /bootui/api/code-paths/probes` | The run's probes with their recorded invocations, the bounds, and the limitations |
| `POST /bootui/api/code-paths/probes` | Starts a probe on `{"method": "com.example.PriceService#quote(I)J"}`, with `"recordShapes": true` for shapes: 400 for a method that cannot be probed, 409 when refused (unavailable, five running, already probed, or shapes unavailable) |
| `GET /bootui/api/code-paths/probes/{id}` | One probe; 404 when this run has none |
| `POST /bootui/api/code-paths/probes/{id}/stop`, `DELETE /bootui/api/code-paths/probes/{id}` | Stops a probe |

AI agents start one with `start_method_probe` (`bootui probe start <method>`) and read it with `get_method_probe`
(`bootui probe show <id>`), only after the user's separate approval; see
[Did this method run, and how?](../AI-AGENTS.md#did-this-method-run-and-how). Agents start metadata-only probes and
never see a shape.

#### Argument and return shapes

A shape says what an argument or the return value looked like without saying what it was: its runtime type, whether it
was `null`, and, for a short list of JDK types, a size, a length, or a presence. It answers "was this list empty?",
"did this get `null`?", or "which implementation came in?" without a debugger, and never shows a value.

| Value | Shape | Example |
| --- | --- | --- |
| `null` | null | `null` |
| A primitive parameter or return value | its declared type, never its value | `int` |
| `String` | its type; its length under `FULL` exposure | `String`, or `String (12 chars)` |
| `ArrayList`, `LinkedList`, `HashSet`, `List.of(...)`, `HashMap`, `TreeMap`, `ConcurrentHashMap`, ... (exact JDK classes) | its type and size | `ArrayList (size 3)` |
| An array | its type and length; a `char[]` or `byte[]` length under `FULL` exposure | `int[] (length 4)` |
| `Optional`, `OptionalInt`, `OptionalLong`, `OptionalDouble` | present or empty | `Optional (present)` |
| An enum constant | its enum; the constant's name under `FULL` exposure | `Level`, or `Level.HIGH` |
| Anything else: boxed numbers, booleans, application objects, proxies, custom or wrapped collections | its runtime type only | `Card`, `PersistentBag`, `Integer` |

- **No application code runs.** The agent reads a value's class and, by exact class, `String.length()`, an array's
  length, `Optional.isPresent()`, `Enum.name()`, and the `size()` of JDK collections and maps that read their own
  fields. It never calls `toString()`, `hashCode()`, `equals()`, a getter, an iterator, or the `size()` of an
  application, Hibernate, unmodifiable, synchronized, or sorted-view collection: those show their type only, and a lazy
  Hibernate collection is never initialized.
- **Exposure.** `bootui.expose-values` decides what the panel shows, live: under `METADATA_ONLY`, no shape (and a probe
  cannot be started with them); under `MASKED`, the default, types, nullness, collection, map, and array sizes, and
  presence; under `FULL` (or `MASKED` with `bootui.mask-secrets=false`), also a string's length, a `char[]`, `byte[]`,
  `Character[]`, or `Byte[]` length, and an enum constant's name, the details derived from a value. A number or a boolean is never shown.
- **The panel only.** MCP, the CLI, and exports never return a shape, whatever the exposure: `get_method_probe` says a
  probe records them (`recordShapes`) and why they are not shown (`shapesHiddenReason`).
- **Bounds.** Shapes are opt-in per probe and cover the first nine arguments; the rest are counted. A shapes probe uses
  its own advice, which builds its argument array only for an invocation it records, so the default metadata probes
  cost what they did. A shape the agent's transport could not take, or one of an invocation running across a
  **Clear recording**, shows as **lost**.

## Code Inventory

The Code Inventory panel answers the question an agent or a developer asks right after an edit: **did the code I changed
actually run?** It needs the agent's [`inventory` sensor](#the-inventory-sensor), on by default once the agent is
attached; without it the panel is unavailable with the Java Agent panel's reason and a link to it, and every read and
`get_code_inventory` answer `available: false` with that reason. The sidebar keeps it in the **Instrumentation** group,
dimmed, with that reason. It is view-only on Spring MVC, Spring WebFlux, and
Quarkus.

Its header states the run and **N of M application methods executed**, where M counts the methods the agent tracked in
this run. Three tabs follow:

- **Changed since the previous run**, first when a previous run of the application was kept in this JVM: the methods
  whose code changed, and those added, since the previous DevTools restart or Quarkus live reload, without git. Each says
  whether it executed in this run, with the first request and route that ran it, which opens that request in Live
  Activity. Methods not executed come first; removed methods are a count.
- **Application code**: the claimed packages, then their classes and methods, with executed, never-executed, and
  not-tracked counts, filterable to *never executed*.
- **Dependencies**: the application's declared dependencies (the Vulnerabilities panel's inventory), matched by
  `groupId:artifactId` from each jar's Maven metadata, else by its file name, to the jars the agent saw define classes:
  classes loaded in this run, whether the first loaded at startup or later, and the route of the request that loaded it.
  A declared jar with no class loaded is **not loaded in this run**, never "unused": a route this run did not exercise
  may load it. BootUI's own jars and the agent are left out.

How it works:

- At each run, BootUI scans the application's own class files in the claimed packages, off the request path, in the
  directories and jars the application class loader finds them in (test roots excluded), and hashes each method over
  its resolved instructions: constant-pool operands by their symbolic value, `invokedynamic` through its bootstrap
  arguments, branch targets as instruction positions, its exception table, and its runtime-visible annotations, never
  line numbers or other debug attributes. The class's own annotations (a `@RequestMapping` prefix) count toward every
  method, its fields' annotations (`@Value`, `@Autowired`) toward its constructors, and abstract methods (a repository's
  `@Query`) are listed too. A recompilation with other debug settings, or another constant-pool order, hashes the same,
  and so does a lambda or anonymous class the compiler only renumbered because another was added before it; a changed
  literal, method reference, or `@GetMapping` value does not. The scan is bounded by
  `bootui.code-inventory.max-classes` (20,000) and `bootui.code-inventory.scan-timeout` (30 seconds), finding its roots
  included; past either it is partial and says so. A class file unchanged since the previous scan is not parsed again,
  nor a class-path jar opened again, and a class file that cannot be parsed, or a class directory or jar that cannot be
  read, makes the scan partial (failed when nothing could be read), so its methods are never reported as removed. While the scan runs,
  or after it failed, the **Changed** tab says so rather than "no previous run", and nothing is compared.
- The previous run's method hashes are kept across restarts in the same JVM, at most 1 MB a run (12 bytes a method),
  per application, beside the run summaries of [run comparison](overview.md). Only classes both scans covered are
  compared. When another application claimed the agent in between, the panel says the runs are mixed. A full JVM
  restart, or BootUI itself loaded by the restart class loader, keeps no previous run, which the panel says too.
- Which methods executed comes from the agent's hit flags, exactly, and the first request, route, and time from its
  records, which a full ring may drop (counted, and said).
- The first request and route belong to [HTTP Exchanges](diagnostics.md#http-exchanges): while that panel is disabled, every read,
  `get_code_inventory`, and Runtime Insights' `changed-code-not-executed` leave them out, with the reason, and say only
  which methods executed and when. While the Code Inventory panel itself is disabled, every read, the MCP tool, and
  `changed-code-not-executed` answer that it is disabled, and the journal status reports only its bytes.
- First calls are kept per method id, bounded by the agent's method limit (about 9 MB with their loads and routes), and counted in the journal
  status's **Agent evidence** with Code Paths' trees ([agent evidence](#agent-evidence-outside-the-journal)).
  **Clear recording** in Live Activity drops every first request and route recorded before it, those still queued in
  the agent's ring included; which methods executed, and when each first ran, still cover the whole run, since the
  agent marks each method once a run, so a method whose first call is older than the clear may not have run since. The
  summary's `recordingClearedAt` and a limitation say when.
- A method counts as executed or never executed only when the agent instrumented its class in this run, or when its
  class has not loaded in this run at all (after a DevTools restart, a class the new class loader has not loaded yet
  has not run). Any other method on disk is **not tracked**, with its reason (static initializer, abstract method,
  `$`-prefixed name, synthetic class, a class the agent never instruments by name such as a generated proxy, transform
  failed, over the agent's method limit, or **ran before instrumentation** when its class loaded before the agent
  instrumented it), and never counted as executed or never executed. A method that executed but has no class file in
  the scanned roots, as in a generated class, is counted apart as **generated**. Methods called before BootUI claimed
  the agent are not seen.
- A HotSwap (a debugger's **Reload Changed Classes**) keeps the agent tracking the edited methods, but the class files
  are hashed only when a run starts: the edit shows in **Changed** after the next DevTools restart or Quarkus live
  reload ([HotSwap](#hotswap)).

API, all `GET`, paged with `offset` and `limit` where they list:

| Path | Returns |
| --- | --- |
| `/bootui/api/code-inventory` | The run, the scan, the method counts, the change counts, the dependency counts, and the limitations |
| `/bootui/api/code-inventory/changes` | The changed and added methods, not executed first |
| `/bootui/api/code-inventory/methods` | Methods filtered by `package`, `class`, and `status` (`executed`, `never-executed`, `not-tracked`, `generated`), with package and class counts |
| `/bootui/api/code-inventory/dependencies` | The dependency use, declared jars not loaded first, filtered by `status` |

`get_code_inventory` and `bootui code inventory` return the counts first, then at most `limit` (25) rows of `query`:
`changed` (the default), `never-executed`, `not-tracked`, `executed`, `dependencies`, or a package, class, or method
name. The `verify_after_change` MCP prompt starts from it, and Runtime Insights reports a changed method no request
executed as `changed-code-not-executed`.

## Side Effects

The Side Effects panel shows what application code starts outside the JVM or touches through agent sensors, grouped by
route, background work, startup, or thread family. It needs the [BootUI agent](#attaching-the-agent) attached and armed for the
application with a bridge that supports Side Effects. Without that, the panel is unavailable with the Java Agent panel's
reason, starting with "Requires the BootUI agent". It is view-only on Spring MVC, Spring WebFlux, and Quarkus, except
that an opt-in sensor's section (`files`, `environment`, `thread-activity`, `thread-locals`) carries its [runtime
switch](#switching-opt-in-sensors-at-run-time), an action of the Java Agent panel shown while that panel is enabled,
with `bootui.agent.sensors` as the other way to turn it on.

Runtime Insights' [run comparison](overview.md#runtime-insights) reads these rows too: under **Outside the JVM**, it
lists the hosts, file patterns, processes, and variable names a route, a job, or startup uses now and did not in the
previous run, or no longer uses, for each sensor that recorded the whole of both runs.

The panel has one tab per sensor group:

| Tab | Sensors | State in this version |
| --- | --- | --- |
| Network | `network` | records connects, datagram sends, and name lookups (see [the network sensor](#the-network-sensor)) |
| Files and processes | `files`, `processes` | Both record; `processes` is on by default and `files` records when `bootui.agent.sensors` opts in or it is switched on. |
| Environment | `environment` | Records when `bootui.agent.sensors` opts in or it is switched on; otherwise `not-claimed`. |
| Threads and leaks | `thread-activity`, `thread-locals`, `resources` | `resources` records by default, sockets through `network` and file streams once `files` is on (see [the resources sensor](#the-resources-sensor)); `thread-activity` and `thread-locals` record when `bootui.agent.sensors` opts in or they are switched on (see [the thread-activity sensor](#the-thread-activity-sensor) and [the thread-locals sensor](#the-thread-locals-sensor)). |
| Blocking | `blocking` | records on Spring WebFlux and Quarkus; `not-applicable` on Spring MVC until a WebClient's event loop is registered. |
| Security sinks | `security-sinks` | Records request input reaching SQL text, a command, a file path, or an outbound URL when `bootui.agent.sensors` opts in and `bootui.agent.security-sinks.request-values=true` (see [the security-sinks sensor](#the-security-sinks-sensor)). |

The `processes` sensor is on by default through `bootui.agent.sensors`. It hooks the JDK process start path used by
`ProcessBuilder.start()`, `ProcessBuilder.startPipeline(...)`, and `Runtime.exec(...)`. A row records the command name
only: for a process that started, the file name of its executable, after the last path separator of the first command
element, so `C:\Program Files\Java\bin\java.exe` records `java.exe`; for a start that failed, the first command
element up to its first whitespace or `=`, then after its last path separator, so a command and its arguments passed as
one element, such as `/bin/sh -c secret`, records only `sh`, and an environment assignment passed as one element keeps
only the variable name. An element that opens with a quote, as Windows starts `"C:\Tools\app.exe" --token x`, keeps
only its quoted text, and nothing past a quote inside the element is kept. Only letters, digits, `.`, `_`, `+`, and `-`
are kept; other characters become `?`, and the result is at most 128 characters. It never records arguments or environment values. The row
also records whether the process started, the failure if `ProcessBuilder` rejected or failed it, and the exit status and
lifetime when the JVM observes completion. `Process.onExit()` completes through a JDK stage on the common ForkJoin pool
before BootUI's own thread records the exit. On Windows, the JDK waits for each watched live process on a reaper thread
of its own, so up to 1,024 such threads while that many processes run.

A process start is published at once because the hook is rare. Its exit is attributed as the start was, carrying the
start's time, and is watched on an agent-owned `bootui-agent-process-exits` executor so the application process and
thread are not pinned. At most 1,024 exits are watched at once; further exits are counted as not watched. BootUI's own
process starts, such as a GitHub panel `gh auth token` call, the agent's threads, BootUI threads whose names start
`bootui-`, and work done while the agent transforms a class are never recorded. A file operation or an environment
read inside another side-effect hook on the same thread is not recorded, nor a process start or network call inside
a process or network hook; a network call inside a file operation is.

The `files` sensor shows a path pattern per row with its kind (`read`, `write`, `delete`, `move from`, `move to`,
`copy from`, `copy to`), location, and origin, never the file's contents; class path, JDK, and logging rows, and the
files counted in buckets, are grouped apart in a collapsed table under the application's rows. The `environment` sensor
shows a variable's or a property's name, never its value. See [The files sensor](#the-files-sensor) and
[The environment sensor](#the-environment-sensor).

Rows are bounded by the agent evidence contract. The agent writes Side Effects records to a ring of 1,024 records and a
string table separate from other sensors. A full ring drops and counts records; application work never blocks on the
panel. The per-thread aggregation table counts the network sensor's repeated datagram sends and the file operations
and environment reads inside a request scope, flushed when the scope ends; the bridge captures the scope's owner once
when the `code-paths` sensor did not.

The runtime model gains **file pattern** and **environment variable** nodes: a route, GraphQL operation, or scheduled
job **opens** the file patterns and **reads** the environment variables and system properties its application rows
name, while Side Effects is visible (and HTTP Exchanges, for routes), beside the hosts the network sensor's rows
**open**. Change impact keeps listing only tables and caches as a route's reads and writes.

How rows are named:

- Attribution is the request route from HTTP Exchanges, then the execution no request owns that did it, named by its
  runtime journal event (`scheduled ReportJob.run`, `consumed kafka orders`, `websocket /ws`), then startup, then a
  normalized thread family such as `pool-{n}-thread-{n}`. A request that never receives a route after 30 seconds is
  `(unknown route)`, and an execution the journal never names is `work no request owns`. A
  task owned by another request but run inline on the current request's thread, as a caller-runs executor or a
  `ForkJoinTask` join can do, is attributed to that other request; owner slots are kept as a small per-thread stack.
  While HTTP Exchanges is disabled, route rows merge under `(route hidden: HTTP Exchanges is disabled)` and carry no
  request ids.
- Targets are normalized: the home directory becomes `~`, UUIDs become `{uuid}`, long hex strings with a digit become
  `{hex}`, and digit runs become `{n}`, except in network targets, whose addresses and ports keep their digits.
- The call site is the first frame in the application's packages, otherwise the first frame outside the JDK, rendered as
  `Class#method`. When the Code Paths panel is enabled and the `code-paths` sensor is on, the row carries the bean
  method stamp active when the process started. While Code Paths is disabled, rows lose this **inside** method and merge
  without it.

Each process row has starts, failed starts, completed exits, non-zero exits, last exit status, total and longest
lifetime, first and last seen times, and up to three exemplar request ids linking to Live Activity. Each network row has
its kind (`connect`, `datagram`, or `lookup`), its client, how it is captured (`captured` with the panel's id,
`not-captured`, or `infrastructure`; none for a lookup), attempts, failures, established connections, total and
longest connect, send, or resolution time, and the same times and request ids. The **Network** tab shows them with a
**Not captured by any panel** badge, or a link to the panel that captured the work. Each thread-activity row has its
kind (`thread`, `virtual thread`, or `executor`), its target (the started thread's family, digits as `{n}`, or the
executor's class), its origin (`application`, `library`, or `jdk`), how many were started or created and by how many
distinct requests, how many were still running when their request ended (`leftRunning`), and for an executor how many
were shut down (`completed`), with their lifetime, or reclaimed without a shutdown (`failed`). Rows are capped at 500 per
sensor and 2,000 per run; extra observations are counted in the sensor's **Other** row. Up to 10,000 observations wait
for a route before falling back to `(unknown route)`. These bounds shrink in proportion when
`bootui.runtime-journal.agent-evidence-max-bytes` is set below its default.

Side Effects is an agent evidence store. **Clear recording** and **Free BootUI memory** clear its rows; records still in
the agent whose first occurrence came before the clear are dropped by a watermark, and the panel then says **The
recording was cleared**. The journal status reports its memory under **Agent evidence** as `sideEffectRows` and
`sideEffectsWaiting`. The Side Effects panel's own visibility gates every read: when disabled, the panel, API, MCP tool,
and CLI show nothing except **The Side Effects panel is disabled.** HTTP Exchanges still gates route attribution as
above.

API, all `GET`:

| Path | Returns |
| --- | --- |
| `/bootui/api/side-effects` | Every sensor with its tab, state, reason, rows, occurrences, dropped records, hooks, and limitations |
| `/bootui/api/side-effects/sensor?sensor=<id>&offset=&limit=` | One sensor's rows, most frequent first, paged; an unknown sensor id returns `400` with `{error}` |

The **Blocking** tab shows one row per attribution, operation (`sleep`, `wait`, or `park`), event loop's thread family
(`reactor-http-nio-{n}`, `vert.x-eventloop-thread-{n}`), call site, and inside bean method, with the calls, those
interrupted or that threw, how long they blocked the loop in all and at most, and up to three exemplar request ids.
See [the blocking sensor](#the-blocking-sensor) for its hooks and what it does not see.

`get_side_effects` and `bootui side-effects` return every sensor's coverage, then at most `limit` (20) rows matching
`query`: a sensor id such as `processes`, `network`, or `blocking`, `not captured` for the connections no panel shows,
or part of a route, target, client, or call site, most frequent first. The MCP tool is
advertised only while the agent is armed for the run.

The Spring sample seeds two routes: `GET /api/side-effects/java-version` starts the JDK's `java -version` from
`JavaVersionReporter#version` with an extra `-D...` argument that never appears, and
`GET /api/side-effects/runtime-version` answers from the running JVM and starts no process. With the agent, the first
route shows a `java` process row and the counterexample shows none. The WebFlux and Quarkus samples seed the same two
routes, and the Quarkus sample's `ScheduledJavaVersion`, when `side-effects-seed.scheduled-every` sets its period (the
agent Playwright leg uses `20s`; it is off otherwise), starts `java -version` from a scheduled run, a row of that run
(`scheduled …ScheduledJavaVersion#report`) with no request.

The three samples also seed the network sensor: `GET /api/side-effects/sdk-call` asks the application's own
`/api/side-effects/runtime-version` through `LicenseSdkClient`, an SDK-style client with its own `java.net.Socket`, so
with the agent a `connect` row to `localhost:<port>` from `LicenseSdkClient#check` reads **Not captured by any panel**,
and the license header it sends never appears; the counterexample `GET /api/side-effects/rest-call` asks the same
application through the recorded REST client (`RestClient` on Spring MVC, `WebClient` on WebFlux, the MicroProfile REST
client on Quarkus), and no row of its route is ever not captured.

The three samples also seed files and environment: `GET /api/side-effects/report` reads the `sample.report.title`
system property and writes a dated report under `target/bootui-side-effects` in the working directory, a `write` of
`./target/bootui-side-effects/report-{n}-{n}-{n}.csv` outside the temporary directory, and a read of
`sample.report.title` (with `environment` opted in). The counterexamples: `GET /api/side-effects/scratch` writes and
deletes a temporary file, under `$TMPDIR`, and `GET /api/side-effects/log` writes through a JDK logging file handler,
grouped apart as logging; class loading shows only in the buckets. No file contents or property value appears.

The WebFlux and Quarkus samples also seed `GET /api/side-effects/event-loop-sleep`, which sleeps 50 ms on an event loop
(`EventLoopSleeper#sleepOnEventLoop`): on WebFlux in the `map` of a WebClient call to the sample's own greeting, on the
Reactor Netty loop the response completed on; on Quarkus in a `@NonBlocking` endpoint, on its Vert.x loop. Its
counterexample
`GET /api/side-effects/worker-sleep`, the same sleep on a `boundedElastic` thread (WebFlux) or a Quarkus worker thread:
with the agent, the first shows a `sleep` row on the event loop's family and the second none. The Spring MVC sample
shows the Blocking tab `not-applicable`.
