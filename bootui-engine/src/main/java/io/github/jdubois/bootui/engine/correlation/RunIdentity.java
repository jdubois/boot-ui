package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.core.dto.ApplicationRunDto;
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

    private static final String INSTANCE_ID = randomId();

    /**
     * The random id of this BootUI instance: one per JVM, since BootUI's jars stay in the class loader that survives
     * DevTools restarts and Quarkus live reloads. It changes only when that class loader is replaced.
     */
    public static String instanceId() {
        return INSTANCE_ID;
    }

    /** The run as published in {@code /overview}. */
    public ApplicationRunDto toDto() {
        return new ApplicationRunDto(INSTANCE_ID, id, ordinal, startedAtEpochMillis);
    }

    /** Starts a new run now. Adapters call it once per application-context start. */
    public static RunIdentity start() {
        return start(System::currentTimeMillis);
    }

    static RunIdentity start(LongSupplier clock) {
        return new RunIdentity(randomId(), ORDINALS.incrementAndGet(), clock.getAsLong());
    }

    private static String randomId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    }
}
