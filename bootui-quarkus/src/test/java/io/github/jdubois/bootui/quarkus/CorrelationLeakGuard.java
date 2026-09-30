package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test that leaves a BootUI correlation scope open on its thread ({@code docs/PLAN-v2.md} §5.1), registered
 * for every test of this module through {@code junit-platform.properties} and a JUnit extension service file.
 *
 * <p>A scope left open would carry one test's request or execution into every later test the same thread runs, which
 * makes those tests pass or fail depending on their order. The guard clears the thread before failing, so only the
 * leaking test fails. The engine, the Spring adapter, and the Quarkus adapter each keep a copy in their test sources,
 * since no test library is shared by all three.</p>
 */
public final class CorrelationLeakGuard implements AfterEachCallback, AfterAllCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        check(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        check(context);
    }

    private static void check(ExtensionContext context) {
        CorrelationContext leaked = BootUiCorrelation.current();
        if (leaked.isEmpty()) {
            return;
        }
        BootUiCorrelation.replace(CorrelationContext.NONE);
        throw new AssertionError(context.getDisplayName() + " left a BootUI correlation scope open on its thread: "
                + leaked + ". Close every scope it opens, for example with try-with-resources.");
    }
}
