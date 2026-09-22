# Runtime

## Health

![BootUI Health panel](../images/bootui-health.webp)

The Health panel displays the Actuator health tree, including nested contributors and detail when the application
exposes it.

Unavailable health data is kept separate from unhealthy application state, so missing Actuator infrastructure is never
presented as a healthy status: the panel shows setup guidance instead. When Actuator health is present but reports only
Spring Boot's default indicators, the panel keeps the live statuses visible and suggests adding application or
dependency contributors.

On Quarkus the panel reads SmallRye Health in-process and maps the aggregated liveness and readiness report onto the
same status tree, with each check's reported data as nested details. When `quarkus-smallrye-health` is absent, the
panel stays visible and shows setup guidance. SmallRye has no framework-default contributors, so the "default
indicators only" guidance does not apply there.

## HTTP Sessions

![BootUI HTTP Sessions panel](../images/bootui-http-sessions.webp)

The HTTP Sessions panel lists local embedded Tomcat sessions with their creation time, last access time, idle duration,
and attribute count, highlighting your current session. The panel returns at most 50 sessions; raise
`bootui.http-sessions.max-sessions` if a local application needs a larger bounded view.

Session identifiers are bearer credentials, so the UI receives only an opaque action key and a masked display id, and
every attribute value is masked. `bootui.expose-values=METADATA_ONLY` keeps attribute names and types without values.
`FULL` reveals display ids and stringified values for local troubleshooting, and shows an explicit exposure warning.

Clear and destroy are confirmation-gated and disabled by global or per-panel read-only mode. Clear removes every
attribute from the selected session while keeping it valid; destroy invalidates it. When the application is not running
on embedded Tomcat, the panel shows an unavailable state rather than guessing at container internals.

This panel is not applicable on WebFlux. `HttpSession` is a servlet container API with no reactive equivalent, since
`WebSession` is a different, non-container-managed model.

## Metrics

![BootUI Metrics panel](../images/bootui-metrics.webp)

The Metrics panel browses the Micrometer meters exposed by Actuator. You can search meter names and descriptions and
filter by meter type on the server, inspect descriptions, base units, tags, and available measurements, and render a
live chart for a selected metric and tag combination.

Meter names are returned in 200-row pages, up to 1 000 per request, and a selected meter's tagged samples in 100-row
pages with the same cap. The UI reports the total, matching, and displayed counts, and keeps tag-value choices bounded
to the first 100 sorted values per key with an explicit truncation badge.

::: details Provenance grouping and honest explanations

Meters are grouped by the integration family that registered them: JVM binders, process and system binders, HTTP server
and client instrumentation, datasource pools, caches, messaging clients, resilience libraries, gRPC, and framework
internals, with anything unrecognized filed under **Application / unclassified**. Each group names the contributing
library, how many of its meters the registry documents, the curated families that matched, and the tag keys most of its
meters share. Selecting a group filters the list on the server.

Explanations say where they come from. A meter's own registry description always wins and is marked **Native
description**. When the registry documents nothing, BootUI falls back to a curated, versioned catalogue of well-known
meter families, marked **BootUI catalogue**. A meter with neither is marked **Not documented**.

Classification matches meter names only, never tag values, on exact names or dot-segment prefixes, so an application
meter such as `orders.processed` is never absorbed into a curated family. The report carries the catalogue version, so
an explanation can be traced back to the catalogue that produced it.

:::

Quarkus has no Actuator, so the panel reads the live composite `MeterRegistry` directly when the application adds a
`quarkus-micrometer` registry, such as `quarkus-micrometer-registry-prometheus`. Without one it renders as unavailable
while staying in the sidebar. On both stacks, meters describing BootUI's own `/bootui/**` traffic are hidden.

## Live Memory

![BootUI Live Memory panel](../images/bootui-live-memory.webp)

The Live Memory panel summarizes current heap and non-heap usage and memory pool utilization, so you can spot heap
pressure, non-heap growth, and pool-level saturation. Sizing controls live in the JVM Tuning panel instead.

## JVM Tuning

![BootUI JVM Tuning panel](../images/bootui-jvm-tuning.webp)

The JVM Tuning panel reviews the current JVM input arguments, explains `spring.threads.virtual.enabled=true`, and runs
sizing calculators for dedicated hosts and for Kubernetes. It detects whether Spring virtual threads are enabled and
shows an information or warning bubble, but does not infer a smaller native-stack budget from that signal or add the
property to generated snippets.

The bare-metal calculator partitions a target process memory budget into heap, metaspace, code cache, direct memory,
thread stacks, and headroom, then turns that plan into copyable JVM options with fixed `-Xms` and `-Xmx`. It keeps the
current collector and omits workload-specific GC, direct-memory-cap, pre-touch, compact-header, string-deduplication,
and out-of-memory policy flags.

The Kubernetes calculator uses `-XX:MaxRAMPercentage`, `-XX:MinRAMPercentage`, and `-XX:InitialRAMPercentage` in
`JAVA_TOOL_OPTIONS` rather than fixed heap sizes. It sets equal memory request and limit values by default but labels
Pod QoS `Depends on CPU`, because Guaranteed QoS also requires matching non-zero CPU resources on every container. You
can instead attempt a lower, snapshot-based Burstable request. A health-probes toggle initializes from the current
framework capability and adds framework-default startup, readiness, and liveness paths on the named container port
`http`; verify those paths and that port name against your deployment configuration.

The full model — the behavior inventory, evidence ledger, cross-platform details, and limitations — is recorded in
[`JVM-TUNING-CHECKS.md`](https://github.com/jdubois/boot-ui/blob/main/docs/JVM-TUNING-CHECKS.md).

::: warning Not available in GraalVM native images
Heap, GC, and flag tuning does not apply to a native executable, so the panel is hidden when the application is
detected to be running as one.
:::

## Heap Dump

![BootUI Heap Dump panel](../images/bootui-heap-dump.webp)

The Heap Dump panel captures local heap dumps on demand and analyzes them through a value-free class histogram, for
investigating suspected leaks or unexpected retention. A confirmed capture triggers a full GC and writes an `.hprof`
file under the configured output directory. The panel then shows live heap usage, the top retaining classes by instance
count and shallow size, and the captured dumps with retention-based eviction.

Capture, live analysis, and delete share one single-flight admission, because they operate on the same directory,
histogram, and status. A conflicting action receives the canonical `409` busy response naming both the requested and
the active operation. Report reads remain available throughout.

::: warning Treat every dump as sensitive
Heap dumps can contain plaintext secrets, credentials, and personal data. The panel only ever summarizes class names
and sizes, never object values. Capture, analyze, and delete are mutating `POST` requests blocked in read-only mode,
and downloading the raw `.hprof` file is disabled unless explicitly enabled. Use this on a local JVM only.
:::

## Threads

![BootUI Threads panel](../images/bootui-threads.webp)

The Threads panel shows a live snapshot of the JVM's threads. It reads them in-process through `ThreadMXBean`, so the
application does not need to expose the Actuator `threaddump` endpoint.

The panel presents a state summary header with counts per thread state, a flag when a deadlock is detected, and
virtual-thread context on a JDK that supports it. The list filters by name and state on the server with paging, and
each row expands to its stack trace.

Stack frames and thread names can incidentally contain sensitive values, so the panel reuses BootUI's masking and
exposure model: names that look like secrets are masked, and stack traces are omitted entirely under metadata-only
exposure. The raw text thread dump is a confirmation-gated `POST` download, blocked when the panel is read-only. When
thread information cannot be read, the panel shows an explained unavailable state rather than disappearing.

## Startup Timeline

![BootUI Startup Timeline panel](../images/bootui-startup-timeline.webp)

The Startup Timeline panel visualizes Spring Boot startup steps from Actuator startup data, which is how you find
expensive startup phases and slow bean initialization.

When BootUI is active, the starter installs a `BufferingApplicationStartup` so the panel has data with no setup on your
side. Disable that with `bootui.startup.enabled=false`, or tune the retained step count with `bootui.startup.capacity`.
If startup data is still unavailable, the panel shows an empty state rather than failing.

## GraalVM

![BootUI GraalVM panel](../images/bootui-graalvm.webp)

The GraalVM panel surveys the application for
[native-image](https://www.graalvm.org/latest/reference-manual/native-image/) readiness. On demand it imports the
application's own classes, bounded to the detected base packages, and runs 27 curated checks: 22 GraalVM checks and 5
Spring AOT checks. After a scan you can filter the concerns by severity, category, or free text without rerunning it.

The checks look for reflection, dynamic class loading, deep reflection, dynamic proxies, runtime resource loading,
resource bundles, serialization, native access, runtime class generation, classpath scanning, `MethodHandles`, security
providers, JMX, FFM, and Spring AOT boundaries. They are heuristic review aids that complement the GraalVM tracing
agent and an actual native build rather than replacing them. See
[GraalVM readiness checks](../GRAALVM-READINESS-CHECKS.md) for the full catalogue.

### Dependency reachability metadata

With **Include dependencies** on, which is the default, the panel also reports which third-party libraries already ship
unified or canonical legacy reachability metadata under `META-INF/native-image/`; arbitrary JSON is ignored. For
libraries that do not, it consults Oracle's
[reachability metadata repository](https://github.com/oracle/graalvm-reachability-metadata) and reports the detected
version as `covered`, `partial` when the repository has metadata for a different version, or `none`, with links to the
matching entry and metadata file. Matching prefers exact tested versions and then honors the repository's `default-for`
regular expressions.

That lookup is the panel's only outbound network call. It is user-initiated and time-bounded, can be aborted from the
panel, and is disabled with `bootui.graalvm.repository-lookup-enabled=false`.

### Generated project assets

The same scan generates a downloadable `reachability-metadata.json` scaffold in the modern unified schema, with
`condition.typeReached` guards, seeded with reflection and serialization candidates and the standard configuration
resource globs. Alongside it, the panel generates a tailored multi-stage `Dockerfile-native`.

Both can be downloaded, or written straight into the project with **Write into project** when BootUI detects an exploded
build rather than a packaged jar. The accordion's top drawer, **All files**, generates and writes both in one step and
reports each file's outcome. Writes are fail-closed: confined under the project tree, and never overwriting a
`reachability-metadata.json` or `Dockerfile-native` that BootUI did not generate.

::: details Where the scaffold is written

**Write into project** writes the metadata scaffold to
`src/main/resources/META-INF/native-image/<groupId>/<artifactId>-additional-hints/reachability-metadata.json`, resolving
coordinates from `build-info.properties` or the project `pom.xml` and falling back to
`bootui-generated/additional-hints`. The non-clashing suffix follows Spring Boot 4.1 guidance, because Spring AOT writes
its generated hints to `<groupId>/<artifactId>/`.

:::

::: details How the Dockerfile-native is built

BootUI detects the build system — Maven or Gradle, with or without a wrapper — and uses the matching native build
command, such as `./mvnw -Pnative -DskipTests clean native:compile` or `./gradlew nativeCompile`. When the project has
no wrapper, the build stage installs a pinned Maven or Gradle release.

The resulting executable, named after the resolved `artifactId`, is packaged into a distroless runtime image
(`gcr.io/distroless/base-debian12:nonroot`) that runs as a non-root user and carries no shell, curl, perl, or tar. The
binary requests mostly static linking with `--static-nolibc`, but some workloads still need `libstdc++`, `libgcc`, or
dynamically loaded native libraries. Inspect the executable's dependencies and test it in the exact runtime image
rather than assuming the scaffold is complete.

:::

This panel is hidden when the application already runs as a native image, since the advisor exists to help you *prepare*
for compilation. It is also not applicable on Quarkus, which compiles native images itself and generates its own
reachability metadata during build-time augmentation.

## CRaC

![BootUI CRaC panel](../images/bootui-crac.webp)

The CRaC panel reviews the application's
[Coordinated Restore at Checkpoint](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
readiness, combining live runtime status with a heuristic advisor. On demand the advisor imports the application's own
classes, bounded to the detected base packages, and runs 17 curated `CRaC-*` checks, which you can then filter in place
by severity, category, or free text. They complement an actual checkpoint and restore run on a CRaC-enabled JDK rather
than replacing it. See [CRaC readiness checks](../CRAC-READINESS-CHECKS.md) for the catalogue.

::: details What the runtime-status card reports

The card is always read-only. It shows whether the `org.crac` API is on the classpath and whether an implementation is
detected, separately from whether the selected engine and host can create a real image: simulation, marker classes, and
checkpoint arguments are not proof of operational readiness.

It also shows the exact Spring checkpoint-on-refresh setting and bounded, exposure-aware CRaC JVM arguments. Resource
caveats reuse the last explicit scan rather than discovering resource beans on page load. A remaining startup property
does not mean its one-shot checkpoint phase is still pending, and `spring.context.exit=onRefresh` halts the JVM before
lifecycle start, so it is not a safe in-process cleanup or restore test.

:::

::: details What the checks cover

The checks review direct acquisition separately from resource liveness. Compatible cleanup calls and equal Hikari
lifecycle counts do not prove field-specific ownership or pool pairing. Known Spring-managed remote factories receive
credit within their observed lifecycle boundary, while caches, background work, fixed-rate scheduling, retained time and
configuration, provider-specific randomness, secrets, and TLS state remain conditional review prompts.

Independent runtime checks still run when application bytecode is unavailable. Missing observations and failed
collection are explicit, never clean results, and lazy resources are not initialized for inspection.

:::

The panel also generates container scaffolds: a multi-stage `Dockerfile-crac` that builds with a plain JDK and runs on a
CRaC-enabled BellSoft Liberica JDK, plus the `checkpoint-and-run.sh` entrypoint it relies on, which takes a checkpoint
on the first start through `spring.context.checkpoint=onRefresh` and restores it afterwards. The build command matches
the detected build system. Each file can be downloaded, or written into the project root from an exploded build, through
the same fail-closed writer the GraalVM panel uses.

::: warning The generated run command is a local CRIU recipe
It includes CRIU's `CHECKPOINT_RESTORE`, `SYS_PTRACE`, `SYS_ADMIN`, and `NET_ADMIN` capabilities, which is a
privileged, local recipe rather than a universal requirement for every CRaC engine. The on-refresh checkpoint precedes
lifecycle startup, so it is not a fully warmed application. Incomplete checkpoint directories are preserved and produce
a clear failure, and `inventory.img` identifies only a candidate restore, not a verified image. No generated fixture
replaces a real checkpoint and restore test on the exact deployment environment.
:::

This panel is hidden in GraalVM native images, since CRaC is a JVM-only feature. It is also not applicable on Quarkus,
whose advisor targets the Spring Boot startup model, while Quarkus achieves fast startup through build-time
augmentation and native images.
