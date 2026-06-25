# CRaC readiness checks

The CRaC panel reviews the host application's [Coordinated Restore at Checkpoint](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
readiness. It combines a live runtime-status view with a heuristic readiness advisor. This page lists every check that
ships with BootUI today, what it inspects, when it fires, and what to do about it.

Each check is a small class registered in
[`CracCheckRegistry`](https://github.com/jdubois/boot-ui/blob/main/bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/crac/CracCheckRegistry.java)
and implemented in
[`CracChecks.java`](https://github.com/jdubois/boot-ui/blob/main/bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/crac/CracChecks.java).
The list intentionally stays compact and reviewable; adding a new check means adding one focused class plus a registry
entry.

## What BootUI does

The runtime-status section (always read-only) reports:

- whether the [`org.crac`](https://crac.org/) API is on the classpath (`org.crac.Core` marker),
- whether the running JVM is a CRaC-capable JDK (such as Azul Zulu CRaC or BellSoft Liberica), detected via the real CRaC
  implementation (`jdk.crac.Core`) rather than the no-op shim that ships with stock JDKs,
- whether automatic checkpoint-on-refresh is enabled (`spring.context.checkpoint=onRefresh`),
- any `-XX:CRaCCheckpointTo` / `-XX:CRaCRestoreFrom` JVM arguments, read from the `RuntimeMXBean` input arguments
  (the same source the JVM Tuning panel uses),
- and any *checkpoint &amp; restore caveats* worth reviewing before a checkpoint: a reminder that CRaC freezes
  configuration (environment variables, system properties, the active Spring profile) into the image at checkpoint time
  when `spring.context.checkpoint=onRefresh` is set, and a note when live connection pools are detected so the backing
  service is kept reachable at both checkpoint and restore (see `CRAC-POOL-001`).

The readiness advisor detects the host application's base package(s) from the `@SpringBootApplication` configuration via
`AutoConfigurationPackages`, imports the compiled `.class` files from those packages with
[ArchUnit](https://www.archunit.org/)'s `ClassFileImporter`, and evaluates every registered check against the imported
classes. Importing is bounded to the application's own base package(s) — never the entire classpath — and runs only on
demand when the scan action is invoked, recomputing the report (with fresh runtime status) on each request.

When BootUI is installed through `bootui-spring-boot-starter`, ArchUnit is included transitively so the panel works
without an extra application dependency. The advisor is available only when ArchUnit is on the classpath and a base
package is resolvable from the running application. If no classes can be imported, the panel degrades to a stable, empty
report with an explanatory reason rather than failing.

Two checks are different: `CRAC-POOL-001` and `CRAC-CACHE-001`. Connection pools and cache managers are
contributed by Spring Boot auto-configuration and never appear in the application's own base package, so those checks
read a small read-only inventory of live beans — pooled clients (JDBC `DataSource`s, plus R2DBC/Redis/RabbitMQ/Kafka/
Mongo/Cassandra/JMS connection factories and similar) and Spring `CacheManager`s — from the running context instead of
imported bytecode. They still trigger only on demand and never open, close, or checkpoint anything.

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
  resources, direct network sockets, connection pools with an open connection, frozen random state, captured secrets).
- **MEDIUM** — a construct that often misbehaves across restore unless handled (unmanaged threads/pools, captured
  wall-clock time).
- **LOW** — a construct that usually needs review but rarely breaks restore on its own.
- **INFO** — an informational prompt about checkpoint/restore lifecycle participation.

The scan evaluates every registered check, but the panel only lists checks that found something to review. Findings are
ordered by importance (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`), then by the number of occurrences, and include up to
a handful of sample detail lines.

---

## Network

### CRAC-NET-001 — Direct network socket creation must be released at checkpoint

- **Severity**: HIGH
- **Inspects**: code that opens network sockets directly (`new Socket` / `ServerSocket` / `DatagramSocket` /
  `MulticastSocket`, or the NIO `ServerSocketChannel` / `SocketChannel` channels).
- **Fires when**: an application class constructs one of those sockets. Open sockets hold OS file descriptors that CRaC
  refuses to checkpoint unless they are closed first.
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
  connections (for example `spring.datasource.hikari.minimum-idle=0`) or rely on Spring closing CRaC-aware pools before
  the checkpoint — and keep the database/cache reachable at both checkpoint and restore. Take the checkpoint after the
  context refreshes but before traffic opens a connection. An in-memory profile (such as H2 with no network socket)
  avoids the problem entirely.

## Caches

### CRAC-CACHE-001 — In-memory caches may hold stale entries after restore

- **Severity**: LOW
- **Inspects**: live Spring `CacheManager` beans in the running context, detected from the application's beans rather
  than its bytecode. A no-op cache manager holds no entries and is ignored.
- **Fires when**: at least one non-no-op `CacheManager` bean is present. Cache entries populated before the checkpoint
  are frozen into the image and survive into every restored process, where they may be stale (for example expired tokens
  or other time-sensitive data).
- **Recommendation**: clear or refresh time-sensitive caches in an `org.crac.Resource.afterRestore()` callback, or use
  restore-aware expiry, so a restored process does not serve data captured at checkpoint time.

## Resources

### CRAC-RES-001 — Open resources held in fields must be released at checkpoint

- **Severity**: HIGH
- **Inspects**: fields whose type holds an OS resource (sockets, file streams, `FileReader`/`FileWriter`,
  `RandomAccessFile`, zip/jar files, NIO channels and selectors, `FileLock`, `WatchService`, `Process`, JDBC
  `Connection`) on classes that do not implement `org.crac.Resource` or a Spring `Lifecycle`.
- **Fires when**: such a field exists on an unmanaged class. CRaC cannot snapshot live file descriptors. Auto-configured
  pools (a HikariCP `DataSource`, a Redis client) are the common case and are covered separately by `CRAC-POOL-001`.
- **Recommendation**: implement `org.crac.Resource` and close the resource in `beforeCheckpoint()`, re-opening it in
  `afterRestore()`; or hold the resource in a Spring `Lifecycle` / `SmartLifecycle` bean so the framework stops it before
  the checkpoint. For auto-configured connection pools, see `CRAC-POOL-001`.

### CRAC-FILE-001 — Direct file handles must be released at checkpoint

- **Severity**: HIGH
- **Inspects**: code that opens file handles directly (`new FileInputStream` / `FileOutputStream` / `RandomAccessFile` /
  `FileReader` / `FileWriter` / `ZipFile` / `JarFile`, or the `Files.newInputStream` / `newOutputStream` /
  `newByteChannel` / `newBufferedReader` / `newBufferedWriter` and `FileChannel` / `AsynchronousFileChannel.open` factory
  methods).
- **Fires when**: an application class opens one of those file handles. An open file holds an OS file descriptor that
  CRaC refuses to checkpoint, so the checkpoint aborts with `CheckpointOpenFileException`.
- **Recommendation**: close the file before the checkpoint (try-with-resources for short-lived handles, or release it in
  an `org.crac.Resource.beforeCheckpoint()` callback and re-open it in `afterRestore()`), or let a managed component own
  the file so the framework closes it around the checkpoint.

## Threads

### CRAC-THREAD-001 — Threads or executor pools created outside the Spring lifecycle

- **Severity**: MEDIUM
- **Inspects**: threads, timers, and executor pools created directly (`new Thread` / `Timer` / `ThreadPoolExecutor`, or
  the `Executors` factory methods).
- **Fires when**: an application class creates one of those directly. Such threads are not paused by CRaC before a
  checkpoint and are snapshotted while running.
- **Recommendation**: drive background work through Spring (`@Async`, `TaskExecutor` / `TaskScheduler` beans,
  `@Scheduled`) so the lifecycle stops it before checkpoint, or register an `org.crac.Resource` that quiesces the pool in
  `beforeCheckpoint()` and restarts it in `afterRestore()`.

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

### CRAC-RANDOM-001 — Static Random/SecureRandom state is frozen into the checkpoint

- **Severity**: HIGH
- **Inspects**: static fields of type `java.util.Random` or `java.security.SecureRandom`.
- **Fires when**: such a static field exists. Its internal state is captured at checkpoint time, so every restored
  instance replays the same sequence.
- **Recommendation**: re-seed or recreate the generator in an `org.crac.Resource.afterRestore()` callback, or avoid a
  static instance so a fresh generator is created per restored process.

## Secrets

### CRAC-SECRET-001 — Secrets captured in static fields are frozen into the checkpoint

- **Severity**: HIGH
- **Inspects**: static fields that capture a secret — a field whose name looks like a secret (`token`, `password`,
  `secret`, `api key`, `credential`, `private key`) holding a `String`, `char[]`, or `byte[]`, or a field holding
  cryptographic key material (`javax.crypto.SecretKey`, `java.security.PrivateKey`, `KeyStore`, `KeyPair`) regardless of
  name.
- **Fires when**: such a static field exists. The value is captured into the checkpoint image and survives in every
  restored process.
- **Recommendation**: load secrets lazily at runtime (or refresh them in an `org.crac.Resource.afterRestore()` callback)
  instead of caching them in a static field, so a checkpoint never freezes a credential.

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
