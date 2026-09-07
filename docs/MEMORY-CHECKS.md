# Memory checks

The Memory advisor evaluates **36 stable rules** against explicit, on-demand JVM observations. Spring MVC,
Spring WebFlux, and Quarkus use the same framework-neutral collector, rules, report, and dismissal IDs. MCP and
the CLI expose that same report. Reading the cached report does not scan, collect a histogram, or start a recording.

Findings are review prompts, not proof of a leak, a sizing prescription, or a production-readiness assessment.
All numerical thresholds below are **BootUI heuristics**, not Oracle-endorsed universal warning thresholds.
Interpret them against representative steady-state and burst workloads.

## Measurement quality, availability, and cost

- **Occupancy is not retained size.** `MemoryUsage` describes a snapshot, including objects that may be
  unreachable but not yet collected. `committed` is capacity available to the JVM for that purpose, not process
  RSS. An undefined maximum (`-1`) is not proof of unlimited memory. A rule requiring a maximum does not
  substitute committed capacity, including on collectors whose old-generation committed value equals used.
- **A histogram is not proof of a full GC.** The existing explicit `GC.class_histogram` command requests GC,
  but HotSpot can skip the requested collection and still produce rows. Allocations can also resume before the
  subsequent usage reading. The advisor therefore calls these **post-histogram snapshots**, not verified
  post-full-GC live sets. A later low snapshot does not explain why usage fell.
- **The histogram is intrusive.** Oracle rates its impact High, depending on heap size/content. `-all` includes
  unreachable objects and is not a pause-free alternative; BootUI does not switch to it. No additional GC, heap
  dump, NMT, JFR, subprocess, network request, or background sampling is introduced by these rules. Scan admission
  is single-flight. Bounded results do not imply a hard execution-time limit for the JVM diagnostic command.
- **Unknown is not zero.** Buffer `memoryUsed=-1` stays unknown; NIO capacity and estimated used bytes remain
  distinct. GC count and elapsed time have independent availability. A partial unknown total is not presented as
  a complete sum. Overflowing estimates become unavailable instead of wrapping into healthy values.
- **Unsupported and failed are different.** Ordinary unsupported optional metrics cause the dependent rule to
  be `SKIPPED`. Actual thread/histogram supplier or rule failures produce existing `ERROR` entries in
  `analysisErrors` and a `PARTIAL` scan while retaining unrelated valid findings. Failure to collect the whole
  context returns `ERROR`. The `results` list contains findings, not passing or skipped checks; **no findings
  does not mean every measurement was available or the JVM is healthy**.
- **Threads are platform threads.** ThreadMXBean excludes virtual threads and cannot detect every deadlock
  involving them. The existing synchronizer-capability fallback can cover monitors only. Summary counts remain
  complete when the detail page is capped at 1,000 threads; CPU-hot-thread analysis skips an incomplete detail page.
  A failed thread report never becomes a passing deadlock check.
- **Histogram bytes are shallow.** Totals cover parsed rows; displayed/evaluated class rows are limited to the
  largest 200, and finding examples remain bounded. Aggregate array/collection observations can consequently
  undercount the tail. A histogram does not identify one owning collection, retention paths, or retained graphs.
  HotSpot can also log histogram undercounting separately from its returned rows.

For the underlying contracts and collection caveats, see [MemoryUsage][memory-usage], [MemoryPoolMXBean][memory-pool],
[GC counters][gc-bean], [BufferPoolMXBean][buffers], [ThreadMXBean][threads], [jcmd][jcmd], and
[HotSpot's skipped-GC path][histogram-source].

## Time and collector boundaries

Recent-GC comparisons span the previous scan's **post-histogram counter sample** to the current scan's
**pre-histogram counter sample**, excluding the scans' own histogram request intervals. Missing endpoints,
non-positive elapsed windows, decreased counters, or changed collector identities cannot become healthy zero
activity. A valid zero timer delta is possible even when the collection count increases because timers are
approximate milliseconds. Known counts remain usable when time is unknown, and vice versa.

The time ratio is approximate **collection elapsed time**, not CPU utilization or an exact percentage of application
pause time. ZGC/Shenandoah whole-cycle timers and legacy CMS concurrent timing are excluded to avoid adding
overlapping cycle and pause time. G1's concurrent manager remains included because its timer covers remark/cleanup
VM operations. Collector implementations outside these known conventions need separate interpretation. A completed
collection that crosses a sampling boundary can distort a short-window ratio. Lifetime totals still include startup
and diagnostic collections.

The direct-buffer and old-generation trends require **three strict increases across four comparable observations**.
A plateau, decrease, missing/unknown reading, or failed whole scan breaks consecutive evidence and requires a new
baseline. This measures sampled net growth: releases and allocations may both occur between endpoints. There is no
inferred continuous growth, minimum workload, or elapsed-duration guarantee from clicking Scan repeatedly.

| JVM/collector scope | Interpretation |
| --- | --- |
| JDK 17 through 26 management APIs | Snapshot, undefined-max, approximate-counter and resettable-thread-peak semantics remain relevant throughout. Implementation details are not promises for every vendor/update build. |
| Serial / Parallel | Old and young collectors/pools differ. A young collection is not a full old-generation live-set refresh. |
| G1 | Reclamation is incremental. `G1 Old Gen` is a pool; `G1 Old Generation` is the full-GC manager. A full-GC count does not reveal its cause. Explicit GC and diagnostic requests can contribute. |
| ZGC | Non-generational ZGC exposes one heap. Generational ZGC arrived in 21, became default ZGC in 23, and replaced non-generational ZGC in 24. JDK 26's old-pool committed equals used; that ratio must not be called cap pressure. Generation maxima are not independent capacities to add together. |
| Shenandoah | Generational mode was experimental in 24 and productized in 25; non-generational remains default through 26. Exposed pools and collection boundaries depend on the actual mode. |
| Virtual threads | Previewed in 19/20, finalized in 21. Their heap-backed stacks are not one native `-Xss` reservation each. Scheduler estimates available in newer JDKs are not a full thread census and are not collected by this advisor. |

Sources: [GC event timing][gc-info], [G1 manager implementation][g1-manager], [Generational ZGC][jep439],
[ZGC default change][jep474], [non-generational ZGC removal][jep490], [ZGC pool accounting][zgc-source],
[Generational Shenandoah experiment][jep404], [Shenandoah productization][jep521], and [virtual threads][jep444].

## Native and container limits

Configured maxima, reserved address space, committed JVM pools, process RSS, and cgroup charges are different
measurements. The configured-envelope rule mixes maximum heap, currently committed non-heap, direct-buffer capacity,
and approximate platform-stack reservation as an **incomplete capacity estimate**, not a measured footprint.
It excludes GC structures, JIT working memory, native libraries, and non-NIO allocations. A default stack estimate
may be used when the effective reservation cannot be read; per-thread reservations and touched pages can differ.
Do not add all stack reservations to cgroup current usage: touched stack pages are already charged there.

Container observations come from Linux cgroups. The existing detector resolves the process cgroup, finds the most
restrictive finite ancestor limit, and reads **leaf** current/stat values. This can miss siblings competing for
an ancestor's ceiling; it does not prove available headroom. Finite zero-limit detection and hierarchy-wide usage
pairing remain detector limitations outside this rule audit. An inactive-file subtraction is a working-set
approximation, not guaranteed reclaimability. Current usage includes the cgroup and descendants, not just this JVM.

Operating-system swap statistics describe the operating environment, not this JVM's swapped pages or active paging.
Comparing the JVM's estimated footprint with currently free physical RAM cannot establish process residency.

NMT is useful confirmation evidence but is disabled by default, requires startup enablement, has documented
overhead, and does not account for all native allocations. Its total includes Java Heap; neither its reserved nor
committed totals equal RSS. BootUI does not enable NMT or run native-memory commands as part of this advisor.
See [NMT][nmt], [OS MXBean][os-bean], [Linux cgroups][cgroups], and [Linux process memory][proc].

## Complete rule audit and current behavior

The September 2026 audit retained all **36 IDs**: no rules were added or removed. `Update` means behavior,
measurement handling, or diagnostic text changed; `Retain` means the existing basic heuristic remains.
Common arithmetic/availability corrections apply without renumbering rules.

### Heap pressure

| ID | Disposition | Current trigger, severity, and appropriate action |
| --- | --- | --- |
| MEM-HEAP-001 | Update | **MEDIUM** at 95% of a known heap maximum, preferring a valid post-histogram snapshot. Histogram success alone no longer escalates to HIGH or claims retained pressure. Confirm representative pressure before changing heap or retention. |
| MEM-HEAP-002 | Update | **MEDIUM** at 85% of a known old-pool maximum. Skip absent pools/unknown maxima; never divide by committed instead. Investigate collector-specific occupancy, not an asserted fully collected live set. |
| MEM-HEAP-003 | Update | **LOW** when max heap is below 15% of a container limit of at least 1 GiB and occupancy is at least 80%. A large limit is not free memory: confirm total native/container headroom before raising heap. |
| MEM-HEAP-004 | Retain | **INFO** just above the approximate compressed-oops boundary through 125% of it; boundary scales with object alignment, normally about 32 GiB at 8 bytes. Skip ZGC/explicit disable; overflow does not manufacture a boundary. This is not a guaranteed capacity improvement. |
| MEM-HEAP-005 | Retain | **INFO** for smaller initial than maximum heap with ZGC/Shenandoah. The collector uses the JVM's reported initial capacity rather than assuming an earlier argument is effective. Equal initial/max may suit latency-sensitive workloads but trades away footprint/uncommit flexibility. |
| MEM-HEAP-006 | Retain | **LOW** for at least 1,000 objects pending finalization. Review persistent backlog and resource lifecycle; prefer explicit close/try-with-resources over finalization, deprecated for removal by JEP 421. |
| MEM-HEAP-007 | Update | **INFO** after 10 minutes uptime when one snapshot has at least 1 GiB slack and committed is at least twice used. Used/committed come from the same observation. No claim of a measured working set, consistently unused memory, or safe production downsizing. |
| MEM-HEAP-008 | Update | **LOW** after three valid increases in old-generation occupancy. Missing observations break the streak. Normal warmup/load changes can explain it; confirm stable load and collector-appropriate reclamation before investigating retention. |

Evidence: [snapshot contracts][memory-usage], [pool semantics][memory-pool], [leak investigation][leaks],
[heap-sizing tradeoffs][gc-tuning], [compressed oops][oops], [ZGC tuning][zgc-tuning], and [JEP 421][jep421].

### Native memory

| ID | Disposition | Current trigger, severity, and appropriate action |
| --- | --- | --- |
| MEM-FOOTPRINT-001 | Update | **HIGH** when known max heap itself meets/exceeds the container limit; otherwise **MEDIUM** at 90% for the incomplete mixed configured-envelope estimate. Unknown components/overflow cannot become zero. A reservation estimate is not committed or resident pressure. |
| MEM-FOOTPRINT-002 | Update | **MEDIUM** for approximate platform-stack reservations of at least 1 GiB or 20% of a known container limit. No HIGH escalation from adding already-accounted touched stack pages to container usage. Review pool counts and stack needs before changing `-Xss`. |
| MEM-FOOTPRINT-003 | Retain | **HIGH** at 90% of the known cgroup limit using current usage or its inactive-file-adjusted working-set estimate. Valid zero usage is not missing. Corroborate hierarchy scope and reclaimability; this is not process RSS. |
| MEM-FOOTPRINT-004 | Update | **INFO** when coherent OS/environment swap readings show at least 50% used. No JVM-footprint/free-RAM test or per-JVM swap attribution. Inspect process residency and paging before changing heap. |

Evidence: [native accounting][nmt], [OS MXBean scope][os-bean], [cgroup semantics][cgroups], and [process residency][proc].

### Memory pools

| ID | Disposition | Current trigger, severity, and appropriate action |
| --- | --- | --- |
| MEM-POOL-001 | Retain | **MEDIUM** at 85% of known Metaspace maximum. Undefined maximum/usage is skipped. Pressure can motivate classloader investigation but is not a diagnosed leak. |
| MEM-POOL-002 | Update | **MEDIUM** at 90% in any known code-cache segment, including unsegmented `CodeCache`. All undefined maxima mean not assessed. Saturation constrains new compilation; it does not make all existing compiled methods revert to interpretation. |
| MEM-POOL-003 | Update | **LOW** at 80% of a known effective NIO direct-buffer capacity cap. Resolve a live HotSpot zero/default option to max heap; otherwise use a known explicit cap or skip. No unknown-to-unlimited inference, mapped-buffer aggregation, or substitution of used bytes for capacity. |
| MEM-POOL-004 | Update | **LOW** for at least 128 MiB Metaspace with no reported maximum inside a detected memory-limited container. Undefined maximum is not proof of a missing effective cap; setting one can cause Metaspace OOM and cannot guarantee graceful failure. |
| MEM-POOL-005 | Update | **MEDIUM** at 85% of reported Compressed Class Space maximum. Compressed class pointers are distinct from ordinary object pointers; no universal 1 GiB default is asserted across versions/header modes. |
| MEM-POOL-006 | Update | **INFO** for input arguments selecting interpreted/disabled/reduced-tier compilation. Respect later mode/tier/enable options and inactive tiered compilation. Deliberate startup/development configuration is not proof of throughput or memory failure. |
| MEM-POOL-007 | Update | **LOW** for three comparable direct-used increases, **MEDIUM** when capacity is also near its known cap. Missing/unknown samples restart the trend. Net growth cannot establish missing releases or a native leak; use supported library lifecycle APIs, not manual Cleaner calls. |

Evidence: [pool contracts][memory-pool], [buffer estimates][buffers], [OpenJDK capacity enforcement][direct-cap],
[OpenJDK default resolution][direct-default], and [VM options][java-options].

### GC configuration and activity

| ID | Disposition | Current trigger, severity, and appropriate action |
| --- | --- | --- |
| MEM-GC-001 | Retain | **INFO** for a detected container without explicit maximum-heap/RAM sizing. HotSpot's approximately 25% default and small-heap ergonomics are context, not a requirement to override them. Recognize `-Xmx`, `MaxHeapSize`, `MaxRAM`, percentage and fraction options. |
| MEM-GC-002 | Update | **MEDIUM** for approximate lifetime collection time at least 10% of uptime after 10 minutes. Preserve unknown totals/counts independently. Startup and diagnostic collections remain included; do not call this CPU utilization or exact paused time. |
| MEM-GC-003 | Update | **MEDIUM** at 10% recent approximate collection-time ratio, **HIGH** at 25%, after a valid interval of at least 10 seconds. Reset/incomparable/unknown endpoints do not yield healthy zero deltas. Corroborate collections crossing the interval boundary. |
| MEM-GC-004 | Update | **LOW** for Serial GC with at least two processors and roughly 2 GiB of known memory. Unknown memory is not proven server-class capacity; small environments skip. Review workload tradeoffs rather than claiming Serial necessarily wastes resources or causes long pauses. |
| MEM-GC-005 | Update | **INFO** for a positive comparable `G1 Old Generation` count delta outside histogram request intervals. A Full GC can be explicit/diagnostic, not necessarily allocation failure. Inspect GC cause/logs before tuning G1. |
| MEM-GC-006 | Retain | **MEDIUM** when the most recently completed event lasted at least 1,000 ms. Select by completion time, not historical maximum duration; suppress an unchanged event from the prior histogram. Elapsed concurrent event duration is not necessarily a pause. |
| MEM-GC-007 | Update | **HIGH** when container awareness remains explicitly disabled despite a visible cgroup limit. Respect a later re-enable option. Keep supported-HotSpot and deliberate-override caveats; no automatic sizing changes. |

Evidence: [GC counters][gc-bean], [event timing][gc-info], [G1 full-GC manager][g1-manager],
[diagnostic full-GC causes][gc-causes], [collector tradeoffs][collectors], and [VM options][java-options].

### Threads

| ID | Disposition | Current trigger, severity, and appropriate action |
| --- | --- | --- |
| MEM-THREAD-001 | Update | **CRITICAL** for detected platform-thread deadlock cycles. Missing/failed thread observations are not PASS. Detection covers the supported monitor/synchronizer scope, not all virtual-thread cycles. |
| MEM-THREAD-002 | Retain | **MEDIUM** at five BLOCKED threads and 25% of the census, or 20 BLOCKED threads and 10%. Full summary counts support this despite detail paging; a transient snapshot does not prove sustained contention. |
| MEM-THREAD-003 | Update | **INFO** when peak is at least twice current count with a gap of at least 50. Peak means since start **or last peak reset**, not an all-time monotonic count or current exhaustion. |
| MEM-THREAD-004 | Update | **INFO** for currently RUNNABLE platform threads with accumulated CPU at least 60 seconds and half JVM uptime. CPU accumulated before the snapshot is not attributed entirely to its current state. Skip unsupported timing/incomplete detail and confirm with consecutive samples. |

Evidence: [ThreadMXBean, including peak reset and deadlock support][threads], and [virtual-thread scope][jep444].

### Heap content and class loading

| ID | Disposition | Current trigger, severity, and appropriate action |
| --- | --- | --- |
| MEM-CONTENT-001 | Retain | **INFO** for average shallow instance size at least 512 KiB and at least 10 MiB total. An average cannot prove an individual G1 humongous allocation; that also depends on region size. |
| MEM-CONTENT-002 | Retain | Collection/node rows reaching 50 MiB or 10% shallow share receive **LOW**; a largest row of 100 MiB or combined selected share of 25% receives **MEDIUM**. This is not one identified collection, retained size, or proof of missing eviction. |
| MEM-CONTENT-003 | Retain | **LOW** when the largest non-array class reaches 25% of total shallow histogram bytes. Arrays are excluded; unexpected retention requires reference-path evidence. |
| MEM-CONTENT-004 | Retain | **INFO** when array rows account for at least half of total histogram bytes. Normal backing arrays can dominate. Top-200 truncation can undercount the aggregate tail; no exhaustive retained-memory claim. |
| MEM-CLASS-001 | Update | **INFO** at 50,000 currently loaded classes, with framework-generation caveats. Historical unloads no longer exempt a large current population: unloading does not prove health or exclude a leak. |
| MEM-CLASS-002 | Update | **INFO** at 50,000 lifetime unloads or a lifetime average of 1,000/minute after 30 minutes. A past burst or redeployment can explain the total; it is not sustained recent churn. |

Evidence: [histogram cost/shape][jcmd], [stable-workload leak investigation][leaks],
[class-loading counters][classes], and [optional class unloading][unloading].

## Confirmation work remains explicit

Use GC logs and comparable workload observations first. For a suspected retention issue, an explicitly requested
heap analysis or JFR old-object/root-path investigation can provide stronger evidence; root-path collection can pause
the application. For native growth, an already enabled NMT baseline/diff can help but remains incomplete accounting.
These are investigation choices, not automatic fixes or additional actions performed by this scan.

No rule was removed solely to improve the advisor score. Severity changes reflect evidence confidence, and dismissals
continue to target the same rule IDs. Shared score calculation and incomplete-report presentation are separate concerns.

[memory-usage]: https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/MemoryUsage.html
[memory-pool]: https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/MemoryPoolMXBean.html
[gc-bean]: https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/GarbageCollectorMXBean.html
[gc-info]: https://docs.oracle.com/en/java/javase/26/docs/api/jdk.management/com/sun/management/GcInfo.html
[buffers]: https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/BufferPoolMXBean.html
[threads]: https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/ThreadMXBean.html
[jcmd]: https://docs.oracle.com/en/java/javase/26/docs/specs/man/jcmd.html
[histogram-source]: https://github.com/openjdk/jdk/blob/jdk-17-ga/src/hotspot/share/gc/shared/gcVMOperations.cpp#L141-L169
[g1-manager]: https://github.com/openjdk/jdk/blob/jdk-26-ga/src/hotspot/share/gc/g1/g1MonitoringSupport.cpp#L90-L94
[gc-causes]: https://github.com/openjdk/jdk/blob/jdk-26-ga/src/hotspot/share/gc/shared/collectedHeap.cpp#L331-L349
[zgc-source]: https://github.com/openjdk/jdk/blob/jdk-26-ga/src/hotspot/share/gc/z/zServiceability.cpp#L142-L171
[direct-cap]: https://github.com/openjdk/jdk/blob/jdk-26-ga/src/java.base/share/classes/java/nio/Bits.java#L224-L234
[direct-default]: https://github.com/openjdk/jdk/blob/jdk-26-ga/src/java.base/share/classes/jdk/internal/misc/VM.java#L247-L260
[jep439]: https://openjdk.org/jeps/439
[jep474]: https://openjdk.org/jeps/474
[jep490]: https://openjdk.org/jeps/490
[jep404]: https://openjdk.org/jeps/404
[jep521]: https://openjdk.org/jeps/521
[jep444]: https://openjdk.org/jeps/444
[jep421]: https://openjdk.org/jeps/421
[nmt]: https://docs.oracle.com/en/java/javase/26/vm/native-memory-tracking.html
[os-bean]: https://docs.oracle.com/en/java/javase/26/docs/api/jdk.management/com/sun/management/OperatingSystemMXBean.html
[cgroups]: https://docs.kernel.org/admin-guide/cgroup-v2.html
[proc]: https://docs.kernel.org/filesystems/proc.html
[leaks]: https://docs.oracle.com/en/java/javase/26/troubleshoot/troubleshooting-memory-leaks.html
[java-options]: https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html
[gc-tuning]: https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html
[zgc-tuning]: https://docs.oracle.com/en/java/javase/21/gctuning/z-garbage-collector.html
[collectors]: https://docs.oracle.com/en/java/javase/17/gctuning/available-collectors.html
[oops]: https://wiki.openjdk.org/display/HotSpot/CompressedOops
[classes]: https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/ClassLoadingMXBean.html
[unloading]: https://docs.oracle.com/javase/specs/jls/se26/html/jls-12.html#jls-12.7
