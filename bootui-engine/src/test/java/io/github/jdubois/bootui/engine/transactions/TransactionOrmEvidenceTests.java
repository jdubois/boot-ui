package io.github.jdubois.bootui.engine.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmSessionEvents;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Hibernate work of a transaction's thread while it was active ({@code readonly-candidate-transaction}): read as the
 * difference between its begin and its completion, never from the lifetime of a session, which begins before the
 * transaction and ends after it.
 */
class TransactionOrmEvidenceTests {

    private final List<RuntimeEvent> published = new ArrayList<>();

    private final TransactionRecorder recorder = recorder(published::add);

    private static TransactionRecorder recorder(RuntimeEventSink sink) {
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, null);
        recorder.setRuntimeEventSink(sink);
        return recorder;
    }

    private static OrmSessionEvents.Session openSession() {
        OrmSessionEvents.Publisher publisher = new OrmSessionEvents.Publisher();
        publisher.setRuntimeEventSink(event -> true);
        OrmSessionEvents.Session session = OrmSessionEvents.open();
        publisher.close();
        return session;
    }

    @Test
    void aTransactionReportsTheStatementsFlushesAndDirtyEntitiesOfItsThreadWhileItWasActive() {
        OrmSessionEvents.Session beforeTheTransaction = openSession();
        // Work done before the transaction began, as a request-scoped session does, is not the transaction's.
        beforeTheTransaction.statementStart();
        beforeTheTransaction.statementEnd();
        beforeTheTransaction.dirtyCalculationEnd(true);
        beforeTheTransaction.flushStart();
        beforeTheTransaction.flushEnd(4);

        long id = recorder.beginTransaction("TagService.summary", false, null, "http-1", null);
        beforeTheTransaction.statementStart();
        beforeTheTransaction.statementEnd();
        beforeTheTransaction.statementStart();
        beforeTheTransaction.statementEnd();
        beforeTheTransaction.partialFlushStart();
        beforeTheTransaction.partialFlushEnd(4);
        beforeTheTransaction.dirtyCalculationEnd(false);
        beforeTheTransaction.flushStart();
        beforeTheTransaction.flushEnd(4);
        recorder.completeTransaction(id, TransactionRecorder.Status.COMMITTED, null);
        // Work done after it completed, as a session closed later does, is not either.
        beforeTheTransaction.statementStart();
        beforeTheTransaction.statementEnd();

        TransactionPayload payload = (TransactionPayload) published.get(0).payload();
        assertThat(payload.ormStatements()).isEqualTo(2);
        assertThat(payload.ormFlushes())
                .as("the full flush at commit, not the auto-flush")
                .isEqualTo(1);
        assertThat(payload.ormDirtyEntities()).isZero();
    }

    @Test
    void aDirtyEntityOfAnySessionOfTheThreadIsTheTransactions() {
        OrmSessionEvents.Session first = openSession();
        OrmSessionEvents.Session second = openSession();

        long id = recorder.beginTransaction("TagService.relabel", false, null, "http-1", null);
        first.flushStart();
        first.flushEnd(2);
        second.dirtyCalculationEnd(true);
        second.flushStart();
        second.flushEnd(1);
        recorder.completeTransaction(id, TransactionRecorder.Status.COMMITTED, null);

        TransactionPayload payload = (TransactionPayload) published.get(0).payload();
        assertThat(payload.ormFlushes()).isEqualTo(2);
        assertThat(payload.ormDirtyEntities()).isEqualTo(1);
    }

    @Test
    void aFlushOverNothingIsNotACountedFlushSinceNothingWasDirtyChecked() {
        OrmSessionEvents.Session session = openSession();

        long id = recorder.beginTransaction("TagService.labels", false, null, "http-1", null);
        session.statementStart();
        session.statementEnd();
        session.flushStart();
        session.flushEnd(0);
        recorder.completeTransaction(id, TransactionRecorder.Status.COMMITTED, null);

        TransactionPayload payload = (TransactionPayload) published.get(0).payload();
        assertThat(payload.ormStatements()).isEqualTo(1);
        assertThat(payload.ormFlushes()).isZero();
    }

    @Test
    void aTransactionThatCompletesOnAnotherThreadHasNoHibernateWorkSinceItsWorkIsNotAllOnOne() throws Exception {
        long id = recorder.beginTransaction("TagService.summary", false, null, "http-1", null);
        Thread other = new Thread(() -> recorder.completeTransaction(id, TransactionRecorder.Status.COMMITTED, null));
        other.start();
        other.join();

        assertThat(published)
                .singleElement()
                .satisfies(event -> assertThat(((TransactionPayload) event.payload()).ormStatements())
                        .isEqualTo(-1));
    }

    @Test
    void aTransactionThatDoesNotOwnItsThreadsWorkOrIsNotThreadBoundHasNone() {
        long jta = recorder.beginTransaction("Jta.run", false, null, "http-1", null, false, true, false);
        recorder.completeTransaction(jta, TransactionRecorder.Status.COMMITTED, null);
        long reactive = recorder.beginTransaction("Reactive.run", false, null, "http-1", null, false, false);
        recorder.completeTransaction(reactive, TransactionRecorder.Status.COMMITTED, null);

        assertThat(published)
                .extracting(event -> ((TransactionPayload) event.payload()).ormFlushes())
                .containsExactly(-1L, -1L);
    }

    @Test
    void withoutTheOrmSourceRecordedTheWorkIsUnknownRatherThanNone() {
        List<RuntimeEvent> offered = new ArrayList<>();
        TransactionRecorder withoutOrm = recorder(new RuntimeEventSink() {
            @Override
            public boolean offer(RuntimeEvent event) {
                return offered.add(event);
            }

            @Override
            public boolean records(JournalSource source) {
                return source != JournalSource.ORM;
            }
        });

        long id = withoutOrm.beginTransaction("TagService.summary", false, null, "http-1", null);
        withoutOrm.completeTransaction(id, TransactionRecorder.Status.COMMITTED, null);

        assertThat(offered)
                .singleElement()
                .satisfies(event -> assertThat(((TransactionPayload) event.payload()).ormStatements())
                        .isEqualTo(-1));
    }
}
