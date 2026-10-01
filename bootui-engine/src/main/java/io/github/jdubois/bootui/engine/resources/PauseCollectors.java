package io.github.jdubois.bootui.engine.resources;

import io.github.jdubois.bootui.engine.memory.GcCollectorKinds;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * The JVM's pause collectors, whose collection counts name the collections that completed while a segment ran
 * ({@code docs/PLAN-v2.md} §5.11). A collector's collection count equals the id of its latest collection, so the counts
 * read when a segment opens and closes bound the ids of the collections that completed in between.
 *
 * <p>Concurrent cycle beans, such as ZGC's and Shenandoah's "Cycles", report concurrent time, never a pause, and are
 * excluded with {@link GcCollectorKinds#isConcurrentCycleBean}.</p>
 */
final class PauseCollectors {

    private static final PauseCollectors INSTANCE = load();

    private final GarbageCollectorMXBean[] beans;
    private final String[] names;

    PauseCollectors(List<GarbageCollectorMXBean> beans) {
        this.beans = beans.toArray(GarbageCollectorMXBean[]::new);
        this.names = new String[this.beans.length];
        for (int i = 0; i < this.beans.length; i++) {
            names[i] = this.beans[i].getName();
        }
    }

    static PauseCollectors get() {
        return INSTANCE;
    }

    private static PauseCollectors load() {
        List<GarbageCollectorMXBean> pauses = new ArrayList<>();
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (!GcCollectorKinds.isConcurrentCycleBean(bean.getName())) {
                    pauses.add(bean);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            pauses.clear();
        }
        return new PauseCollectors(pauses);
    }

    int size() {
        return beans.length;
    }

    String name(int index) {
        return names[index];
    }

    /** Writes each collector's collection count into {@code counts}, {@code -1} where it cannot be read. */
    void read(long[] counts) {
        for (int i = 0; i < beans.length; i++) {
            try {
                counts[i] = beans[i].getCollectionCount();
            } catch (RuntimeException ex) {
                counts[i] = -1;
            }
        }
    }
}
