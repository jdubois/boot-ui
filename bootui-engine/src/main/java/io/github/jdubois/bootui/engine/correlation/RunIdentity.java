package io.github.jdubois.bootui.engine.correlation;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Identifies one run of the application: one application-context start.
 *
 * <p>A Spring DevTools restart or a Quarkus live reload starts a new run inside the same JVM, which is what run
 * comparison compares ({@code docs/PLAN-v2.md} §5.8). BootUI's jars stay in the class loader that survives those
 * restarts, so the ordinal counts the runs of the JVM: 1, 2, 3, and so on. When the engine is itself reloaded, for
 * example from a reactor build, the ordinal restarts at 1, while the random id stays unique.</p>
 *
 * @param id a random id, 8 lowercase hexadecimal characters, unique for practical purposes
 * @param ordinal the run's position among the runs of this engine class loader, starting at 1
 * @param startedAtEpochMillis when the run started
 */
public record RunIdentity(String id, int ordinal, long startedAtEpochMillis) {

    private static final AtomicInteger ORDINALS = new AtomicInteger();

    /** Starts a new run now. Adapters call it once per application-context start. */
    public static RunIdentity start() {
        return start(System::currentTimeMillis);
    }

    static RunIdentity start(LongSupplier clock) {
        String id = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        return new RunIdentity(id, ORDINALS.incrementAndGet(), clock.getAsLong());
    }
}
