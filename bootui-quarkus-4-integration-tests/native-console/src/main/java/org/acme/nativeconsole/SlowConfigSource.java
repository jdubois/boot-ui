package org.acme.nativeconsole;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * A bounded, explicitly armed host operation for testing modern MCP disconnect cancellation. It is
 * inert during bootstrap and ordinary requests; no BootUI implementation is replaced.
 */
public final class SlowConfigSource implements ConfigSource {

    private static final String PROPERTY = "fixture.slow-witness";
    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static final AtomicInteger ENTERED = new AtomicInteger();
    private static final AtomicInteger INTERRUPTED = new AtomicInteger();
    private static final AtomicInteger COMPLETED = new AtomicInteger();

    public static void arm() {
        ARMED.set(true);
    }

    public static Map<String, Integer> counters() {
        return Map.of("entered", ENTERED.get(), "interrupted", INTERRUPTED.get(), "completed", COMPLETED.get());
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of(PROPERTY, "bounded-host-config-witness");
    }

    @Override
    public Set<String> getPropertyNames() {
        return Set.of(PROPERTY);
    }

    @Override
    public String getValue(String propertyName) {
        if (!PROPERTY.equals(propertyName)) {
            return null;
        }
        if (ARMED.compareAndSet(true, false)) {
            ENTERED.incrementAndGet();
            try {
                new CountDownLatch(1).await(8, TimeUnit.SECONDS);
                COMPLETED.incrementAndGet();
            } catch (InterruptedException interrupted) {
                INTERRUPTED.incrementAndGet();
                Thread.currentThread().interrupt();
            }
        }
        return "bounded-host-config-witness";
    }

    @Override
    public String getName() {
        return "native-acceptance-host-config";
    }
}
