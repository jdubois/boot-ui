package io.github.jdubois.bootui.autoconfigure.kernel;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Abstraction over the local Inspektor Gadget {@code ig} binary used by the Kernel Insights panel.
 *
 * <p>The default implementation ({@link ProcessIgGadgetRunner}) shells out to {@code ig}; tests use a
 * fake so the panel's normalization and status handling can be exercised without a Linux kernel,
 * elevated privileges, or the binary installed.
 */
public interface IgGadgetRunner {

    /** Whether a capture can run in the current environment (feature enabled, Linux, binary present). */
    boolean available();

    /** Human-readable reason a capture cannot run; {@code null} when {@link #available()} is true. */
    @Nullable
    String unavailableReason();

    /** The resolved {@code ig} command or path used for captures. */
    String igPath();

    /** The detected {@code ig} version, or {@code null} when it cannot be determined. */
    @Nullable
    String igVersion();

    /**
     * Run a single gadget, capturing up to {@code maxEvents} events. Streaming gadgets are captured
     * for {@code captureDuration}; snapshot gadgets return immediately and ignore the duration.
     */
    IgRunResult run(IgGadget gadget, Duration captureDuration, int maxEvents);
}
