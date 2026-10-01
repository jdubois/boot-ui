package io.github.jdubois.bootui.engine.journal;

/**
 * A recorder that publishes what it records to the runtime journal ({@code docs/PLAN-v2.md} §5.2), once an adapter
 * installs the journal. Until then, it publishes nothing.
 */
public interface RuntimeEventPublisher {

    /** Installs the journal; {@code null} restores the default, which publishes nothing. */
    void setRuntimeEventSink(RuntimeEventSink journal);
}
