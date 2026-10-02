package io.github.jdubois.bootui.autoconfigure.orm;

import io.github.jdubois.bootui.engine.journal.OrmSessionEvents;
import org.hibernate.SessionEventListener;

/**
 * The {@code orm} source on Spring ({@code docs/PLAN-v2.md} §5.18, M4-9): Hibernate instantiates this listener for every
 * session, through the {@code spring.jpa.properties.hibernate.session.events.auto} default BootUI contributes, and it
 * forwards the session's JDBC, flush, dirty-check, and second-level cache callbacks to an engine meter, which publishes
 * the session once it ends. It reads no entity, parameter, or statement, and never throws into Hibernate.
 */
public class BootUiHibernateSessionListener implements SessionEventListener {

    private static final long serialVersionUID = 1L;

    private final transient OrmSessionEvents.Session session = OrmSessionEvents.open();

    @Override
    public void jdbcConnectionAcquisitionStart() {
        if (session != null) {
            session.connectionAcquisitionStart();
        }
    }

    @Override
    public void jdbcConnectionAcquisitionEnd() {
        if (session != null) {
            session.connectionAcquisitionEnd();
        }
    }

    @Override
    public void jdbcExecuteStatementStart() {
        if (session != null) {
            session.statementStart();
        }
    }

    @Override
    public void jdbcExecuteStatementEnd() {
        if (session != null) {
            session.statementEnd();
        }
    }

    @Override
    public void jdbcExecuteBatchStart() {
        if (session != null) {
            session.statementStart();
        }
    }

    @Override
    public void jdbcExecuteBatchEnd() {
        if (session != null) {
            session.statementEnd();
        }
    }

    @Override
    public void flushStart() {
        if (session != null) {
            session.flushStart();
        }
    }

    @Override
    public void flushEnd(int numberOfEntities, int numberOfCollections) {
        if (session != null) {
            session.flushEnd(numberOfEntities);
        }
    }

    @Override
    public void partialFlushStart() {
        if (session != null) {
            session.partialFlushStart();
        }
    }

    @Override
    public void partialFlushEnd(int numberOfEntities, int numberOfCollections) {
        if (session != null) {
            session.partialFlushEnd(numberOfEntities);
        }
    }

    @Override
    public void dirtyCalculationEnd(boolean dirty) {
        if (session != null) {
            session.dirtyCalculationEnd(dirty);
        }
    }

    @Override
    public void cacheGetEnd(boolean hit) {
        if (session != null) {
            session.cacheGetEnd(hit);
        }
    }

    @Override
    public void cachePutEnd() {
        if (session != null) {
            session.cachePutEnd();
        }
    }

    @Override
    public void end() {
        if (session != null) {
            session.end(null);
        }
    }
}
