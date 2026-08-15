# CRaC readiness checks

The CRaC panel reviews the host application's
[Coordinated Restore at Checkpoint](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
readiness. It combines read-only runtime status with an on-demand heuristic advisor. The current inventory contains
**17 checks**. Every check is implemented in the framework-neutral engine and, where runtime evidence is needed, consumes
a small neutral inventory supplied by the Spring adapter.

The panel is supported on Spring Boot MVC and Spring Boot WebFlux. It is deliberately `NOT_APPLICABLE` on Quarkus:
BootUI's rules and generated assets target Spring's `LifecycleProcessor`,
`spring.context.checkpoint=onRefresh`, Spring scheduling, and Spring Boot's Hikari integration. Quarkus remains explicitly
unsupported rather than receiving misleading partial parity. The panel is also hidden in a GraalVM native executable,
where CRaC does not apply.

## Evidence used by the audit

The inventory was re-audited against current primary sources:

- [OpenJDK Project CRaC](https://openjdk.org/projects/crac/) and the
  [OpenJDK CRaC source](https://github.com/openjdk/crac/tree/crac)
- [`org.crac.Context`](https://github.com/CRaC/org.crac/blob/master/src/main/java/org/crac/Context.java), including
  `Context.isImplemented()`
- OpenJDK's
  [`OrderedContext`](https://github.com/openjdk/crac/blob/crac/src/java.base/share/classes/jdk/internal/crac/mirror/impl/OrderedContext.java)
  implementation and
  [SUN `SecureRandom` CRaC hooks](https://github.com/openjdk/crac/blob/crac/src/java.base/share/classes/sun/security/provider/SecureRandom.java)
- [Spring Framework checkpoint/restore reference](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
- [Spring Boot checkpoint/restore reference](https://docs.spring.io/spring-boot/reference/packaging/checkpoint-restore.html)
  and
  [`HikariCheckpointRestoreLifecycle`](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/jdbc/HikariCheckpointRestoreLifecycle.html)
- Spring Framework's
  [`SimpleAsyncTaskExecutor`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/task/SimpleAsyncTaskExecutor.html)
  and
  [`SimpleAsyncTaskScheduler`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/concurrent/SimpleAsyncTaskScheduler.html)
  contracts

Comparable tooling was reviewed for transferable ideas, notably
[Micronaut CRaC 3.1](https://micronaut-projects.github.io/micronaut-crac/latest/guide/), which provides framework-specific
ordered resources and a checkpoint simulator. BootUI does not copy Micronaut-specific lifecycle assumptions into the
Spring advisor.

## Audit decisions

The audit classified every check that existed before this revision and added only bounded signals with deterministic
tests.

| Previous check | Decision | Result |
| --- | --- | --- |
| `CRAC-NET-001` | MODIFY | Keeps exact socket/channel acquisition calls, but no longer claims a call proves a live socket. Only restore/start acquisition is exempt; acquisition during cleanup remains visible. |
| `CRAC-FILE-001` | MODIFY | Keeps exact file-handle acquisition calls with the same acquisition-versus-liveness wording and callback boundary. |
| `CRAC-THREAD-001` | SPLIT | Direct starts and executor ownership stay here. Runtime Spring thread-per-task beans move to `CRAC-THREAD-002`. Unstarted `Thread` objects and `ThreadFactory` creation are no longer findings. |
| `CRAC-TIME-001` | MODIFY | Keeps static wall-clock reads as LOW-confidence retention prompts. `System.nanoTime()` is excluded because it is not wall-clock time. |
| `CRAC-CONFIG-001` | MODIFY | Keeps static configuration reads as LOW-confidence retention prompts without claiming data flow the bytecode rule cannot prove. |
| `CRAC-RES-001` | MODIFY | A lifecycle interface alone no longer suppresses a resource field. The exact callback must also contain a compatible cleanup call for that field type. |
| `CRAC-RANDOM-001` | MODIFY | Keeps `Random` fields and explicit `SecureRandom` seeds, but reseeding from a real restore/start callback is exempt. |
| `CRAC-RANDOM-002` | MODIFY | Keeps INFO-level provider verification and narrows the automatic-reseed claim to the documented OpenJDK provider path. |
| `CRAC-SECRET-001` | SPLIT | Keeps named credentials and key material. TLS context/manager state moves to `CRAC-SECRET-002` at lower severity. |
| `CRAC-LIFECYCLE-001` | REMOVE | Implementing `Resource` does not prove registration, strong retention, callback ordering, or correct cleanup; absence is also normal when Spring owns all resources. |
| `CRAC-POOL-001` | SPLIT | Covers non-Hikari pools and remote clients. Hikari-specific observable lifecycle and suspension evidence moves to `CRAC-POOL-004`. |
| `CRAC-CACHE-001` | MODIFY | Reports only known local managers (`ConcurrentMapCacheManager` and `CaffeineCacheManager`), not every manager that is not Redis. |
| `CRAC-SCHED-001` | MODIFY | Keeps Spring's documented fixed-rate warning and suppresses it only before the original on-refresh checkpoint, not in an already-restored process. |
| `CRAC-LIFECYCLE-002` | MODIFY | Remains MEDIUM planning guidance and becomes HIGH when checkpoint-on-refresh is configured but the API is absent. |
| `CRAC-POOL-002` | MODIFY | Keeps known transport owners, removes Reactor `HttpClient` facade matching, and explicitly treats a field as ownership evidence rather than proof of an open connection. |
| `CRAC-POOL-003` | REMOVE | `RestClient` and `WebClient` are facades; their fields do not reveal the lifecycle of the underlying transport and generated too many false positives. |

New checks:

| Check | Bounded signal |
| --- | --- |
| `CRAC-POOL-004` | Existing Hikari singleton, Spring Boot lifecycle bean cardinality, and public `allowPoolSuspension` state; lazy pools are never initialized for inspection. |
| `CRAC-THREAD-002` | Live `SimpleAsyncTaskExecutor` or `SimpleAsyncTaskScheduler` bean type, based on their documented lifecycle limits. |
| `CRAC-SECRET-002` | Field type is `SSLContext`, `KeyManager`, `TrustManager`, or the corresponding manager array. No secret value is read. |

## How scanning works

The advisor:

1. Detects application base packages from Spring Boot's `AutoConfigurationPackages`.
2. Imports only compiled application classes under those packages with ArchUnit.
3. Captures one bounded Spring runtime inventory for the scan.
4. Evaluates the fixed 17-check registry.
5. Returns only `REVIEW` and `ERROR` outcomes to the concerns list.

It runs only after the user invokes **Run readiness checks**. Page load never scans bytecode, initializes a lazy bean,
opens a connection, calls the network, or triggers a checkpoint. Unexpected runtime-inventory failures are surfaced as
scan warnings; they are not converted into a clean result.

Six checks consume Spring runtime evidence:

- `CRAC-POOL-001`
- `CRAC-POOL-004`
- `CRAC-CACHE-001`
- `CRAC-THREAD-002`
- `CRAC-SCHED-001`
- `CRAC-LIFECYCLE-002`

The collector resolves optional types through the application class loader, calls
`getBeanNamesForType(type, false, false)`, sorts observations deterministically, and never instantiates a bean merely to
inspect it.

## What BootUI does not infer

- A constructor or factory call does not prove the returned handle remains live at checkpoint.
- A field does not prove its value is non-null, open, initialized, or reachable from a live singleton.
- Implementing `org.crac.Resource`, `javax.crac.Resource`, `jdk.crac.Resource`, or Spring `Lifecycle` does not prove that
  the instance is registered or managed.
- BootUI does not inspect private pool internals, `/proc`, `lsof`, open file descriptors, thread stacks, or secret values.
- BootUI does not scan third-party dependency bytecode.
- BootUI does not trigger checkpoint/restore and is not a replacement for a real test on the target CRaC JDK, kernel,
  container runtime, CPU architecture, and dependency versions.

### Resource registration and ordering

BootUI deliberately has no generic "resource order is correct" check. The public `org.crac.Context` API exposes
registration but no safe ordering or introspection contract. The current OpenJDK `OrderedContext` implementation uses
weak keys, invokes `beforeCheckpoint` in reverse registration order, and invokes `afterRestore` in registration order,
but that implementation detail is not a portable static-analysis contract.

Keep every registered resource strongly referenced, express ordering through framework-supported composition where
available, and exercise the complete callback sequence in a test. Micronaut's `OrderedResource` is useful comparable
tooling, but it is a Micronaut contract and is not evidence that arbitrary Spring resources can be ordered reliably by
BootUI.

## Severity scale

Severity reflects plausible impact when the concern is real; descriptions separately state the confidence of the
observable signal.

- **HIGH** -- may block checkpoint, duplicate sensitive state, or retain a transport/pool without verified lifecycle
- **MEDIUM** -- lifecycle or scheduling behavior needs explicit restore handling
- **LOW** -- bounded heuristic with meaningful false-positive potential
- **INFO** -- target-runtime/provider verification

No active CRaC check emits `CRITICAL`.

---

## Resources and network

### CRAC-RES-001 -- Resource fields need observable checkpoint cleanup

- **Severity:** HIGH
- **Signal:** a field is assignable to a curated OS-resource type: socket, stream/reader/writer, random-access file,
  zip/jar file, NIO channel/selector/lock, `WatchService`, `Process`, or JDBC `Connection`.
- **Suppressed only when:** the declaring class implements a CRaC or Spring lifecycle and the exact
  `beforeCheckpoint(Context)`/`stop()` callback contains a compatible cleanup call such as `close`, `shutdown`, or
  `disconnect` for that field type, either directly or delegated to a private helper method declared on the same
  class (including transitively, through further private helpers).
- **Boundary:** interface implementation, an overloaded callback name, cleanup of an unrelated field type, or
  delegation to a non-private method or a different class is not suppression.
- **Action:** verify the field's runtime lifecycle, close before checkpoint, and recreate after restore.

### CRAC-FILE-001 -- Direct file handle acquisition needs checkpoint lifecycle review

- **Severity:** HIGH
- **Signal:** exact constructors and factories for `FileInputStream`, `FileOutputStream`, `FileReader`, `FileWriter`,
  `RandomAccessFile`, `ZipFile`, `JarFile`, `Files.new*`, `FileChannel.open`, or
  `AsynchronousFileChannel.open`.
- **Boundary:** try-with-resources still appears because ArchUnit does not perform object-level close data flow. The
  finding is an acquisition review prompt, not proof of a leaked descriptor.
- **Exempt:** acquisition from `Resource.afterRestore()` or Spring `Lifecycle.start()`.
- **Not exempt:** acquisition from `beforeCheckpoint()`, `stop()`, or an unrelated method on a managed class.

### CRAC-NET-001 -- Direct network socket acquisition needs checkpoint lifecycle review

- **Severity:** HIGH
- **Signal:** exact socket constructors or NIO socket/channel `open(...)` factories.
- **Boundary and callback rules:** identical to `CRAC-FILE-001`.
- **Action:** close before checkpoint and reopen after restore, or place ownership in a lifecycle-managed transport.

## Connection pools, transports, and caches

### CRAC-POOL-001 -- Non-Hikari pools need verified checkpoint lifecycle support

- **Severity:** HIGH
- **Signal:** a non-Hikari pool/client bean matches a bounded type list: R2DBC, Redis, RabbitMQ, Kafka, MongoDB,
  Cassandra, Elasticsearch, JMS, or another non-Hikari `DataSource`.
- **Boundary:** bean presence does not prove an open connection.
- **Action:** verify the exact library version's CRaC support or register a wrapper that closes and recreates the client.
  Keep the backing service reachable at checkpoint and restore.

### CRAC-POOL-002 -- HTTP/RPC transport owners need checkpoint lifecycle review

- **Severity:** HIGH
- **Signal:** an application field is typed as JDK `HttpClient`, Apache `CloseableHttpClient`, OkHttp `OkHttpClient`,
  Reactor Netty `ConnectionProvider`, or gRPC `ManagedChannel`.
- **Excluded:** Spring `RestClient`, Spring `WebClient`, and Reactor Netty `HttpClient` facades.
- **Suppressed only when:** cleanup evidence is present using the same rule as `CRAC-RES-001` (a compatible call in
  the exact callback, directly or via a private same-class helper).
- **Boundary:** the field is ownership evidence, not proof of an active socket or worker.
- **Action:** verify and manage the concrete transport owner, not merely the injected facade.

### CRAC-POOL-004 -- Hikari pools need Spring Boot lifecycle coverage and suspension

- **Severity:** HIGH
- **Signal:** a detected Hikari pool lacks an unambiguous
  `HikariCheckpointRestoreLifecycle`, has `allowPoolSuspension=false`, or cannot be inspected without initializing a lazy
  bean.
- **Positive evidence:** the Hikari pool count equals the `HikariCheckpointRestoreLifecycle` bean count (Boot only
  auto-wires a lifecycle bean for the single-candidate `DataSource` case, so multi-pool apps must register the
  remaining beans themselves; equal counts are treated as sufficient bean-count evidence for this heuristic), and
  each already-created pool reports `allowPoolSuspension=true`.
- **Boundary:** mismatched pool/lifecycle-bean counts are reported as unmatched rather than guessed. Equal counts do
  not prove that each lifecycle bean wraps a distinct pool; it is a bean-count heuristic, not a verified pairing.
- **Action:** keep `org.crac:crac` present, retain Boot's lifecycle auto-configuration, and set
  `spring.datasource.hikari.allow-pool-suspension=true`.

Spring Boot's lifecycle suspends new borrows when suspension is enabled, evicts connections, waits for closure, and
resumes the pool. It warns when suspension is disabled because new borrows can race with draining.

### CRAC-CACHE-001 -- In-memory caches may hold stale entries after restore

- **Severity:** LOW
- **Signal:** a `ConcurrentMapCacheManager` or `CaffeineCacheManager` bean.
- **Excluded:** `NoOpCacheManager`, Redis, and unknown manager types. Unknown does not mean local.
- **Action:** clear or refresh time-sensitive entries after restore, or use expiry semantics verified against the
  checkpoint gap.

## Threads and scheduling

### CRAC-THREAD-001 -- Threads or executor pools created outside the Spring lifecycle

- **Severity:** MEDIUM
- **Signal:** `Thread.start`, `Thread.startVirtualThread`, a platform/virtual thread builder's `start`, `Timer`
  construction, executor implementation construction, or a bounded `Executors.new*` factory.
- **Excluded:** `new Thread(...)` without `start`, builder `unstarted(...)`, and `ThreadFactory` creation.
- **Boundary:** executor construction proves ownership, not active workers.
- **Action:** use lifecycle-managed Spring task infrastructure or a CRaC resource that quiesces and restarts the work.

### CRAC-THREAD-002 -- Spring thread-per-task executors need explicit restore handling

- **Severity:** MEDIUM
- **Signal:** a `SimpleAsyncTaskExecutor` or `SimpleAsyncTaskScheduler` bean.
- **Why:** Spring documents that `SimpleAsyncTaskExecutor` does not participate in context-level lifecycle management.
  `SimpleAsyncTaskScheduler` stops trigger firing only to a limited degree and does not stop handed-off work.
- **Action:** prefer `ThreadPoolTaskExecutor`/`ThreadPoolTaskScheduler` or explicitly prove task quiescence and restart.

### CRAC-SCHED-001 -- Fixed-rate scheduled tasks may run a catch-up burst after restore

- **Severity:** MEDIUM
- **Signal:** Spring `@Scheduled(fixedRate=...)` or `fixedRateString=...`.
- **Excluded:** `fixedDelay` and cron expressions.
- **Not applicable:** the original automatic `onRefresh` checkpoint, which occurs before lifecycle start.
- **Boundary:** after a process has been restored, the original property remains visible even though that startup phase
  was consumed. BootUI therefore runs this check again for a restored process.
- **Action:** use `fixedDelay` or cron if a catch-up burst is not desired.

## Time and configuration

### CRAC-TIME-001 -- Static initializer may retain checkpoint-era wall-clock time

- **Severity:** LOW
- **Signal:** `System.currentTimeMillis()`, `java.time.*.now()`, or `new Date()` in a static initializer.
- **Excluded:** `System.nanoTime()`.
- **Boundary:** the rule observes the read, not data flow into a retained field.
- **Action:** resolve the time when needed or refresh retained state after restore.

### CRAC-CONFIG-001 -- Static initializer may retain startup configuration

- **Severity:** LOW
- **Signal:** `System.getenv`, `System.getProperty`, or `System.getProperties` in a static initializer.
- **Boundary:** the rule observes the read, not retention.
- **Action:** avoid restore-varying configuration in checkpoint-era static state and regenerate the checkpoint after
  startup configuration changes.

## Randomness and secrets

### CRAC-RANDOM-001 -- Random state or explicit SecureRandom seeding needs restore handling

- **Severity:** HIGH
- **Signal:** a `java.util.Random` field, `new SecureRandom(byte[])`, or `SecureRandom.setSeed(...)`.
- **Exempt:** explicit reseeding from a real `Resource.afterRestore()` or Spring `Lifecycle.start()` callback.
- **Action:** use an unseeded `SecureRandom` for security-sensitive values and verify the exact JDK/provider. Recreate
  intentional deterministic state with process-specific input after restore.

### CRAC-RANDOM-002 -- SecureRandom restore behavior depends on construction and provider

- **Severity:** INFO
- **Signal:** a `SecureRandom` field.
- **Boundary:** field type cannot reveal constructor, algorithm, provider, or later explicit seeding.
- **Why INFO:** OpenJDK CRaC documents restore handling for its SUN SHA1PRNG path created without an explicit seed.
  Behavior is not generalized to custom, PKCS#11, FIPS, or other providers.
- **Action:** run a real test against the deployed JDK, algorithm, and provider.

### CRAC-SECRET-001 -- Potential secret or key material is retained in a field

- **Severity:** HIGH
- **Signal:** a `String`, `char[]`, or `byte[]` field whose normalized name ends in `secret`, `password`, `passwd`,
  `token`, `api_key`, `credential`, or `private_key`; or a field typed as `SecretKey`, `PrivateKey`, `KeyStore`, or
  `KeyPair`.
- **Boundary:** values are never read. A field such as `tokenUrl` is not treated as a credential.
- **Action:** minimize pre-checkpoint secret exposure and protect checkpoint files as secret artifacts. Refresh after
  restore does not remove the original bytes from an already-created image.

### CRAC-SECRET-002 -- Cached TLS state may need restore-time rebuilding

- **Severity:** MEDIUM
- **Signal:** an `SSLContext`, `KeyManager`, `TrustManager`, or manager-array field.
- **Boundary:** type does not prove initialized keys or sessions, so this is separate from high-confidence key material.
- **Action:** verify the provider and rebuild initialized TLS state after restore when credentials, entropy, or sessions
  must change.

## Lifecycle

### CRAC-LIFECYCLE-002 -- The org.crac:crac API is not on the classpath

- **Severity:** MEDIUM for planning; HIGH when `spring.context.checkpoint=onRefresh` is configured.
- **Signal:** `org.crac.Core` is absent from the application class loader.
- **Action:** add `org.crac:crac` using the version managed by the Spring Boot BOM.

`CRAC-LIFECYCLE-001` was removed. No `Resource` implementer is a valid state when Spring owns all relevant resources, and
an implementer is not evidence of registration or correct cleanup.

## Deterministic test matrix

| Area | Positive | Negative | Boundary | Not applicable |
| --- | --- | --- | --- | --- |
| Resource acquisition | exact socket/file open | unrelated constructor | acquisition in `beforeCheckpoint()` remains visible; `afterRestore()` is exempt | no imported application classes |
| Resource fields | known resource field without cleanup | managed holder with compatible cleanup | overloaded callback or cleanup of another field type remains visible | no matching field |
| Hikari | missing lifecycle or suspension disabled | one lifecycle + one existing suspended pool | lazy pool remains uninitialized and reports unknown; multiple pools are unmatched | Hikari absent |
| Other pools | known non-Hikari factory bean | no pool beans | bean presence does not assert an open connection | optional API absent |
| Threads | direct start/executor factory | unstarted `Thread` | executor creation is ownership evidence | no matching calls |
| Spring tasks | `SimpleAsync*` bean | `ThreadPoolTaskExecutor` | scheduler hand-off is called out separately | Spring task type absent |
| Scheduling | fixed rate | fixed delay/cron | restored process is checked even when the original property remains set | original pre-start `onRefresh` checkpoint |
| Time/config | static wall-clock/config read | ordinary runtime read | `nanoTime` excluded; retention is not asserted | no matching static initializer |
| Random | explicit seed | no-arg `SecureRandom` under RANDOM-001 | reseed from `afterRestore()` exempt | no matching state |
| Secrets | credential/key field | ordinary string and `tokenUrl` | TLS state split to MEDIUM | no matching field |
| Assets | generated output contains all four capabilities and CRaC flags | hand-written files are never overwritten | a non-empty directory is not claimed as proof of a valid image | real CRIU test skipped off supported Linux |

## Generated container assets

The generated `Dockerfile-crac` and `checkpoint-and-run.sh` are contract-tested output, not readiness checks. BootUI does
not parse arbitrary user Dockerfiles.

The generated run command includes:

```text
--cap-add=CHECKPOINT_RESTORE --cap-add=SYS_PTRACE --cap-add=SYS_ADMIN --cap-add=NET_ADMIN
```

`CHECKPOINT_RESTORE` requires a sufficiently recent Linux kernel, `SYS_ADMIN` is needed with Docker's default `/proc`
restrictions for restoring the checkpointed PID, and `NET_ADMIN` allows CRIU to recreate container network interfaces.
These capabilities are powerful; use the image only for local development on an isolated host.

The generated image also sets `SPRING_DATASOURCE_HIKARI_ALLOWPOOLSUSPENSION=true`, the canonical environment-variable
form of `spring.datasource.hikari.allow-pool-suspension`, so Spring Boot's Hikari lifecycle can
block new borrows while it drains the pool. Applications without Hikari ignore that setting.

The entrypoint applies `JAVA_OPTS` only while creating the checkpoint and restores with
`-XX:CRaCRestoreFrom`. JVM, GC, heap, CPU feature, and startup-configuration choices are part of the image; delete and
regenerate the checkpoint after changing them. A deterministic string test can prove the generated contract, but only a
real Linux CRIU run can prove host compatibility and a valid image.
