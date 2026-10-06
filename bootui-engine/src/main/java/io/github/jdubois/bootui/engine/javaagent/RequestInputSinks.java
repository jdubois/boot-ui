package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceGrouping;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The engine-side sinks of the {@code security-sinks} sensor's request-value matching ({@code docs/PLAN-v2.md} §5.16,
 * §5.17 {@code request-input-in-sink}, M5-6b): SQL text where SQL Trace's JDBC capture records a statement, and an
 * outbound URL where the REST client recorders record a call, both on the thread that issued it. Each checks the text
 * against the calling thread's request's values through the BootUI agent's holder, then builds its target from the
 * matched spans <b>redacted</b> to {@code {name}}, and publishes one record per matched parameter: the sink, the
 * redacted target, where the value sat, whether it is digits only, and per-process keyed hashes of the raw text and of
 * the redacted text, so the engine can tell a text that varies with the value from one that always contains it. Never a
 * value, a raw text, or a bind value.
 *
 * <p>Does nothing unless {@code bootui.agent.security-sinks.request-values} is on, and nothing on work a request handed
 * to another thread ({@code async-} and {@code task-} executions); the holder itself also refuses such work. A text
 * scanned only in part, or with more matches than could be redacted, keeps no target (fails closed). Never throws.
 */
public final class RequestInputSinks {

    /** The longest target kept: a longer one is cut, marked with an ellipsis. */
    static final int MAX_TARGET = 1_000;

    /** Separates a URL's components in the text checked, so a decoded {@code ?}, {@code &}, or {@code =} splits none. */
    static final char SEPARATOR = '\u0000';

    private static final long HASH_KEY = new SecureRandom().nextLong() | 1L;

    private RequestInputSinks() {}

    /** An SQL statement this thread ran or is about to run, as the driver received it, under {@code correlation}. */
    public static void sql(String sql, CorrelationContext correlation) {
        if (!checked(sql, correlation)) {
            return;
        }
        try {
            int[] spans = new int[AgentRequestValues.SPANS_LENGTH];
            String[] names = new String[AgentRequestValues.MAX_VALUES];
            int mask = AgentRequestValues.match(sql, AgentRequestValues.SINK_SQL, spans, names);
            if (mask == 0 || seen(spans)) {
                return;
            }
            int[] positions = new int[AgentRequestValues.MAX_VALUES];
            String masked = SqlSinkText.mask(sql, spans, names, positions);
            publish(AgentRequestValues.SINK_SQL, sql, mask, spans, names, positions, keep(spans) ? masked : null);
        } catch (RuntimeException | LinkageError ex) {
            // Request-value matching is diagnostics only; the statement runs untouched.
        }
    }

    /**
     * An outbound URI this thread is about to call, under {@code correlation}: its host, decoded path, and each decoded
     * query parameter checked as components apart, as the application's values are decoded; its target is the scheme,
     * host, and port, the redacted path with ids templated as the REST client panel groups calls, and the query's keys
     * only. User information, query values, and the fragment never reach it.
     */
    public static void url(URI uri, CorrelationContext correlation) {
        if (uri == null || !checked("http", correlation)) {
            return;
        }
        try {
            Url url = Url.of(uri);
            if (url == null) {
                return;
            }
            int[] spans = new int[AgentRequestValues.SPANS_LENGTH];
            String[] names = new String[AgentRequestValues.MAX_VALUES];
            int mask = AgentRequestValues.match(url.text, AgentRequestValues.SINK_URL, spans, names);
            if (mask == 0 || seen(spans)) {
                return;
            }
            String target = keep(spans) ? url.target(spans, names) : null;
            publish(AgentRequestValues.SINK_URL, url.text, mask, spans, names, new int[0], target);
        } catch (RuntimeException | LinkageError ex) {
            // Request-value matching is diagnostics only; the call runs untouched.
        }
    }

    private static boolean checked(String text, CorrelationContext correlation) {
        if (!AgentRequestValues.enabled() || text == null || text.length() < 4) {
            return false;
        }
        // BootUI's own work, or work a request handed off: never checked (M5-6 design B4).
        return correlation == null || (!correlation.bootUi() && correlation.executionId() == null);
    }

    /** Whether the request matched this same text before: its record was already published. */
    private static boolean seen(int[] spans) {
        return (spans[AgentRequestValues.S_FLAGS] & AgentRequestValues.F_SEEN) != 0;
    }

    /** Whether every occurrence was reported, so a redacted target covers them all. */
    private static boolean keep(int[] spans) {
        return (spans[AgentRequestValues.S_FLAGS] & ~AgentRequestValues.F_SEEN) == 0
                && spans[AgentRequestValues.S_COUNT] > 0;
    }

    private static void publish(
            int kind, String text, int mask, int[] spans, String[] names, int[] positions, String target) {
        long rawHash = keyedHash(text);
        long redactedHash = keep(spans) ? keyedHash(redact(text, spans, names)) : 0L;
        long stamp = AgentCodePaths.stamp();
        for (int i = 0; i < AgentRequestValues.MAX_VALUES; i++) {
            if ((mask & (1 << i)) == 0) {
                continue;
            }
            int position = i < positions.length ? positions[i] : 0;
            if (!reported(spans, i)) {
                // Past the spans reported: where it sat is not known, so the row waits for confirmation.
                position = AgentRequestValues.POSITION_UNKNOWN;
            }
            AgentRequestValues.publish(
                    kind, names[i], position | numeric(text, spans, i), cut(target), rawHash, redactedHash, stamp);
        }
    }

    /** {@code text} with every span replaced by {@code {name}}, overlapping spans merged under the first one's name. */
    static String redact(String text, int[] spans, String[] names) {
        return redact(text, 0, text.length(), spans, names);
    }

    /**
     * {@code text}'s characters {@code from} to {@code to}, every span inside replaced by {@code {name}}; {@code null}
     * when a span crosses either bound, so no part of a value is ever kept.
     */
    static String redact(String text, int from, int to, int[] spans, String[] names) {
        int count = Math.min(spans[AgentRequestValues.S_COUNT], AgentRequestValues.MAX_SPANS);
        int length = to - from;
        boolean[] covered = new boolean[length];
        String[] at = new String[length];
        for (int s = 0; s < count; s++) {
            int slot = AgentRequestValues.S_FIRST + 3 * s;
            int start = spans[slot + 1];
            int end = spans[slot + 2];
            if (end <= from || start >= to) {
                continue;
            }
            if (start < from || end > to) {
                return null;
            }
            for (int c = start; c < end; c++) {
                covered[c - from] = true;
            }
            if (at[start - from] == null) {
                int index = spans[slot];
                at[start - from] = index >= 0 && index < names.length && names[index] != null ? names[index] : "param";
            }
        }
        StringBuilder out = new StringBuilder(length);
        for (int c = 0; c < length; c++) {
            if (!covered[c]) {
                out.append(text.charAt(from + c));
            } else if (c == 0 || !covered[c - 1]) {
                out.append('{').append(at[c] == null ? "param" : at[c]).append('}');
            }
        }
        return out.toString();
    }

    /** Whether a span of value {@code index} was reported: one past the spans reported is not. */
    static boolean reported(int[] spans, int index) {
        int count = Math.min(spans[AgentRequestValues.S_COUNT], AgentRequestValues.MAX_SPANS);
        for (int s = 0; s < count; s++) {
            if (spans[AgentRequestValues.S_FIRST + 3 * s] == index) {
                return true;
            }
        }
        return false;
    }

    /** {@link AgentRequestValues#FLAG_NUMERIC} when the first span of value {@code index} holds digits only. */
    static int numeric(String text, int[] spans, int index) {
        int count = Math.min(spans[AgentRequestValues.S_COUNT], AgentRequestValues.MAX_SPANS);
        for (int s = 0; s < count; s++) {
            int slot = AgentRequestValues.S_FIRST + 3 * s;
            if (spans[slot] != index) {
                continue;
            }
            int start = spans[slot + 1];
            int end = spans[slot + 2];
            if (start < 0 || end > text.length() || start >= end) {
                return 0;
            }
            for (int c = start; c < end; c++) {
                if (!Character.isDigit(text.charAt(c))) {
                    return 0;
                }
            }
            return AgentRequestValues.FLAG_NUMERIC;
        }
        return 0;
    }

    /** A per-process keyed hash of a text, never negative, never the text. */
    static long keyedHash(String text) {
        if (text == null) {
            return 0L;
        }
        long hash = HASH_KEY;
        int length = Math.min(text.length(), 16 * 1024);
        for (int i = 0; i < length; i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        hash ^= hash >>> 29;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 32;
        return (hash & Long.MAX_VALUE) | 1L;
    }

    private static String cut(String target) {
        if (target == null || target.length() <= MAX_TARGET) {
            return target;
        }
        return target.substring(0, MAX_TARGET) + "…";
    }

    /**
     * A URL as checked: {@code scheme://host[:port]}, the decoded path, then each decoded query parameter, the
     * components joined by {@link #SEPARATOR}, with where each starts and ends.
     */
    static final class Url {
        final String text;
        final String origin;
        final int pathStart;
        final int pathEnd;
        final int[] keyStarts;
        final int[] keyEnds;

        private Url(String text, String origin, int pathStart, int pathEnd, int[] keyStarts, int[] keyEnds) {
            this.text = text;
            this.origin = origin;
            this.pathStart = pathStart;
            this.pathEnd = pathEnd;
            this.keyStarts = keyStarts;
            this.keyEnds = keyEnds;
        }

        static Url of(URI uri) {
            if (uri.isOpaque()) {
                return null;
            }
            StringBuilder origin = new StringBuilder();
            if (uri.getScheme() != null) {
                origin.append(uri.getScheme()).append("://");
            }
            if (uri.getHost() != null) {
                origin.append(uri.getHost());
                if (uri.getPort() >= 0) {
                    origin.append(':').append(uri.getPort());
                }
            }
            StringBuilder text = new StringBuilder(origin).append(SEPARATOR);
            int pathStart = text.length();
            text.append(uri.getPath() == null ? "" : uri.getPath());
            int pathEnd = text.length();
            String raw = uri.getRawQuery();
            String[] pairs = raw == null || raw.isEmpty() ? new String[0] : raw.split("&");
            int[] keyStarts = new int[pairs.length];
            int[] keyEnds = new int[pairs.length];
            for (int i = 0; i < pairs.length; i++) {
                int equals = pairs[i].indexOf('=');
                String key = decode(equals < 0 ? pairs[i] : pairs[i].substring(0, equals));
                String value = equals < 0 ? "" : decode(pairs[i].substring(equals + 1));
                text.append(SEPARATOR);
                keyStarts[i] = text.length();
                text.append(key);
                keyEnds[i] = text.length();
                text.append('=').append(value);
            }
            return new Url(text.toString(), origin.toString(), pathStart, pathEnd, keyStarts, keyEnds);
        }

        /** The target, or {@code null} when a value crosses a component's bound or sits in the host. */
        String target(int[] spans, String[] names) {
            String host = redact(text, 0, origin.length(), spans, names);
            String path = redact(text, pathStart, pathEnd, spans, names);
            if (host == null || path == null || !host.equals(origin)) {
                // A value in the host, or across components: no text is kept.
                return null;
            }
            StringBuilder out = new StringBuilder(origin).append(RestClientTraceGrouping.normalizePath(path));
            for (int i = 0; i < keyStarts.length; i++) {
                String key = redact(text, keyStarts[i], keyEnds[i], spans, names);
                if (key == null) {
                    return null;
                }
                out.append(i == 0 ? '?' : '&').append(key);
            }
            return out.toString();
        }

        private static String decode(String text) {
            try {
                return URLDecoder.decode(text, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                return text;
            }
        }
    }
}
