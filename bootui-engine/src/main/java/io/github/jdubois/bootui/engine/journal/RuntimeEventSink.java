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
     * Whether events of {@code source} are recorded, so a recorder can skip work only the journal needs, such as
     * walking the stack for application frames.
     */
    default boolean records(JournalSource source) {
        return true;
    }
}
