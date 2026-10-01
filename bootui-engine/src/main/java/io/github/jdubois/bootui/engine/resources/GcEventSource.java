package io.github.jdubois.bootui.engine.resources;

import io.github.jdubois.bootui.engine.journal.GcPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.memory.GcCollectorKinds;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Publishes one {@code GC} event per completed collection, from the JVM's GC notifications ({@code docs/PLAN-v2.md}
 * §5.11). Each event is keyed by collector and id, which requests join through their {@link GcPauseRange}s, so a
 * notification that arrives late or out of order still joins the right requests.
 *
 * <p>{@code com.sun.management} is reached only through {@link Notifications}; on a runtime without it, the source
 * publishes nothing. The source listens until it is {@linkplain #close closed}, which the journal does when its run
 * ends, so a restarted application context never leaves a listener on the JVM's collectors.</p>
 */
public final class GcEventSource implements AutoCloseable {

    private static final Logger log = Logger.getLogger(GcEventSource.class.getName());

    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final NotificationListener listener;

    private GcEventSource(RuntimeEventSink sink, long jvmStartEpochMillis, Set<String> heapPools) {
        this.listener = (notification, handback) -> publish(sink, notification, jvmStartEpochMillis, heapPools);
    }

    /** Starts listening to every collector, publishing to {@code sink}; listens to none when the JVM offers none. */
    public static GcEventSource start(RuntimeEventSink sink) {
        long jvmStart;
        Set<String> heapPools = new HashSet<>();
        List<GarbageCollectorMXBean> collectors;
        try {
            jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    heapPools.add(pool.getName());
                }
            }
            collectors = ManagementFactory.getGarbageCollectorMXBeans();
        } catch (RuntimeException | LinkageError ex) {
            return new GcEventSource(sink, 0, heapPools);
        }
        GcEventSource source = new GcEventSource(sink, jvmStart, heapPools);
        for (GarbageCollectorMXBean collector : collectors) {
            if (collector instanceof NotificationEmitter emitter) {
                try {
                    emitter.addNotificationListener(source.listener, null, null);
                    source.emitters.add(emitter);
                } catch (RuntimeException ex) {
                    log.log(Level.FINE, "BootUI could not listen to GC notifications of " + collector.getName(), ex);
                }
            }
        }
        return source;
    }

    /** How many collectors the source listens to. */
    public int collectors() {
        return emitters.size();
    }

    @Override
    public void close() {
        for (NotificationEmitter emitter : emitters) {
            try {
                emitter.removeNotificationListener(listener);
            } catch (ListenerNotFoundException | RuntimeException ex) {
                // Already removed.
            }
        }
        emitters.clear();
    }

    private static void publish(
            RuntimeEventSink sink, Notification notification, long jvmStartEpochMillis, Set<String> heapPools) {
        try {
            if (!Notifications.isGc(notification)) {
                return;
            }
            RuntimeEvent event = Notifications.event(notification, jvmStartEpochMillis, heapPools);
            if (event != null) {
                sink.offer(event);
            }
        } catch (RuntimeException | LinkageError ex) {
            // A notification BootUI cannot read is skipped; the JVM's notification thread must never fail.
        }
    }

    /** A GC event built from the JVM's notification fields; for tests and {@link Notifications}. */
    static RuntimeEvent event(
            String collector,
            long gcId,
            String action,
            String cause,
            long startEpochMillis,
            long durationMillis,
            long heapBeforeBytes,
            long heapAfterBytes) {
        return new RuntimeEvent(
                JournalSource.GC,
                startEpochMillis,
                Math.max(0, durationMillis) * 1_000_000L,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                new GcPayload(
                        collector,
                        gcId,
                        action,
                        cause,
                        !GcCollectorKinds.isConcurrentCycleBean(collector),
                        heapBeforeBytes,
                        heapAfterBytes));
    }

    /** The only class that names {@code com.sun.management}, loaded on the first notification. */
    private static final class Notifications {

        static boolean isGc(Notification notification) {
            return com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(
                            notification.getType())
                    && notification.getUserData() instanceof CompositeData;
        }

        static RuntimeEvent event(Notification notification, long jvmStartEpochMillis, Set<String> heapPools) {
            com.sun.management.GarbageCollectionNotificationInfo info =
                    com.sun.management.GarbageCollectionNotificationInfo.from(
                            (CompositeData) notification.getUserData());
            com.sun.management.GcInfo gc = info.getGcInfo();
            if (gc == null) {
                return null;
            }
            return GcEventSource.event(
                    info.getGcName(),
                    gc.getId(),
                    info.getGcAction(),
                    info.getGcCause(),
                    jvmStartEpochMillis + gc.getStartTime(),
                    gc.getDuration(),
                    heapUsed(gc.getMemoryUsageBeforeGc(), heapPools),
                    heapUsed(gc.getMemoryUsageAfterGc(), heapPools));
        }

        private static long heapUsed(Map<String, MemoryUsage> pools, Set<String> heapPools) {
            if (pools == null || heapPools.isEmpty()) {
                return -1;
            }
            long used = 0;
            for (Map.Entry<String, MemoryUsage> pool : pools.entrySet()) {
                if (heapPools.contains(pool.getKey()) && pool.getValue() != null) {
                    used += pool.getValue().getUsed();
                }
            }
            return used;
        }
    }
}
