package io.github.jdubois.bootui.engine.correlation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the trace id of a W3C {@code traceparent} header, such as one a producer wrote on a message, so a consumer's
 * execution can link to the trace that sent it ({@code docs/PLAN-v2.md} §5.2). Never throws.
 */
public final class TraceParents {

    /** The header and message property name. */
    public static final String HEADER = "traceparent";

    private static final Pattern TRACEPARENT =
            Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}(-.*)?", Pattern.CASE_INSENSITIVE);

    private static final String INVALID_TRACE_ID = "0".repeat(32);

    private TraceParents() {}

    /**
     * The trace id {@code header} names, in lowercase, or {@code null} when it is absent or not a valid
     * {@code traceparent}: version {@code ff} and an all-zero trace id are invalid.
     */
    public static String traceIdOf(String header) {
        if (header == null) {
            return null;
        }
        var matcher = TRACEPARENT.matcher(header.trim());
        if (!matcher.matches() || "ff".equalsIgnoreCase(matcher.group(1))) {
            return null;
        }
        String traceId = matcher.group(2).toLowerCase(Locale.ROOT);
        return INVALID_TRACE_ID.equals(traceId) ? null : traceId;
    }

    /** {@link #traceIdOf(String)} of a header value given as bytes or any object, as message headers carry it. */
    public static String traceIdOf(Object header) {
        if (header instanceof byte[] bytes) {
            return traceIdOf(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        return header == null ? null : traceIdOf(header.toString());
    }
}
