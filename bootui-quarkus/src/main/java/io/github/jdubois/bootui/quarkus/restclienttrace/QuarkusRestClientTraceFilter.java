package io.github.jdubois.bootui.quarkus.restclienttrace;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * Plain JAX-RS client filter (no CDI scope) that captures outbound REST Client Reactive calls into the
 * shared engine {@link RestClientTraceRecorder}. One instance is registered per {@code @RegisterRestClient}
 * interface by {@link QuarkusRestClientTraceListener} via the MicroProfile {@code RestClientListener} SPI.
 *
 * <p>Records method, sanitized URI/host/path, status, duration, and thread; never captures request/response
 * bodies, credentials, authorization headers, cookies, or tokens. When the response filter is not called
 * (e.g., the recorder is disabled or paused) the call is simply skipped — no state is left behind. Transport-
 * level failures (network errors, connection timeouts) before an HTTP response is received are not captured;
 * only calls that produce an HTTP status code appear in the panel. This is an accepted fidelity difference
 * vs. Spring's {@code RestClientTraceInterceptor}, which wraps the underlying transport and sees
 * {@code IOException}s too.</p>
 */
public final class QuarkusRestClientTraceFilter implements ClientRequestFilter, ClientResponseFilter {

    /** Property key used to propagate the call start time from request filter to response filter. */
    private static final String START_NANOS_PROPERTY = "bootui.rc.start";

    private static final String CLIENT_TYPE = "REST Client Reactive";

    private final RestClientTraceRecorder recorder;

    public QuarkusRestClientTraceFilter(RestClientTraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        if (!recorder.isEnabled() || !recorder.isRecording()) {
            return;
        }
        requestContext.setProperty(START_NANOS_PROPERTY, System.nanoTime());
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {
        Long startNanos = (Long) requestContext.getProperty(START_NANOS_PROPERTY);
        if (startNanos == null) {
            // Request filter skipped (recorder disabled/paused) — nothing to record.
            return;
        }
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        URI uri = requestContext.getUri();
        String host = uri.getHost();
        String path = uri.getPath();
        String uriString = uri.toString();
        String method = requestContext.getMethod();
        int status = responseContext.getStatus();
        boolean success = status < 400;
        String errorMessage = success ? null : "HTTP " + status;
        String thread = Thread.currentThread().getName();

        Map<String, String> headers = Map.of();
        if (recorder.isCaptureHeaders()) {
            headers = extractHeaders(requestContext);
        }

        recorder.record(
                method,
                uriString,
                host,
                path,
                status,
                durationMillis,
                success,
                errorMessage,
                CLIENT_TYPE,
                headers,
                thread);
    }

    /** Extracts request headers as a name→value map (case-folded names, first value per header). */
    private static Map<String, String> extractHeaders(ClientRequestContext requestContext) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        requestContext.getHeaders().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                Object firstValue = values.get(0);
                if (firstValue != null) {
                    result.put(name.toLowerCase(java.util.Locale.ROOT), String.valueOf(firstValue));
                }
            }
        });
        return result;
    }
}
