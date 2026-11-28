package io.github.jdubois.bootui.quarkus4.orm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import io.github.jdubois.bootui.engine.journal.OrmSessionEvents;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import org.junit.jupiter.api.Test;

class BootUiHibernateSessionListenerTest {

    @Test
    void listenerConstructedBeforeFreezeOpensNoMeterUntilTheFirstAdmittedCallback() {
        OrmSessionEvents.Session session = mock(OrmSessionEvents.Session.class);
        try (var activation = mockStatic(BootUiNativeActivation.class);
                var events = mockStatic(OrmSessionEvents.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            events.when(OrmSessionEvents::open).thenReturn(session);
            BootUiHibernateSessionListener listener = new BootUiHibernateSessionListener();
            listener.jdbcConnectionAcquisitionStart();
            listener.jdbcConnectionAcquisitionEnd();
            listener.jdbcExecuteStatementStart();
            listener.jdbcExecuteStatementEnd();
            listener.jdbcExecuteBatchStart();
            listener.jdbcExecuteBatchEnd();
            listener.flushStart();
            listener.flushEnd(1, 1);
            listener.partialFlushStart();
            listener.partialFlushEnd(1, 1);
            listener.dirtyCalculationEnd(true);
            listener.cacheGetEnd(true);
            listener.cachePutEnd();
            events.verifyNoInteractions();

            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(false);
            listener.jdbcExecuteStatementStart();
            listener.jdbcExecuteStatementEnd();
            listener.end();
            events.verify(OrmSessionEvents::open);
            verify(session).statementStart();
            verify(session).statementEnd();
            verify(session).end(null);
        }
    }
}
