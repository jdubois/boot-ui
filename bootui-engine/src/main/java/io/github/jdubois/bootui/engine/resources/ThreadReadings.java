package io.github.jdubois.bootui.engine.resources;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Reads a thread's CPU time and allocated bytes. CPU time comes from the standard {@link ThreadMXBean}; allocated bytes
 * come from {@code com.sun.management.ThreadMXBean}, reached only through {@link Extended}, so a runtime without it
 * reports allocation as unsupported instead of failing to load this class.
 *
 * <p>Every reading returns {@code -1} when the JVM does not measure it, which it also does for a virtual thread.</p>
 */
final class ThreadReadings {

    private static final ThreadReadings INSTANCE = load();

    private final ThreadMXBean threads;
    private final boolean cpu;
    private final boolean allocation;

    ThreadReadings(ThreadMXBean threads, boolean cpu, boolean allocation) {
        this.threads = threads;
        this.cpu = cpu;
        this.allocation = allocation;
    }

    static ThreadReadings get() {
        return INSTANCE;
    }

    private static ThreadReadings load() {
        ThreadMXBean threads;
        try {
            threads = ManagementFactory.getThreadMXBean();
        } catch (RuntimeException | LinkageError ex) {
            return new ThreadReadings(null, false, false);
        }
        boolean cpu;
        try {
            cpu = threads.isCurrentThreadCpuTimeSupported()
                    && threads.isThreadCpuTimeSupported()
                    && threads.isThreadCpuTimeEnabled();
        } catch (RuntimeException ex) {
            cpu = false;
        }
        boolean allocation;
        try {
            allocation = Extended.supports(threads);
        } catch (RuntimeException | LinkageError ex) {
            allocation = false;
        }
        return new ThreadReadings(threads, cpu, allocation);
    }

    /** Whether this JVM measures both CPU time and allocated bytes of a platform thread. */
    boolean supported() {
        return cpu && allocation;
    }

    long currentCpuNanos() {
        try {
            return cpu ? threads.getCurrentThreadCpuTime() : -1;
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    long currentAllocatedBytes() {
        try {
            return allocation ? Extended.currentAllocatedBytes(threads) : -1;
        } catch (RuntimeException | LinkageError ex) {
            return -1;
        }
    }

    long cpuNanos(long threadId) {
        try {
            return cpu ? threads.getThreadCpuTime(threadId) : -1;
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    long allocatedBytes(long threadId) {
        try {
            return allocation ? Extended.allocatedBytes(threads, threadId) : -1;
        } catch (RuntimeException | LinkageError ex) {
            return -1;
        }
    }

    /** The only class that names {@code com.sun.management}, loaded on first use. */
    private static final class Extended {

        static boolean supports(ThreadMXBean threads) {
            return threads instanceof com.sun.management.ThreadMXBean extended
                    && extended.isThreadAllocatedMemorySupported()
                    && extended.isThreadAllocatedMemoryEnabled();
        }

        static long currentAllocatedBytes(ThreadMXBean threads) {
            return ((com.sun.management.ThreadMXBean) threads).getCurrentThreadAllocatedBytes();
        }

        static long allocatedBytes(ThreadMXBean threads, long threadId) {
            return ((com.sun.management.ThreadMXBean) threads).getThreadAllocatedBytes(threadId);
        }
    }
}
