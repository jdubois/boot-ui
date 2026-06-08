# Memory advisor checks

The Memory panel runs a fixed, on-demand ruleset against the host JVM's **live management beans**. It takes a read-only snapshot of heap and memory-pool usage, garbage-collector and class-loading counters, a thread census, a handful of process-level scalars (uptime, cumulative GC time, pending finalizers, parsed `-Xms`/`-Xss`), and an optional class histogram, then evaluates a curated set of memory-health checks. It never mutates the JVM, forces a heap dump on page load, intercepts live traffic, or surfaces secrets.

Most checks are single-snapshot heuristics, while the recent-GC check compares the current scan with the previous scan and heap-pressure checks prefer post-histogram heap readings when available. Snapshot and lifetime counters can still be skewed by transient garbage, startup spikes, or past bursts, so several findings are explicit prompts to confirm with a second reading, the Live Memory panel, or a profiler rather than verdicts. The right remediation still depends on the application's workload, heap sizing, and deployment topology.

This advisor is complementary to the **Live Memory** and **Threads** panels: those show the raw, continuously-updating numbers, whereas the Memory advisor diagnoses them into severity-ranked findings.

## Availability and bounds

The panel is always available (the JVM always exposes memory and thread beans). Readings that a particular JVM does not expose — a class histogram, per-thread CPU time, cumulative GC time, a previous-GC baseline, a container memory limit — are skipped gracefully and reported rather than failing the panel. The class histogram is only collected on an explicit scan. The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id.

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

### MEM-HEAP-003 - Maximum heap is unset or capped well below the container limit

- **Severity**: LOW
- **Detects**: -Xmx is effectively unbounded, or a small max heap is already under pressure while a much larger container memory limit is available to grow into.
- **Recommendation**: Set an explicit -Xmx or -XX:MaxRAMPercentage that lets the heap use a sensible share of the container memory limit instead of staying small while under pressure.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html>

### MEM-HEAP-004 - Max heap is just above the compressed-oops threshold

- **Severity**: INFO
- **Detects**: A max heap just above the boundary where the JVM disables compressed ordinary object pointers, after which 64-bit references take more space and a heap just over the boundary can hold fewer live objects than one capped just below it. The boundary defaults to ~32 GiB but scales with -XX:ObjectAlignmentInBytes; the check is skipped for ZGC and when compressed oops are explicitly disabled.
- **Recommendation**: Either cap the heap just below the compressed-oops boundary, or grow it well past this range (and scale out) when a larger heap is genuinely required.
- **Learn more**: <https://wiki.openjdk.org/display/HotSpot/CompressedOops>

### MEM-HEAP-006 - Objects are backing up awaiting finalization

- **Severity**: LOW
- **Detects**: A large backlog of objects pending finalization. The finalizer thread cannot keep up, so these objects (and any native resources they hold) are retained longer than expected. Finalization is deprecated for removal (JEP 421); a backlog usually points to legacy finalizers.
- **Recommendation**: Replace finalizers with try-with-resources, java.lang.ref.Cleaner, or explicit close() methods, and ensure resources are released promptly.
- **Learn more**: <https://openjdk.org/jeps/421>

## Native memory

### MEM-FOOTPRINT-001 - Committed native footprint is close to the container limit

- **Severity**: HIGH
- **Detects**: Estimates the JVM's committed native-memory budget (committed heap, committed non-heap such as Metaspace and code cache, direct buffers, and an approximate thread-stack reservation) against the detected container memory limit. When this estimate approaches the limit the container can be OOM-killed by the kernel even though the heap alone looks healthy. The estimate is approximate and excludes some JVM/native overhead (GC structures, JIT, native libraries).
- **Recommendation**: Lower -Xmx/-XX:MaxRAMPercentage, reduce thread counts or direct-buffer use, or raise the container memory limit so the total committed footprint keeps headroom.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html>

### MEM-FOOTPRINT-002 - Platform thread stacks reserve a large amount of native memory

- **Severity**: HIGH
- **Detects**: Estimates native memory reserved for platform thread stacks (live platform threads times the -Xss/-XX:ThreadStackSize reservation) and flags when stacks alone reserve at least 1 GiB or at least 20% of the detected container memory limit. This is reservation, not necessarily resident memory, and virtual threads are excluded because their stacks live on the heap.
- **Recommendation**: Reduce the platform thread count (bound pools, prefer virtual threads or async I/O) or lower an oversized -Xss so thread stacks do not dominate native memory.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html>

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
- **Detects**: java.nio direct (off-heap) buffer capacity that is near an explicit -XX:MaxDirectMemorySize, or large without any cap. Direct memory is not bounded by -Xmx and can leak native memory.
- **Recommendation**: Audit direct ByteBuffer allocations and pooling; set or raise -XX:MaxDirectMemorySize and ensure buffers are released.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html>

### MEM-POOL-004 - Metaspace is unbounded inside a memory-limited container

- **Severity**: LOW
- **Detects**: A container memory limit with no -XX:MaxMetaspaceSize while Metaspace is already sizable. Unbounded Metaspace can grow until the container is OOM-killed by the kernel instead of failing with a graceful OutOfMemoryError: Metaspace.
- **Recommendation**: Set -XX:MaxMetaspaceSize to a sensible ceiling so class-metadata growth fails fast inside the JVM rather than triggering a kernel OOM kill.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/vm/class-metadata.html>

## GC configuration

### MEM-GC-001 - Heap sizing is left to default container ergonomics

- **Severity**: INFO
- **Detects**: A detected container memory limit with neither -Xmx nor -XX:MaxRAMPercentage set. The JVM is container-aware and defaults the max heap to about 25% of the limit, which is safe but conservative and easy to overlook.
- **Recommendation**: Set -XX:MaxRAMPercentage (or an explicit -Xmx) if you want the heap sized deliberately rather than at the ~25% default.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html>

### MEM-GC-002 - Cumulative GC time is a large share of uptime

- **Severity**: MEDIUM
- **Detects**: Compares total time spent in garbage collection since JVM start against the JVM uptime. A high lifetime ratio is a classic sign of an undersized heap or an excessive allocation rate. This is a cumulative average and can be skewed by a one-off startup spike, so corroborate with live GC metrics.
- **Recommendation**: Increase the heap (-Xmx/-XX:MaxRAMPercentage), reduce the allocation rate, or review the collector choice if GC consistently consumes this much time.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html>

### MEM-GC-003 - Recent GC overhead is high

- **Severity**: MEDIUM (HIGH when recent GC overhead is at least 25%)
- **Detects**: Compares time spent in garbage collection against wall-clock time over the interval between the last two scans, excluding the scan's own forced histogram GC. The first scan only establishes a baseline; later scans fire when GC used at least 10% of the interval, and escalate to HIGH at 25%.
- **Recommendation**: Re-run the scan after a representative workload; if recent GC overhead stays high, increase the heap (-Xmx/-XX:MaxRAMPercentage), reduce the allocation rate, or review the collector choice.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/GarbageCollectorMXBean.html>

### MEM-HEAP-005 - Initial and maximum heap differ for a low-latency collector

- **Severity**: INFO
- **Detects**: For low-latency collectors (ZGC, Shenandoah), a smaller -Xms than -Xmx makes the JVM grow and re-commit the heap on demand, which can add latency and commit/uncommit churn. Equal -Xms and -Xmx keep the heap fully committed for steady-state, latency-sensitive services.
- **Recommendation**: For latency-sensitive services using ZGC or Shenandoah, set -Xms equal to -Xmx so the heap is fully committed up front.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/gctuning/z-garbage-collector.html>

## Threads

### MEM-THREAD-001 - Thread deadlock detected

- **Severity**: CRITICAL
- **Detects**: Platform threads blocked in a cycle of lock acquisition; deadlocked threads make no progress and can hang request processing.
- **Recommendation**: Inspect the deadlocked threads in the Threads panel, then establish a consistent global lock-ordering or use tryLock with timeouts.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html#findDeadlockedThreads()>

### MEM-THREAD-002 - High proportion of BLOCKED threads

- **Severity**: MEDIUM
- **Detects**: A large share of live threads are BLOCKED waiting for monitors, indicating lock contention that limits throughput.
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
- **Detects**: One class (excluding primitive arrays, which are routinely dominant) occupies a large fraction of the sampled heap by shallow bytes; a strongly dominant top class is worth understanding even if expected.
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
- **Detects**: A high loaded-class count combined with little or no class unloading, which can indicate a classloader leak or runaway dynamic class generation and pressures Metaspace. A large class count that is matched by active unloading is treated as a legitimately large application instead.
- **Recommendation**: If the application does not legitimately use this many classes, look for classloader leaks (redeploys, scripting, proxy generation).
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html>

### MEM-CLASS-002 - High class-loading churn

- **Severity**: LOW
- **Detects**: Heavy class unloading, either a large absolute count or a high sustained unload rate over the JVM's lifetime. Persistent churn points to dynamic proxy/CGLIB generation, scripting, or redeploy-style classloader cycling that strains Metaspace and the GC.
- **Recommendation**: Identify the source of dynamic class generation (proxies, scripting engines, repeated context refreshes) and cache or bound it.
- **Learn more**: <https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html>
