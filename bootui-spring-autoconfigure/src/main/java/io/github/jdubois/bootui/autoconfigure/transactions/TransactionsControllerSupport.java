package io.github.jdubois.bootui.autoconfigure.transactions;

import io.github.jdubois.bootui.core.dto.TransactionRecordingRequest;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Shared read/clear/recording business logic for the BootUI Transactions panel, used by both the
 * servlet {@code TransactionsController} and the WebFlux {@code ReactiveTransactionsController}.
 * Both bindings expose the identical REST contract over the same framework-neutral
 * {@link TransactionRecorder}, and none of this logic touches a servlet or reactive
 * request/response type, so it is extracted once here rather than duplicated per transport,
 * mirroring {@code SqlTraceControllerSupport}.
 */
public final class TransactionsControllerSupport {

    private static final String NOT_CONFIGURED = "Transaction capture is not configured";

    private TransactionsControllerSupport() {}

    public static TransactionReport trace(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        TransactionRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return TransactionReport.unavailable(NOT_CONFIGURED);
        }
        return report(recorder, transactionManagerProvider);
    }

    public static TransactionReport clear(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        TransactionRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return TransactionReport.unavailable(NOT_CONFIGURED);
        }
        recorder.clear();
        return report(recorder, transactionManagerProvider);
    }

    public static TransactionReport recording(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            TransactionRecordingRequest request) {
        TransactionRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return TransactionReport.unavailable(NOT_CONFIGURED);
        }
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report(recorder, transactionManagerProvider);
    }

    private static TransactionReport report(
            TransactionRecorder recorder, ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        if (!recorder.isEnabled()) {
            return TransactionReport.unavailable(
                    "Transaction capture is disabled (set bootui.transactions.enabled=true in a trusted local"
                            + " profile).");
        }
        if (transactionManagerProvider.getIfAvailable() == null) {
            return TransactionReport.unavailable("No PlatformTransactionManager bean is available");
        }
        return recorder.report();
    }
}
