package io.github.jdubois.bootui.autoconfigure.restclienttrace;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveThreadKinds;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.engine.javaagent.RequestInputSinks;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * {@link ExchangeFilterFunction} that records every outbound call made through a BootUI-customized {@code
 * WebClient} into a {@link RestClientTraceRecorder}, using the {@code "WebClient"} client-type label.
 *
 * <p>{@code WebClient} is reactive, so the call is timed around the {@link Mono} returned by the
 * downstream {@link ExchangeFunction} rather than around a blocking call: {@link Mono#doOnNext} captures a
 * successful exchange (the response is available, even for an error HTTP status) and {@link Mono#doOnError}
 * captures a transport-level failure. Neither callback can alter the emitted signal, so a capture failure
 * can never disrupt the outbound call. Query parameter and header values are passed through raw (only
 * truncated for size); the recorder itself applies exposure-aware masking by name at display time, never at
 * capture time.</p>
 *
 * <p>The filter runs when the exchange is subscribed, on the subscribing thread, which issued the call when the caller
 * blocks on it or subscribes in place: the caller's code-paths stamp ({@code docs/PLAN-v2.md} §5.14, M5-4c) is taken
 * there, with its correlation, so the call shows under the method that issued it. A call subscribed on another thread,
 * as with {@code subscribeOn}, takes that thread's stamp, usually none.</p>
 */
public class RestClientTraceExchangeFilter implements ExchangeFilterFunction {

    private static final String CLIENT_TYPE = "WebClient";

    private final RestClientTraceRecorder recorder;

    public RestClientTraceExchangeFilter(RestClientTraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        long start = System.nanoTime();
        // The call starts here, on the subscribing thread; its outcome arrives on an event loop, so the caller's
        // correlation and thread kind are captured now (docs/PLAN-v2.md §5.1).
        Caller caller = caller();
        // Whether request input reached this call's URL unchanged, where it is issued (docs/PLAN-v2.md §5.16, M5-6b).
        if (AgentRequestValues.enabled()) {
            RequestInputSinks.url(request.url(), caller.correlation());
        }
        return next.exchange(request)
                .doOnNext(response -> {
                    // The response arrives on the client's event loop: the agent's blocking sensor watches it (M5-5c).
                    ReactiveThreadKinds.registerIfEventLoop();
                    recordSafely(request, elapsedNanos(start), statusOf(response), true, null, caller);
                })
                .doOnError(ex -> recordSafely(request, elapsedNanos(start), null, false, ex.getMessage(), caller));
    }

    private Caller caller() {
        try {
            return new Caller(recorder.currentCorrelation(), recorder.currentThreadKind(), AgentCodePaths.stamp());
        } catch (RuntimeException ex) {
            return new Caller(CorrelationContext.NONE, null, 0L);
        }
    }

    private static Integer statusOf(ClientResponse response) {
        try {
            return response.statusCode().value();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void recordSafely(
            ClientRequest request,
            long durationNanos,
            Integer status,
            boolean success,
            String errorMessage,
            Caller caller) {
        try {
            URI uri = request.url();
            recorder.recordNanos(
                    request.method() == null ? null : request.method().name(),
                    uri.toString(),
                    uri.getHost(),
                    uri.getPath(),
                    status,
                    durationNanos,
                    success,
                    errorMessage,
                    CLIENT_TYPE,
                    flattenHeaders(request.headers()),
                    Thread.currentThread().getName(),
                    recorder.currentTraceId(),
                    caller.correlation(),
                    caller.threadKind(),
                    caller.codePathStamp());
        } catch (RuntimeException ignored) {
            // The response/error has already been emitted downstream by the time this runs - a capture
            // failure must never disrupt the outbound call.
        }
    }

    private record Caller(CorrelationContext correlation, ThreadKind threadKind, long codePathStamp) {}

    private static long elapsedNanos(long startNanos) {
        return Math.max(0, System.nanoTime() - startNanos);
    }

    private static Map<String, String> flattenHeaders(HttpHeaders headers) {
        Map<String, String> flattened = new LinkedHashMap<>();
        headers.forEach((name, values) -> flattened.put(name, String.join(", ", values)));
        return flattened;
    }
}
