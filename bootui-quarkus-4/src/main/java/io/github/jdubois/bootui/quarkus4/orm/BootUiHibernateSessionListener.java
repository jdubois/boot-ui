package io.github.jdubois.bootui.quarkus4.orm;

import io.github.jdubois.bootui.engine.journal.OrmSessionEvents;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import org.hibernate.SessionEventListener;

/**
 * The {@code orm} source on Quarkus ({@code docs/PLAN-v2.md} §5.18, M4-9): Hibernate instantiates this listener for every
 * session, through the {@code hibernate.session.events.auto} unsupported property BootUI's deployment processor defaults, and it
 * forwards the session's JDBC, flush, dirty-check, and second-level cache callbacks to an engine meter, which publishes
 * the session once it ends. It reads no entity, parameter, or statement, and never throws into Hibernate.
 */
public class BootUiHibernateSessionListener implements SessionEventListener {

    private static final long serialVersionUID = 1L;

    private transient OrmSessionEvents.Session session;
    private transient boolean opened;

    public BootUiHibernateSessionListener() {
        session();
    }

    private OrmSessionEvents.Session session() {
        if (BootUiNativeActivation.isNativeInactive()) {
            return null;
        }
        if (!opened) {
            session = OrmSessionEvents.open();
            opened = true;
        }
        return session;
    }

    @Override
    public void jdbcConnectionAcquisitionStart() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.connectionAcquisitionStart();
        }
    }

    @Override
    public void jdbcConnectionAcquisitionEnd() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.connectionAcquisitionEnd();
        }
    }

    @Override
    public void jdbcExecuteStatementStart() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.statementStart();
        }
    }

    @Override
    public void jdbcExecuteStatementEnd() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.statementEnd();
        }
    }

    @Override
    public void jdbcExecuteBatchStart() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.statementStart();
        }
    }

    @Override
    public void jdbcExecuteBatchEnd() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.statementEnd();
        }
    }

    @Override
    public void flushStart() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.flushStart();
        }
    }

    @Override
    public void flushEnd(int numberOfEntities, int numberOfCollections) {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.flushEnd(numberOfEntities);
        }
    }

    @Override
    public void partialFlushStart() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.partialFlushStart();
        }
    }

    @Override
    public void partialFlushEnd(int numberOfEntities, int numberOfCollections) {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.partialFlushEnd(numberOfEntities);
        }
    }

    @Override
    public void dirtyCalculationEnd(boolean dirty) {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.dirtyCalculationEnd(dirty);
        }
    }

    @Override
    public void cacheGetEnd(boolean hit) {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.cacheGetEnd(hit);
        }
    }

    @Override
    public void cachePutEnd() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.cachePutEnd();
        }
    }

    @Override
    public void end() {
        OrmSessionEvents.Session session = session();
        if (session != null) {
            session.end(null);
        }
    }
}
