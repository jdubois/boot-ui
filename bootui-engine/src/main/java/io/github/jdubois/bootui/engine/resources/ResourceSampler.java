package io.github.jdubois.bootui.engine.resources;

import io.github.jdubois.bootui.engine.journal.ThreadFamilies;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sweeps the JVM once per {@code bootui.resources.sample-interval} on one BootUI daemon thread, adding a point to the
 * {@link ResourceTrack} ({@code docs/PLAN-v2.md} §5.11).
 *
 * <p>The CPU ledger splits the process's CPU time in each interval three ways. For each platform thread it reads, the
 * share the {@link SegmentMeter} credited to requests goes to requests, and the rest to the thread's family, named as
 * the journal's aggregates name families ({@link ThreadFamilies}), with BootUI's own threads, this sampler included,
 * as {@link ResourceTrack#BOOTUI_FAMILY}. The process remainder includes GC, JIT, and VM threads, plus work on threads
 * that ended, went unread, or lacked consecutive CPU readings. A thread's first reading only establishes its baseline.
 * The process counter is never increased to match the thread counters; if those independent readings disagree, the
 * remainder is unknown. Otherwise the parts sum to measured process CPU; the first sweep only takes the baseline.</p>
 *
 * <p>Virtual threads run on carrier threads, so their CPU time appears in the carriers' family, never in requests.</p>
 */
public final class ResourceSampler implements AutoCloseable {

    /** The sampler thread's name; its {@code bootui-} prefix keeps its work out of the journal. */
    public static final String THREAD_NAME = "bootui-resources";

    private static final Logger log = Logger.getLogger(ResourceSampler.class.getName());

    private final ResourceSettings settings;
    private final ResourceTrack track;
    private final SegmentMeter meter;
    private final LongSupplier sequence;
    private final Probe probe;
    private final Map<Long, ThreadState> threads = new HashMap<>();
    private Thread thread;
    private volatile boolean running;
    private boolean baseline;
    private long lastNanos;
    private long lastProcessCpuNanos;

    ResourceSampler(
            ResourceSettings settings, ResourceTrack track, SegmentMeter meter, LongSupplier sequence, Probe probe) {
        this.settings = settings;
        this.track = track;
        this.meter = meter;
        this.sequence = sequence;
        this.probe = probe;
    }

    /** Starts sweeping the JVM into {@code track} on the sampler thread, until {@linkplain #close closed}. */
    public static ResourceSampler start(ResourceSettings settings, ResourceTrack track, LongSupplier sequence) {
        ResourceSampler sampler = new ResourceSampler(settings, track, SegmentMeter.shared(), sequence, new JvmProbe());
        sampler.running = true;
        sampler.thread = new Thread(sampler::loop, THREAD_NAME);
        sampler.thread.setDaemon(true);
        sampler.thread.start();
        return sampler;
    }

    @Override
    public void close() {
        running = false;
        Thread sampling = thread;
        if (sampling != null) {
            sampling.interrupt();
            try {
                sampling.join(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        long intervalMillis = settings.sampleInterval().toMillis();
        while (running) {
            try {
                sweep(System.currentTimeMillis(), System.nanoTime());
            } catch (Throwable ex) {
                // The sampler must survive anything a sweep throws, or the ledger would silently stop.
                log.log(Level.FINE, "BootUI's resource sampler skipped a sweep", ex);
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException ex) {
                return;
            }
        }
    }

    /** One sweep; the first only takes the baseline. */
    synchronized void sweep(long epochMillis, long nanoTime) {
        Sweep sweep = probe.threads(settings.maxThreads());
        long processCpu = probe.processCpuNanos();
        Set<Long> seen = new HashSet<>();
        long[] families = new long[ResourceTrack.MAX_FAMILIES + 1];
        long requests = 0;
        long threadsCpu = 0;
        long allocated = 0;
        boolean allocationKnown = true;
        for (ThreadReading reading : sweep.threads()) {
            seen.add(reading.id());
            ThreadState state = threads.computeIfAbsent(reading.id(), id -> new ThreadState());
            long attributed = reading.cpuNanos() < 0 ? -1 : meter.attributedCpuNanos(reading.id(), reading.cpuNanos());
            if (state.cpuNanos >= 0 && reading.cpuNanos() >= state.cpuNanos) {
                long cpuDelta = reading.cpuNanos() - state.cpuNanos;
                long requestDelta = Math.min(cpuDelta, Math.max(0, attributed - state.attributedCpuNanos));
                threadsCpu += cpuDelta;
                requests += requestDelta;
                int family = track.family(familyOf(reading.name()));
                families[family] += cpuDelta - requestDelta;
            }
            if (state.allocatedBytes < 0 || reading.allocatedBytes() < state.allocatedBytes) {
                allocationKnown = false;
            } else {
                allocated += reading.allocatedBytes() - state.allocatedBytes;
            }
            state.cpuNanos = reading.cpuNanos();
            state.attributedCpuNanos = attributed;
            state.allocatedBytes = reading.allocatedBytes();
        }
        threads.keySet().retainAll(seen);
        meter.forgetEndedThreads();
        boolean first = !baseline;
        baseline = true;
        long interval = nanoTime - lastNanos;
        long processDelta = processCpu < 0 || lastProcessCpuNanos < 0 ? -1 : processCpu - lastProcessCpuNanos;
        lastNanos = nanoTime;
        lastProcessCpuNanos = processCpu;
        if (first) {
            return;
        }
        long internal = processDelta < threadsCpu ? -1 : processDelta - threadsCpu;
        long[] heap = probe.heap();
        int[] counts = probe.threadCounts();
        int used = 0;
        for (int i = 0; i < families.length; i++) {
            if (families[i] != 0) {
                used = i + 1;
            }
        }
        long[] parts = new long[used];
        System.arraycopy(families, 0, parts, 0, used);
        track.add(new ResourceTrack.Point(
                epochMillis,
                sequence.getAsLong(),
                interval,
                processDelta < 0 ? -1 : processDelta,
                requests,
                internal,
                parts,
                sweep.unreadThreads(),
                heap[0],
                heap[1],
                heap[2],
                allocationKnown ? allocated : -1,
                counts[0],
                counts[1]));
    }

    static String familyOf(String threadName) {
        return threadName != null && threadName.startsWith("bootui-")
                ? ResourceTrack.BOOTUI_FAMILY
                : ThreadFamilies.of(threadName);
    }

    private static final class ThreadState {
        private long cpuNanos = -1;
        private long attributedCpuNanos = -1;
        private long allocatedBytes = -1;
    }

    /** One platform thread's readings; {@code -1} where the JVM does not measure them. */
    record ThreadReading(long id, String name, long cpuNanos, long allocatedBytes) {}

    /** The threads one sweep read, and how many it left unread beyond the cap. */
    record Sweep(List<ThreadReading> threads, int unreadThreads) {}

    /** Where a sweep's readings come from, so tests can drive the ledger deterministically. */
    interface Probe {

        Sweep threads(int maxThreads);

        /** The process's CPU time, or {@code -1} when the JVM does not report it. */
        long processCpuNanos();

        /** Heap used, committed, and used after each heap pool's last collection ({@code -1} when unknown). */
        long[] heap();

        /** Live and daemon threads. */
        int[] threadCounts();
    }

    /** The JVM's own readings; {@code com.sun.management} only through guarded nested classes. */
    private static final class JvmProbe implements Probe {

        private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final List<MemoryPoolMXBean> heapPools = heapPools();
        private final ThreadReadings readings = ThreadReadings.get();

        private static List<MemoryPoolMXBean> heapPools() {
            List<MemoryPoolMXBean> pools = new ArrayList<>();
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()) {
                    pools.add(pool);
                }
            }
            return pools;
        }

        @Override
        @SuppressWarnings("deprecation") // Thread.threadId() is JDK 19+; the baseline is 17.
        public Sweep threads(int maxThreads) {
            Thread[] all = platformThreads();
            int read = Math.min(all.length, maxThreads);
            List<ThreadReading> threads = new ArrayList<>(read);
            for (int i = 0; i < read; i++) {
                Thread candidate = all[i];
                long id = candidate.getId();
                threads.add(
                        new ThreadReading(id, candidate.getName(), readings.cpuNanos(id), readings.allocatedBytes(id)));
            }
            return new Sweep(threads, all.length - read);
        }

        private static Thread[] platformThreads() {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) {
                root = root.getParent();
            }
            Thread[] threads = new Thread[root.activeCount() * 2 + 16];
            int count;
            while ((count = root.enumerate(threads, true)) == threads.length) {
                threads = new Thread[threads.length * 2];
            }
            Thread[] live = new Thread[count];
            System.arraycopy(threads, 0, live, 0, count);
            return live;
        }

        @Override
        public long processCpuNanos() {
            try {
                return ProcessCpu.nanos(ManagementFactory.getOperatingSystemMXBean());
            } catch (RuntimeException | LinkageError ex) {
                return -1;
            }
        }

        @Override
        public long[] heap() {
            MemoryUsage usage = memory.getHeapMemoryUsage();
            long afterGc = 0;
            boolean known = !heapPools.isEmpty();
            for (MemoryPoolMXBean pool : heapPools) {
                MemoryUsage collection = pool.getCollectionUsage();
                if (collection == null) {
                    known = false;
                } else {
                    afterGc += collection.getUsed();
                }
            }
            return new long[] {usage.getUsed(), usage.getCommitted(), known ? afterGc : -1};
        }

        @Override
        public int[] threadCounts() {
            return new int[] {threadBean.getThreadCount(), threadBean.getDaemonThreadCount()};
        }
    }

    /** The only class that names {@code com.sun.management.OperatingSystemMXBean}, loaded on first use. */
    private static final class ProcessCpu {

        static long nanos(OperatingSystemMXBean os) {
            return os instanceof com.sun.management.OperatingSystemMXBean extended ? extended.getProcessCpuTime() : -1;
        }
    }
}
