# CRaC readiness checks

The CRaC panel combines passive JVM status with **17 on-demand heuristic checks** of application bytecode and Spring
resource metadata. It does not take a checkpoint, invoke resource callbacks, stop work, open connections, or inspect
secret values. A report without findings is not proof that an application will checkpoint or restore successfully.

The same engine, collector, and JSON contract serve Spring MVC and WebFlux. Quarkus remains deliberately
`NOT_APPLICABLE`: these checks and generated assets target Spring lifecycle, scheduling, and Hikari integration.
Native executables are also unavailable. An ordinary JVM can use the advisor for migration planning; an unsupported
checkpoint environment is not evidence that the application itself is defective.

## Version and source applicability

This audit uses **Spring Boot 4.1.1**, which selects **Spring Framework 7.0.9**, **org.crac 1.5.0**, **HikariCP 7.0.2**,
and Spring Data Redis, AMQP, and Kafka **4.1.1**. See the tagged
[Boot version properties](https://github.com/spring-projects/spring-boot/blob/v4.1.1/gradle.properties) and
[dependency declarations](https://github.com/spring-projects/spring-boot/blob/v4.1.1/platform/spring-boot-dependencies/build.gradle).
Framework 7.1 development documentation is not substituted for this release baseline.

Primary evidence:

| Source | Contract used |
| --- | --- |
| [org.crac 1.5.0 package contract](https://github.com/CRaC/org.crac/blob/1.5.0/src/main/java/org/crac/package-info.java) | Weak resource retention, global callback ordering, failure notifications and suppressed exceptions. |
| [org.crac 1.5.0 Core](https://github.com/CRaC/org.crac/blob/1.5.0/src/main/java/org/crac/Core.java) and [Context](https://github.com/CRaC/org.crac/blob/1.5.0/src/main/java/org/crac/Context.java) | Unsupported checkpoint requests throw; the managed API has no `Context.isImplemented()` method. |
| [Framework 7.0.9 checkpoint reference](https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/integration/checkpoint-restore.adoc) | On-demand versus startup checkpoint, scheduling and snapshot-secret boundaries. |
| [DefaultLifecycleProcessor](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/context/support/DefaultLifecycleProcessor.java) and [SpringProperties](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-core/src/main/java/org/springframework/core/SpringProperties.java) | Exact startup-property semantics, one-shot checkpoint phase, lifecycle stop/restart, and exit-on-refresh halt. |
| [Boot Hikari lifecycle](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-jdbc/src/main/java/org/springframework/boot/jdbc/HikariCheckpointRestoreLifecycle.java) and [configuration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-jdbc/src/main/java/org/springframework/boot/jdbc/autoconfigure/DataSourceCheckpointRestoreConfiguration.java) | One lifecycle targets one unwrapped datasource; suspension is conditional, not automatically enabled. |
| [Hikari 7.0.2 configuration](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java) | Suspension must be configured before the pool is initialized. |
| [Lettuce](https://github.com/spring-projects/spring-data-redis/blob/4.1.1/src/main/java/org/springframework/data/redis/connection/lettuce/LettuceConnectionFactory.java), [Rabbit](https://github.com/spring-projects/spring-amqp/blob/v4.1.1/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/connection/CachingConnectionFactory.java), [Kafka producer factory](https://github.com/spring-projects/spring-kafka/blob/v4.1.1/spring-kafka/src/main/java/org/springframework/kafka/core/DefaultKafkaProducerFactory.java), [JMS shared factory](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-jms/src/main/java/org/springframework/jms/connection/SingleConnectionFactory.java) | Specific Spring-managed implementations already stop/restart their owned resources; generic factory interfaces do not prove this. |
| [SimpleAsyncTaskExecutor](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-core/src/main/java/org/springframework/core/task/SimpleAsyncTaskExecutor.java), [SimpleAsyncTaskScheduler](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/scheduling/concurrent/SimpleAsyncTaskScheduler.java), [ExecutorConfigurationSupport](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/scheduling/concurrent/ExecutorConfigurationSupport.java) | Thread-per-task and pooled Spring executors have different quiescence contracts; pausing is not destruction. |
| [Scheduled 7.0.9](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/scheduling/annotation/Scheduled.java) | Repeatable/composed scheduling, default attributes, and independent triggers. |
| [OpenJDK 21 Files](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/nio/file/Files.java), [Date](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/Date.java), [Executors](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/concurrent/Executors.java), [Thread](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/Thread.java) | Exact resource-returning factories, current-time versus supplied-time constructors, per-task executor and builder APIs. |
| [OpenJDK 21 SecureRandom](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/security/SecureRandom.java) | The public byte-array constructor explicitly seeds; the protected SPI/provider constructor does not. |
| [CRaC descriptor policies](https://github.com/CRaC/docs/blob/f528f3d205148f6d31aac4e7c2319a1e7d16f2af/fd-policies.md) and [best practices](https://github.com/CRaC/docs/blob/f528f3d205148f6d31aac4e7c2319a1e7d16f2af/best-practices.md) | Version-specific JDK descriptor policy, strong resource ownership and scheduling review. |
| [Azul runtime support](https://docs.azul.com/crac/usage/running-crac) and [engines](https://docs.azul.com/crac/usage/crac-engines.html) | CRaC-specific 17/21/25 distributions, Linux support, simulation and engine-specific prerequisites. These are living vendor documents, not guarantees for every patch build. |
| [BellSoft CRaC announcement](https://bell-sw.com/news/bellsoft-releases-dedicated-builds-of-liberica-jdk-17-and-21-with-crac/) | The announced JDK 17/21 Linux x86_64/AArch64 builds; not an exhaustive current release matrix. |
| [Pinned OpenJDK CRaC SecureRandom](https://github.com/openjdk/crac/blob/945496fe5fded24a64a6a3683979bbc76788f83c/src/java.base/share/classes/sun/security/provider/SecureRandom.java), [NativePRNG](https://github.com/openjdk/crac/blob/945496fe5fded24a64a6a3683979bbc76788f83c/src/java.base/unix/classes/sun/security/provider/NativePRNG.java), [System](https://github.com/openjdk/crac/blob/945496fe5fded24a64a6a3683979bbc76788f83c/src/java.base/share/classes/java/lang/System.java) | Provider-specific reseeding and restore-time property/clock behavior in a **JDK 28 development tree**, not certification of deployed CRaC 17/21/25 behavior. |
| [CRIU 4.1 completion](https://github.com/checkpoint-restore/criu/blob/v4.1/criu/cr-dump.c) and [pinned CRaC CRIU fork](https://github.com/CRaC/criu/blob/0ac0a95f1eb10fcb1f3fe23bae72322c4625ee4c/criu/cr-dump.c) | Inventory is written late, but subsequent completion can fail. A marker file is not an integrity certificate. |

## Complete audit disposition

Every active ID is retained, preserving dismissals. Updates change evidence interpretation or precise matching, not the
public JSON record shape. Retained checks remain conditional review prompts.

| Check | Disposition | Decision |
| --- | --- | --- |
| `CRAC-RES-001` | UPDATE | Same-type cleanup cannot establish field identity or registration; keep it as lower-severity contextual evidence instead of suppressing fields. Include `DirectoryStream`. |
| `CRAC-FILE-001` | UPDATE | Add `Files.list`, `walk`, `find`, `lines`, and `newDirectoryStream`; distinguish acquisition from retained open state. |
| `CRAC-NET-001` | RETAIN | Exact socket/channel acquisition, with only exact restore/start call-site exclusions. |
| `CRAC-POOL-001` | UPDATE | Credit documented existing managed factories in the applicable running lifecycle; do not equate a generic factory or startup-phase presence with coverage. |
| `CRAC-POOL-002` | UPDATE | Keep transport-owner fields and facade exclusions; remove same-type cleanup certainty and make shutdown guidance JDK/ownership-specific. |
| `CRAC-POOL-004` | UPDATE | Equal counts do not establish Hikari pairing; report suspension independently and preserve unknown/lazy observations. |
| `CRAC-CACHE-001` | RETAIN | Known local managers only, LOW and conditional on time-sensitive retained entries. |
| `CRAC-THREAD-001` | UPDATE | Add per-task executor/base builder APIs; ownership does not prove currently active unmanaged work. |
| `CRAC-THREAD-002` | RETAIN | Documented SimpleAsync lifecycle limitations; no task submission, stop or active-state inference. |
| `CRAC-SCHED-001` | UPDATE | Recognize repeated/composed metadata, exclude explicit default rates, and do not mistake a running context's original onRefresh setting for a future pre-start checkpoint. |
| `CRAC-TIME-001` | UPDATE | Only no-argument Date construction reads current time; retain LOW static-read heuristic. |
| `CRAC-CONFIG-001` | UPDATE | Distinguish cached values/bean bindings from runtime-specific restore-time property updates. |
| `CRAC-RANDOM-001` | UPDATE | Distinguish security-sensitive uniqueness from intentional reproducibility, exclude non-seeding provider constructors, and avoid universal provider assertions. |
| `CRAC-RANDOM-002` | UPDATE | Retain INFO provider verification with pinned development-source limitations. |
| `CRAC-SECRET-001` | RETAIN | Value-free credential/key field signals and protection of the original image, not merely refreshed fields. |
| `CRAC-SECRET-002` | RETAIN | Conditional TLS-state review without assuming initialized keys, sessions or stale trust. |
| `CRAC-LIFECYCLE-002` | UPDATE | Missing compatibility API remains a planning/configuration concern; unavailable collection is not evidence of absence. |

`CRAC-LIFECYCLE-001` remains **removed**: implementing Resource proves neither registration nor ordering/cleanup, and
applications can rely entirely on Spring-managed resources. `CRAC-POOL-003` remains **removed**: RestClient/WebClient
facades do not establish ownership of their underlying transports.

## Collection and observation limits

Only **Run readiness checks** imports compiled application classes and collects resource metadata. Passive reports reuse
cached resource evidence while refreshing cheap runtime status. Collection never initializes a lazy bean or FactoryBean
product to improve confidence. Discovery uses non-eager type lookup and existing singleton metadata; samples are
bounded and diagnostics do not expose arbitrary exception messages or secret-bearing JVM properties.

Five rules need only runtime inventory: POOL-001, POOL-004, CACHE-001, THREAD-002 and LIFECYCLE-002. They still run when
application packages or bytecode are unavailable. SCHED-001 needs bytecode and runtime phase evidence. The other eleven
checks need bytecode. Missing observations are skipped with visible warnings, not converted into clean results.
Import/collection failure remains an error even when independent checks produced useful findings. Checks-run counts
describe evaluated checks, not a claim that every possible resource was inspected.

The advisor does **not** establish object reachability, non-null fields, open file descriptors, active connections,
currently executing tasks, successful unwrapping of arbitrary resources, third-party implementation bytecode, resource
registration, callback correctness, native-code descriptors, custom context ordering, or eventual application readiness.
It does not inspect `/proc`, invoke `lsof`, traverse private pool fields, close resources, or read heap/snapshot values.

### Registration, ordering and checkpoint phase

The org.crac 1.5.0 **global context contract** retains resources weakly, calls `beforeCheckpoint` in reverse registration
order and `afterRestore` in forward order. Keep a strong owner reference. Custom contexts can specify different
ordering; Spring lifecycle phases/dependencies are a separate ordering mechanism.

After callbacks can also run following failed preparation without a real image, and callback failures can be suppressed
exceptions. Callback execution alone is not successful restore evidence. BootUI does not infer correct registration or
ordering from bytecode.

The original `spring.context.checkpoint=onRefresh` checkpoint occurs after non-lazy singletons have initialized but
**before lifecycle startup**. It is not a fully warmed traffic-tested application. Spring's stop-for-restart cycle
depends on an already-running lifecycle, so documented on-demand stop/restart support is not a blanket exemption for
early-created resources. The one-shot startup flag is consumed; the original property can remain in an already-running
or restored process.

## Rule catalogue

### CRAC-RES-001 - Resource fields need observable checkpoint cleanup

**HIGH**, or **MEDIUM** when all matching fields have compatible callback cleanup evidence. Curated resource types
include sockets, file streams/readers/writers, random-access and zip/jar files, channels/selectors/locks, WatchService,
DirectoryStream, Process and JDBC Connection. A compatible cleanup call in the exact before/stop callback, including
bounded private same-class helper traversal, is reported but **never proves which field instance is closed** or that the
owner is registered. Review actual ownership before adding cleanup; preserve existing framework lifecycle handling.

### CRAC-FILE-001 - Direct file handle acquisition needs checkpoint lifecycle review

**HIGH.** Exact file constructors, Files handle/stream factories and file-channel open calls are acquisition evidence.
Includes `list`, `walk`, `find`, `lines` and `newDirectoryStream`; does not treat eager `readString`/`readAllLines` as
retained-handle factories. Try-with-resources still appears because no object-close dataflow is performed. Exact
afterRestore/start acquisition is excluded; beforeCheckpoint/stop acquisition remains visible. Verify closure or the
deployed JDK's specific descriptor policy; never apply a blanket ignore policy.

### CRAC-NET-001 - Direct network socket acquisition needs checkpoint lifecycle review

**HIGH.** Exact socket constructors and synchronous/asynchronous channel `open` factories. Same callback and liveness
limits as FILE-001. Verify lifecycle ownership and close/recreate only where needed; a constructor does not establish
an active connection at checkpoint.

### CRAC-POOL-001 - Non-Hikari pools need verified checkpoint lifecycle support

**HIGH** for unverified generic pools/clients; **MEDIUM** when only known managed factories need startup/API review.
The bounded inventory covers DataSource, R2DBC, Redis, RabbitMQ, Kafka, MongoDB, Cassandra, Elasticsearch and JMS.
Specific existing Spring-managed Lettuce, Caching Rabbit, Default Kafka producer and JMS SingleConnectionFactory
implementations have documented handling of their owned resources. A running lifecycle with the compatibility API
receives that credit, but generic interfaces, unknown wrappers/subclasses, externally shared resources and original
startup initialization do not become universally safe. Do not wrap/close managed resources twice or require every
backing service to be reachable at checkpoint regardless of initialization behavior.

### CRAC-POOL-002 - HTTP/RPC transport owners need checkpoint lifecycle review

**HIGH**, or **MEDIUM** when all matching fields have compatible cleanup evidence. Matches JDK HttpClient, Apache
CloseableHttpClient, OkHttpClient, Reactor ConnectionProvider and gRPC ManagedChannel fields. Excludes Spring RestClient,
WebClient and Reactor HttpClient facades. Cleanup has RES-001's identity/registration limits. JDK HttpClient shutdown
APIs require Java 21 or later; unread response bodies/in-flight work can affect shutdown. No client is closed by a scan.

### CRAC-POOL-004 - Hikari pools need Spring Boot lifecycle coverage and suspension

**HIGH manual review**, not a proven open connection. Each Boot lifecycle wraps one datasource and can fail to unwrap
it. Equal counts, even one pool and one lifecycle, do not prove pairing. Pairing uncertainty and
`allowPoolSuspension=false` are reported independently. Lazy/dynamic targets remain unknown without initialization.
Set suspension before pool startup using `spring.datasource.hikari.allow-pool-suspension=true` for the stock Boot pool
or the appropriate custom binding. This setting alone proves neither pairing nor original startup-phase cleanup.

### CRAC-CACHE-001 - In-memory caches may hold stale entries after restore

**LOW.** ConcurrentMapCacheManager and CaffeineCacheManager only; unknown, no-op and remote managers are excluded.
Manager presence proves neither populated entries nor stale data. Refresh time-sensitive entries or verify their expiry
semantics across the checkpoint gap; the scan never clears a cache.

### CRAC-THREAD-001 - Threads or executor pools created outside the Spring lifecycle

**MEDIUM.** Direct thread starts, platform/virtual/base builder starts, Timer construction, curated executor
constructors and Executors factories including both thread-per-task forms. Unstarted Thread objects and ThreadFactory
creation are excluded. Creation is ownership evidence, not active worker state or proof that external lifecycle handling
is absent. Prefer appropriate managed infrastructure or explicit quiescence/restart; do not blindly destroy executors.

### CRAC-THREAD-002 - Spring thread-per-task executors need explicit restore handling

**MEDIUM.** SimpleAsyncTaskExecutor does not participate in context-level lifecycle management. SimpleAsyncTaskScheduler
has limited trigger/fixed-delay control and does not stop already handed-off tasks. Bean metadata does not prove running
work. Pooled Spring infrastructure has different pause/resume coordination, not an unconditional readiness guarantee.

### CRAC-SCHED-001 - Fixed-rate scheduled tasks may run a catch-up burst after restore

**MEDIUM.** Direct, repeatable and bounded composed Scheduled metadata with fixed-rate declarations; one occurrence per
method. Explicit default `fixedRate=-1` and empty `fixedRateString` do not establish a rate. The scan is not Spring's
full alias/placeholder resolver; composed overrides, unresolved properties and custom schedulers require review.
Only an observed original pre-start onRefresh phase is excluded; a running/restored process is checked even when its
original property persists. Fixed delay, cron and explicit rescheduling have different semantics and tradeoffs.

### CRAC-TIME-001 - Static initializer may retain checkpoint-era wall-clock time

**LOW.** Static `System.currentTimeMillis`, java.time `now` or **no-argument** Date construction. Date(long) is a supplied
instant, not a clock read. The rule cannot establish retention. nanoTime is excluded from this wall-clock rule, not
certified portable across every restore/reboot/machine.

### CRAC-CONFIG-001 - Static initializer may retain startup configuration

**LOW.** Static getenv/getProperty/getProperties reads. Some runtimes update environment/system properties on restore;
existing cached values and Spring bean bindings do not automatically rebind. Explicitly reload/rebind when required or
regenerate the image when startup assumptions change.

### CRAC-RANDOM-001 - Random state or explicit SecureRandom seeding needs restore handling

**HIGH potential impact**, not proof of security-sensitive use. Random fields and explicit SecureRandom seed calls
outside exact afterRestore/start callbacks. Protected provider/SPI constructors are not explicit seeds.
Intentional deterministic simulation may require no change. For
security-sensitive uniqueness, verify initialization and the deployed provider rather than assuming universal reseeding.
Excluding a restore seed call does not certify its entropy.

### CRAC-RANDOM-002 - SecureRandom restore behavior depends on construction and provider

**INFO.** SecureRandom fields cannot reveal construction, algorithm, explicit later seeding or provider. The pinned
OpenJDK development sources demonstrate provider-specific handling, not a guarantee for all deployed CRaC versions.
Verify the actual JDK/provider, especially custom, FIPS and PKCS#11 implementations.

### CRAC-SECRET-001 - Potential secret or key material is retained in a field

**HIGH.** String/char[]/byte[] fields with bounded credential-like names and SecretKey, PrivateKey, KeyStore or KeyPair
types. Values are never read; tokenUrl is not a credential name. Protect checkpoint files and minimize pre-checkpoint
exposure. Refreshing a field after restore cannot remove original bytes from an already-created image.

### CRAC-SECRET-002 - Cached TLS state may need restore-time rebuilding

**MEDIUM.** SSLContext, KeyManager, TrustManager and manager arrays. Type alone does not prove initialized keys/sessions.
Rebuild only when provider and ownership requirements demand changed state; preserve framework-managed resources.

### CRAC-LIFECYCLE-002 - The org.crac:crac API is not on the classpath

**MEDIUM planning guidance**, **HIGH** when onRefresh checkpointing is configured. Verify the compatibility API used by
Spring's integration; dependency presence is separate from JVM implementation and operational engine support. Add the
Boot-managed org.crac dependency when needed. Unavailable collection is not evidence that the dependency is absent.

## Runtime-status recommendations

| Signal | Interpretation |
| --- | --- |
| API/implementation presence | Metadata evidence only, not readiness. org.crac 1.5.0 has no Context.isImplemented; later proxy detection still cannot certify host/engine support. |
| API without implementation | Registration can be ineffective; checkpoint requests throw UnsupportedOperationException, not a harmless no-op. |
| simengine/pauseengine | Simulation is not a real checkpoint image. Linux/native engine support is separate from the development host OS. |
| SpringProperties onRefresh | Exact framework setting, not arbitrary application.yml/Boot Environment configuration. A remaining setting does not prove the one-shot startup phase is still pending. |
| exit=onRefresh | Halts the JVM before normal lifecycle startup. It does not exercise checkpoint cleanup or restore callbacks. Only consider it as a separate-process startup-boundary test; if both options are enabled, checkpoint runs first. |
| Restore argument/MXBean | An option is a hint; available public restore-time metadata is stronger runtime evidence, but neither proves post-restore application correctness. |
| Resource caveats | Cached explicit-scan metadata, not a complete current live-resource inventory. Unknowns and collection failures are shown, not silently discarded. |

## Generated assets and sample parity

The Dockerfile and entrypoint are **scaffolds**, not additional readiness checks or parsed claims about an arbitrary
user Dockerfile. Their Maven/Gradle variants assume a single-module Spring Boot application compatible with the selected
JDK21 build/runtime images. Source-tree writes stay explicit, confined and refuse to overwrite handwritten files.

| Recommendation | Disposition |
| --- | --- |
| onRefresh creation then restore | Retain the recipe; describe initialized non-lazy singletons before lifecycle start, not a guaranteed fully warmed application. |
| JDK21 Liberica CRaC runtime | Retain the explicit scaffold target; do not certify every vendor release, application toolchain, CPU or kernel. |
| CRIU capability recipe | Retain CHECKPOINT_RESTORE, SYS_PTRACE, SYS_ADMIN and NET_ADMIN as this local CRIU-oriented recipe, not universal CRaC/Warp requirements. These are broad privileges; use an isolated local host. |
| Published port | Bind generated local examples to loopback. |
| Hikari suspension environment | Retain the stock Boot binding, not proof of lifecycle pairing or custom pool configuration. |
| Creation-only JAVA_OPTS | Retain this scaffold's command contract. Regenerate the image when application/JDK/startup assumptions change; restore behavior is runtime-specific. |
| inventory.img | Treat as a candidate marker for a restore attempt, never a complete-image, integrity or compatibility certificate. Restore errors remain errors. |
| Incomplete directory recovery | Remove automatic deletion. Fail nonzero, preserve partial images/logs/user data, and require deliberate operator recovery. Reject unsafe configured paths before creating or using them. |
| Diagnostics | Surface failure and bounded log tails; checkpoint logs and images can be sensitive. Never turn creation/restore failure into a success-shaped fallback. |
| Sample counterparts | The shipped sample entrypoint follows the same non-destructive policy. H2/local caches and broad vendor/CPU assumptions do not prove descriptor absence or successful restore. |

## Deterministic verification and remaining limits

Tests use imported fixture bytecode, bounded fake inventories, non-eager Spring contexts and temporary directories.
Script tests replace Java with a fake executable; **they never execute real checkpoint flags**. Coverage includes missing
bytecode/runtime evidence, cleanup ambiguity, Hikari counts and suspension, managed/unknown client metadata, lazy
resources, scheduling forms, precise JDK API boundaries, runtime-setting combinations, and preserved partial files.

Only a separate real test on the exact CRaC JDK/provider, engine, Linux environment, container policy, CPU architecture,
application configuration and dependencies can establish whether a particular image and restored application work.
Simulation, dependency badges, static fixtures and marker files cannot replace that test.
