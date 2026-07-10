# Memory advisor checks

The Memory panel runs a fixed, on-demand ruleset against the host JVM's **live management beans**. It takes a read-only snapshot of heap and memory-pool usage, garbage-collector and class-loading counters, a thread census, process-level scalars (uptime, cumulative GC time, pending finalizers, parsed `-Xms`/`-Xss`, physical memory, and swap), and an optional class histogram, then evaluates a curated set of memory-health checks. Rules read only this immutable snapshot; they never perform their own JMX or filesystem I/O. The advisor never mutates the JVM, forces a heap dump on page load, intercepts live traffic, or surfaces secrets.

Most checks are single-snapshot heuristics, while the recent-GC check compares the current scan with the previous scan and heap-pressure checks prefer post-histogram heap readings when available. Snapshot and lifetime counters can still be skewed by transient garbage, startup spikes, or past bursts, so several findings are explicit prompts to confirm with a second reading, the Live Memory panel, or a profiler rather than verdicts. The right remediation still depends on the application's workload, heap sizing, and deployment topology.

This advisor is complementary to the **Live Memory** and **Threads** panels: those show the raw, continuously-updating numbers, whereas the Memory advisor diagnoses them into severity-ranked findings.

## Availability and bounds

The panel is always available (the JVM always exposes memory and thread beans). Readings that a particular JVM does not expose — a class histogram, per-thread CPU time, cumulative GC time, a previous-GC baseline, a container memory limit — are skipped gracefully and reported rather than failing the panel. The class histogram is only collected on an explicit scan. The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id.

## Known limitations

- **Container-limit detection assumes the process's cgroup is the container root.** Every container-aware check (MEM-FOOTPRINT-001/002/003/004, MEM-GC-001/004/007, MEM-POOL-004) uses one shared detector for the fixed root-level cgroup limit/current paths (for example `/sys/fs/cgroup/memory.max`). This is correct under the default modern Docker/Kubernetes setup where the process's own cgroup **is** the container root. It is not correct under `--cgroupns=host` or some older/nested container runtimes, where the process's actual cgroup lives elsewhere in the hierarchy; in that situation these paths read as "no container memory limit detected." A full fix would resolve the process's cgroup path via `/proc/self/cgroup` and `/proc/self/mountinfo`; no supported public JVM API exposes HotSpot's internal cgroup metrics.

## Severity scale

- **CRITICAL** - an active fault (such as a deadlock) that is already harming the application.
- **HIGH** - a condition that commonly causes outages or OOM kills and usually needs attention before production.
- **MEDIUM** - a sizing or contention gap that warrants review.
- **LOW** - lower-impact hygiene or tuning findings.
- **INFO** - informational prompts where the right fix depends heavily on project context.

---

## Heap pressure

### MEM-HEAP-001 - Heap utilization is critically high

- **Severity**: HIGH (MEDIUM when only a pre-GC snapshot is available)
- **Detects**: Live heap usage is very close to the maximum heap. When post-histogram heap data is available, the rule fires only if the heap is still at least 95% full after a full GC, indicating sustained retained pressure; if post-GC data is unavailable, a single pre-GC snapshot at the same threshold is reported at MEDIUM until confirmed.
- **Recommendation**: Increase -Xmx (or MaxRAMPercentage), reduce retained objects, or profile the heap to find the growth source.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html>

### MEM-HEAP-002 - Old generation is near its maximum

- **Severity**: MEDIUM
- **Detects**: The tenured/old-generation pool is nearly full, a common precursor to full GCs and promotion failures. The scan prefers post-GC occupancy after the histogram's full GC so it reflects long-lived retention rather than reclaimable garbage.
- **Recommendation**: Investigate long-lived object retention; consider raising the heap size or tuning the generation sizes for the active collector.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html>

### MEM-HEAP-003 - Maximum heap is capped well below the container limit

- **Severity**: LOW
- **Detects**: A small max heap that is already under pressure while a much larger container memory limit is available to grow into. The check prefers post-GC heap occupancy (consistent with MEM-HEAP-001/002) to avoid false positives from transient garbage. The previously documented "effectively unbounded" detection branch has been removed; HotSpot effectively never reports an unbounded heap max, and that branch was dead code.
- **Recommendation**: Set an explicit -Xmx or -XX:MaxRAMPercentage that lets the heap use a sensible share of the container memory limit instead of staying small while under pressure.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html>

### MEM-HEAP-004 - Max heap is just above the compressed-oops threshold

- **Severity**: INFO
- **Detects**: A max heap in the narrow 25% window just above the boundary where the JVM disables compressed ordinary object pointers, after which 64-bit references take more space and a heap just over the boundary can hold fewer live objects than one capped just below it. The documented boundary is `4 GiB * ObjectAlignmentInBytes` (32 GiB at the default 8-byte alignment), so the default warning window is above 32 GiB through 40 GiB; deliberately larger heaps such as 47 GiB are not described as "just above." The rule reads the live `UseCompressedOops` VM option via HotSpot JMX when available, falling back to the input-arguments heuristic on non-HotSpot JVMs; it is skipped for ZGC and when compressed oops are disabled.
- **Recommendation**: Either cap the heap just below the compressed-oops boundary, or grow it well past this range (and scale out) when a larger heap is genuinely required.
- **Learn more**: <https://wiki.openjdk.org/display/HotSpot/CompressedOops>

### MEM-HEAP-006 - Objects are backing up awaiting finalization

- **Severity**: LOW
- **Detects**: A large backlog of objects pending finalization. The finalizer thread cannot keep up, so these objects (and any native resources they hold) are retained longer than expected. Finalization is deprecated for removal (JEP 421); a backlog usually points to legacy finalizers.
- **Recommendation**: Replace finalizers with try-with-resources, java.lang.ref.Cleaner, or explicit close() methods, and ensure resources are released promptly.
- **Learn more**: <https://openjdk.org/jeps/421>

### MEM-HEAP-007 - Committed heap is far above post-GC live data

- **Severity**: INFO
- **Detects**: When the committed heap is at least twice the post-GC live set and the slack is at least 1 GiB, after at least 10 minutes of uptime. This suggests the heap is over-provisioned: the JVM has committed memory to the OS that the application consistently does not need. Reducing -Xmx can free host memory for other workloads without harming the application.
- **Recommendation**: Consider lowering -Xmx (or -XX:MaxRAMPercentage) closer to the observed post-GC live set to free host memory; alternatively, confirm the oversized heap is intentional to absorb allocation bursts or reduce GC frequency.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html>

### MEM-HEAP-008 - Post-GC old-generation usage is trending upward across scans

- **Severity**: MEDIUM
- **Detects**: Tracks post-GC old-generation usage (the same reading MEM-HEAP-002 compares against a static percentage) across consecutive user-triggered scans and flags a monotonic increase over the last 3 consecutive scans with no decrease in between, independent of the absolute percentage. This is the standard textbook Java heap-leak diagnostic — retained-size growth across successive full GCs — and can catch a slow leak (for example, one climbing steadily through 40% old-generation usage) well before MEM-HEAP-002's static high-water-mark threshold fires. Requires several consecutive scans to build a trend; the first scans only establish the baseline.
- **Recommendation**: Take a heap dump and compare successive class histograms (the Heap Dump panel) to find the retained-object type driving the growth, and confirm with a profiler whether this is a real leak or a temporarily growing cache/working set.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html>

## Native memory

### MEM-FOOTPRINT-001 - Configured JVM memory leaves little container headroom

- **Severity**: HIGH
- **Detects**: Estimates the configured JVM memory envelope (maximum heap, currently committed non-heap such as Metaspace and code cache, direct-buffer capacity, and an approximate thread-stack reservation) against the detected container limit. At 90% or more, the configuration leaves too little room for native memory as the heap grows. Using maximum rather than currently committed heap catches unsafe sizing before that memory is committed. The estimate is conservative but incomplete: it excludes GC structures, JIT working memory, native libraries, and non-NIO native allocations.
- **Recommendation**: Lower -Xmx/-XX:MaxRAMPercentage, reduce thread counts or direct-buffer use, or raise the container memory limit so the configured envelope keeps native headroom.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html>

### MEM-FOOTPRINT-002 - Platform thread stacks reserve a large amount of native memory

- **Severity**: MEDIUM; HIGH only when the reservation is also a confirmed resident risk (see below)
- **Detects**: Estimates native memory reserved for platform thread stacks (live platform threads times the -Xss/-XX:ThreadStackSize reservation) and flags when stacks alone reserve at least 1 GiB or at least 20% of the detected container memory limit. The actual stack size is read from the live `ThreadStackSize` JVM option via HotSpot JMX when available, falling back to any -Xss/-XX:ThreadStackSize command-line flag, and finally to a 1 MiB compile-time default. Thread stacks are demand-paged virtual-memory reservations, not committed/resident memory (kernel.org: cgroup `memory.current` tracks charged/resident memory, not reservations; the `/proc` docs' own VmSize-vs-VmRSS example shows a roughly 10x virtual-vs-resident gap) — a JVM with many idle or shallow-call-depth threads can reserve a large amount of stack memory while only a small fraction of it is ever touched (becomes resident), so a large reservation alone does not prove memory pressure. Severity is HIGH only when the reservation is both a large share (>=20%) of a detected container memory limit **and**, combined with memory already resident in the container, fully realizing the reservation would breach that limit (`current + reserved >= limit`) — i.e. there is confirmed resident risk, not just a large reservation. Otherwise the finding is reported at MEDIUM: still worth reviewing, but without confirmed resident risk it may never materialize as actual memory pressure. Virtual threads are excluded because their stacks live on the heap.
- **Recommendation**: Reduce the platform thread count (bound pools, prefer virtual threads or async I/O) or lower an oversized -Xss so thread stacks do not dominate native memory.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html>

### MEM-FOOTPRINT-003 - Container memory usage is near the cgroup limit

- **Severity**: HIGH
- **Detects**: Reads current container memory usage from cgroup files (cgroup v2: `memory.current`; cgroup v1: `memory.usage_in_bytes`) and compares it against the detected container memory limit. When usage reaches 90% of the limit the container is at immediate risk of an OOM kill by the kernel, which is abrupt and does not invoke JVM OutOfMemoryError handling or heap-dump logic. Caveat: raw cgroup current-usage numbers can overstate real memory pressure — cgroup v2's `memory.current` includes reclaimable page cache, not only the process's own footprint, and cgroup v1's `memory.usage_in_bytes` is documented by the kernel itself as an approximate "fuzz value" (the kernel's own guidance for an exact figure is `memory.stat`'s RSS+CACHE breakdown). Treat a reading near the limit as a strong signal to investigate, not an exact resident-set measurement.
- **Recommendation**: Lower -Xmx/-XX:MaxRAMPercentage, reduce non-heap footprint (thread stacks, Metaspace, direct buffers), or raise the container memory limit to restore headroom.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html>

### MEM-FOOTPRINT-004 - High swap utilization while JVM footprint exceeds free physical memory

- **Severity**: MEDIUM
- **Detects**: Uses swap and free-physical-memory values collected once with the rest of the runtime snapshot, and flags when used swap is at least 50% of total swap AND the estimated JVM committed footprint (heap + non-heap + direct buffers + thread-stack reservation) exceeds free physical memory. This combination suggests the JVM may be partially swapped out. The check is skipped where these operating-system MXBean values are unavailable.
- **Recommendation**: Reduce the JVM's committed footprint (lower -Xmx, reduce thread count, tune direct-buffer use) or add physical memory; avoid large heaps on hosts with active swap.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html>

## Memory pools

### MEM-POOL-001 - Metaspace is close to its maximum

- **Severity**: MEDIUM
- **Detects**: The Metaspace pool is nearly full, which can cause OutOfMemoryError: Metaspace, often from classloader leaks or excessive dynamic class generation.
- **Recommendation**: Raise -XX:MaxMetaspaceSize, or investigate classloader leaks and runtime class generation (proxies, scripting).
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/vm/class-metadata.html>

### MEM-POOL-002 - Code cache is close to its maximum

- **Severity**: MEDIUM
- **Detects**: Any JIT code-cache segment is nearly full. With tiered compilation the cache is split into separate segments (non-nmethods, profiled, non-profiled); a single saturated segment can stop the JIT even when the aggregate looks healthy, after which the application falls back to slower interpreted execution.
- **Recommendation**: Increase -XX:ReservedCodeCacheSize, or reduce the amount of compiled code (fewer megamorphic call sites, less code).
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/vm/codecache.html>

### MEM-POOL-003 - Direct buffer usage is high

- **Severity**: LOW
- **Detects**: java.nio direct (off-heap) buffer capacity that is near an explicit -XX:MaxDirectMemorySize cap, or that is large relative to the effective HotSpot default cap. When -XX:MaxDirectMemorySize is not set, HotSpot defaults the direct-memory cap to the max heap (-Xmx), so the rule compares against that value rather than treating the absence of an explicit flag as "no cap". Direct memory is not bounded by -Xmx and can leak native memory.
- **Recommendation**: Audit direct ByteBuffer allocations and pooling; set or raise -XX:MaxDirectMemorySize and ensure buffers are released.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html>

### MEM-POOL-004 - Metaspace is unbounded inside a memory-limited container

- **Severity**: LOW
- **Detects**: A container memory limit with no -XX:MaxMetaspaceSize while Metaspace is already sizable. Unbounded Metaspace can grow until the container is OOM-killed by the kernel instead of failing with a graceful OutOfMemoryError: Metaspace.
- **Recommendation**: Set -XX:MaxMetaspaceSize to a sensible ceiling so class-metadata growth fails fast inside the JVM rather than triggering a kernel OOM kill.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/vm/class-metadata.html>

### MEM-POOL-005 - Compressed Class Space is close to its maximum

- **Severity**: MEDIUM
- **Detects**: The Compressed Class Space pool is at or above 85% of its cap. This pool holds the compressed representation of class metadata in the compressed-oops range and has a hard default cap of 1 GiB even when -XX:MaxMetaspaceSize is unset. Exhaustion causes OutOfMemoryError: Compressed class space, which is distinct from OutOfMemoryError: Metaspace.
- **Recommendation**: Increase -XX:CompressedClassSpaceSize (or reduce dynamic class generation); also set -XX:MaxMetaspaceSize so broader Metaspace growth is bounded.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/vm/class-metadata.html>

### MEM-POOL-006 - JIT compiler is disabled or capped below full optimization

- **Severity**: LOW
- **Detects**: `-Xint` (fully interpreted mode), `-XX:-UseCompiler` (JIT disabled), or `-XX:TieredStopAtLevel<4` (JIT capped before C2 full optimization) in the JVM input arguments. These flags are used for debugging and profiling but left in production significantly reduce throughput and increase CPU usage, which can manifest as elevated heap pressure due to longer-living objects.
- **Recommendation**: Remove -Xint, -XX:-UseCompiler, or -XX:TieredStopAtLevel<4 from production JVM arguments unless specifically required for a diagnostic session.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/vm/java-virtual-machine-technology-overview.html>

### MEM-POOL-007 - Buffer pool usage has grown on every recent scan without release

- **Severity**: MEDIUM (HIGH when the growing pool is the "direct" pool and MEM-POOL-003's static threshold has also been crossed)
- **Detects**: Tracks each java.nio buffer pool's (`BufferPoolMXBean`, typically "direct" and "mapped") used-byte reading across scans and flags a pool whose usage has strictly increased on every one of the last 3 consecutive scans with no decrease in between — the classic native-memory-leak signature of leaked direct/mapped `ByteBuffer`s (common with NIO channel or Netty-style misuse where buffers are allocated but never released). This can catch a leak while the pool is still well under MEM-POOL-003's static high-water threshold, since a monotonic trend is a stronger signal than any single absolute reading. Requires several consecutive user-triggered scans to build a trend; the first scans only establish the baseline.
- **Recommendation**: Audit code paths that allocate direct or mapped ByteBuffers (including NIO channels and libraries like Netty) for missing release/cleaner calls, and confirm the pool eventually plateaus or shrinks under normal load instead of only ever growing.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/BufferPoolMXBean.html>

## GC configuration

### MEM-GC-001 - Heap sizing is left to default container ergonomics

- **Severity**: INFO
- **Detects**: A detected container memory limit with neither -Xmx nor -XX:MaxRAMPercentage set. The JVM is container-aware and defaults the max heap to about 25% of the limit, which is safe but conservative and easy to overlook.
- **Recommendation**: Set -XX:MaxRAMPercentage (or an explicit -Xmx) if you want the heap sized deliberately rather than at the ~25% default.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html>

### MEM-GC-002 - Cumulative GC time is a large share of uptime

- **Severity**: MEDIUM
- **Detects**: After at least 10 minutes of uptime, flags when total **stop-the-world** collection time is at least 10% of JVM uptime. Concurrent-cycle beans (ZGC Cycles, Shenandoah Cycles, G1 Concurrent GC) are excluded because their concurrent phases run while the application executes. No collector-independent universal threshold exists, so the rule retains this conservative review threshold rather than tuning it from anecdotal application sizes. The cumulative average can be skewed by startup, so corroborate it with recent GC metrics.
- **Recommendation**: Increase the heap (-Xmx/-XX:MaxRAMPercentage), reduce the allocation rate, or review the collector choice if GC consistently consumes this much time.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html>

### MEM-GC-003 - Recent GC overhead is high

- **Severity**: MEDIUM (HIGH when recent GC overhead is at least 25%)
- **Detects**: Compares **stop-the-world** GC time against wall-clock time over the interval between the last two scans, excluding the scan's own forced histogram GC. Concurrent-cycle beans (ZGC Cycles, Shenandoah Cycles, G1 Concurrent GC) are excluded from the time delta to avoid false positives with concurrent collectors. The first scan only establishes a baseline; later scans fire when GC used at least 10% of the interval, and escalate to HIGH at 25%.
- **Recommendation**: Re-run the scan after a representative workload; if recent GC overhead stays high, increase the heap (-Xmx/-XX:MaxRAMPercentage), reduce the allocation rate, or review the collector choice.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/GarbageCollectorMXBean.html>

### MEM-GC-004 - Serial GC selected on a multi-core system

- **Severity**: LOW
- **Detects**: The Serial GC collector (GarbageCollectorMXBean names "Copy" and/or "MarkSweepCompact") running on a JVM with two or more available processors **and** roughly 2 GiB or more of memory (the detected container memory limit, else total physical memory). On supported JDK 17/21/25 releases, G1 is selected ergonomically for server-class machines while Serial remains expected in constrained environments, so the rule avoids false positives on small containers. Above both thresholds, explicitly or unexpectedly staying on Serial underutilises multi-core hosts and causes long stop-the-world pauses.
- **Recommendation**: Switch to G1 (-XX:+UseG1GC), ZGC (-XX:+UseZGC), or Parallel GC (-XX:+UseParallelGC) to use all available cores, unless binary size or footprint constraints explicitly require Serial.
- **Learn more**: <https://openjdk.org/jeps/248>

### MEM-GC-005 - G1 Full GC occurred between scans

- **Severity**: MEDIUM
- **Detects**: An increase in the "G1 Old Generation" GarbageCollectorMXBean collection count between two consecutive scans. A G1 Full GC is G1's fallback path, triggered when its normal concurrent-marking/mixed-collection cycle could not keep up with the allocation rate (to-space exhaustion, humongous-allocation failure, or concurrent mark failure). Since JDK 10 (JEP 307, "Parallel Full GC for G1") this fallback runs on multiple threads, so it is not single-threaded — but it is still a fully stop-the-world pause across the entire heap; even one Full GC per scan window is a sign that G1 failed to reclaim memory through its normal cycle and indicates heap or tuning pressure. The first scan only establishes a baseline.
- **Recommendation**: Increase -Xmx or tune -XX:G1HeapRegionSize to reduce humongous allocations; consider -XX:G1ReservePercent and -XX:InitiatingHeapOccupancyPercent to give G1 more headroom for concurrent marking.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html>

### MEM-GC-006 - Most recently completed GC event was long

- **Severity**: MEDIUM
- **Detects**: Reads each HotSpot collector bean's `getLastGcInfo()`, compares `GcInfo.endTime` values on their shared JVM-uptime time base, and evaluates the event that actually completed most recently. It flags when that event's duration is at least 1000 ms. Duration is elapsed collection time and is not necessarily a stop-the-world pause for a concurrent collector, so the finding deliberately says "GC event" rather than "pause." This is a fresh single-sample reading, not a cross-scan trend.
- **Recommendation**: Capture unified GC logs (`-Xlog:gc*:file=gc.log:time,level,tags`) and inspect the event's phases and cause before tuning heap size, allocation rate, or collector pause goals.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/GcInfo.html>

### MEM-GC-007 - JVM container awareness is explicitly disabled

- **Severity**: HIGH
- **Detects**: A visible cgroup memory limit together with `-XX:-UseContainerSupport`. Container support defaults to enabled on JDK 17/21/25; disabling it makes JVM ergonomics use host-level memory and CPU information, which can oversize the heap, GC/JIT worker pools, and common pools relative to the container and lead to throttling or an abrupt cgroup OOM kill.
- **Recommendation**: Remove `-XX:-UseContainerSupport` so JVM ergonomics respect container limits. Use explicit `-Xmx`/`-XX:MaxRAMPercentage` and `-XX:ActiveProcessorCount` only for deliberate overrides.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html>

### MEM-HEAP-005 - Initial and maximum heap differ for a low-latency collector

- **Severity**: INFO
- **Detects**: For low-latency collectors (ZGC, Shenandoah), a smaller -Xms than -Xmx makes the JVM grow and re-commit the heap on demand, which can add latency and commit/uncommit churn. Equal -Xms and -Xmx keep the heap fully committed for steady-state, latency-sensitive services. When -Xms is not set in the input arguments the rule falls back to `MemoryMXBean.getHeapMemoryUsage().getInit()`, which returns the ergonomic default initial heap, to avoid false skips on JVMs where the flag is set via environment or ergonomics rather than an explicit `-Xms`.
- **Recommendation**: For latency-sensitive services using ZGC or Shenandoah, set -Xms equal to -Xmx so the heap is fully committed up front; also consider -XX:+AlwaysPreTouch to touch every heap page at startup and avoid OS demand-paging latency during warmup.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/gctuning/z-garbage-collector.html>

## Threads

### MEM-THREAD-001 - Thread deadlock detected

- **Severity**: CRITICAL
- **Detects**: Platform threads blocked in a cycle of lock acquisition; deadlocked threads make no progress and can hang request processing.
- **Recommendation**: Inspect the deadlocked threads in the Threads panel, then establish a consistent global lock-ordering or use tryLock with timeouts.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html#findDeadlockedThreads()>

### MEM-THREAD-002 - High proportion of BLOCKED threads

- **Severity**: MEDIUM
- **Detects**: A large share of live threads are BLOCKED waiting for monitors, indicating lock contention that limits throughput. Two trigger paths: (1) **ratio path** — at least 5 BLOCKED threads and ≥25% of all live threads; (2) **absolute path** — at least 20 BLOCKED threads and ≥10% of all live threads (the 10% floor prevents false positives in large thread pools where 20 blocked threads may represent a small fraction). Both paths apply to a single snapshot; a transient burst can trigger the rule so confirm the finding persists before acting.
- **Recommendation**: Identify the contended lock in the Threads panel and reduce the critical section, shard the lock, or use lock-free structures.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.State.html>

### MEM-THREAD-003 - Peak thread count was far above the current count

- **Severity**: INFO
- **Detects**: A large gap between the all-time peak platform-thread count and the current live count. The peak is monotonic since JVM start, so this reflects a past burst (pool churn or a transient spike) rather than a current leak; treat it as historical context to correlate with a live thread trend, not as evidence of a present problem.
- **Recommendation**: Review thread-pool sizing and lifecycle; bound pool sizes and ensure short-lived threads are not created per request if these bursts recur.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html>

### MEM-THREAD-004 - Runnable threads with very high lifetime CPU usage

- **Severity**: INFO
- **Detects**: RUNNABLE threads whose accumulated CPU time is a large fraction of the JVM's uptime, i.e. they have kept a core busy for much of the process's life. CPU time is cumulative since the thread started, so this is a hot-loop candidate to investigate, not a confirmed problem.
- **Recommendation**: Correlate with two consecutive thread snapshots; if CPU keeps climbing for the same thread, profile its stack for a hot or spinning loop.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html>

## Heap content

Heap-content checks require a class histogram, which is collected only on an explicit scan. They report **shallow** sizes (the bytes attributed to the instances of a class by `GC.class_histogram`), not retained sizes, so a flagged class is a starting point for investigation rather than a confirmed leak.

### MEM-CONTENT-001 - Classes with very large average instance size

- **Severity**: INFO
- **Detects**: Classes whose average shallow size per instance is large; these big objects dominate allocation, can become G1 humongous allocations, and may fragment the heap.
- **Recommendation**: Review whether these objects can be streamed, paged, or pooled instead of held whole in memory.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html>

### MEM-CONTENT-002 - Collections occupy a large share of the heap

- **Severity**: MEDIUM (LOW when only shallow evidence is weak)
- **Detects**: JDK collection or map classes, including their node/entry backing structures, that occupy a large amount of heap by shallow histogram bytes. Severity is raised when the combined collection footprint is a large share of the sampled heap and softened when it is a single shallow contributor; array backing storage such as ArrayList's Object[] is reported separately by MEM-CONTENT-004.
- **Recommendation**: Confirm whether the offending collection is bounded; if it is meant to be a cache, give it an eviction policy or size limit and verify entries are removed.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html>

### MEM-CONTENT-003 - A single class dominates the sampled heap

- **Severity**: LOW
- **Detects**: One class (excluding **all** array classes — primitive arrays such as `byte[]`/`char[]` as well as `Object[]` and other reference arrays — which are routinely dominant and reported in aggregate by MEM-CONTENT-004) occupies a large fraction of the sampled heap by shallow bytes. Previously only primitive arrays were excluded, allowing `Object[]` (which backs most collections and is often the top class) to trigger this rule and overlap with MEM-CONTENT-004; the fix excludes all types whose normalised name ends with `[]`.
- **Recommendation**: Confirm the dominant class is expected; if not, trace its references to find what keeps the instances alive.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html>

### MEM-CONTENT-004 - Arrays dominate the sampled heap

- **Severity**: INFO
- **Detects**: Array classes (primitive arrays such as byte[]/char[], Object[], and map-node arrays) together occupy at least half of the post-GC histogram bytes. Array dominance is often normal, but it complements the collection view in MEM-CONTENT-002 and the single-dominant-class view in MEM-CONTENT-003 by surfacing aggregate backing storage that those rules exclude.
- **Recommendation**: Inspect the top array classes below; if growth is unexpected, trace what retains the backing arrays (oversized buffers, unbounded lists/maps, or duplicated byte[]/char[] data).
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html>

## Class loading

### MEM-CLASS-001 - Very large number of loaded classes with little unloading

- **Severity**: INFO
- **Detects**: At least 50,000 currently loaded classes with less than 1% as many unloads, which can indicate a classloader leak or runaway dynamic class generation and pressures Metaspace. No OpenJDK/Oracle source defines a universally healthy class count, so this remains an INFO-level review heuristic and was not retuned from anecdotal application sizes. Frameworks that generate proxies and configuration classes at runtime can structurally load more classes than build-time-oriented frameworks; compare repeated readings and Metaspace pressure rather than treating the count alone as proof of a leak.
- **Recommendation**: If the application does not legitimately use this many classes, look for classloader leaks (redeploys, scripting, proxy generation).
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html>

### MEM-CLASS-002 - High class-loading churn

- **Severity**: LOW
- **Detects**: Heavy class unloading, either a large absolute count or a high sustained unload rate over the JVM's lifetime. Persistent churn points to dynamic proxy/CGLIB generation, scripting, or redeploy-style classloader cycling that strains Metaspace and the GC.
- **Recommendation**: Identify the source of dynamic class generation (proxies, scripting engines, repeated context refreshes) and cache or bound it.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html>
