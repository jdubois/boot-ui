package io.github.jdubois.bootui.engine.journal;

/**
 * Where recorders publish runtime events, right after their existing ring-buffer write ({@code docs/PLAN-v2.md}
 * §5.2). Publishing never blocks and never throws: an event the journal cannot take now is dropped and counted.
 */
@FunctionalInterface
public interface RuntimeEventSink {

    /** A sink that records nothing, used while the journal is disabled. */
    RuntimeEventSink NONE = event -> false;

    /**
     * Offers {@code event} to the journal without blocking.
     *
     * @return whether the journal accepted it; {@code false} when it is disabled, the source is off, or the event was
     *     dropped
     */
    boolean offer(RuntimeEvent event);
}
