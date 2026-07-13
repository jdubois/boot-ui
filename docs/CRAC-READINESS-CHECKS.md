# CRaC readiness checks

The CRaC panel reviews the host application's [Coordinated Restore at Checkpoint](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
readiness. It combines a live runtime-status view with a heuristic readiness advisor. This page lists every check that
ships with BootUI today, what it inspects, when it fires, and what to do about it.

Each check is a small class registered in
[`CracCheckRegistry`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/crac/CracCheckRegistry.java)
and implemented in
[`CracChecks.java`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/crac/CracChecks.java).
Both live in the framework-neutral `bootui-engine` module — CRaC readiness is a Spring-only *panel* today (see
[QUARKUS-SUPPORT.md](QUARKUS-SUPPORT.md)), but its rule engine has no Spring dependency of its own. The list intentionally
stays compact and reviewable; adding a new check means adding one focused class plus a registry entry.

## What BootUI does

The runtime-status section (always read-only) reports:

- whether the application-facing [`org.crac`](https://crac.org/) compatibility API is on the classpath
  (`org.crac.Core` marker),
- whether the running JVM is a CRaC-capable JDK (such as Azul Zulu CRaC or BellSoft Liberica), detected via the real CRaC
  implementation (`jdk.crac.Core` or `javax.crac.Core`) rather than the no-op shim that ships with stock JDKs,
- whether automatic checkpoint-on-refresh is actually active. Spring Framework's `DefaultLifecycleProcessor` only honors
  `spring.context.checkpoint=onRefresh` through `org.springframework.core.SpringProperties` — a JVM system property
  (`-Dspring.context.checkpoint=onRefresh`) or a classpath `spring.properties` file — **never** the Spring Boot
  `Environment` (so setting it in `application.yml`/`application.properties`/an OS environment variable has no effect).
  BootUI reads the property the same way Spring Framework does, and separately checks whether the Boot `Environment` also
  claims the property is set; if the Environment says one thing and the real property says another, a restore caveat
  flags the mismatch,
- whether `spring.context.exit=onRefresh` is active. This runs the same lifecycle stop phase and exits without creating a
  checkpoint, so it is a safe dry run on a regular JDK before using CRIU,
- any `-XX:CRaCCheckpointTo` / `-XX:CRaCRestoreFrom` JVM arguments, read from the `RuntimeMXBean` input arguments
  (the same source the JVM Tuning panel uses),
- and any *checkpoint &amp; restore caveats* worth reviewing before a checkpoint: a reminder that CRaC freezes
  configuration (environment variables, system properties, the active Spring profile) into the image at checkpoint time
  when checkpoint-on-refresh is actually active, a warning when the Boot `Environment` claims checkpoint-on-refresh is set
  but the real, `SpringProperties`-backed property is not (so no automatic checkpoint will actually be taken), and a note
  when live connection pools are detected so the backing service is kept reachable at both checkpoint and restore (see
  `CRAC-POOL-001`).

The readiness advisor detects the host application's base package(s) from the `@SpringBootApplication` configuration via
`AutoConfigurationPackages`, imports the compiled `.class` files from those packages with
[ArchUnit](https://www.archunit.org/)'s `ClassFileImporter`, and evaluates every registered check against the imported
classes. Importing is bounded to the application's own base package(s) — never the entire classpath — and runs only on
demand when the scan action is invoked, recomputing the report (with fresh runtime status) on each request.

When BootUI is installed through `bootui-spring-boot-starter`, ArchUnit is included transitively so the panel works
without an extra application dependency. The advisor is available only when ArchUnit is on the classpath and a base
package is resolvable from the running application. If no classes can be imported, the panel degrades to a stable, empty
report with an explanatory reason rather than failing.

Four checks also consume live runtime context: `CRAC-POOL-001`, `CRAC-CACHE-001`, `CRAC-SCHED-001`, and
`CRAC-LIFECYCLE-002`. Connection pools, cache
managers, and classpath dependency presence are either contributed by Spring Boot auto-configuration or are a
runtime/dependency signal rather than something visible in the application's own base package, so those checks
read a small read-only runtime inventory — pooled clients (JDBC `DataSource`s, plus R2DBC/Redis/RabbitMQ/Kafka/
Mongo/Cassandra/JMS connection factories and similar), Spring `CacheManager`s backed by local/in-heap storage, and
whether `org.crac:crac` is on the classpath, plus whether checkpoint-on-refresh is active — instead of imported bytecode.
They still trigger only on demand and never
open, close, or checkpoint anything. Every other check, including `CRAC-POOL-002` (unmanaged HTTP/RPC clients), inspects
imported application bytecode like the rest of the catalogue.

## What BootUI does not do

- BootUI does **not** trigger a checkpoint or restore. Taking a checkpoint is a process-level JVM operation; the panel
  only inspects the running process and the application's bytecode.
- It is **not a replacement for an actual checkpoint/restore run on a CRaC-enabled JDK**. Static analysis cannot see
  resources acquired through runtime data, so the checks are heuristic review prompts.
- It does not analyze third-party dependency bytecode; it inspects only the application's own base-package classes.
- It does not modify, compile, or instrument application code; it reads already-compiled bytecode.

## Severity scale

Severity reflects the worst plausible impact if the finding is real, not the likelihood:

- **CRITICAL** — a construct with the most severe checkpoint/restore impact if the finding is real. No active CRaC check
  currently emits this severity.
- **HIGH** — a construct that typically blocks a clean checkpoint or leaks/duplicates state across restore (open OS
  resources, direct network sockets, unmanaged HTTP/RPC clients, connection pools with an open connection, predictable
  random state, captured secrets).
- **MEDIUM** — a construct that often misbehaves across restore unless handled (unmanaged threads/pools, fixed-rate
  scheduled tasks that may burst after restore, captured wall-clock time, captured environment/system configuration, a
  missing `org.crac:crac` dependency).
- **LOW** — a construct that usually needs review but rarely breaks restore on its own.
- **INFO** — an informational prompt about checkpoint/restore lifecycle participation.

The scan evaluates every registered check, but the panel only lists checks that found something to review. Findings are
ordered by importance (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`), then by the number of occurrences, and include up to
a handful of sample detail lines.

---

## Network

### CRAC-NET-001 — Direct network socket creation must be released at checkpoint

- **Severity**: HIGH
- **Inspects**: code that opens network sockets directly — constructors (`new Socket` / `ServerSocket` /
  `DatagramSocket` / `MulticastSocket`) and the NIO static factory methods that are the conventional way to obtain a
  channel (`SocketChannel.open(...)`, `ServerSocketChannel.open()`, `DatagramChannel.open(...)`,
  `AsynchronousSocketChannel.open(...)`, `AsynchronousServerSocketChannel.open(...)`).
- **Fires when**: an application class constructs or opens one of those sockets/channels outside a managed CRaC
  lifecycle. Open sockets hold OS file descriptors that CRaC refuses to checkpoint unless they are closed first.
- **Exempt when**: the call site is inside `beforeCheckpoint`/`afterRestore`/`start`/`stop` on a class that already
  implements `org.crac.Resource`, `org.springframework.context.Lifecycle`, or `org.springframework.context.SmartLifecycle`
  — i.e. exactly the pattern this check's own recommendation asks for. Without this exemption, following the
  recommendation (re-opening the socket inside `afterRestore()`) would keep re-triggering the same finding forever. A
  socket opened elsewhere in an otherwise-managed class (outside those callback methods) is still flagged.
- **Recommendation**: close the socket in an `org.crac.Resource.beforeCheckpoint()` callback and re-open it in
  `afterRestore()`, or let a managed component (Spring `Lifecycle` bean, server connector) own the socket so the
  framework closes it around the checkpoint.

## Connection pools

### CRAC-POOL-001 — Connection pools must hold no open connection at checkpoint

- **Severity**: HIGH
- **Inspects**: live connection pools and pooled clients in the running context — JDBC `DataSource`s, plus R2DBC/Redis/
  RabbitMQ/Kafka/MongoDB/Cassandra/JMS connection factories and similar — detected from the application's beans rather
  than its bytecode.
- **Fires when**: at least one such pool bean is present. This is the most common real-world cause of a failed
  checkpoint: a pooled connection that is still open when `spring.context.checkpoint=onRefresh` fires holds an OS socket
  that CRaC refuses to snapshot, so the checkpoint aborts with `CheckpointOpenSocketException`. The backing service must
  also be reachable both when the checkpoint is taken and when it is restored.
- **Recommendation**: ensure no pooled connection is open at checkpoint time and keep the database/cache reachable at
  checkpoint and restore. HikariCP itself does not implement `org.crac.Resource`, but Spring Boot 3.2+ supplies
  `HikariCheckpointRestoreLifecycle` when `org.crac:crac` is present. Set
  `spring.datasource.hikari.allow-pool-suspension=true`: Boot then suspends new borrows, evicts connections, waits for
  closure, and resumes after restore. For other pools/clients, verify the exact version's native support or register an
  `org.crac.Resource` wrapper.

### CRAC-POOL-002 — HTTP/RPC clients hold sockets and threads that must be released at checkpoint

- **Severity**: HIGH
- **Inspects**: fields that hold a long-lived HTTP/RPC client with its own connection pool or event-loop threads — the
  JDK's `java.net.http.HttpClient`, Apache HttpClient's `CloseableHttpClient` (4.x and 5.x), OkHttp's `OkHttpClient`,
  Reactor Netty's `HttpClient`/`ConnectionProvider`, or gRPC's `ManagedChannel`.
- **Fires when**: an application class holds one of those client types in a field outside a managed CRaC lifecycle.
  These clients hold open OS file descriptors and background threads exactly like the raw socket/pool types
  `CRAC-RES-001` already covers, but are easy to miss because the client is typically built once via a builder rather
  than constructed directly.
- **Exempt when**: the field is declared on a class that already implements `org.crac.Resource`,
  `org.springframework.context.Lifecycle`, or `org.springframework.context.SmartLifecycle` — the same whole-class
  exemption `CRAC-RES-001` uses, since the managed class is expected to close and rebuild the client itself around the
  checkpoint.
- **Recommendation**: close the client (and its underlying connection pool/event-loop) in an
  `org.crac.Resource.beforeCheckpoint()` callback and rebuild it in `afterRestore()`, or hold it in a Spring
  `Lifecycle`/`SmartLifecycle` bean so the framework stops it before the checkpoint.

### CRAC-POOL-003 — Spring HTTP client fields require a restore-aware transport

- **Severity**: MEDIUM
- **Inspects**: fields of type Spring `RestClient` or `WebClient`.
- **Fires when**: an unmanaged application class holds one of these facades. The facade itself may share
  framework-managed resources, so this is deliberately not a HIGH-severity assertion that every field owns a socket.
- **Recommendation**: verify the underlying `ClientHttpRequestFactory`, Reactor Netty `ConnectionProvider`, and event-loop
  lifecycle. Prefer a Spring-managed `ReactorResourceFactory` with non-global resources or another lifecycle-managed
  connector; otherwise close/recreate the transport owner around checkpoint/restore.

## Caches

### CRAC-CACHE-001 — In-memory caches may hold stale entries after restore

- **Severity**: LOW
- **Inspects**: live Spring `CacheManager` beans backed by local, in-heap storage (for example
  `ConcurrentMapCacheManager` or Caffeine), detected from the application's beans rather than its bytecode. A no-op
  cache manager holds no entries and is ignored, and well-known remote/external-store-backed managers — currently
  Spring Data Redis's `RedisCacheManager` — are excluded too, because their entries live outside the JVM heap in an
  external store and are not frozen into the checkpoint image the way a local manager's entries are.
- **Fires when**: at least one non-no-op, local/in-heap `CacheManager` bean is present. Cache entries populated before
  the checkpoint are frozen into the image and survive into every restored process, where they may be stale (for
  example expired tokens or other time-sensitive data). Cache managers backed by a remote store are a lower concern and
  are not reported here.
- **Recommendation**: clear or refresh time-sensitive local caches in an `org.crac.Resource.afterRestore()` callback, or
  use restore-aware expiry, so a restored process does not serve data captured at checkpoint time.

Hazelcast is intentionally not excluded by type: BootUI cannot reliably distinguish an embedded member from a remote
client from the `CacheManager` bean type. Hazelcast has no currently verified CRaC lifecycle hooks in its public core
source. Client mode is safer because it can reconnect to an existing cluster; an embedded member must explicitly
quiesce/leave and rejoin, and restoring multiple copies of one member checkpoint risks duplicate identity/topology state.

## Resources

### CRAC-RES-001 — Open resources held in fields must be released at checkpoint

- **Severity**: HIGH
- **Inspects**: fields whose type holds an OS resource (sockets, file streams, `FileReader`/`FileWriter`,
  `RandomAccessFile`, zip/jar files, NIO channels and selectors, `FileLock`, `WatchService`, `Process`, JDBC
  `Connection`) on classes that do not implement `org.crac.Resource` or a Spring `Lifecycle`.
- **Fires when**: such a field exists on an unmanaged class. CRaC cannot snapshot live file descriptors. Auto-configured
  pools (a HikariCP `DataSource`, a Redis client) are the common case and are covered separately by `CRAC-POOL-001`;
  long-lived HTTP/RPC clients (`HttpClient`, `OkHttpClient`, gRPC `ManagedChannel` and similar) are covered separately by
  `CRAC-POOL-002`.
- **Exempt when**: the field is declared on a class that already implements `org.crac.Resource`,
  `org.springframework.context.Lifecycle`, or `org.springframework.context.SmartLifecycle` — the managed class is
  expected to close and reopen the resource itself around the checkpoint.
- **Recommendation**: implement `org.crac.Resource` and close the resource in `beforeCheckpoint()`, re-opening it in
  `afterRestore()`; or hold the resource in a Spring `Lifecycle` / `SmartLifecycle` bean so the framework stops it before
  the checkpoint. For auto-configured connection pools, see `CRAC-POOL-001`.

### CRAC-FILE-001 — Direct file handles must be released at checkpoint

- **Severity**: HIGH
- **Inspects**: code that opens file handles directly (`new FileInputStream` / `FileOutputStream` / `RandomAccessFile` /
  `FileReader` / `FileWriter` / `ZipFile` / `JarFile`, or the `Files.newInputStream` / `newOutputStream` /
  `newByteChannel` / `newBufferedReader` / `newBufferedWriter` and `FileChannel` / `AsynchronousFileChannel.open` factory
  methods).
- **Fires when**: an application class opens one of those file handles outside a managed CRaC lifecycle. An open file
  holds an OS file descriptor that CRaC refuses to checkpoint, so the checkpoint aborts with
  `CheckpointOpenFileException`.
- **Exempt when**: the call site is inside `beforeCheckpoint`/`afterRestore`/`start`/`stop` on a class that already
  implements `org.crac.Resource`, `org.springframework.context.Lifecycle`, or `org.springframework.context.SmartLifecycle`
  — reopening the file from `afterRestore()`/`start()` on a managed class is the recommended pattern and is not flagged.
  A file opened elsewhere in an otherwise-managed class is still flagged.
- **Recommendation**: close the file before the checkpoint (try-with-resources for short-lived handles, or release it in
  an `org.crac.Resource.beforeCheckpoint()` callback and re-open it in `afterRestore()`), or let a managed component own
  the file so the framework closes it around the checkpoint.

## Threads

### CRAC-THREAD-001 — Threads or executor pools created outside the Spring lifecycle

- **Severity**: MEDIUM
- **Inspects**: threads, timers, and executor pools created directly (`new Thread` / `Timer` / `ThreadPoolExecutor`,
  `Executors` factories, `Thread.startVirtualThread()`, and `Thread.ofVirtual()` / `Thread.ofPlatform()` builders when
  they call `start(...)` or escape as a `ThreadFactory`).
- **Fires when**: an application class creates one of those directly outside a managed CRaC lifecycle. CRIU (which CRaC
  is built on) freezes *every* OS thread in the process to take a checkpoint — no thread "keeps running through" it, and
  none is skipped. The actual risk is in what happens just *before* that freeze: Spring stops `SmartLifecycle` beans
  gracefully before the checkpoint is even requested, so managed background work reaches a quiescent, consistent state
  first. A raw thread or executor has no such hook, so CRIU captures it abruptly mid-execution — in whatever state it
  happens to be in at that instant — which risks deadlocks, stale locks, or inconsistent state on restore.
- **Does not fire when**: a builder only creates an `unstarted(...)` thread object. That is ordinary heap state, not a
  running unmanaged thread. Virtual threads executing native or pinned work still need to quiesce; JDK 24's JEP 491
  reduces `synchronized` pinning but does not make arbitrary running work lifecycle-managed.
- **Exempt when**: the call site is inside `beforeCheckpoint`/`afterRestore`/`start`/`stop` on a class that already
  implements `org.crac.Resource`, `org.springframework.context.Lifecycle`, or `org.springframework.context.SmartLifecycle`
  — restarting the pool from `afterRestore()`/`start()` on a managed class is the recommended pattern and is not
  flagged. A thread/pool created elsewhere in an otherwise-managed class is still flagged.
- **Recommendation**: drive background work through Spring (`@Async`, `TaskExecutor` / `TaskScheduler` beans,
  `@Scheduled`) so the lifecycle stops it gracefully before checkpoint, or register an `org.crac.Resource` that quiesces
  the pool in `beforeCheckpoint()` and restarts it in `afterRestore()`.

### CRAC-SCHED-001 — Fixed-rate scheduled tasks may run a catch-up burst after restore

- **Severity**: MEDIUM
- **Inspects**: `@Scheduled` methods that explicitly declare `fixedRate` or `fixedRateString`.
- **Fires when**: a method is annotated this way **and** the running application is not configured for
  `spring.context.checkpoint=onRefresh`. Fixed-rate scheduling computes each execution from a fixed wall-clock
  point rather than the end of the previous run, so [on-demand checkpoint/restore of an already-running
  application](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html#_on_demand_checkpointrestore_of_a_running_application)
  can leave a long idle gap between the checkpoint and a later restore; every execution missed during that gap fires
  back-to-back immediately after restore. This risk is specific to on-demand checkpoint/restore of a running
  application. Automatic checkpoint/restore at startup takes the checkpoint before `Lifecycle.start()` and before
  `ContextRefreshedEvent`, so the scheduler has not started and this check is suppressed.
- **Recommendation**: if a catch-up burst after restore is not the behavior you want, switch to `fixedDelay` (or a cron
  expression), which Spring schedules relative to the end of the previous execution rather than a fixed wall-clock
  point, so a checkpoint/restore gap does not queue up missed runs.

## Time

### CRAC-TIME-001 — Static initializer captures wall-clock time

- **Severity**: MEDIUM
- **Inspects**: static initializers that read the current time (`System.currentTimeMillis` / `nanoTime`, `java.time`
  `now()`, `new Date()`, `Instant` / `Clock`).
- **Fires when**: a static initializer caches a current-time value. The value is frozen into the checkpoint image and
  becomes stale after every restore.
- **Recommendation**: read the time when it is needed at runtime instead of caching it in a static field, or refresh the
  cached value in an `org.crac.Resource.afterRestore()` callback.

## Configuration

### CRAC-CONFIG-001 — Static initializer captures environment or system configuration

- **Severity**: MEDIUM
- **Inspects**: static initializers that read environment variables or system properties (`System.getenv`,
  `System.getProperty`, `System.getProperties`).
- **Fires when**: a static initializer caches an environment- or property-derived value. With
  `spring.context.checkpoint=onRefresh` the value is read once when the original JVM starts and frozen into the
  checkpoint image, so changing it for a restore-only start has no effect until a new checkpoint is taken.
- **Recommendation**: read environment- or property-derived configuration at runtime (or refresh it in an
  `org.crac.Resource.afterRestore()` callback) instead of caching it in a static field, so configuration changes take
  effect for a restored process.

## Randomness

### CRAC-RANDOM-001 — Predictable random state is frozen into the checkpoint

- **Severity**: HIGH
- **Inspects**: fields of type `java.util.Random` (including subclasses), plus `SecureRandom(byte[])` construction and
  `SecureRandom.setSeed(...)` calls.
- **Fires when**: predictable generator state or explicit seeding would be copied into every process restored from one
  checkpoint.
- **Recommendation**: recreate/reseed deterministic generators with process-specific state in `afterRestore()`. For
  security-sensitive values, use an unseeded standard `SecureRandom`.

### CRAC-RANDOM-002 — SecureRandom restore-time reseeding depends on the target CRaC JDK

- **Severity**: INFO
- **Inspects**: fields of type `java.security.SecureRandom`.
- **Fires when**: a cached `SecureRandom` exists. Supported Azul/BellSoft CRaC JDKs automatically reseed standard no-arg,
  never-explicitly-seeded instances after restore, so this is a target-runtime verification prompt rather than an
  unjustified HIGH finding. Custom security providers may not participate in those JDK hooks.
- **Recommendation**: use a current supported CRaC JDK, avoid explicit seeds, and verify the exact JDK/provider with a
  real checkpoint/restore test. Explicit seeding is still reported by `CRAC-RANDOM-001`.

## Secrets

### CRAC-SECRET-001 — Secrets captured in fields are frozen into the checkpoint

- **Severity**: HIGH
- **Inspects**: fields, static or instance, that capture a secret — a field whose name looks like a secret (`token`,
  `password`, `secret`, `api key`, `credential`, `private key`) holding a `String`, `char[]`, or `byte[]`, or a field
  holding cryptographic key material (`javax.crypto.SecretKey`, `java.security.PrivateKey`, `KeyStore`, `KeyPair`,
  `SSLContext`, `KeyManager`, or `TrustManager`) regardless of name.
- **Fires when**: such a field exists. A secret loaded at startup — whether into a static field or a singleton bean's
  instance field, such as an `@Value`-injected credential — is baked into the checkpoint image and shipped with every
  restored process, so rotation no longer takes effect and the snapshot leaks the value.
- **Recommendation**: load secrets lazily, or wipe and rebuild key/trust managers and initialized `SSLContext`s in an
  `org.crac.Resource.afterRestore()` callback. Treat checkpoint files as sensitive artifacts: anyone who can pull an
  image containing them may be able to recover private keys or TLS session material.

## Lifecycle

### CRAC-LIFECYCLE-001 — No CRaC Resource implementations were found

- **Severity**: INFO
- **Inspects**: whether the application implements current `org.crac.Resource` or legacy `javax.crac.Resource`.
- **Fires when**: no application class implements either Resource API. These callbacks let components release and
  re-acquire stateful resources around a checkpoint; an application with none usually relies entirely on Spring lifecycle
  handling.
- **Recommendation**: if the application owns resources that Spring does not manage (custom sockets, native handles,
  caches), implement `org.crac.Resource` and register it with `Core.getGlobalContext().register(...)` so it participates
  in checkpoint/restore.

### CRAC-LIFECYCLE-002 — The org.crac:crac API is not on the classpath

- **Severity**: MEDIUM
- **Inspects**: whether the `org.crac:crac` API (`org.crac.Core` / `org.crac.Resource`) is present on the application's
  classpath, read from the live runtime inventory rather than imported bytecode — the same mechanism `CRAC-POOL-001` and
  `CRAC-CACHE-001` use, since classpath presence is a runtime/dependency signal, not something visible in any one class's
  bytecode.
- **Fires when**: the dependency is absent. Spring Boot's checkpoint/restore lifecycle auto-configuration is conditional
  on `org.crac.Resource`, even when the CRaC-enabled JVM exposes its vendor implementation as `javax.crac` or `jdk.crac`.
- **Recommendation**: add the `org.crac:crac` dependency (its version is managed by the Spring Boot BOM) so application
  classes and Spring Boot integrations can register `org.crac.Resource` callbacks. BootUI probes `javax.crac.Core`
  (BellSoft) and `jdk.crac.Core` (Azul) separately as JVM capability markers; applications should depend on the
  vendor-neutral `org.crac` compatibility API.

## Generated container assets

The generated `Dockerfile-crac` uses
`--cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN --cap-add=NET_ADMIN`. `CHECKPOINT_RESTORE`
requires Linux kernel 5.9 or newer, Docker's default `/proc` restrictions require `SYS_ADMIN` so CRIU can restore the
checkpointed PID, and `NET_ADMIN` lets CRIU recreate the container's network interfaces.
Because `SYS_ADMIN` grants broad host access, run the generated image only for local development on an isolated machine,
never production or a shared host.

CRIU/kernel compatibility must be validated on the same host family used for deployment. macOS and Windows cannot create
the deployable CRIU image; use `-Dspring.context.exit=onRefresh` there to exercise Spring's lifecycle stop phase, then run
the real checkpoint/restore test on supported Linux. JVM/GC/heap/CPU feature choices and already-read startup configuration
are frozen when the checkpoint is created. The generated entrypoint therefore applies `JAVA_OPTS` only to
`-XX:CRaCCheckpointTo`; change those flags by deleting and regenerating the checkpoint, not by appending flags to
`-XX:CRaCRestoreFrom`.
