package io.github.jdubois.bootui.quarkus.restclienttrace;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.support.SensitiveNames;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Plain JAX-RS client filter that captures outbound REST Client Reactive metadata into the shared engine
 * {@link RestClientTraceRecorder}.
 *
 * <p>It never reads request/response entities or headers. User-info, fragments, and recognizable secrets in
 * path/query components are removed before the URI reaches the in-memory recorder. Quarkus invokes the
 * response filter for received HTTP responses (including 4xx/5xx) and represents a transport failure with
 * status {@code 0}, which is captured as a failed call with no HTTP status.</p>
 */
public final class QuarkusRestClientTraceFilter implements ClientRequestFilter, ClientResponseFilter {

    private static final Logger LOG = Logger.getLogger(QuarkusRestClientTraceFilter.class);
    private static final String CAPTURE_PROPERTY = QuarkusRestClientTraceFilter.class.getName() + ".capture";
    private static final String CLIENT_TYPE = "Quarkus REST Client Reactive";
    private static final SecretMasker SECRET_MASKER = new SecretMasker();

    private final RestClientTraceRecorder recorder;

    public QuarkusRestClientTraceFilter(RestClientTraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        try {
            requestContext.setProperty(
                    CAPTURE_PROPERTY, new RequestCapture(System.nanoTime(), recorder.currentTraceId()));
        } catch (RuntimeException failure) {
            logCaptureFailure(failure);
        }
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        try {
            RequestCapture capture = requestCapture(requestContext.getProperty(CAPTURE_PROPERTY));
            long durationMillis = elapsedMillis(capture);
            CapturedUri uri = sanitize(requestContext.getUri());
            int status = responseContext.getStatus();
            boolean responseReceived = status > 0;
            // Any received HTTP response is a successful transport exchange. Quarkus reports pre-response
            // transport failures to client filters with status 0.
            recorder.record(
                    requestContext.getMethod(),
                    uri.value(),
                    uri.host(),
                    uri.path(),
                    responseReceived ? status : null,
                    durationMillis,
                    responseReceived,
                    responseReceived ? null : "REST client transport failed before receiving an HTTP response",
                    CLIENT_TYPE,
                    Map.of(),
                    Thread.currentThread().getName(),
                    capture.traceId());
        } catch (RuntimeException failure) {
            // Capture must not change the response observed by the application.
            logCaptureFailure(failure);
        }
    }

    private static RequestCapture requestCapture(Object capture) {
        return capture instanceof RequestCapture requestCapture
                ? requestCapture
                : new RequestCapture(System.nanoTime(), null);
    }

    private static long elapsedMillis(RequestCapture capture) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - capture.startNanos()));
    }

    /**
     * Sanitizes before storage. Report-time exposure policy still applies, but the Quarkus adapter never
     * retains raw URI credentials.
     */
    static CapturedUri sanitize(URI uri) {
        if (uri == null) {
            return new CapturedUri(null, null, null);
        }
        String authority = uri.getRawAuthority();
        if (authority != null) {
            int userInfoEnd = authority.lastIndexOf('@');
            if (userInfoEnd >= 0) {
                authority = authority.substring(userInfoEnd + 1);
            }
        }
        String path = sanitizePath(uri.getRawPath());
        String query = sanitizeQuery(uri.getRawQuery());
        StringBuilder value = new StringBuilder();
        if (uri.getScheme() != null) {
            value.append(uri.getScheme()).append(':');
        }
        if (authority != null) {
            value.append("//").append(authority);
        }
        if (path != null) {
            value.append(path);
        }
        if (query != null) {
            value.append('?').append(query);
        }
        return new CapturedUri(value.toString(), uri.getHost(), path);
    }

    private static String sanitizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }
        String[] segments = rawPath.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (SECRET_MASKER.shouldMask("path-segment", decode(segments[i], false))) {
                segments[i] = SecretMasker.MASKED_VALUE;
            }
        }
        return String.join("/", segments);
    }

    private static String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return rawQuery;
        }
        String[] parameters = rawQuery.split("&", -1);
        for (int i = 0; i < parameters.length; i++) {
            int equals = parameters[i].indexOf('=');
            if (equals < 0) {
                String decoded = decode(parameters[i], true);
                if (SECRET_MASKER.shouldMask(decoded, decoded)) {
                    parameters[i] = SecretMasker.MASKED_VALUE;
                }
                continue;
            }
            String rawName = parameters[i].substring(0, equals);
            String rawValue = parameters[i].substring(equals + 1);
            if (SECRET_MASKER.shouldMask(decode(rawName, true), decode(rawValue, true))) {
                parameters[i] = rawName + '=' + SecretMasker.MASKED_VALUE;
            }
        }
        return String.join("&", parameters);
    }

    private static String decode(String value, boolean formEncoded) {
        return formEncoded ? SensitiveNames.decodeQueryComponent(value) : SensitiveNames.decodePathComponent(value);
    }

    private static void logCaptureFailure(RuntimeException failure) {
        LOG.warnf(
                "BootUI skipped a Quarkus REST Client capture (%s)",
                failure.getClass().getSimpleName());
    }

    record CapturedUri(String value, String host, String path) {}

    private record RequestCapture(long startNanos, String traceId) {}
}
