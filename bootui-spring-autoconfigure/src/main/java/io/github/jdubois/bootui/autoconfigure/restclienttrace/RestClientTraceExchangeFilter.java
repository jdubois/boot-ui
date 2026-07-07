package io.github.jdubois.bootui.autoconfigure.restclienttrace;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
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
        return next.exchange(request)
                .doOnNext(response -> recordSafely(request, elapsedMillis(start), statusOf(response), true, null))
                .doOnError(ex -> recordSafely(request, elapsedMillis(start), null, false, ex.getMessage()));
    }

    private static Integer statusOf(ClientResponse response) {
        try {
            return response.statusCode().value();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void recordSafely(
            ClientRequest request, long durationMillis, Integer status, boolean success, String errorMessage) {
        try {
            URI uri = request.url();
            recorder.record(
                    request.method() == null ? null : request.method().name(),
                    uri.toString(),
                    uri.getHost(),
                    uri.getPath(),
                    status,
                    durationMillis,
                    success,
                    errorMessage,
                    CLIENT_TYPE,
                    flattenHeaders(request.headers()),
                    Thread.currentThread().getName());
        } catch (RuntimeException ignored) {
            // The response/error has already been emitted downstream by the time this runs - a capture
            // failure must never disrupt the outbound call.
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static Map<String, String> flattenHeaders(HttpHeaders headers) {
        Map<String, String> flattened = new LinkedHashMap<>();
        headers.forEach((name, values) -> flattened.put(name, String.join(", ", values)));
        return flattened;
    }
}
