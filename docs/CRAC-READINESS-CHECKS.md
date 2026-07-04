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

- whether the [`org.crac`](https://crac.org/) API is on the classpath (`org.crac.Core` marker),
- whether the running JVM is a CRaC-capable JDK (such as Azul Zulu CRaC or BellSoft Liberica), detected via the real CRaC
  implementation (`jdk.crac.Core`) rather than the no-op shim that ships with stock JDKs,
- whether automatic checkpoint-on-refresh is actually active. Spring Framework's `DefaultLifecycleProcessor` only honors
  `spring.context.checkpoint=onRefresh` through `org.springframework.core.SpringProperties` — a JVM system property
  (`-Dspring.context.checkpoint=onRefresh`) or a classpath `spring.properties` file — **never** the Spring Boot
  `Environment` (so setting it in `application.yml`/`application.properties`/an OS environment variable has no effect).
  BootUI reads the property the same way Spring Framework does, and separately checks whether the Boot `Environment` also
  claims the property is set; if the Environment says one thing and the real property says another, a restore caveat
  flags the mismatch,
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

Three checks are different: `CRAC-POOL-001`, `CRAC-CACHE-001`, and `CRAC-LIFECYCLE-002`. Connection pools, cache
managers, and classpath dependency presence are either contributed by Spring Boot auto-configuration or are a
runtime/dependency signal rather than something visible in the application's own base package, so those three checks
read a small read-only runtime inventory — pooled clients (JDBC `DataSource`s, plus R2DBC/Redis/RabbitMQ/Kafka/
Mongo/Cassandra/JMS connection factories and similar), Spring `CacheManager`s backed by local/in-heap storage, and
whether `org.crac:crac` is on the classpath — instead of imported bytecode. They still trigger only on demand and never
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
  resources, direct network sockets, unmanaged HTTP/RPC clients, connection pools with an open connection, frozen random
  state, captured secrets).
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
- **Recommendation**: ensure no pooled connection is open at checkpoint time — let the pool drain to zero idle
  connections (for example `spring.datasource.hikari.minimum-idle=0`) — and keep the database/cache reachable at both
  checkpoint and restore. Take the checkpoint after the context refreshes but before traffic opens a connection. Do not
  assume the pool closes itself for CRaC: check whether your specific pool/client library has adopted
  `org.crac.Resource` natively. As of today, most common pools — including HikariCP and Lettuce, Spring Boot's default
  JDBC pool and Redis client — do **not** ship this out of the box (a search of both projects' source for
  `org.crac.Resource` returns no matches as of this writing), so unless you have verified otherwise for your library's
  current version, plan to register your own `org.crac.Resource` wrapper around the `DataSource`/connection factory bean
  to close and reopen it around the checkpoint. An in-memory profile (such as H2 with no network socket) avoids the
  problem entirely.

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
- **Inspects**: threads, timers, and executor pools created directly (`new Thread` / `Timer` / `ThreadPoolExecutor`, or
  the `Executors` factory methods).
- **Fires when**: an application class creates one of those directly outside a managed CRaC lifecycle. CRIU (which CRaC
  is built on) freezes *every* OS thread in the process to take a checkpoint — no thread "keeps running through" it, and
  none is skipped. The actual risk is in what happens just *before* that freeze: Spring stops `SmartLifecycle` beans
  gracefully before the checkpoint is even requested, so managed background work reaches a quiescent, consistent state
  first. A raw thread or executor has no such hook, so CRIU captures it abruptly mid-execution — in whatever state it
  happens to be in at that instant — which risks deadlocks, stale locks, or inconsistent state on restore.
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
- **Fires when**: a method is annotated this way. Fixed-rate scheduling computes each execution from a fixed wall-clock
  point rather than the end of the previous run, so [on-demand checkpoint/restore of an already-running
  application](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html#_on_demand_checkpointrestore_of_a_running_application)
  can leave a long idle gap between the checkpoint and a later restore; every execution missed during that gap fires
  back-to-back immediately after restore. This risk is specific to on-demand checkpoint/restore of a running
  application — automatic checkpoint/restore at startup (`spring.context.checkpoint=onRefresh`) takes the checkpoint
  before the scheduler starts and is not affected.
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

### CRAC-RANDOM-001 — Random/SecureRandom state is frozen into the checkpoint

- **Severity**: HIGH
- **Inspects**: fields, static or instance, of type `java.util.Random` or `java.security.SecureRandom`. Idiomatic Spring
  code rarely uses a *static* PRNG field — the dominant pattern is a singleton-bean *instance* field, such as a
  `@Component`-injected `SecureRandom` — but a checkpoint snapshots every object reachable from GC roots, not just
  static state, so an instance field is captured just as completely as a static one.
- **Fires when**: such a field exists. A checkpoint freezes whatever internal state the generator holds at that moment
  into the image, whether the field is static or an ordinary singleton-bean field. A JDK built for CRaC already
  auto-reseeds a no-arg, never-explicitly-seeded `SecureRandom` on restore, so plain `new SecureRandom()` usage is
  commonly safe there without any code change — but this is not guaranteed on every CRaC-capable platform, so confirm
  your target JDK/runtime's actual behavior before relying on it. An explicitly-seeded `SecureRandom`, or a plain
  `java.util.Random`, keeps its frozen state across restore in every case and needs its own fix.
- **Recommendation**: for an explicitly-seeded `SecureRandom` or a plain `Random`, re-seed or recreate the generator in
  an `org.crac.Resource.afterRestore()` callback, or avoid caching a shared instance so a fresh generator is created per
  restored process. For a no-arg `SecureRandom`, verify your target JDK/runtime re-seeds it automatically on restore
  before treating this as a non-issue.

## Secrets

### CRAC-SECRET-001 — Secrets captured in fields are frozen into the checkpoint

- **Severity**: HIGH
- **Inspects**: fields, static or instance, that capture a secret — a field whose name looks like a secret (`token`,
  `password`, `secret`, `api key`, `credential`, `private key`) holding a `String`, `char[]`, or `byte[]`, or a field
  holding cryptographic key material (`javax.crypto.SecretKey`, `java.security.PrivateKey`, `KeyStore`, `KeyPair`)
  regardless of name.
- **Fires when**: such a field exists. A secret loaded at startup — whether into a static field or a singleton bean's
  instance field, such as an `@Value`-injected credential — is baked into the checkpoint image and shipped with every
  restored process, so rotation no longer takes effect and the snapshot leaks the value.
- **Recommendation**: load secrets lazily at runtime (or refresh them in an `org.crac.Resource.afterRestore()` callback)
  instead of caching them in a field, so a checkpoint never freezes a credential.

## Lifecycle

### CRAC-LIFECYCLE-001 — No org.crac.Resource implementations were found

- **Severity**: INFO
- **Inspects**: whether the application implements `org.crac.Resource`.
- **Fires when**: no application class implements `org.crac.Resource`. These callbacks let components release and
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
- **Fires when**: the dependency is absent. A Spring Boot application on a CRaC-enabled JVM does not strictly need this
  dependency to take an automatic checkpoint, but without it no application class can implement `org.crac.Resource`, so
  it cannot hook `beforeCheckpoint()`/`afterRestore()` to release and reacquire resources, re-seed randomness, or refresh
  secrets around a checkpoint — the fix every other resource/random/secret finding in this rule set recommends.
- **Recommendation**: add the `org.crac:crac` dependency (its version is managed by the Spring Boot BOM) so application
  classes can implement `org.crac.Resource` and register with `Core.getGlobalContext().register(...)` to participate in
  checkpoint/restore.
