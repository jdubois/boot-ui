package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of <b>Free BootUI memory</b> ({@code POST /bootui/api/live-memory/offload}): BootUI dropped the data it
 * buffers in memory, then asked the JVM for a garbage collection so memory panels measure the application rather than
 * BootUI.
 *
 * @param heapUsedBeforeBytes heap used before any store was emptied
 * @param heapUsedAfterBytes heap used after the garbage collection request returned
 * @param reclaimedBytes {@code heapUsedBeforeBytes - heapUsedAfterBytes}, never negative; an estimate, because the
 *     application keeps allocating meanwhile
 * @param gcRequested whether BootUI called {@code System.gc()}; it is only a hint the JVM may ignore
 * @param explicitGcDisabled whether the JVM runs with {@code -XX:+DisableExplicitGC}, which ignores that hint
 * @param entriesCleared the total retained entries the stores dropped
 * @param stores every store BootUI tried to empty, in a stable order
 * @param durationMillis how long the action took
 */
public record MemoryOffloadReport(
        long heapUsedBeforeBytes,
        long heapUsedAfterBytes,
        long reclaimedBytes,
        boolean gcRequested,
        boolean explicitGcDisabled,
        long entriesCleared,
        List<MemoryOffloadStoreDto> stores,
        long durationMillis) {

    public MemoryOffloadReport {
        stores = DtoCollections.immutableCopy(stores);
    }
}
