package io.github.jdubois.bootui.autoconfigure.transactions;

import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.core.dto.TransactionRecordingRequest;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read-mostly endpoint backing the Transactions panel.
 *
 * <p>Returns the transaction boundaries captured by BootUI's {@link BootUiTransactionExecutionListener}
 * wiring (no third-party transaction-observability library) and exposes state-changing {@code clear}
 * and {@code recording} (pause/resume) actions (gated by the panel access filter when the panel is
 * read-only).</p>
 *
 * <p>The trace/clear/recording business logic lives in {@link TransactionsControllerSupport}, shared
 * with the WebFlux sibling {@code ReactiveTransactionsController} since none of it touches a servlet
 * or reactive request/response type. This class keeps only the {@code @RestController} wiring, the
 * SSE {@code /stream} endpoint, and the recorder-listener lifecycle — the same split
 * {@code SqlTraceController} uses.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/transactions")
public class TransactionsController {

    private final ObjectProvider<TransactionRecorder> recorderProvider;
    private final ObjectProvider<ConfigurableTransactionManager> transactionManagerProvider;
    private final BootUiChangeStream changeStream;
    private Runnable recorderUnsubscribe;

    public TransactionsController(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ObjectProvider<ConfigurableTransactionManager> transactionManagerProvider) {
        this.recorderProvider = recorderProvider;
        this.transactionManagerProvider = transactionManagerProvider;
        this.changeStream = new BootUiChangeStream("transactions");
        TransactionRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            this.recorderUnsubscribe = recorder.subscribe(changeStream::signal);
        }
    }

    /**
     * Completes any open SSE streams and detaches the recorder listener when the context starts
     * closing. See {@code SqlTraceController#shutdown} for why this runs on {@link ContextClosedEvent}
     * rather than a destroy callback.
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        if (recorderUnsubscribe != null) {
            recorderUnsubscribe.run();
            recorderUnsubscribe = null;
        }
        changeStream.close();
    }

    @GetMapping
    public TransactionReport trace() {
        return TransactionsControllerSupport.trace(recorderProvider, transactionManagerProvider);
    }

    @PostMapping("/clear")
    public TransactionReport clear() {
        return TransactionsControllerSupport.clear(recorderProvider, transactionManagerProvider);
    }

    @PostMapping("/recording")
    public TransactionReport recording(@RequestBody(required = false) TransactionRecordingRequest request) {
        return TransactionsControllerSupport.recording(recorderProvider, transactionManagerProvider, request);
    }

    /**
     * Streams a coalesced {@code update} notification whenever a transaction completes, the buffer is
     * cleared, or recording is paused/resumed, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }
}
