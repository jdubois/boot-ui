package io.github.jdubois.bootui.engine.journal;

/**
 * Where recorders publish runtime events, right after their existing ring-buffer write ({@code docs/PLAN-v2.md}
 * §5.2). Publishing never blocks and never throws: an event the journal cannot take now is dropped and counted.
 */
@FunctionalInterface
public interface RuntimeEventSink {

    /** A sink that records nothing, used while the journal is disabled. */
    RuntimeEventSink NONE = new RuntimeEventSink() {
        @Override
        public boolean offer(RuntimeEvent event) {
            return false;
        }

        @Override
        public boolean records(JournalSource source) {
            return false;
        }
    };

    /**
     * Offers {@code event} to the journal without blocking.
     *
     * @return whether the journal accepted it; {@code false} when it is disabled, the source is off, or the event was
     *     dropped
     */
    boolean offer(RuntimeEvent event);

    /**
     * Pins admission for work whose immutable event will only be complete later, such as a JDBC query whose row count
     * is known after its result set is consumed. The returned capture is valid only for the run state at this call.
     * Implementations with recording generations should reject it after clear, pause, or close.
     */
    default DeferredCapture beginCapture(JournalSource source) {
        if (source == null || paused() || !records(source)) {
            return null;
        }
        return this::offer;
    }

    /** A one-use admission lease for an event completed after the observed operation. */
    @FunctionalInterface
    interface DeferredCapture {

        /** Offers the completed event through the admission state pinned by {@link #beginCapture(JournalSource)}. */
        boolean offer(RuntimeEvent event);

        /** Marks a capture that could not be published because its owning recorder was replaced. */
        default void discard(RuntimeEvent event) {}
    }

    /**
     * Offers an application event already classified while BootUI handles an import request. Implementations may
     * bypass only the current request's self-work marker; every other source, capacity, and thread guard still applies.
     */
    default boolean offerImported(RuntimeEvent event) {
        return offer(event);
    }

    /**
     * Offers an event the BootUI agent recorded on an application thread and BootUI's drain thread publishes
     * ({@code docs/PLAN-v2.md} M5-6a). Implementations may bypass only the publishing thread's own BootUI-work guard,
     * since the agent already left BootUI's threads and work out when it recorded; every other guard still applies.
     */
    default boolean offerAgentRecord(RuntimeEvent event) {
        return offer(event);
    }

    /** Whether low-footprint mode paused recording, so an appender can skip building an event. */
    default boolean paused() {
        return false;
    }

    /**
     * Whether events of {@code source} are recorded, so a recorder can skip work only the journal needs, such as
     * walking the stack for application frames.
     */
    default boolean records(JournalSource source) {
        return true;
    }

    /** Declares the actual feeder's scope, independently of whether it has emitted a statement yet. */
    default void registerSqlCapture(String dataSource, SqlPayload.Provenance provenance) {}
}
