# JVM Tuning Advisor Model

The JVM Tuning panel is a deterministic memory-budget calculator, not an automatic performance tuner. It turns live,
local JVM observations and operator inputs into a reviewable starting point. It does not modify the running process,
select a garbage collector, inspect application traffic, or claim that a snapshot predicts peak production demand.

This document records the evidence and decisions behind the shared engine used by Spring MVC, Spring WebFlux, and
Quarkus. The compatibility baseline is Java 17 through Java 26, Spring Boot 4.1, and Quarkus 3.33 LTS.

The full audit behind [#955](https://github.com/jdubois/boot-ui/issues/955) retains the existing partition and
operator policies, corrects numerical/output boundaries, and separates generated requests from effective JVM memory.
Observed-metaspace sizing now bounds the class model; Spring health capability honors native endpoint access settings
without treating development-time Actuator as proof of the deployed artifact's dependencies.
Sources were independently revalidated against JDK 17–26 GA source, Spring Boot 4.1.1,
and Quarkus 3.33; this is not an execution matrix for every JVM vendor, update, collector, and architecture.

## Evidence ledger

| Source | Evidence used |
| ------ | ------------- |
| [Spring Boot 4.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html) | Spring Boot 4.1 requires Java 17 and supports Java through 26, defining the generated-option compatibility range. |
| [Oracle JDK 17 `java` launcher](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html) and [JDK 26 launcher](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html) | Defines `-Xms`, `-Xmx`, `-Xss`, container support, RAM-percentage, metaspace, code-cache, GC, pre-touch, and OOM options across the supported range. `MinRAMPercentage` governs maximum-heap ergonomics for small heaps, so it must accompany `MaxRAMPercentage`. `UseContainerSupport` is Linux-only and already defaults to true where supported, so a portable snippet must not force it. |
| [HotSpot JDK 17 heap validation](https://github.com/openjdk/jdk/blob/jdk-17-ga/src/hotspot/share/gc/shared/gcArguments.cpp#L130-L150) and [JDK 26 heap validation](https://github.com/openjdk/jdk/blob/jdk-26-ga/src/hotspot/share/gc/shared/gcArguments.cpp#L113-L133) | Reject maximum heaps below 2 MiB before rounding up to collector-specific alignment. The launcher manuals say “greater than” 2 MB; the inclusive 2 MiB bound used here follows inspected HotSpot source and is not a startup guarantee. |
| [JDK 26 G1 alignment](https://github.com/openjdk/jdk/blob/jdk-26-ga/src/hotspot/share/gc/g1/g1Arguments.cpp#L43-L61) and [card-table constraint](https://github.com/openjdk/jdk/blob/jdk-26-ga/src/hotspot/share/gc/shared/cardTable.cpp#L221-L224) | Effective heap can exceed a floored option literal. Alignment depends on collector, region size, OS/card/page settings; 8 MiB is not a universal alignment. |
| [JDK 26 argument ergonomics](https://github.com/openjdk/jdk/blob/jdk-26-ga/src/hotspot/share/runtime/arguments.cpp) | Percentage sizing depends on JVM-visible RAM and existing flags. `MaxRAM` is deprecated in JDK 26 and is a sizing input, not a process-memory cap. JDK 26 changes the default `InitialRAMPercentage` to 0.0; this calculator supplies an explicit value. |
| [Paketo Java memory calculator reference](https://paketo.io/docs/reference/java-reference/#memory-calculator), [pinned `libjvm` calculator](https://github.com/paketo-buildpacks/libjvm/blob/83e37180564a488c514ce039217dde75b8fcfa39/calc/calculator.go), and [launch helper](https://github.com/paketo-buildpacks/libjvm/blob/83e37180564a488c514ce039217dde75b8fcfa39/helper/memory_calculator.go) | Supplies the partition formula and the 10 MiB direct-memory, 240 MiB code-cache, 1 MiB stack, and 250-thread modeling defaults. Paketo's headroom default is 0%, not BootUI's 10%; upstream calculation does not establish portable minimum heaps or comprehensive overflow validation. |
| [Paketo launch-time memory flags](https://paketo.io/docs/reference/java-reference/#memory-calculator), [Quarkus quickstart Dockerfile](https://github.com/quarkusio/quarkus-quickstarts/blob/main/getting-started/src/main/docker/Dockerfile.jvm), and [Red Hat UBI OpenJDK image configuration](https://rh-openjdk.github.io/redhat-openjdk-containers/ubi9-openjdk-containers-1.24/ubi9-openjdk-21.html) | Paketo appends calculated flags including `-Xmx` to `JAVA_TOOL_OPTIONS`. The Quarkus quickstart uses UBI OpenJDK's `run-java.sh`, which can generate heap flags; exact flags and controls depend on the image version. Neither launcher is implied by the application framework. |
| [`ClassLoadingMXBean`](https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/ClassLoadingMXBean.html) and [`BufferPoolMXBean`](https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/BufferPoolMXBean.html) | Provide observable current loaded-class count and estimated direct-buffer memory usage. Both are snapshots, not peak forecasts. |
| [`MemoryMXBean.getHeapMemoryUsage()`](https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/MemoryMXBean.html#getHeapMemoryUsage()) and [`MemoryUsage.getUsed()`](https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/MemoryUsage.html#getUsed()) | Supply the observed heap-used lower bound. Reading usage does not request GC; this is not a live-after-GC measurement and can include reclaimable objects. |
| [`MemoryMXBean.getNonHeapMemoryUsage()`](https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/MemoryMXBean.html#getNonHeapMemoryUsage()) | Used and committed aggregate all non-heap pools. Raw pool accounting can overlap; BootUI retains this conservative aggregate rather than deduplicating it. |
| [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) | Virtual threads are not tied one-to-one to OS threads and are mounted on carrier platform threads. Their presence does not provide an observable, deterministic replacement for a platform-thread native-stack budget. |
| [JEP 491](https://openjdk.org/jeps/491) and [`ThreadMXBean`](https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/ThreadMXBean.html) | ThreadMXBean observes platform threads, not virtual threads. Synchronized-related virtual-thread pinning changed in JDK 24; older pinning guidance is version-dependent. |
| [Oracle GC ergonomics guidance](https://docs.oracle.com/en/java/javase/17/gctuning/ergonomics.html) | GC sizing and pause/throughput choices are competing workload goals; heap size alone is not sufficient evidence for changing collectors or enabling pre-touch. |
| [JEP 439](https://openjdk.org/jeps/439), [JEP 474](https://openjdk.org/jeps/474), and [JEP 490](https://openjdk.org/jeps/490) | Generational ZGC availability and defaults changed after Java 17; JDK 24 removed non-generational mode and obsoleted `ZGenerational`. Emitting that flag is not portable across the supported range. |
| [JEP 450](https://openjdk.org/jeps/450) and [JEP 519](https://openjdk.org/jeps/519) | Compact headers progressed from an experimental to a product feature, not a universal default. That evolution does not establish workload suitability or availability across Java 17–26. |
| [JDK 17 class metadata](https://docs.oracle.com/en/java/javase/17/gctuning/other-considerations.html), [JDK 26 class metadata](https://docs.oracle.com/en/java/javase/26/gctuning/other-considerations.html), and HotSpot memory pools ([17 GA](https://github.com/openjdk/jdk/blob/jdk-17-ga/src/hotspot/share/services/memoryPool.cpp), [26 GA](https://github.com/openjdk/jdk/blob/jdk-26-ga/src/hotspot/share/services/memoryPool.cpp)) | `MaxMetaspaceSize` covers combined committed metadata; the named Metaspace pool reports combined statistics and already includes compressed class metadata. Adding the Compressed Class Space pool again double-counts it, and its reserved address space is not committed RAM. |
| [Kubernetes resource management](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/) and [Pod QoS classes](https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/) | Memory limits are hard OOM boundaries; requests drive scheduling. Guaranteed QoS requires equal, non-zero CPU and memory requests and limits for every container, not memory equality alone. |
| [Linux cgroup v2 memory controller](https://docs.kernel.org/admin-guide/cgroup-v2.html#memory-interface-files) | `memory.current` is the current total memory attributed to a cgroup and `memory.max` is its hard limit. |
| [Oracle Native Memory Tracking](https://docs.oracle.com/en/java/javase/17/vm/native-memory-tracking.html) | NMT is off by default, requires `jcmd` output to use, and tracks JVM/HotSpot memory rather than all user-native allocations. Detecting its startup flag alone is not stronger sizing evidence. |
| [Kubernetes probe configuration](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#use-a-named-port) | HTTP probes may refer to a named container port, avoiding a framework-specific hard-coded port number. |
| [Spring Boot 4.1.1 health documentation](https://github.com/spring-projects/spring-boot/blob/v4.1.1/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/actuator/endpoints.adoc), [probe autoconfiguration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-health/src/main/java/org/springframework/boot/health/autoconfigure/actuate/endpoint/AvailabilityProbesAutoConfiguration.java), and [Quarkus 3.33 SmallRye Health](https://quarkus.io/version/3.33/guides/smallrye-health) | Boot 4.1 defaults probe groups on when applicable; older Kubernetes-only enablement guidance is not the baseline. Dependency, exposure, group and routing configuration still matter. Quarkus management-interface exposure is build-time configurable. |
| [Spring Boot endpoint access resolver](https://github.com/spring-projects/spring-boot/blob/v4.1.2/module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/PropertiesEndpointAccessResolver.java) | Endpoint-specific access overrides the global default; the maximum-permitted cap applies last. Legacy enabled settings remain aliases and conflict with access settings at the same level. Health reads require at least read-only access. |

## Memory model

The calculator keeps the Paketo-style partition:

```text
heap = total process budget
     - operator-selected headroom
     - modeled direct memory
     - modeled metaspace
     - reserved code cache
     - (platform-thread budget × stack size)
```

The current inputs and bounds are:

| Input or region | Current behavior |
| --------------- | ---------------- |
| Total process budget | Operator input clamped to 128 MiB–64 GiB before byte conversion. A detected limit is rounded down to whole MiB before applying the same bounds; its original byte value remains in the report. The initial host default is an explicit BootUI heuristic, approximately 1.5 times committed heap plus non-heap, rounded to 64 MiB and clamped to 384 MiB–2 GiB. Footprint arithmetic is bounded before addition/rounding; negative/unknown counters contribute no observed bytes. |
| Headroom | Operator-selected 0%–30%; default 10%. It covers unmodeled native/process overhead but is not a measured guarantee. |
| Direct memory | Greater of Paketo's 10 MiB fallback and current `java.nio` direct-buffer memory, rounded up to MiB. It is modeled but not hard-capped. |
| Metaspace | Greater of `(14,000,000 + 5,800 × current loaded classes) × 1.25` and observed **Metaspace committed × 1.25**, rounded up to MiB. The class constants follow Paketo; each 25% allowance is an explicit BootUI starting heuristic, not a measured peak forecast. |
| Code cache | 240 MiB, following the comparable Paketo model. |
| Platform-thread stacks | 1 MiB times the operator budget. The initial budget is `max(current live threads, 250)` for every runtime, including applications using virtual threads. |
| Heap | Exact remaining modeled bytes in valid reports; fixed option literals round down to MiB. Every fixed and three-decimal percentage request must reach both HotSpot's generic 2 MiB maximum-heap lower bound and observed heap used. Untouched host defaults apply the larger target below. Otherwise the report is invalid with an explanation and no generated options/YAML. No upward heap adjustment rescues an exhausted budget. |

With no observed heap data, the generic bound retains its neutral behavior. For example, 2 MiB is representable as
`0.5%` of 400 MiB, but flooring its ratio against 519 MiB to `0.385%` requests less than 2 MiB; the latter plan is
invalid even though its fixed option alone could request 2 MiB. Passing these arithmetic checks does not guarantee
collector initialization, application startup, or workload sufficiency.

Without a detected container limit or sizing overrides, the host default targets the larger of **128 MiB** and
**observed heap used plus 20%**, after the default 10% headroom and option rounding. The 20% allowance is an explicit
BootUI starting heuristic, rounded up to a byte, not a peak/workload guarantee. The floor is
`(fixed regions + target heap) / 0.9`, rounded up to 64 MiB and, if necessary, raised one more step so fixed whole-MiB
and floored three-decimal percentage options both preserve the target. Default sizing and final validation reuse the
same target and observed direct-memory reservation. The existing 2 GiB default clamp still takes precedence: when it
cannot fit the target, the default report is invalid with an explanation and no options/YAML.

When total memory is still automatic, editing only threads or headroom retains that automatic-total target and its
20% allowance. Those edits relax only the final validation minimum below; they do not shrink the automatic budget.
An explicit total or detected container limit bypasses the automatic-total heuristic entirely.

Explicit/manual and detected-container plans require the larger of observed heap used and 2 MiB, without the
mandatory default 20% allowance or 128 MiB validation floor. Operator budgets are not silently raised to fit demand.
The same single `MemoryUsage` heap snapshot supplies defaults, validation, and the displayed heap report. BootUI
does not trigger GC or a histogram to obtain it; observed heap used can include reclaimable objects and is not
measured live-after-GC. Negative/unknown heap-used inputs contribute no observed lower bound; zero preserves the
generic/default floors. Oversized observations and allowance arithmetic saturate rather than wrapping or dropping the
bound, leaving an invalid plan when the selected budget cannot fit.

The named **Metaspace** pool is read once per report. Its committed bytes supply the same reservation for default
totals, final partitioning, fixed options, and Kubernetes options, and its unmodified observation appears in the
live-memory pool list. BootUI uses committed rather than used bytes, adds a 25% starting allowance rounded up to a
byte, then rounds the larger observed/class-model target up to MiB. Compressed Class Space is already included:
neither that pool nor unrelated non-heap/code-cache pools are added to the observed target. No GC, histogram, NMT
command, or application callback is requested.

Missing or unsupported Metaspace observations retain only the class-count model, with an explicit unavailable note;
they do not establish zero metadata demand. The allowance is not live-after-GC usage or a peak-demand guarantee.
Exceeding the generated `MaxMetaspaceSize` hard cap can cause **`OutOfMemoryError: Metaspace`**. Validate class loading
after warmup and under representative peak load. Metaspace allowance, upward rounding, and fixed-region sums saturate
rather than wrap for oversized counters. The automatic fixed-region floor includes this reservation and the existing
heap target/headroom/rounding; the hard 2 GiB default clamp and explicit/detected total budgets are never raised to
rescue an infeasible plan. Such plans retain the invalid/error, no-options/no-YAML, `Unavailable` shape.

Displayed defaults remain defaults through refreshes and Kubernetes-only toggle changes. Only editing a total,
thread or headroom input sends that sizing override; untouched inputs continue to follow the current report.

### Generated option inventory

For a valid dedicated-host plan, the advisor emits only:

```text
-Xms<heapMiB>m
-Xmx<heapMiB>m
-XX:MaxMetaspaceSize=<metaspaceMiB>m
-XX:ReservedCodeCacheSize=240m
-Xss1024k
```

For Kubernetes, fixed heap flags are replaced with:

```text
-XX:MaxRAMPercentage=<calculated>
-XX:MinRAMPercentage=<calculated>
-XX:InitialRAMPercentage=<calculated>
-XX:MaxMetaspaceSize=<metaspaceMiB>m
-XX:ReservedCodeCacheSize=240m
-Xss1024k
```

The heap percentage is the modeled heap divided by the selected whole-MiB memory limit, floored to three decimal
places using bounded integer arithmetic. There is no universal 75% cap: fixed regions and selected headroom have
already been subtracted.

The panel shows Max/Min and Initial percentages alongside **MODEL** heap MiB and the selected limit MiB, reusing
`calculation.heapBytes` and `kubernetes.limitMemoryBytes` rather than recalculating a heap from the rounded percentage.
MiB display rounds to three decimal places; the exact modeled bytes and floored option percentages are unchanged.
This is the partition model, not actual JVM `-Xmx` or a peak-workload guarantee. **Recalculate and replace the snippet
whenever the memory limit changes**: the non-heap reservations mean reusing a percentage at a different limit does
not preserve the model.

These are **requested settings**, not measured effective JVM settings. HotSpot can round heap sizes upward for
collector/platform alignment even when the option literal rounds down. Percentage flags use JVM-visible RAM or an
existing sizing override, which can differ from the chosen budget if container support or deployment settings differ.
Explicit `-Xmx`/`-Xms` settings and other startup configuration can supersede generated environment options.
`InitialRAMPercentage` also does not establish the same minimum-heap contract as `-Xms`. Check effective settings on
the target JVM; neither these flags nor model validity enforce a total process-memory limit.

Review the final merged JVM command and environment in the target image. For example, Paketo Java buildpacks'
memory calculator can append `-Xmx` to `JAVA_TOOL_OPTIONS`; Red Hat UBI OpenJDK's `run-java.sh`, used by the Quarkus
quickstart Dockerfile, can generate heap flags too. The quickstart's comments and current image documentation differ
on the precise flags/defaults, so check the chosen image version rather than assuming a universal launcher policy.
Spring Boot or Quarkus alone does not imply either launcher. This repository's root `Dockerfile` and
`Dockerfile-webflux` instead invoke Java directly with explicit `-Xms`/`-Xmx` in `JAVA_TOOL_OPTIONS`;
`Dockerfile-quarkus` runs Maven dev mode with an explicit `-Xmx` in that environment variable. Those sample settings
also need reconciliation with copied percentages; BootUI does not merge or remove them automatically.

## Kubernetes behavior

- The hard memory limit is the calculator total.
- The default memory request equals that limit. The panel reports QoS as `Depends on CPU`; it cannot see or safely invent
  the CPU settings and resources of every container in the final Pod. Default emitted request/limit, QoS, and YAML
  retain their equal-request policy; the informational `burstableRequestMemory*` alternative is floored even when
  opt-in is off, so not every DTO value is unchanged.
- Opt-in Burstable mode retains `current snapshot + max(15% of snapshot, 64 MiB)` and the 128 MiB minimum,
  but also floors the request at the modeled heap plus observed non-heap committed bytes. The modeled heap also
  covers the numerical emitted `InitialRAMPercentage` target against the selected limit because that percentage
  is rounded downward. The larger bound is rounded up to 64 MiB and capped by the limit.
  `InitialRAMPercentage` remains equal to `MaxRAMPercentage`; it is not omitted to obtain a smaller request.
  If the floor itself exceeds the fixed limit, the Kubernetes recommendation is `Unavailable`, with an explanation
  and no options or YAML, not a request clamped below that bound or an inflated limit. The separate bare-metal
  calculation remains unchanged. Review whether modeled headroom and reservations reflect the intended deployment,
  or disable Burstable to retain default equal memory request/limit output. Increasing the total alone need not
  resolve the floor, because it also increases modeled heap. If a fitting result reaches the limit, memory request
  and limit stay equal and QoS remains `Depends on CPU`.
- The snapshot margin and modeled-heap/non-heap floor are starting heuristics, not peak forecasts, live-after-GC
  measurements, or effective aligned heap sizes. The floor adds the aggregate non-heap commitment once, not the
  individual Metaspace or Compressed Class Space pools again. The raw JVM aggregate sums all non-heap pools and may
  itself contain overlapping accounting: HotSpot's named Metaspace pool already includes compressed class metadata.
  BootUI does not deduplicate this conservative aggregate or infer a vendor-independent overlap amount.
  Oversized sums saturate and are refused when they cannot fit; default emitted resources remain equal-request.
- `memory.current` (or the cgroup v1 current-usage file) is preferred for the snapshot. When unavailable, the fallback is
  committed heap + committed non-heap + observed direct buffers. Reserved stack address space is not added to resident
  memory. Limit and usage come from one detector invocation, not separate re-selections; the underlying file reads are
  still a non-atomic snapshot. Cgroup v1 usage is an approximate counter, not exact process RSS.
- Confidence is `Medium` only when both cgroup limit and current usage are available and the detected limit matches the
  selected total. Every other valid model is `Low`; the advisor does not claim high confidence from NMT merely being
  enabled. This describes model-input confidence, not production safety. A minimum ancestor limit can cover siblings
  whose consumption is absent from the process cgroup's usage; the model does not estimate that competing demand.
- Diagnostic MiB strings round up for display without overflowing, including saturated `long` counters; raw byte
  fields remain authoritative. Generated request/limit quantities are whole MiB and agree exactly with their byte
  fields and the calculation denominator.
- Generated health probes use framework-default paths and the named container port `http`. The fragment assumes the
  surrounding container declares that port name. Custom application paths, management interfaces, and ports must be
  reconciled by the operator. Timings are example policy, not measured startup or latency requirements. Startup probes
  suppress readiness/liveness until success; readiness failures do not restart the container, unlike startup/liveness
  failure handling.

## Decision inventory

| Decision | Result | Rationale |
| -------- | ------ | --------- |
| Paketo partition | **RETAIN** | Established deterministic policy, not exhaustive process-memory accounting. |
| Total/thread/headroom input clamps | **RETAIN** | Existing bounded operator contract, not universal JVM limits. |
| Host default and rounding | **UPDATE** | Preserve 1.5x/384 MiB–2 GiB/64 MiB policy; include default headroom and both option representations in the larger of 128 MiB and observed heap used plus a 20% starting allowance. Reject defaults that cannot fit within the clamp. |
| Detected total and YAML denominator | **UPDATE** | Round detected budget down to whole MiB, preserve raw evidence, and align emitted quantities with the model. |
| Headroom default 10%, selectable 0%–30% | **RETAIN** | Explicit BootUI/operator heuristic; not Paketo's default or a safety guarantee. |
| Live class count and observed Metaspace commitment | **UPDATE** | Retain the class model when larger; otherwise reserve current named-pool committed bytes plus 25%. Reuse one observation, round upward, preserve an honest class-model fallback, and never double-count compressed class space. A hard cap can still cause Metaspace OOME under later demand. |
| Metaspace upward MiB rounding | **RETAIN** | Does not shrink the modeled requested cap; not evidence that the cap fits the workload. |
| Direct fallback/observed usage without cap | **RETAIN** | Current buffers are not peak demand or all native allocations. Unavailable observations do not establish zero demand. |
| Code cache 240 MiB | **RETAIN** | Paketo reserve, not every JVM mode's default or observed commitment. |
| 1 MiB stacks; max(live platform threads,250) default | **RETAIN** | Explicit setting and reserve, not the platform's default stack size or future/native-thread count. |
| Virtual-thread stack discount | **RETAIN REMOVAL** | Heap-backed virtual stacks do not remove native platform/carrier stacks. |
| Minimum generated maximum heap | **UPDATE** | Reject fixed or serialized-percentage candidates below observed heap used or the generic 2 MiB source bound; explicit and detected budgets are not inflated and do not require the default 20% allowance. No speculative 8 MiB floor. |
| Fixed `-Xms`/`-Xmx` equality | **RETAIN** | Explicit fixed modeled heap policy, not an evidence-based production commitment recommendation. |
| Literal flooring guarantees effective budget | **REMOVE CLAIM** | Target collector/platform alignment may enlarge effective heap; validity is arithmetic, not startup/load proof. |
| Max/Min/Initial RAM percentages | **UPDATE** | Exact three-decimal flooring and emitted-bound validation; explain denominator, override and initial/minimum semantics. |
| Universal 75% heap ceiling | **RETAIN REMOVAL** | Double-counts a reserve after fixed regions/headroom. |
| Explicit `UseContainerSupport` | **RETAIN REMOVAL** | Linux-only and default-on where supported; verify deployment assumptions instead. |
| `MaxRAM` as a budget fix | **REJECT ADDITION** | Not an enforced cap; deprecated in JDK 26. |
| Automatic GC selection and `ZGenerational` | **RETAIN REMOVAL** | Workload- and version-dependent; omitting flags also does not copy the current collector to a new JVM. |
| Deduplication, compact headers, pre-touch | **RETAIN REMOVAL** | No snapshot-based workload justification; compact-header productization does not make it universally available or appropriate. |
| OOM exit/dump paths and policy | **RETAIN REMOVAL** | Restart/storage/sensitive-data policy belongs to the deployment. |
| Equal requests/limits and `Depends on CPU` | **RETAIN** | Container memory alone cannot establish Guaranteed Pod QoS. Newer Pod-level resource policy is cluster-dependent and outside this fragment. |
| Opt-in Burstable snapshot margin/floor/rounding | **UPDATE** | Retain the snapshot heuristic; also cover the modeled heap plus observed non-heap committed, then round the request upward. The model bounds the downward-rounded initial-heap option. Refuse an infeasible floor without raising the limit; no peak or production forecast. |
| Cgroup current vs committed-pool fallback | **UPDATE READ COHERENCE** | Reuse one detector sample; charged memory and JVM commitment are not interchangeable physical-memory measures. |
| Overflow/unknown observation handling | **UPDATE** | Preserve large quantities without wrapping to zero; retain low confidence without required observations. |
| Medium model confidence | **RETAIN WITH LIMITATIONS** | Matching observed inputs only; no inference of sibling usage, future demand, or guaranteed free memory. |
| NMT startup flag | **RETAIN** | No consumed NMT output or confidence boost; NMT excludes some process-native memory even when enabled. |
| Named port, framework-default paths, explicit probe toggle | **RETAIN** | Review dependency, routing, management interface, declared port and custom paths. |
| Probe timings | **RETAIN AS EXAMPLES** | User must establish startup/timeout/restart policy; not measured recommendations. |
| Spring effective health capability | **UPDATE ACCESS; RETAIN LIMITATIONS** | Require current Actuator health/access classes and honor native endpoint access, global defaults, legacy aliases, and the maximum-permitted cap before the probe-group switch. Development dependencies and configured access do not prove deployed dependencies, web exposure, custom groups, or reachability. |
| Quarkus health capability | **RETAIN LIMITED SIGNAL; ENHANCEMENT DEFERRED** | Extension presence is not full effective endpoint/management routing evidence. |
| Native image/alternative JVMs | **RETAIN LIMITATIONS** | Native-image tuning is unavailable; HotSpot options require manual review on alternative JVM implementations. |

Rejected new candidates include automatic CPU sizing, fixed GC pause targets, large pages, NUMA settings, Shenandoah or
ZGC recommendations, automatic NMT enablement, and a universal native-overhead multiplier. They are unavailable on part
of the supported range, depend on workload/host/cluster policy, or cannot be derived reliably from current local
observations.

## Platform behavior and limitations

- **Spring MVC and WebFlux:** both use the same engine and DTOs. Spring's virtual-thread property is explanatory only.
  Spring Actuator probe groups default on only when Actuator health/access classes are present, health permits reads,
  and `management.endpoint.health.probes.enabled` is not false. Native Spring access precedence applies:
  `management.endpoint.health.access` (or legacy `management.endpoint.health.enabled`) overrides
  `management.endpoints.access.default` (or legacy `management.endpoints.enabled-by-default`), and
  `management.endpoints.access.max-permitted` caps either. Both `read-only` and `unrestricted` permit health reads;
  `none` suppresses the default probes. Conflicting access/enabled settings at the same level remain errors, not an
  enabled fallback. The explicit snippet toggle still overrides this default; it does not change application access.
  BootUI supplies Actuator during development: verify that the deployed artifact declares Actuator independently
  when BootUI is removed. Probe settings do not install dependencies or establish web exposure, custom groups, or
  reachability. WebFlux/Netty can use native
  allocations that the standard direct-buffer MXBean does not fully describe, so direct memory must be load-tested.
- **Quarkus 3.33 LTS:** the same calculation is used. Quarkus does not expose a single application-wide virtual-thread
  switch equivalent to Spring's property, so that bubble is absent. SmallRye Health contributes `/q/health/started`,
  `/q/health/ready`, and `/q/health/live`; a separate management interface can move them.
- **HotSpot options:** generated flags are documented HotSpot options. Alternative JVM implementations require manual
  review.
- **Snapshots:** current classes, threads, buffers, pools, and cgroup usage can all grow after the panel is opened.
  Recommendations must be tested after warmup and under representative peak load.
- **NMT:** BootUI detects the startup option but does not execute or parse `jcmd VM.native_memory`; no confidence claim
  depends on NMT output.
- **Native memory:** GC/compiler structures, native libraries/agents, allocator effects, and container-charged
  cache/kernel/tmpfs memory are not separately measured by the partition. Fixed JVM region settings do not cap these.
- **Cgroup scope:** ancestor-limit and leaf-usage scope differences remain a limitation; finite cgroup-v2 zero limits
  are currently treated as unavailable by the shared detector. This audit does not change that separately owned
  observation layer.
- **YAML scope:** the output is a container fragment, not a complete Deployment. It does not invent CPU resources,
  container names, images, security context, or a `ports` declaration.
