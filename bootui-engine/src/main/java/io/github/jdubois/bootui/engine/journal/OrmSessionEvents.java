package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;

/**
 * The {@code orm} source ({@code docs/PLAN-v2.md} §5.18, M4-9): each adapter's Hibernate {@code SessionEventListener},
 * which Hibernate instantiates itself for every session through {@code hibernate.session.events.auto}, opens one {@link
 * Session} meter and forwards Hibernate's callbacks to it; the meter publishes one {@link OrmPayload} event when the
 * session ends, owned by the request or execution that opened it, so its statements, flushes, and persistence-context
 * size nest under that work.
 *
 * <p>Hibernate gives the listener no constructor arguments, so the journal and the correlation are installed here, once
 * per run, by {@link Publisher}. A session that opens while nothing is installed, or while the journal does not record
 * the source, meters nothing. Metering never throws into Hibernate.</p>
 */
public final class OrmSessionEvents {

    private static volatile RuntimeEventSink journal = RuntimeEventSink.NONE;
    private static final CorrelationSource CORRELATION = new CorrelationSource();

    private OrmSessionEvents() {}

    /**
     * A new meter for a session that is opening, or {@code null} when the journal does not record the source, in which
     * case the listener forwards nothing.
     */
    public static Session open() {
        try {
            RuntimeEventSink sink = journal;
            return sink.records(JournalSource.ORM) ? new Session(sink, CORRELATION.current()) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static void install(RuntimeEventSink sink) {
        journal = sink == null ? RuntimeEventSink.NONE : sink;
    }

    /**
     * Installs the journal for the {@code orm} source, as a {@link RuntimeEventPublisher} every adapter already wires;
     * {@link #setCorrelationContextProvider} names the stack's correlation, for Quarkus's Vert.x context.
     */
    public static final class Publisher implements RuntimeEventPublisher, AutoCloseable {

        private volatile RuntimeEventSink installed;

        @Override
        public void setRuntimeEventSink(RuntimeEventSink sink) {
            installed = sink;
            install(sink);
        }

        public void setCorrelationContextProvider(CorrelationContextProvider provider) {
            CORRELATION.set(provider);
        }

        /** Uninstalls this run's journal, unless a later run already installed its own. */
        @Override
        public void close() {
            RuntimeEventSink mine = installed;
            if (mine != null && journal == mine) {
                install(null);
                CORRELATION.set(null);
            }
        }
    }

    /**
     * One session's counters. A Hibernate session is used by one thread at a time, so it needs no synchronization; a
     * flush window remembers the statement time inside it, so flush time is reported without it.
     */
    public static final class Session {

        private final RuntimeEventSink sink;
        private final long openedMillis = System.currentTimeMillis();
        private final long openedNanos = System.nanoTime();
        private CorrelationContext owner;
        private int statements;
        private long statementNanos;
        private long statementStart;
        private int acquisitions;
        private long acquisitionNanos;
        private long acquisitionStart;
        private int flushes;
        private long flushNanos;
        private long flushStart;
        private long flushStatementsAtStart;
        private int partialFlushes;
        private long partialFlushNanos;
        private long partialFlushStart;
        private long partialStatementNanosAtStart;
        private int partialStatementsAtStart;
        private int dirtyEntities;
        private int entitiesInContext = -1;
        private int l2Hits;
        private int l2Misses;
        private int l2Puts;
        private boolean ended;

        Session(RuntimeEventSink sink, CorrelationContext owner) {
            this.sink = sink;
            this.owner = owner;
        }

        public void connectionAcquisitionStart() {
            acquisitionStart = System.nanoTime();
        }

        public void connectionAcquisitionEnd() {
            acquisitions++;
            acquisitionNanos += elapsed(acquisitionStart);
        }

        public void statementStart() {
            statementStart = System.nanoTime();
        }

        public void statementEnd() {
            statements++;
            statementNanos += elapsed(statementStart);
        }

        public void flushStart() {
            flushStart = System.nanoTime();
            flushStatementsAtStart = statementNanos;
        }

        public void flushEnd(int entities) {
            flushes++;
            flushNanos += Math.max(0, elapsed(flushStart) - (statementNanos - flushStatementsAtStart));
            entitiesInContext = Math.max(entitiesInContext, entities);
        }

        public void partialFlushStart() {
            partialFlushStart = System.nanoTime();
            partialStatementNanosAtStart = statementNanos;
            partialStatementsAtStart = statements;
        }

        /** Counts the auto-flush when it executed a statement; every check still adds its time. */
        public void partialFlushEnd(int entities) {
            if (statements > partialStatementsAtStart) {
                partialFlushes++;
            }
            partialFlushNanos +=
                    Math.max(0, elapsed(partialFlushStart) - (statementNanos - partialStatementNanosAtStart));
            entitiesInContext = Math.max(entitiesInContext, entities);
        }

        public void dirtyCalculationEnd(boolean dirty) {
            if (dirty) {
                dirtyEntities++;
            }
        }

        public void cacheGetEnd(boolean hit) {
            if (hit) {
                l2Hits++;
            } else {
                l2Misses++;
            }
        }

        public void cachePutEnd() {
            l2Puts++;
        }

        /** Publishes the session once, when it did any database work. */
        public void end(String persistenceUnit) {
            if (ended) {
                return;
            }
            ended = true;
            if (statements == 0 && flushes == 0 && partialFlushes == 0) {
                return;
            }
            try {
                CorrelationContext context = owner;
                if (context == null || (context.requestId() == null && context.executionId() == null)) {
                    context = CORRELATION.current();
                }
                sink.offer(RuntimeEvent.of(
                        JournalSource.ORM,
                        openedMillis,
                        Math.max(0, System.nanoTime() - openedNanos),
                        context,
                        Thread.currentThread().getName(),
                        null,
                        false,
                        new OrmPayload(
                                persistenceUnit,
                                statements,
                                statementNanos,
                                acquisitions,
                                acquisitionNanos,
                                flushes,
                                flushNanos,
                                partialFlushes,
                                partialFlushNanos,
                                dirtyEntities,
                                entitiesInContext,
                                l2Hits,
                                l2Misses,
                                l2Puts)));
            } catch (RuntimeException ex) {
                // Metering never throws into Hibernate.
            }
        }

        private static long elapsed(long start) {
            return start == 0 ? 0 : Math.max(0, System.nanoTime() - start);
        }
    }
}
