package io.github.jdubois.bootui.engine.resources;

/** The calling thread's allocated bytes, for measurements taken outside the {@link SegmentMeter}. */
public final class ThreadAllocations {

    private ThreadAllocations() {}

    /**
     * The bytes the calling platform thread has allocated so far, or {@code -1} on a virtual thread or a JVM that does
     * not measure it. Never throws.
     */
    public static long currentAllocatedBytes() {
        try {
            if ("java.lang.VirtualThread"
                    .equals(Thread.currentThread().getClass().getName())) {
                return -1;
            }
            return ThreadReadings.get().currentAllocatedBytes();
        } catch (RuntimeException | LinkageError ex) {
            return -1;
        }
    }
}
