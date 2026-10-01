package io.github.jdubois.bootui.engine.resources;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Readings the test sets per thread, with one pause collector named {@code Young} and one named {@code Old}. */
final class FakeReadings implements SegmentMeter.Readings {

    private final Map<Long, long[]> threads = new ConcurrentHashMap<>();
    private volatile long[] collections = {0, 0};
    volatile boolean supported = true;

    @SuppressWarnings("deprecation")
    void set(Thread thread, long cpu, long allocated) {
        threads.put(thread.getId(), new long[] {cpu, allocated});
    }

    void collections(long young, long old) {
        collections = new long[] {young, old};
    }

    @SuppressWarnings("deprecation")
    private long[] of(long threadId) {
        return threads.getOrDefault(threadId, new long[] {0, 0});
    }

    @Override
    public boolean supported() {
        return supported;
    }

    @Override
    @SuppressWarnings("deprecation")
    public long currentCpuNanos() {
        return supported ? of(Thread.currentThread().getId())[0] : -1;
    }

    @Override
    @SuppressWarnings("deprecation")
    public long currentAllocatedBytes() {
        return supported ? of(Thread.currentThread().getId())[1] : -1;
    }

    @Override
    public long cpuNanos(long threadId) {
        return supported ? of(threadId)[0] : -1;
    }

    @Override
    public long allocatedBytes(long threadId) {
        return supported ? of(threadId)[1] : -1;
    }

    @Override
    public int collectors() {
        return 2;
    }

    @Override
    public String collector(int index) {
        return index == 0 ? "Young" : "Old";
    }

    @Override
    public void readCollections(long[] counts) {
        System.arraycopy(collections, 0, counts, 0, counts.length);
    }
}
