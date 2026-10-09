package io.github.jdubois.bootui.autoconfigure.transactions;

import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder.Status;
import java.sql.Connection;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bridges Spring Framework's {@code TransactionExecutionListener} (Spring 6.1+) to the
 * framework-neutral {@link TransactionRecorder}. Exposed as a Spring bean so Spring Boot's standard
 * transaction-manager customization registers it against every {@code ConfigurableTransactionManager};
 * it composes with, and never replaces, the application's own transaction management or any other
 * listener.
 *
 * <p>The recorder is told about a boundary at {@code afterBegin} rather than {@code beforeBegin} so
 * that, on success, {@link TransactionSynchronizationManager#getCurrentTransactionIsolationLevel()} is
 * already populated (isolation is bound to the synchronization only once the manager's {@code
 * doBegin} has actually run). Each callback names its transaction by the {@link TransactionExecution}
 * Spring passes to every callback of one boundary, the same instance from begin to completion, so the
 * matching {@code afterCommit}/{@code afterRollback} completes the right entry wherever it runs. A
 * reactive transaction ({@code AbstractReactiveTransactionManager}, such as R2DBC's) begins and completes
 * on whichever threads its pipeline runs, so it is recorded without a thread-bound parent or isolation.
 * The executions are held weakly: a boundary that never completes leaves nothing behind here.</p>
 *
 * <p>Every callback is fully guarded: a recorder failure must never fail, roll back, or otherwise
 * disrupt the application's actual transaction.</p>
 */
public final class BootUiTransactionExecutionListener implements TransactionExecutionListener {

    private final TransactionRecorder recorder;
    /** Per begun boundary, its recorder id and whether it was marked rollback-only just before it completed. */
    private final Map<TransactionExecution, Pending> pending = Collections.synchronizedMap(new WeakHashMap<>());

    public BootUiTransactionExecutionListener(TransactionRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void afterBegin(TransactionExecution transactionExecution, Throwable beginFailure) {
        try {
            String name = transactionName(transactionExecution);
            boolean readOnly = transactionExecution.isReadOnly();
            String thread = Thread.currentThread().getName();
            String traceId = mdcTraceId();
            if (beginFailure != null) {
                long id = recorder.beginTransaction(name, readOnly, null, thread, traceId);
                recorder.completeTransaction(id, Status.UNKNOWN, message(beginFailure));
                return;
            }
            boolean threadBound = !(transactionExecution instanceof ReactiveTransaction);
            long id = recorder.beginTransaction(
                    name,
                    readOnly,
                    threadBound ? currentIsolation() : null,
                    thread,
                    traceId,
                    transactionExecution.isNested(),
                    threadBound);
            if (id >= 0) {
                pending.put(transactionExecution, new Pending(id));
            }
        } catch (RuntimeException ignored) {
            // A recorder failure must never disrupt the application's real transaction.
        }
    }

    @Override
    public void beforeCommit(TransactionExecution transactionExecution) {
        markRollbackOnly(transactionExecution);
    }

    @Override
    public void beforeRollback(TransactionExecution transactionExecution) {
        markRollbackOnly(transactionExecution);
    }

    @Override
    public void afterCommit(TransactionExecution transactionExecution, Throwable commitFailure) {
        complete(transactionExecution, commitFailure == null ? Status.COMMITTED : Status.UNKNOWN, commitFailure);
    }

    @Override
    public void afterRollback(TransactionExecution transactionExecution, Throwable rollbackFailure) {
        complete(transactionExecution, Status.ROLLED_BACK, rollbackFailure);
    }

    private void markRollbackOnly(TransactionExecution transactionExecution) {
        try {
            Pending begun = pending.get(transactionExecution);
            if (begun != null) {
                begun.rollbackOnly = transactionExecution.isRollbackOnly();
            }
        } catch (RuntimeException ignored) {
            // A recorder failure must never disrupt the application's real transaction.
        }
    }

    private void complete(TransactionExecution transactionExecution, Status status, Throwable failure) {
        try {
            Pending begun = pending.remove(transactionExecution);
            if (begun != null) {
                recorder.completeTransaction(
                        begun.id,
                        status,
                        message(failure),
                        begun.rollbackOnly,
                        failure == null ? null : failure.getClass().getName());
            }
        } catch (RuntimeException ignored) {
            // A recorder failure must never disrupt the application's real transaction.
        }
    }

    /** A begun boundary: its recorder id, and whether it was marked rollback-only when it was about to complete. */
    private static final class Pending {

        final long id;
        volatile boolean rollbackOnly;

        Pending(long id) {
            this.id = id;
        }
    }

    private static String transactionName(TransactionExecution transactionExecution) {
        String name = transactionExecution.getTransactionName();
        return name == null || name.isBlank() ? "unknown" : name;
    }

    private static String message(Throwable failure) {
        return failure == null ? null : failure.getMessage();
    }

    /**
     * Reads the JDBC isolation level {@code AbstractPlatformTransactionManager} bound to the current
     * transaction synchronization once {@code doBegin} completes, mapped to its readable {@code
     * java.sql.Connection} constant name. Returns {@code null} (recorded as {@code UNKNOWN}) when no
     * isolation level was bound, e.g. a resource-less transaction or a manager that leaves the default.
     */
    private static String currentIsolation() {
        try {
            Integer level = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (level == null) {
                return null;
            }
            return switch (level) {
                case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
                case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
                case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
                case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
                default -> "UNKNOWN";
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Same SLF4J MDC {@code traceId} lookup {@code SqlTraceRecorder} uses by default: the correlation
     * key Micrometer Tracing publishes, read defensively so a missing or misbehaving MDC never disrupts
     * the transaction being observed.
     */
    private static String mdcTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
