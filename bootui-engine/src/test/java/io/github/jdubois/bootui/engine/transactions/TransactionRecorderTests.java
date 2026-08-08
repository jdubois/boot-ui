package io.github.jdubois.bootui.engine.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionRecorderTests {

    private TransactionRecorder recorder(boolean enabled, int maxEntries, long slowMillis, long connectionHoldMillis) {
        return new TransactionRecorder(enabled, true, maxEntries, slowMillis, connectionHoldMillis, null);
    }

    @Test
    void beginTransactionReturnsSentinelWhenDisabled() {
        TransactionRecorder recorder = recorder(false, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        assertThat(id).isEqualTo(-1);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void beginTransactionReturnsSentinelWhenPaused() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        recorder.setRecording(false);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        assertThat(id).isEqualTo(-1);

        recorder.setRecording(true);
        long resumedId = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        assertThat(resumedId).isNotEqualTo(-1);
        recorder.completeTransaction(resumedId, Status.COMMITTED, null);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void recordsACommittedRootTransaction() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("OrderService.placeOrder", false, "READ_COMMITTED", "http-1", "trace-1");
        recorder.completeTransaction(id, Status.COMMITTED, null);

        List<TransactionEntryDto> entries = recorder.recent();
        assertThat(entries).hasSize(1);
        TransactionEntryDto entry = entries.get(0);
        assertThat(entry.methodName()).isEqualTo("OrderService.placeOrder");
        assertThat(entry.propagation()).isEqualTo(TransactionRecorder.PROPAGATION_NEW);
        assertThat(entry.isolation()).isEqualTo("READ_COMMITTED");
        assertThat(entry.status()).isEqualTo("COMMITTED");
        assertThat(entry.parentId()).isNull();
        assertThat(entry.thread()).isEqualTo("http-1");
        assertThat(entry.traceId()).isEqualTo("trace-1");
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }

    @Test
    void recordsARolledBackTransactionWithErrorMessage() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, null, "main", null);
        recorder.completeTransaction(id, Status.ROLLED_BACK, "boom");

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.status()).isEqualTo("ROLLED_BACK");
        assertThat(entry.errorMessage()).isEqualTo("boom");
        assertThat(entry.isolation()).isEqualTo(TransactionRecorder.ISOLATION_UNKNOWN);
    }

    @Test
    void tracksParentChildNestingOnTheSameThread() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long parentId = recorder.beginTransaction("Outer.method", false, "READ_COMMITTED", "main", null);
        long childId = recorder.beginTransaction("Inner.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(childId, Status.COMMITTED, null);
        recorder.completeTransaction(parentId, Status.COMMITTED, null);

        List<TransactionEntryDto> entries = recorder.recent();
        TransactionEntryDto child =
                entries.stream().filter(e -> e.id() == childId).findFirst().orElseThrow();
        TransactionEntryDto parent =
                entries.stream().filter(e -> e.id() == parentId).findFirst().orElseThrow();
        assertThat(child.propagation()).isEqualTo(TransactionRecorder.PROPAGATION_PARTICIPATING);
        assertThat(child.parentId()).isEqualTo(parentId);
        assertThat(parent.propagation()).isEqualTo(TransactionRecorder.PROPAGATION_NEW);
        assertThat(parent.parentId()).isNull();
        assertThat(recorder.stats().nestedCount()).isEqualTo(1);
    }

    @Test
    void evictsOldestEntryOnceBufferIsFull() {
        TransactionRecorder recorder = recorder(true, 2, 100, 100);
        for (int i = 0; i < 3; i++) {
            long id = recorder.beginTransaction("Service.method" + i, false, "READ_COMMITTED", "main", null);
            recorder.completeTransaction(id, Status.COMMITTED, null);
        }
        assertThat(recorder.recent()).hasSize(2);
        assertThat(recorder.evicted()).isEqualTo(1);
        assertThat(recorder.totalCaptured()).isEqualTo(3);
        // Most recently completed first.
        assertThat(recorder.recent().get(0).methodName()).isEqualTo("Service.method2");
    }

    @Test
    void flagsSlowAndConnectionHeldTransactions() throws InterruptedException {
        TransactionRecorder recorder = recorder(true, 10, 5, 5);
        long id = recorder.beginTransaction("Service.slowMethod", false, "READ_COMMITTED", "main", null);
        Thread.sleep(20);
        recorder.completeTransaction(id, Status.COMMITTED, null);

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.slow()).isTrue();
        assertThat(entry.connectionHeld()).isTrue();
        assertThat(recorder.stats().slowTransactions()).isEqualTo(1);
        assertThat(recorder.stats().connectionHeldTransactions()).isEqualTo(1);
    }

    @Test
    void clearEmptiesTheBufferWithoutResettingTotalCaptured() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        assertThat(recorder.recent()).hasSize(1);

        recorder.clear();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }

    @Test
    void suspendForIdleClearsAndStopsCaptureUntilResumed() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        assertThat(recorder.recent()).hasSize(1);

        recorder.suspendForIdle();
        assertThat(recorder.recent()).isEmpty();
        long suspendedId = recorder.beginTransaction("Service.other", false, "READ_COMMITTED", "main", null);
        assertThat(suspendedId).isEqualTo(-1);

        recorder.resumeFromIdle();
        long resumedId = recorder.beginTransaction("Service.other", false, "READ_COMMITTED", "main", null);
        assertThat(resumedId).isNotEqualTo(-1);
        recorder.completeTransaction(resumedId, Status.COMMITTED, null);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void completeTransactionIgnoresSentinelAndUnknownIds() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        recorder.completeTransaction(-1, Status.COMMITTED, null);
        recorder.completeTransaction(9999, Status.COMMITTED, null);
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void subscribeIsNotifiedOnCompletionClearAndRecordingToggle() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        int[] notifications = {0};
        Runnable unsubscribe = recorder.subscribe(() -> notifications[0]++);

        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);
        assertThat(notifications[0]).isEqualTo(1);

        recorder.clear();
        assertThat(notifications[0]).isEqualTo(2);

        recorder.setRecording(false);
        assertThat(notifications[0]).isEqualTo(3);

        unsubscribe.run();
        recorder.setRecording(true);
        assertThat(notifications[0]).isEqualTo(3);
    }

    @Test
    void reportReflectsUnavailableSqlTraceCorrelationWarning() {
        TransactionRecorder recorder = recorder(true, 10, 100, 100);
        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "main", null);
        recorder.completeTransaction(id, Status.COMMITTED, null);

        TransactionReport report = recorder.report();
        assertThat(report.available()).isTrue();
        assertThat(report.warnings()).anyMatch(w -> w.contains("SQL Trace is not active"));
        assertThat(report.entries()).hasSize(1);
        assertThat(report.entries().get(0).sqlStatementCount()).isZero();
        assertThat(report.entries().get(0).connectionCount()).isZero();
    }

    @Test
    void correlatesCompletedTransactionToSqlTraceExecutionsOnTheSameThread() throws InterruptedException {
        SqlTraceRecorder sqlTraceRecorder = new SqlTraceRecorder(true, true, false, false, 50, 100, 2000, 200, 5);
        TransactionRecorder recorder = new TransactionRecorder(true, true, 10, 100, 100, sqlTraceRecorder);

        long id = recorder.beginTransaction("Service.method", false, "READ_COMMITTED", "worker-1", null);
        sqlTraceRecorder.record(
                StatementType.STATEMENT,
                Category.SELECT,
                "select 1",
                List.of(),
                1,
                true,
                null,
                null,
                0,
                "conn-1",
                "worker-1");
        sqlTraceRecorder.record(
                StatementType.STATEMENT,
                Category.INSERT,
                "insert into t values (1)",
                List.of(),
                1,
                true,
                null,
                null,
                0,
                "conn-1",
                "worker-1");
        // A statement on a different thread must not be attributed to this transaction.
        sqlTraceRecorder.record(
                StatementType.STATEMENT,
                Category.SELECT,
                "select 2",
                List.of(),
                1,
                true,
                null,
                null,
                0,
                "conn-2",
                "worker-2");
        recorder.completeTransaction(id, Status.COMMITTED, null);

        TransactionEntryDto entry = recorder.recent().get(0);
        assertThat(entry.sqlStatementCount()).isEqualTo(2);
        assertThat(entry.connectionCount()).isEqualTo(1);
    }

    @Test
    void unavailableFactoryReportsReasonWithNoStatsOrEntries() {
        TransactionReport report = TransactionReport.unavailable("No PlatformTransactionManager bean is available");
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("No PlatformTransactionManager bean is available");
        assertThat(report.entries()).isEmpty();
        assertThat(report.stats().totalTransactions()).isZero();
    }
}
