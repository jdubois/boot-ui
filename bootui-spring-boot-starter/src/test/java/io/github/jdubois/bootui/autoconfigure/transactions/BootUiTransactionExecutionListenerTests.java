package io.github.jdubois.bootui.autoconfigure.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager;
import org.springframework.transaction.reactive.GenericReactiveTransaction;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class BootUiTransactionExecutionListenerTests {

    @AfterEach
    void resetSynchronizationState() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
        MDC.clear();
    }

    @Test
    void recordsACommittedTransactionWithIsolationAndReadOnlyFlag() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);

        TransactionExecution execution = execution("OrderService.placeOrder", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, null);

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.methodName()).isEqualTo("OrderService.placeOrder");
        assertThat(entry.isolation()).isEqualTo("READ_COMMITTED");
        assertThat(entry.readOnly()).isFalse();
        assertThat(entry.status()).isEqualTo("COMMITTED");
    }

    @Test
    void recordsARolledBackTransactionWithErrorMessage() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        listener.afterBegin(execution, null);
        listener.afterRollback(execution, new IllegalStateException("constraint violated"));

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.status()).isEqualTo("ROLLED_BACK");
        assertThat(entry.errorMessage()).isEqualTo("constraint violated");
    }

    @Test
    void recordsUnknownStatusWhenBeginFails() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        listener.afterBegin(execution, new IllegalStateException("could not open connection"));

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.status()).isEqualTo("UNKNOWN");
        assertThat(entry.errorMessage()).isEqualTo("could not open connection");
    }

    @Test
    void recordsUnknownStatusWhenCommitFails() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, new IllegalStateException("commit failed"));

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.status()).isEqualTo("UNKNOWN");
        assertThat(entry.errorMessage()).isEqualTo("commit failed");
    }

    @Test
    void tracksNestingAcrossSequentialBeginsOnTheSameThread() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution outer = execution("Outer.method", false);
        TransactionExecution inner = execution("Inner.method", false);
        listener.afterBegin(outer, null);
        listener.afterBegin(inner, null);
        listener.afterCommit(inner, null);
        listener.afterCommit(outer, null);

        List<TransactionEntryDto> entries = recorder.recent();
        TransactionEntryDto innerEntry = entries.stream()
                .filter(e -> e.methodName().equals("Inner.method"))
                .findFirst()
                .orElseThrow();
        TransactionEntryDto outerEntry = entries.stream()
                .filter(e -> e.methodName().equals("Outer.method"))
                .findFirst()
                .orElseThrow();
        assertThat(innerEntry.parentId()).isEqualTo(outerEntry.id());
    }

    @Test
    void publishesWhetherANestedBoundaryIsASavepointOrItsOwnPhysicalTransaction() {
        TransactionRecorder recorder = recorder();
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution outer = execution("Outer.method", false);
        TransactionExecution requiresNew = execution("Audit.log", false);
        TransactionExecution savepoint = execution("Stock.reserve", false);
        when(savepoint.isNested()).thenReturn(true);
        listener.afterBegin(outer, null);
        listener.afterBegin(requiresNew, null);
        listener.afterCommit(requiresNew, null);
        listener.afterBegin(savepoint, null);
        listener.afterCommit(savepoint, null);
        listener.afterCommit(outer, null);

        assertThat(published)
                .extracting(event -> (TransactionPayload) event.payload())
                .extracting(TransactionPayload::method, TransactionPayload::nested, TransactionPayload::savepoint)
                .containsExactly(
                        tuple("Audit.log", true, false),
                        tuple("Stock.reserve", true, true),
                        tuple("Outer.method", false, false));
    }

    @Test
    void publishesTheDeclaredAttributesARollbackOnlyMarkAndTheExceptionThatFailedTheCommit() {
        TransactionRecorder recorder = recorder();
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);

        TransactionExecution report = execution("ReportService.export", true);
        listener.afterBegin(report, null);
        listener.beforeCommit(report);
        listener.afterCommit(report, null);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
        TransactionExecution order = execution("OrderService.place", false);
        when(order.isRollbackOnly()).thenReturn(true);
        listener.afterBegin(order, null);
        listener.beforeCommit(order);
        listener.afterCommit(order, new UnexpectedRollbackException("marked rollback-only"));

        assertThat(published)
                .extracting(event -> (TransactionPayload) event.payload())
                .extracting(
                        TransactionPayload::readOnly,
                        TransactionPayload::isolation,
                        TransactionPayload::propagation,
                        TransactionPayload::rollbackOnly,
                        TransactionPayload::failureClass)
                .containsExactly(
                        tuple(true, "REPEATABLE_READ", "REQUIRED", false, null),
                        tuple(
                                false,
                                null,
                                "REQUIRED",
                                true,
                                "org.springframework.transaction.UnexpectedRollbackException"));
    }

    @Test
    void usesUnknownNameWhenTransactionNameIsBlank() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution execution = execution("  ", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, null);

        assertThat(recorder.recent().get(0).methodName()).isEqualTo("unknown");
    }

    @Test
    void readsTraceIdFromMdcWhenPresent() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);
        MDC.put("traceId", "abc123");

        TransactionExecution execution = execution("Service.method", false);
        listener.afterBegin(execution, null);
        listener.afterCommit(execution, null);

        assertThat(recorder.recent().get(0).traceId()).isEqualTo("abc123");
    }

    @Test
    void failsOpenWhenRecorderThrowsOnBegin() {
        TransactionRecorder recorder = mock(TransactionRecorder.class);
        when(recorder.beginTransaction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new IllegalStateException("boom"));
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        org.assertj.core.api.Assertions.assertThatCode(() -> listener.afterBegin(execution, null))
                .doesNotThrowAnyException();
    }

    @Test
    void completesAReactiveTransactionOnAnotherThreadWithoutAThreadBoundParent() throws Exception {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);

        TransactionExecution first = reactive("R2dbcOrders.place");
        TransactionExecution second = reactive("R2dbcOrders.cancel");
        listener.afterBegin(first, null);
        listener.afterBegin(second, null);
        // Reactor completes each wherever its pipeline runs, in any order.
        Thread elsewhere = new Thread(() -> {
            listener.afterCommit(first, null);
            listener.beforeRollback(second);
            listener.afterRollback(second, null);
        });
        elsewhere.start();
        elsewhere.join();
        TransactionExecution blocking = execution("Jdbc.audit", false);
        listener.afterBegin(blocking, null);
        listener.afterCommit(blocking, null);

        assertThat(recorder.recent())
                .extracting(
                        TransactionEntryDto::methodName,
                        TransactionEntryDto::status,
                        TransactionEntryDto::parentId,
                        TransactionEntryDto::isolation)
                .containsExactlyInAnyOrder(
                        tuple("R2dbcOrders.place", "COMMITTED", null, "UNKNOWN"),
                        tuple("R2dbcOrders.cancel", "ROLLED_BACK", null, "UNKNOWN"),
                        tuple("Jdbc.audit", "COMMITTED", null, "SERIALIZABLE"));
    }

    @Test
    void recordsTransactionsASpringReactiveTransactionManagerRunsAcrossThreads() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);
        ThreadHoppingReactiveTransactionManager manager = new ThreadHoppingReactiveTransactionManager();
        manager.addListener(listener);
        TransactionalOperator operator = TransactionalOperator.create(manager);
        Scheduler other = Schedulers.newSingle("bootui-test-other");
        try {
            Mono<String> committed = Mono.just("ok").publishOn(other).as(operator::transactional);
            Mono<String> rolledBack = Mono.<String>error(new IllegalStateException("expected"))
                    .publishOn(other)
                    .as(operator::transactional);

            assertThat(committed.block(Duration.ofSeconds(5))).isEqualTo("ok");
            assertThatThrownBy(() -> rolledBack.block(Duration.ofSeconds(5))).hasMessage("expected");
        } finally {
            other.dispose();
        }

        assertThat(recorder.recent())
                .extracting(TransactionEntryDto::status, TransactionEntryDto::parentId)
                .containsExactly(tuple("ROLLED_BACK", null), tuple("COMMITTED", null));
        assertThat(manager.completedOn).allMatch(thread -> thread.startsWith("bootui-test-other"));
    }

    /** A reactive transaction manager with no resource, recording the threads its transactions complete on. */
    private static final class ThreadHoppingReactiveTransactionManager extends AbstractReactiveTransactionManager {

        final List<String> completedOn = new CopyOnWriteArrayList<>();

        @Override
        protected Object doGetTransaction(
                org.springframework.transaction.reactive.TransactionSynchronizationManager synchronizationManager) {
            return new Object();
        }

        @Override
        protected Mono<Void> doBegin(
                org.springframework.transaction.reactive.TransactionSynchronizationManager synchronizationManager,
                Object transaction,
                TransactionDefinition definition) {
            return Mono.empty();
        }

        @Override
        protected Mono<Void> doCommit(
                org.springframework.transaction.reactive.TransactionSynchronizationManager synchronizationManager,
                GenericReactiveTransaction status) {
            return Mono.fromRunnable(
                    () -> completedOn.add(Thread.currentThread().getName()));
        }

        @Override
        protected Mono<Void> doRollback(
                org.springframework.transaction.reactive.TransactionSynchronizationManager synchronizationManager,
                GenericReactiveTransaction status) {
            return Mono.fromRunnable(
                    () -> completedOn.add(Thread.currentThread().getName()));
        }
    }

    @Test
    void completeIsANoOpWhenNoMatchingBeginWasRecorded() {
        TransactionRecorder recorder = recorder();
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        listener.afterCommit(execution, null);

        assertThat(recorder.recent()).isEmpty();
    }

    private static TransactionRecorder recorder() {
        return new TransactionRecorder(true, true, 10, 100, 100, null);
    }

    private static TransactionExecution reactive(String name) {
        ReactiveTransaction execution = mock(ReactiveTransaction.class);
        when(execution.getTransactionName()).thenReturn(name);
        return execution;
    }

    private static TransactionExecution execution(String name, boolean readOnly) {
        TransactionExecution execution = mock(TransactionExecution.class);
        when(execution.getTransactionName()).thenReturn(name);
        when(execution.isReadOnly()).thenReturn(readOnly);
        return execution;
    }
}
