package io.github.jdubois.bootui.autoconfigure.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("boom"));
        BootUiTransactionExecutionListener listener = new BootUiTransactionExecutionListener(recorder);

        TransactionExecution execution = execution("Service.method", false);
        org.assertj.core.api.Assertions.assertThatCode(() -> listener.afterBegin(execution, null))
                .doesNotThrowAnyException();
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

    private static TransactionExecution execution(String name, boolean readOnly) {
        TransactionExecution execution = mock(TransactionExecution.class);
        when(execution.getTransactionName()).thenReturn(name);
        when(execution.isReadOnly()).thenReturn(readOnly);
        return execution;
    }
}
