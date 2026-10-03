package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.MemoryOffloadReport;
import io.github.jdubois.bootui.core.dto.MemoryOffloadStoreDto;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>Free BootUI memory</b>: empties every BootUI-owned in-memory store, then asks the JVM for a garbage collection, so
 * the Live Memory, JVM Tuning, Heap Dump, and Memory panels measure the application rather than BootUI.
 *
 * <p>Stores opt in through {@link MemoryOffloadable}. The adapter supplies the singletons it already holds; this
 * service keeps only the offloadable ones, once each, so it never creates a store nor links an optional integration.
 * Configuration overrides, dismissed rules, MCP and session security state, scan reports, and the run history are not
 * offloadable and are kept.</p>
 *
 * <p>{@code System.gc()} is a hint: the JVM may ignore it, and ignores it entirely under
 * {@code -XX:+DisableExplicitGC}, which the report states. The reclaimed figure is an estimate, because the application
 * keeps allocating while the action runs. Calls are serialized, so two clicks cannot interleave their measurements.</p>
 */
public final class MemoryOffloadService {

    private static final Logger log = Logger.getLogger(MemoryOffloadService.class.getName());

    private static final String DISABLE_EXPLICIT_GC = "-XX:+DisableExplicitGC";
    private static final String ENABLE_EXPLICIT_GC = "-XX:-DisableExplicitGC";

    private final Supplier<? extends Collection<?>> candidates;
    private final LongSupplier heapUsed;
    private final Runnable gc;
    private final BooleanSupplier explicitGcDisabled;
    private final LongSupplier nanoTime;

    /**
     * @param candidates the live singletons the adapter holds, re-read on every call; only {@link MemoryOffloadable}
     *     instances are used
     */
    public MemoryOffloadService(Supplier<? extends Collection<?>> candidates) {
        this(
                candidates,
                () -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                System::gc,
                () -> explicitGcDisabled(ManagementFactory.getRuntimeMXBean().getInputArguments()),
                System::nanoTime);
    }

    MemoryOffloadService(
            Supplier<? extends Collection<?>> candidates,
            LongSupplier heapUsed,
            Runnable gc,
            BooleanSupplier explicitGcDisabled,
            LongSupplier nanoTime) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.heapUsed = heapUsed;
        this.gc = gc;
        this.explicitGcDisabled = explicitGcDisabled;
        this.nanoTime = nanoTime;
    }

    /** Empties every offloadable store, requests a garbage collection, and reports what changed. */
    public synchronized MemoryOffloadReport offload() {
        long start = nanoTime.getAsLong();
        long before = heapUsed.getAsLong();
        List<MemoryOffloadStoreDto> stores = new ArrayList<>();
        long total = 0;
        for (MemoryOffloadable store : offloadables()) {
            MemoryOffloadStoreDto result = offload(store);
            total += result.entriesCleared();
            stores.add(result);
        }
        gc.run();
        long after = heapUsed.getAsLong();
        long durationMillis = Math.max(0L, (nanoTime.getAsLong() - start) / 1_000_000L);
        return new MemoryOffloadReport(
                before,
                after,
                Math.max(0L, before - after),
                true,
                explicitGcDisabled.getAsBoolean(),
                total,
                stores,
                durationMillis);
    }

    /**
     * Whether {@code arguments} disable explicit garbage collection. The JVM applies the last occurrence of a flag, so
     * a later {@code -XX:-DisableExplicitGC} re-enables it.
     */
    static boolean explicitGcDisabled(List<String> arguments) {
        boolean disabled = false;
        if (arguments == null) {
            return false;
        }
        for (String argument : arguments) {
            if (DISABLE_EXPLICIT_GC.equals(argument)) {
                disabled = true;
            } else if (ENABLE_EXPLICIT_GC.equals(argument)) {
                disabled = false;
            }
        }
        return disabled;
    }

    private List<MemoryOffloadable> offloadables() {
        Collection<?> live = candidates.get();
        if (live == null || live.isEmpty()) {
            return List.of();
        }
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        List<MemoryOffloadable> stores = new ArrayList<>();
        for (Object candidate : live) {
            if (candidate instanceof MemoryOffloadable store && seen.put(store, Boolean.TRUE) == null) {
                stores.add(store);
            }
        }
        stores.sort(Comparator.comparing(MemoryOffloadService::idOf));
        return stores;
    }

    private static MemoryOffloadStoreDto offload(MemoryOffloadable store) {
        String id = idOf(store);
        String label = id;
        try {
            label = Objects.requireNonNullElse(store.offloadLabel(), id);
            long cleared = Math.max(0L, store.offloadRetainedData());
            return new MemoryOffloadStoreDto(id, label, true, cleared, null);
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI could not free the memory held by its " + id + " store", ex);
            String message = ex.getMessage();
            String failure = message == null || message.isBlank()
                    ? ex.getClass().getSimpleName()
                    : ex.getClass().getSimpleName() + ": " + message;
            return new MemoryOffloadStoreDto(id, label, false, 0L, failure);
        }
    }

    private static String idOf(MemoryOffloadable store) {
        try {
            String id = store.offloadId();
            return id == null || id.isBlank() ? store.getClass().getSimpleName() : id;
        } catch (RuntimeException ex) {
            return store.getClass().getSimpleName();
        }
    }
}
