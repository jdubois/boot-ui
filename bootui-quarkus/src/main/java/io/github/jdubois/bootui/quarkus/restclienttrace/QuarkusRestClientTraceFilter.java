package io.github.jdubois.bootui.quarkus.restclienttrace;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import io.github.jdubois.bootui.engine.support.SensitiveNames;
import io.github.jdubois.bootui.engine.support.UriMasking;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import java.net.URI;
import java.util.Map;
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
                    CAPTURE_PROPERTY,
                    new RequestCapture(
                            System.nanoTime(),
                            recorder.currentTraceId(),
                            recorder.currentCorrelation(),
                            recorder.currentThreadKind()));
        } catch (RuntimeException failure) {
            logCaptureFailure(failure);
        }
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        try {
            RequestCapture capture = requestCapture(requestContext.getProperty(CAPTURE_PROPERTY));
            long durationNanos = elapsedNanos(capture);
            CapturedUri uri = sanitize(requestContext.getUri());
            int status = responseContext.getStatus();
            boolean responseReceived = status > 0;
            // Any received HTTP response is a successful transport exchange. Quarkus reports pre-response
            // transport failures to client filters with status 0.
            recorder.recordNanos(
                    requestContext.getMethod(),
                    uri.value(),
                    uri.host(),
                    uri.path(),
                    responseReceived ? status : null,
                    durationNanos,
                    responseReceived,
                    responseReceived ? null : "REST client transport failed before receiving an HTTP response",
                    CLIENT_TYPE,
                    Map.of(),
                    Thread.currentThread().getName(),
                    capture.traceId(),
                    capture.caller(),
                    capture.threadKind());
        } catch (RuntimeException failure) {
            // Capture must not change the response observed by the application.
            logCaptureFailure(failure);
        }
    }

    private static RequestCapture requestCapture(Object capture) {
        return capture instanceof RequestCapture requestCapture
                ? requestCapture
                : new RequestCapture(System.nanoTime(), null, CorrelationContext.NONE, null);
    }

    private static long elapsedNanos(RequestCapture capture) {
        return Math.max(0L, System.nanoTime() - capture.startNanos());
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

    /**
     * Sanitizes a path before storage: a segment whose own name looks like a secret is masked, and each segment's
     * {@code ;name=value} matrix parameters are sanitized like query parameters (the segment name itself is never
     * treated as a parameter). Splitting matches display-time masking through {@link UriMasking}.
     */
    private static String sanitizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }
        String[] segments = rawPath.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = sanitizeSegment(segments[i]);
        }
        return String.join("/", segments);
    }

    private static String sanitizeSegment(String segment) {
        int firstSemicolon = segment.indexOf(';');
        String name = firstSemicolon < 0 ? segment : segment.substring(0, firstSemicolon);
        String sanitizedName =
                SECRET_MASKER.shouldMask("path-segment", decode(name, false)) ? SecretMasker.MASKED_VALUE : name;
        if (firstSemicolon < 0) {
            return sanitizedName;
        }
        return sanitizedName
                + ';'
                + UriMasking.maskParameters(
                        segment.substring(firstSemicolon + 1), QuarkusRestClientTraceFilter::sanitizeParameter);
    }

    /**
     * Sanitizes a query before storage. Parameters are split on {@code &} and the legacy {@code ;} through
     * {@link UriMasking}, so a semicolon-separated secret cannot reach the recorder raw.
     */
    private static String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return rawQuery;
        }
        return UriMasking.maskParameters(rawQuery, QuarkusRestClientTraceFilter::sanitizeParameter);
    }

    /**
     * Sanitizes one {@code name=value} parameter. Unlike display-time masking this also inspects the value, since
     * Quarkus never retains a secret it could later reveal under {@code bootui.expose-values=FULL}.
     */
    private static String sanitizeParameter(String parameter) {
        int equals = parameter.indexOf('=');
        if (equals < 0) {
            String decoded = decode(parameter, true);
            return SECRET_MASKER.shouldMask(decoded, decoded) ? SecretMasker.MASKED_VALUE : parameter;
        }
        String rawName = parameter.substring(0, equals);
        String rawValue = parameter.substring(equals + 1);
        if (SECRET_MASKER.shouldMask(decode(rawName, true), decode(rawValue, true))
                || CredentialRedaction.carriesCredentials(rawValue)) {
            return rawName + '=' + SecretMasker.MASKED_VALUE;
        }
        return parameter;
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

    private record RequestCapture(long startNanos, String traceId, CorrelationContext caller, ThreadKind threadKind) {}
}
