package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.transactions.TransactionsControllerSupport;
import io.github.jdubois.bootui.core.dto.TransactionRecordingRequest;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code TransactionsController}: identical read/clear/recording
 * semantics over the same framework-neutral {@link TransactionRecorder}, with the {@code /stream}
 * endpoint rebuilt on {@link ReactiveBootUiChangeStream} instead of a servlet {@code SseEmitter}.
 *
 * <p>Capture itself is wired the same way as Spring MVC: BootUI's {@code
 * BootUiTransactionManagerBeanPostProcessor} registers a {@code TransactionExecutionListener} against
 * every {@code ConfigurableTransactionManager} bean, which observes any blocking {@code
 * PlatformTransactionManager} a WebFlux application still uses (e.g. wrapping JDBC repositories). A
 * WebFlux application backed only by a {@code ReactiveTransactionManager} (R2DBC) has no such bean to
 * observe — Spring's transaction-execution listener hook exists solely on the blocking SPI — so the
 * panel honestly reports unavailable in that case, exactly as it does when no manager exists at all.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/transactions")
public class ReactiveTransactionsController {

    private final ObjectProvider<TransactionRecorder> recorderProvider;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    private final ReactiveBootUiChangeStream changeStream;
    private Runnable recorderUnsubscribe;

    public ReactiveTransactionsController(
            ObjectProvider<TransactionRecorder> recorderProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.recorderProvider = recorderProvider;
        this.transactionManagerProvider = transactionManagerProvider;
        this.changeStream = new ReactiveBootUiChangeStream("transactions");
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
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        return changeStream.open();
    }
}
