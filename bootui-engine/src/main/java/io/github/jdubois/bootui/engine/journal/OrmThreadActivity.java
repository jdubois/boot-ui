package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.support.BootUiThreadLocal;

/**
 * The Hibernate work one thread has done, cumulatively, so that a transaction can read what ran on its thread while it
 * was active: the difference between its begin and its completion. The sessions of the thread meter into it as they
 * work ({@link OrmSessionEvents.Session}), whichever session or persistence unit they belong to.
 *
 * <p>This is what ties Hibernate's evidence to a transaction by the thread and the time it was active, not by the
 * lifetime of a session: a session may begin before the transaction and end after it, as with a request-scoped session
 * or a session that Spring closes once the transaction has completed. It is a superset of the transaction's own session
 * work, so it can only over-count what the transaction did, never miss it.</p>
 */
public final class OrmThreadActivity {

    private static final BootUiThreadLocal<Counters> CURRENT = BootUiThreadLocal.withInitial(Counters::new);

    private OrmThreadActivity() {}

    /**
     * What a thread had done at one moment.
     *
     * @param statements the JDBC statements and batches its Hibernate sessions executed
     * @param flushes the full flushes its sessions ran over a persistence context that held entities, which is where
     *     Hibernate dirty-checks them for the last time before a commit; an auto-flush before a query, or a flush with
     *     nothing to dirty-check, is not counted
     * @param dirtyEntities the entities its sessions found dirty, at any flush or auto-flush
     */
    public record Snapshot(long statements, long flushes, long dirtyEntities) {}

    /** A moment of the calling thread, to read the work done since on that same thread. */
    public static final class Mark {

        private final Counters owner;
        private final Snapshot at;

        private Mark(Counters owner) {
            this.owner = owner;
            this.at = owner.snapshot();
        }

        /** The work done since this mark, or {@code null} when the caller is not the thread that took it. */
        public Snapshot since() {
            Counters current = CURRENT.get();
            if (current != owner) {
                return null;
            }
            Snapshot now = current.snapshot();
            return new Snapshot(
                    now.statements - at.statements, now.flushes - at.flushes, now.dirtyEntities - at.dirtyEntities);
        }
    }

    /** A mark of the calling thread. */
    public static Mark mark() {
        return new Mark(CURRENT.get());
    }

    static void statement() {
        CURRENT.get().statements++;
    }

    static void flush() {
        CURRENT.get().flushes++;
    }

    static void dirtyEntity() {
        CURRENT.get().dirtyEntities++;
    }

    private static final class Counters {

        long statements;
        long flushes;
        long dirtyEntities;

        Snapshot snapshot() {
            return new Snapshot(statements, flushes, dirtyEntities);
        }
    }
}
