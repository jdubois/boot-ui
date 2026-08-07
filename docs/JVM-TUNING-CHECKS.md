# JVM Tuning Advisor Model

The JVM Tuning panel is a deterministic memory-budget calculator, not an automatic performance tuner. It turns live,
local JVM observations and operator inputs into a reviewable starting point. It does not modify the running process,
select a garbage collector, inspect application traffic, or claim that a snapshot predicts peak production demand.

This document records the evidence and decisions behind the shared engine used by Spring MVC, Spring WebFlux, and
Quarkus. The compatibility baseline is Java 17 through Java 26, Spring Boot 4.1, and Quarkus 3.33 LTS.

## Evidence ledger

| Source | Evidence used |
| ------ | ------------- |
| [Spring Boot 4.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html) | Spring Boot 4.1 requires Java 17 and supports Java through 26, defining the generated-option compatibility range. |
| [Oracle JDK 17 `java` launcher](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html) and [JDK 26 launcher](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html) | Defines `-Xms`, `-Xmx`, `-Xss`, container support, RAM-percentage, metaspace, code-cache, GC, pre-touch, and OOM options across the supported range. `MinRAMPercentage` governs maximum-heap ergonomics for small heaps, so it must accompany `MaxRAMPercentage`. `UseContainerSupport` is Linux-only and already defaults to true where supported, so a portable snippet must not force it. |
| [Paketo Java memory calculator reference](https://paketo.io/docs/reference/java-reference/#memory-calculator) and [`libjvm` calculator source](https://github.com/paketo-buildpacks/libjvm/blob/main/calc/calculator.go) | Supplies the partition formula and the 10 MiB direct-memory, 240 MiB code-cache, 1 MiB stack, and 250-thread modeling defaults. |
| [`ClassLoadingMXBean`](https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/ClassLoadingMXBean.html) and [`BufferPoolMXBean`](https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/BufferPoolMXBean.html) | Provide observable current loaded-class count and estimated direct-buffer memory usage. Both are snapshots, not peak forecasts. |
| [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) | Virtual threads are not tied one-to-one to OS threads and are mounted on carrier platform threads. Their presence does not provide an observable, deterministic replacement for a platform-thread native-stack budget. |
| [Oracle GC ergonomics guidance](https://docs.oracle.com/en/java/javase/17/gctuning/ergonomics.html) | GC sizing and pause/throughput choices are competing workload goals; heap size alone is not sufficient evidence for changing collectors or enabling pre-touch. |
| [JEP 439](https://openjdk.org/jeps/439) and [JEP 474](https://openjdk.org/jeps/474) | Generational ZGC availability and defaults changed after Java 17, so emitting `ZGenerational` cannot be portable across the supported range. |
| [JEP 450: Compact Object Headers](https://openjdk.org/jeps/450) | Compact headers entered as a disabled experimental feature after Java 17 and require workload validation, so the advisor cannot enable them generically. |
| [Kubernetes resource management](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/) and [Pod QoS classes](https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/) | Memory limits are hard OOM boundaries; requests drive scheduling. Guaranteed QoS requires equal, non-zero CPU and memory requests and limits for every container, not memory equality alone. |
| [Linux cgroup v2 memory controller](https://docs.kernel.org/admin-guide/cgroup-v2.html#memory-interface-files) | `memory.current` is the current total memory attributed to a cgroup and `memory.max` is its hard limit. |
| [Oracle Native Memory Tracking](https://docs.oracle.com/en/java/javase/17/vm/native-memory-tracking.html) | NMT is off by default, requires `jcmd` output to use, and tracks JVM/HotSpot memory rather than all user-native allocations. Detecting its startup flag alone is not stronger sizing evidence. |
| [Kubernetes probe configuration](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#use-a-named-port) | HTTP probes may refer to a named container port, avoiding a framework-specific hard-coded port number. |
| [Spring Boot Kubernetes probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes) and [Quarkus 3.33 SmallRye Health](https://quarkus.io/version/3.33/guides/smallrye-health) | Supplies framework-default health capabilities and paths. Both frameworks allow configuration that can move those endpoints or their port. |

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
| Total process budget | Operator input clamped to 128 MiB–64 GiB before byte conversion. The initial host default is an explicit BootUI heuristic, approximately 1.5 times committed heap plus non-heap, rounded to 64 MiB and clamped to 384 MiB–2 GiB. |
| Headroom | Operator-selected 0%–30%; default 10%. It covers unmodeled native/process overhead but is not a measured guarantee. |
| Direct memory | Greater of Paketo's 10 MiB fallback and current `java.nio` direct-buffer memory, rounded up to MiB. It is modeled but not hard-capped. |
| Metaspace | `(14,000,000 + 5,800 × current loaded classes) × 1.25`, rounded up to MiB. The constants follow Paketo; 1.25 is an explicit BootUI allowance for later class loading. |
| Code cache | 240 MiB, following the comparable Paketo model. |
| Platform-thread stacks | 1 MiB times the operator budget. The initial budget is `max(current live threads, 250)` for every runtime, including applications using virtual threads. |
| Heap | Exact remaining bytes in the report; generated MiB options round down so rendering never exceeds the modeled budget. A plan with less than 1 MiB of renderable heap is invalid. |

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

The heap percentage is the modeled heap divided by the memory limit, floored to three decimal places. There is no
universal 75% cap: fixed regions and selected headroom have already been subtracted.

## Kubernetes behavior

- The hard memory limit is the calculator total.
- The default memory request equals that limit. The panel reports QoS as `Depends on CPU`; it cannot see or safely invent
  the CPU settings and resources of every container in the final Pod.
- Burstable mode attempts to lower the request using `current snapshot + max(15%, 64 MiB)`, rounded up to 64 MiB,
  floored at 128 MiB, and capped by the limit. If the result reaches the limit, memory request and limit stay equal and
  QoS remains `Depends on CPU`. This is explicitly a starting heuristic.
- `memory.current` (or the cgroup v1 current-usage file) is preferred for the snapshot. When unavailable, the fallback is
  committed heap + committed non-heap + observed direct buffers. Reserved stack address space is not added to resident
  memory.
- Confidence is `Medium` only when both cgroup limit and current usage are available and the detected limit matches the
  selected total. Every other valid model is `Low`; the advisor does not claim high confidence from NMT merely being
  enabled.
- Generated health probes use framework-default paths and the named container port `http`. The fragment assumes the
  surrounding container declares that port name. Custom application paths, management interfaces, and ports must be
  reconciled by the operator.

## Decision inventory

| Decision | Result | Rationale |
| -------- | ------ | --------- |
| Paketo partition and fixed-region defaults | **KEEP** | Established, deterministic baseline with transparent arithmetic. |
| Live class count with 1.25 allowance | **KEEP** | Observable input plus an explicit, testable heuristic. |
| Metaspace and rendered-cap rounding | **MODIFY** | Caps round up; heap rounds down, preventing snippets from under-allocating caps or exceeding the plan. |
| Direct-memory region | **MODIFY** | Raise the model to observed direct-buffer usage, but do not convert a transient observation into a hard cap. |
| Virtual-thread stack discount | **REMOVE** | The previous 80-thread/512 KiB assumption was not derivable from `ThreadMXBean` or the virtual-thread scheduler. |
| Automatic G1/ZGC selection and `ZGenerational` | **REMOVE** | Heap size alone does not establish pause/throughput goals, and flag behavior varies inside the supported JDK range. |
| String deduplication, compact headers, and pre-touch | **REMOVE** | Workload- or JDK-specific choices cannot be inferred from this snapshot. |
| Direct-memory cap and OOM exit/dump policy | **REMOVE** | Future native demand, restart policy, dump storage, and sensitive-data handling are deployment decisions. |
| Kubernetes 75% heap ceiling | **REMOVE** | It double-counted native safety after fixed regions and headroom were already subtracted. |
| `MinRAMPercentage` | **ADD** | Keeps the calculated maximum-heap percentage effective for HotSpot's small-heap path. |
| Explicit `UseContainerSupport` | **REMOVE** | The option is Linux-only and already enabled by default where supported; omitting it keeps the fragment valid on other container platforms. |
| cgroup current usage | **ADD** | More complete current container observation than summing selected JVM pools. |
| Guaranteed QoS claim | **MODIFY** | Memory equality is necessary but not sufficient; CPU and all Pod containers also determine QoS. |
| Hard-coded probe port 8080 | **MODIFY** | A named port is portable across framework defaults, with an explicit operator-verification warning. |

Rejected new candidates include automatic CPU sizing, fixed GC pause targets, large pages, NUMA settings, Shenandoah or
ZGC recommendations, automatic NMT enablement, and a universal native-overhead multiplier. They are unavailable on part
of the supported range, depend on workload/host/cluster policy, or cannot be derived reliably from current local
observations.

## Platform behavior and limitations

- **Spring MVC and WebFlux:** both use the same engine and DTOs. Spring's virtual-thread property is explanatory only.
  Spring Actuator probe groups are included only when the adapter reports them enabled. WebFlux/Netty can use native
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
- **YAML scope:** the output is a container fragment, not a complete Deployment. It does not invent CPU resources,
  container names, images, security context, or a `ports` declaration.
