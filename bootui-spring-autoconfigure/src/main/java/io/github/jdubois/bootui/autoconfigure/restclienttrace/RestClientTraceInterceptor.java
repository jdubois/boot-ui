package io.github.jdubois.bootui.autoconfigure.restclienttrace;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Shared {@link ClientHttpRequestInterceptor} that records every outbound call made through a
 * BootUI-customized {@code RestClient} or {@code RestTemplate} into a {@link RestClientTraceRecorder}. One
 * instance is created per client type (see the customizer beans in {@code BootUiAutoConfiguration}) so the
 * recorded {@code clientType} label ("RestClient" or "RestTemplate") is correct without any request-time
 * detection.
 *
 * <p>Mirrors {@code SqlTracingProxies}: always times and always calls {@code recorder.record(...)}
 * unconditionally, relying entirely on the recorder's own enabled/recording check to no-op cheaply when
 * tracing is off. Query parameter and header values are passed through raw (only truncated for size); the
 * recorder itself applies exposure-aware masking by name at display time, never at capture time. This
 * interceptor only extracts the raw request/response data and measures duration, and never lets a capture
 * failure disrupt the outbound call.</p>
 */
public class RestClientTraceInterceptor implements ClientHttpRequestInterceptor {

    private final RestClientTraceRecorder recorder;
    private final String clientType;

    public RestClientTraceInterceptor(RestClientTraceRecorder recorder, String clientType) {
        this.recorder = recorder;
        this.clientType = clientType;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long start = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            recordSafely(request, elapsedMillis(start), statusOf(response), true, null);
            return response;
        } catch (IOException | RuntimeException ex) {
            recordSafely(request, elapsedMillis(start), null, false, ex.getMessage());
            throw ex;
        }
    }

    private Integer statusOf(ClientHttpResponse response) {
        try {
            return response.getStatusCode().value();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private void recordSafely(
            HttpRequest request, long durationMillis, Integer status, boolean success, String errorMessage) {
        try {
            URI uri = request.getURI();
            recorder.record(
                    request.getMethod() == null ? null : request.getMethod().name(),
                    uri.toString(),
                    uri.getHost(),
                    uri.getPath(),
                    status,
                    durationMillis,
                    success,
                    errorMessage,
                    clientType,
                    flattenHeaders(request.getHeaders()),
                    Thread.currentThread().getName());
        } catch (RuntimeException ignored) {
            // The response has already been returned (or the exception already thrown) by the time this
            // runs - a capture failure must never disrupt the outbound call.
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
