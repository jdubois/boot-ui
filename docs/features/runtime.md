# Runtime

## Health

![BootUI Health panel](../images/bootui-health.webp)

The Health panel displays the Actuator health tree, including nested contributors and detailed status information when
the host app exposes it. It keeps unavailable health data separate from unhealthy application state so missing Actuator
infrastructure is clear, and shows setup guidance instead of a healthy-looking status when the Actuator health endpoint
is not available. When Actuator health is present but only Spring Boot's default indicators are reported, it keeps the
live statuses visible and shows guidance for adding application or dependency health contributors.

The panel is identical on Quarkus, served over SmallRye Health (the MicroProfile Health implementation Quarkus uses): it
reads the aggregated liveness and readiness report in-process and maps each check onto the same neutral status tree, with
every check's reported data shown as nested details. When `quarkus-smallrye-health` is absent the panel stays visible and
shows setup guidance for adding it. SmallRye has no fixed framework-default contributors — every check is
application-authored — so the Spring-only "default indicators only" guidance does not apply on Quarkus.

## HTTP Sessions

![BootUI HTTP Sessions panel](../images/bootui-http-sessions.webp)

The HTTP Sessions panel lists local embedded Tomcat sessions with creation time, last access time, idle duration,
attribute count, and current-session highlighting. Session identifiers are treated as bearer credentials: by default the
UI only receives an opaque action key and a masked display id, and every attribute value is masked. Setting
`bootui.expose-values=FULL` reveals display ids and stringified attribute values for local troubleshooting and shows an
explicit FULL exposure warning, while `METADATA_ONLY` keeps attribute names and types without values. The panel returns
at most 50 sessions by default; raise `bootui.http-sessions.max-sessions` if a local app needs a larger bounded view.

Clear and destroy actions are confirmation-gated and disabled by global or per-panel read-only mode. Clear removes all
attributes from the selected session while keeping it valid; destroy invalidates the selected session. When the app is
not running on embedded Tomcat, the panel shows an unavailable state instead of guessing at container internals.

This panel is **deliberately not applicable on Spring Boot WebFlux**: HTTP Sessions are the servlet container's
`HttpSession` API, which has no reactive equivalent (`WebSession` is a different, non-container-managed model), so the
panel reports an honest "not applicable" reason rather than implying a port is forthcoming — the same treatment
GraalVM/CRaC get on Quarkus.

## Metrics

![BootUI Metrics panel](../images/bootui-metrics.webp)

The Metrics panel browses Micrometer meters exposed by Actuator. You can search meter names/descriptions and filter by
meter type on the server, inspect descriptions, base units, tags and available measurements, and render a local live chart
for a selected metric/tag combination. Meter names are returned in deterministic 200-row pages (up to 1,000 per request),
while a selected meter's concrete tagged samples use 100-row pages (also capped at 1,000). The UI reports the total,
matching and displayed counts, provides load-more and sample Previous/Next controls, and keeps tag-value choices bounded to
the first 100 sorted values per key with an explicit truncation badge.

::: details Provenance grouping and honest explanations

Meters are also grouped by **provenance**: the integration family that registered them (JVM binders, process and system
binders, HTTP server and client instrumentation, datasource pools, caches, messaging clients, resilience libraries, gRPC,
framework internals) with anything unrecognized filed under "Application / unclassified". Each group names the library
that contributes it, how many of its meters the registry itself documents, the curated families that matched, and the tag
keys most of its meters share. Selecting a group filters the meter list on the server; two additional filters narrow by
provenance (known integration vs application/unclassified) and by explanation source.

Explanations are honest about where they come from. A meter's own registry description always wins and is marked
**Native description**. When the registry documents nothing, BootUI falls back to a curated, versioned catalogue of
well-known meter families and marks the text **BootUI catalogue**. A meter that has neither is marked **Not documented**
and BootUI says so instead of guessing. Classification matches meter names only — never tag values — on exact names or
dot-segment prefixes, so an application meter such as `orders.processed` is never absorbed into a curated family. The
report carries the catalogue version so an explanation can be traced back to the catalogue that produced it.

:::

The panel is identical on Quarkus, served over Micrometer directly (Quarkus has no Actuator): it reads the live composite
`MeterRegistry` when the application adds a `quarkus-micrometer` registry (for example
`quarkus-micrometer-registry-prometheus`), and otherwise renders as unavailable while staying in the sidebar. As on Spring
Boot, meters describing BootUI's own `/bootui/**` traffic are hidden so the console never reports on itself.

## Live Memory

![BootUI Live Memory panel](../images/bootui-live-memory.webp)

The Live Memory panel summarizes current live JVM heap and non-heap usage plus memory pool utilization. It stays focused on
the running process metrics so you can spot high heap pressure, non-heap growth, and pool-level saturation without the
JVM sizing controls mixed into the view.

## JVM Tuning

![BootUI JVM Tuning panel](../images/bootui-jvm-tuning.webp)

The JVM Tuning panel uses the same live JVM context to review current JVM input arguments, explain
`spring.threads.virtual.enabled=true`, and run JVM sizing calculators for both dedicated hosts and Kubernetes. It detects
whether Spring virtual threads are enabled in the current application and shows an information or warning bubble, but
does not infer a smaller native-stack budget from that signal or add the Spring property to generated snippets.

The bare-metal calculator partitions a target JVM process memory budget into heap, metaspace, code cache, direct memory,
thread stacks, and headroom, then turns that plan into copyable JVM options with fixed `-Xms` and `-Xmx` values. It keeps
the current collector and omits workload-specific GC, direct-memory-cap, pre-touch, compact-header, string-deduplication,
and out-of-memory policy flags.

The Kubernetes calculator sets equal memory request and limit values by default but labels Pod QoS `Depends on CPU`,
because Kubernetes also requires matching non-zero CPU resources on every container for Guaranteed QoS. Operators can
instead attempt a lower, snapshot-based Burstable request. `JAVA_TOOL_OPTIONS` uses `-XX:MaxRAMPercentage`,
`-XX:MinRAMPercentage`, and `-XX:InitialRAMPercentage` instead of fixed heap sizes. A health-probes toggle initializes
from the current framework capability and adds framework-default startup/readiness/liveness paths on the named container
port `http`; those paths and the port name must be verified against deployment configuration.

The full model is recorded in
[`JVM-TUNING-CHECKS.md`](https://github.com/jdubois/boot-ui/blob/main/docs/JVM-TUNING-CHECKS.md) on GitHub: the behavior
inventory, evidence ledger, cross-platform details, and model limitations.

> **Not available in GraalVM native images.** JVM heap, GC, and flag tuning does not apply to a native executable;
> the panel is automatically hidden when the application is detected to be running as a native image.

## Heap Dump

![BootUI Heap Dump panel](../images/bootui-heap-dump.webp)

The Heap Dump panel captures local JVM heap dumps on demand and analyzes them through a value-free class histogram, so
you can investigate suspected memory leaks or unexpected retention during the local development loop. Capture and analyze
actions run an explicit, confirmed request that triggers a full GC and writes an `.hprof` file under the configured
output directory; the panel then shows live heap usage, the top retaining classes by instance count and shallow size,
and the list of captured dumps with retention-based eviction.

Capture, live analysis, and delete share one single-flight admission because they operate on the same dump directory,
histogram, and status. A conflicting action receives the canonical `409` busy response naming both the requested and
active operation; passive report reads remain available throughout.

Heap dumps can contain plaintext secrets, credentials, and personal data, so the panel is safe by default. It only
summarizes class names and sizes, never object values. All capture/analyze/delete operations are mutating `POST` requests
that are blocked when the panel is read-only, and downloading the raw `.hprof` file is disabled unless explicitly enabled
via configuration. Use it on a local JVM only, and treat any exported dump as sensitive.

## Threads

![BootUI Threads panel](../images/bootui-threads.webp)

The Threads panel shows a live snapshot of the JVM's threads so you can answer "what is the application doing right
now?" during local development. It reads thread information in-process through `ThreadMXBean` rather than requiring the
host application to expose the Actuator `threaddump` endpoint, and presents a state summary header (counts per thread
state), a flag when a deadlock is detected, and virtual-thread context when running on a JDK that supports it. The thread
list supports server-side filtering by name and by state with paging, and each row can expand to show its stack trace.

Stack frames and thread names can incidentally contain sensitive values, so the panel reuses BootUI's masking and
value-exposure model: names are masked when they look like secrets, and stack traces are omitted entirely under
metadata-only exposure. The raw text thread dump is offered as a confirmation-gated `POST` download that is blocked when
the panel is read-only. The panel stays loopback-only and fails closed, showing an explained unavailable state instead
of disappearing when thread information cannot be read.

## Startup Timeline

![BootUI Startup Timeline panel](../images/bootui-startup-timeline.webp)

The Startup Timeline panel visualizes Spring Boot startup steps from Actuator startup data. It helps identify expensive
startup phases, slow bean initialization, and the overall application startup shape. When BootUI is active, the starter
installs a `BufferingApplicationStartup` by default so the panel has data without host-app setup; disable that with
`bootui.startup.enabled=false` or tune the retained step count with `bootui.startup.capacity`. If startup data is still
unavailable, the panel shows an empty state instead of failing.

## GraalVM

![BootUI GraalVM panel](../images/bootui-graalvm.webp)

The GraalVM panel surveys the host application for [GraalVM native-image](https://www.graalvm.org/latest/reference-manual/native-image/)
readiness. On demand it imports the application's own classes (bounded to the detected base package(s)) and runs **27
curated checks (22 GraalVM and 5 Spring AOT)** for constructs that native-image or Spring AOT cannot resolve reliably.
After a scan, the concerns list can be filtered in place by severity, category, or free-text search without rerunning it.
The checks and generated metadata are heuristic review aids that complement, but do not replace, the GraalVM tracing
agent and an actual native build. See [GRAALVM-READINESS-CHECKS.md](../GRAALVM-READINESS-CHECKS.md) for the full catalogue
of checks and what each one inspects.

::: details What the checks cover

The checks look for reflection, dynamic class loading, deep reflection, dynamic proxies, runtime resource loading,
resource bundles, serialization, native access, runtime class generation, classpath scanning, MethodHandles, security
providers, JMX, FFM, and Spring AOT boundaries.

:::

### Dependency reachability metadata

With the _Include dependencies_ toggle on (the default), the panel also surveys the classpath to report which
third-party libraries already ship unified or canonical legacy reachability metadata under `META-INF/native-image/`
(arbitrary JSON is ignored). For libraries that do not, it looks up Oracle's
[GraalVM reachability metadata repository](https://github.com/oracle/graalvm-reachability-metadata) to show whether the
detected dependency version is `covered`, only `partial` (the repository has metadata for a different version), or has
`none`, with links to the matching repository entry and metadata file.

Repository matching prefers exact tested versions and then honors the repository's `default-for` Java regular
expressions. That repository lookup is the panel's only outbound network call; it is user-initiated, time-bounded, and
can be disabled with `bootui.graalvm.repository-lookup-enabled=false`. Long dependency lookups report progress and can be
aborted from the panel.

### Generated project assets

From the same scan the panel generates a downloadable `reachability-metadata.json` scaffold (modern unified schema, with
`condition.typeReached` guards) seeded with reflection/serialization candidates and the standard configuration resource
globs. Alongside it the panel generates a tailored, multi-stage **`Dockerfile-native`** that builds a GraalVM native
image of the host application. Both artifacts can be downloaded, or — when BootUI detects an exploded build (for example
`mvn spring-boot:run` or an IDE) rather than a packaged jar — written directly into the project via a **Write into
project** action. A three-drawer accordion's default top drawer is an **All files** action that generates and writes both
artifacts in a single step, reporting each file's outcome. Writes are fail-closed: confined under the project tree and
never overwriting a `reachability-metadata.json` or `Dockerfile-native` that BootUI did not generate.

::: details Where the scaffold is written

The **Write into project** action writes the metadata scaffold to
`src/main/resources/META-INF/native-image/<groupId>/<artifactId>-additional-hints/reachability-metadata.json` (resolving
coordinates from `build-info.properties` or the project `pom.xml`, falling back to `bootui-generated/additional-hints`).
The non-clashing suffix follows Spring Boot 4.1 guidance because Spring AOT writes generated hints to
`<groupId>/<artifactId>/`. The install is confined under `src/main/resources` and never overwrites a file BootUI did not
generate.

:::

::: details How the Dockerfile-native is built

It detects the project's build system — Maven or Gradle, with or without the wrapper — and uses the matching native build
command (`./mvnw`/`mvn -Pnative -DskipTests clean native:compile`, or `./gradlew`/`gradle nativeCompile`). It then
packages the resulting executable — named after the resolved `artifactId` — into a minimal, distroless runtime image
(`gcr.io/distroless/base-debian12:nonroot`). That image runs as a non-root user and carries no shell/curl/perl/tar,
keeping the OS-package CVE surface near zero; the binary is built *mostly static* so it needs only glibc, and the build
stage installs a known, pinned Maven/Gradle release when the project has no wrapper.

:::

> **Not available when already running as a GraalVM native image.** The readiness advisor scans compiled `.class` files
> to help you *prepare* an application for native-image compilation; once the application is already running as a native
> executable the advisor has no purpose, and the panel is automatically hidden.

This panel is Spring Boot only and is **deliberately not applicable on Quarkus**. Quarkus compiles native images itself
(`quarkus build -Dnative` / the native build profile) and generates its own reachability metadata at build time through
its build-time augmentation, so a Spring-oriented native-readiness advisor — and the generic `reachability-metadata.json`
and `Dockerfile-native` it scaffolds — would not match how Quarkus produces native images. The panel therefore reports an
honest "not applicable on Quarkus" reason rather than implying a port is forthcoming.

## CRaC

![BootUI CRaC panel](../images/bootui-crac.webp)

The CRaC panel reviews the host application's [Coordinated Restore at Checkpoint](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
readiness, combining live runtime status with a heuristic readiness advisor. On demand the readiness advisor imports the
application's own classes (bounded to the detected base package(s)) and runs 17 curated `CRaC-*` checks. After a scan, the
concerns list can be filtered in place by severity, category, or free-text search without rerunning it. The checks are
heuristic review aids that complement, but do not replace, an actual checkpoint/restore run on a CRaC-enabled JDK. See
[CRAC-READINESS-CHECKS.md](../CRAC-READINESS-CHECKS.md) for the full catalogue of checks and what each one inspects.

::: details What the runtime-status card reports

The runtime-status card (always read-only) reports several signals. It shows whether the `org.crac` API is on the
classpath and whether the running JVM is a CRaC-capable JDK, such as Azul Zulu CRaC or BellSoft Liberica, detected via
the real CRaC implementation rather than the no-op shim. It also shows whether `spring.context.checkpoint=onRefresh` is
set, and any `-XX:CRaCCheckpointTo` / `-XX:CRaCRestoreFrom` JVM arguments (read from the same `RuntimeMXBean` input
arguments the JVM Tuning panel uses).

:::

::: details What the checks cover

The checks review direct resource acquisition separately from resource liveness, require observable cleanup before
suppressing resource fields, distinguish Spring Boot's Hikari lifecycle and pool-suspension evidence from other remote
clients, limit cache findings to known local managers, and flag direct background work plus Spring thread-per-task
executors with incomplete lifecycle support. They also cover Spring's documented fixed-rate catch-up behavior, retained
startup time/configuration, provider-specific Random/SecureRandom behavior, bounded secret and TLS-state fields, and a
missing `org.crac:crac` dependency. Runtime observations never initialize a lazy pool, and inventory failures remain
visible as scan warnings.

:::

The panel also generates ready-to-use container assets for the host application: a multi-stage `Dockerfile-crac` that
builds with a plain JDK and runs on a CRaC-enabled BellSoft Liberica JDK, plus the `checkpoint-and-run.sh` entrypoint it
relies on (it takes a checkpoint on the first start via `spring.context.checkpoint=onRefresh` and restores it on later
starts). The build command is tailored to the detected build system (Maven or Gradle, with or without the wrapper). Each
file can be downloaded, and — when the application is running from an exploded build (for example `mvn spring-boot:run`
or an IDE) rather than a packaged jar — written directly into the project root. Writes are fail-closed and never
overwrite a file BootUI did not generate. This shares the same source-tree writer the GraalVM panel uses for its
`Dockerfile-native`. The generated local run command includes CRIU's `CHECKPOINT_RESTORE`, `SYS_PTRACE`, `SYS_ADMIN`, and
`NET_ADMIN` capabilities; the panel does not claim that string generation can replace a real Linux checkpoint/restore
test.
builds with a plain JDK and runs on a CRaC-enabled BellSoft Liberica JDK, plus the `checkpoint-and-run.sh` entrypoint it
relies on (it takes a checkpoint on the first start via `spring.context.checkpoint=onRefresh` and restores it on later
starts). The build command is tailored to the detected build system (Maven or Gradle, with or without the wrapper). Each
file can be downloaded, and — when the application is running from an exploded build (for example `mvn spring-boot:run`
or an IDE) rather than a packaged jar — written directly into the project root. Writes are fail-closed and never
overwrite a file BootUI did not generate. This shares the same source-tree writer the GraalVM panel uses for its
`Dockerfile-native`. The generated local run command includes CRIU's `CHECKPOINT_RESTORE`, `SYS_PTRACE`, `SYS_ADMIN`, and
`NET_ADMIN` capabilities; the panel does not claim that string generation can replace a real Linux checkpoint/restore
test.

> **Not available in GraalVM native images.** CRaC (Coordinated Restore at Checkpoint) is a JVM-only feature and is
> mutually exclusive with native executables; the panel is automatically hidden when the application is detected to be
> running as a native image.

This panel is Spring Boot only and is **deliberately not applicable on Quarkus**. The advisor and its generated assets
target the Spring Boot startup model (`spring.context.checkpoint=onRefresh` and Spring's checkpoint/restore lifecycle),
whereas Quarkus achieves fast startup through build-time augmentation and native images rather than CRaC checkpoint/
restore. The panel therefore reports an honest "not applicable on Quarkus" reason rather than implying a port is
forthcoming.
