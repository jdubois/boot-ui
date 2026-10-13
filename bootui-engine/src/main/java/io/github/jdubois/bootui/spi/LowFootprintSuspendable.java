package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * A BootUI component that stops recording while <b>low-footprint mode</b> is on, then records again when the developer
 * turns it off.
 *
 * <p>This is not {@link IdleReclaimable}: idle reclamation follows console activity and is undone by it, while this
 * pause follows an explicit user decision and only that decision undoes it. A component implements both when it has
 * both lifecycles; the two never share a flag.</p>
 *
 * <p>Adapters never list implementations by type. They hand every live singleton they already hold to
 * {@code LowFootprintService}, which keeps only instances of this interface, so a component whose optional integration
 * is absent is never created nor linked.</p>
 *
 * <p>Implementations stop accepting new data (a volatile read on the hot path, failing closed), keep their
 * configuration and subscribers, and never bring dropped data back on resume: they refill from live traffic.</p>
 */
public interface LowFootprintSuspendable {

    /** A stable kebab-case id, such as {@code runtime-journal}. */
    String suspendId();

    /** A short human-readable name, such as {@code Runtime journal recording}. */
    String suspendLabel();

    /** Stops recording. Must be idempotent and safe to call from a request thread while the application runs. */
    void suspendForLowFootprint();

    /** Records again from live traffic. Must be idempotent and safe to call from a request thread. */
    void resumeFromLowFootprint();

    /**
     * The independently reported parts of this component. A component that owns several things to pause, such as a
     * journal with its GC listener and resource sampler, returns one part for each so the report names them
     * separately; the default is this component alone.
     */
    default List<LowFootprintSuspendable> suspendableParts() {
        return List.of(this);
    }
}
