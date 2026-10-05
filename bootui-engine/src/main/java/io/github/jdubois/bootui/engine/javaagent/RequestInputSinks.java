package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.spi.CorrelationContext;
import java.net.URI;
import java.security.SecureRandom;

/**
 * The engine-side sinks of the {@code security-sinks} sensor's request-value matching ({@code docs/PLAN-v2.md} §5.16,
 * §5.17 {@code request-input-in-sink}, M5-6b): SQL text where each stack's SQL capture records a statement, and an
 * outbound URL where the REST client recorders record a call, both on the thread that issued it. Each checks the text
 * against the calling thread's request's values through the BootUI agent's holder, then <b>redacts</b> every matched
 * span to {@code {name}} before building anything, and publishes one record per matched parameter: the sink, the
 * redacted target, where the value sat, whether it is digits only, and a per-process keyed hash of the raw text, so the
 * engine can tell a text that varies with the value from one that always contains it. Never a value, a raw text, or a
 * bind value.
 *
 * <p>Does nothing unless {@code bootui.agent.security-sinks.request-values} is on, and nothing on work a request handed
 * to another thread ({@code async-} and {@code task-} executions); the holder itself also refuses such work. A
 * statement scanned only in part, or with more matches than could be redacted, keeps no target (fails closed). Never
 * throws.
 */
public final class RequestInputSinks {

    /** The longest target kept: a longer redacted text is cut, marked with an ellipsis. */
    static final int MAX_TARGET = 1_000;

    private static final long HASH_KEY = new SecureRandom().nextLong() | 1L;

    private RequestInputSinks() {}

    /** An SQL statement this thread ran or is about to run, as the driver received it, under {@code correlation}. */
    public static void sql(String sql, CorrelationContext correlation) {
        check(sql, AgentRequestValues.SINK_SQL, correlation);
    }

    /** An outbound URL this thread is about to call, as the REST client sends it, under {@code correlation}. */
    public static void url(String url, CorrelationContext correlation) {
        check(url, AgentRequestValues.SINK_URL, correlation);
    }

    /**
     * An outbound URI this thread is about to call, under {@code correlation}: checked in its decoded form, as the
     * application's values are, so a value the client percent-encoded is still seen.
     */
    public static void url(URI uri, CorrelationContext correlation) {
        if (!AgentRequestValues.enabled() || uri == null) {
            return;
        }
        try {
            StringBuilder decoded = new StringBuilder();
            if (uri.getScheme() != null) {
                decoded.append(uri.getScheme()).append("://");
            }
            if (uri.getAuthority() != null) {
                decoded.append(uri.getAuthority());
            }
            if (uri.getPath() != null) {
                decoded.append(uri.getPath());
            }
            if (uri.getQuery() != null) {
                decoded.append('?').append(uri.getQuery());
            }
            check(uri.isOpaque() ? uri.toString() : decoded.toString(), AgentRequestValues.SINK_URL, correlation);
        } catch (RuntimeException ex) {
            // Request-value matching is diagnostics only; the call runs untouched.
        }
    }

    private static void check(String text, int kind, CorrelationContext correlation) {
        if (!AgentRequestValues.enabled() || text == null || text.length() < 4) {
            return;
        }
        try {
            if (correlation != null && (correlation.bootUi() || correlation.executionId() != null)) {
                // BootUI's own work, or work a request handed off: never checked (M5-6 design B4).
                return;
            }
            int[] spans = new int[AgentRequestValues.SPANS_LENGTH];
            String[] names = new String[AgentRequestValues.MAX_VALUES];
            int mask = AgentRequestValues.match(text, kind, spans, names);
            if (mask == 0) {
                return;
            }
            boolean keep = spans[AgentRequestValues.S_FLAGS] == 0 && spans[AgentRequestValues.S_COUNT] > 0;
            int[] positions = new int[AgentRequestValues.MAX_VALUES];
            String target = null;
            if (kind == AgentRequestValues.SINK_SQL) {
                String masked = SqlSinkText.mask(text, spans, names, positions);
                target = keep ? masked : null;
            } else if (keep) {
                target = UrlSinkText.normalize(redact(text, spans, names));
            }
            long rawHash = keyedHash(text);
            long stamp = AgentCodePaths.stamp();
            for (int i = 0; i < AgentRequestValues.MAX_VALUES; i++) {
                if ((mask & (1 << i)) != 0) {
                    int flags = positions[i] | numeric(text, spans, i);
                    AgentRequestValues.publish(kind, names[i], flags, cut(target), rawHash, stamp);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            // Request-value matching is diagnostics only; the statement or call runs untouched.
        }
    }

    /** {@code text} with every span replaced by {@code {name}}, overlapping spans merged under the longer one's name. */
    static String redact(String text, int[] spans, String[] names) {
        int count = Math.min(spans[AgentRequestValues.S_COUNT], AgentRequestValues.MAX_SPANS);
        boolean[] covered = new boolean[text.length() + 1];
        String[] at = new String[text.length() + 1];
        for (int s = 0; s < count; s++) {
            int slot = AgentRequestValues.S_FIRST + 3 * s;
            int start = spans[slot + 1];
            int end = spans[slot + 2];
            for (int c = start; c < end && c < text.length(); c++) {
                covered[c] = true;
            }
            if (start < at.length && (at[start] == null || end - start > 0)) {
                at[start] = at[start] == null ? names[spans[slot]] : at[start];
            }
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int c = 0; c < text.length(); c++) {
            if (!covered[c]) {
                out.append(text.charAt(c));
            } else if (c == 0 || !covered[c - 1]) {
                String name = at[c];
                out.append('{').append(name == null ? "param" : name).append('}');
            }
        }
        return out.toString();
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

    /** A per-process keyed hash of the raw text, never negative, never the text. */
    static long keyedHash(String text) {
        long hash = HASH_KEY;
        int length = Math.min(text.length(), 16 * 1024);
        for (int i = 0; i < length; i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        hash ^= hash >>> 29;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 32;
        return hash & Long.MAX_VALUE;
    }

    private static String cut(String target) {
        if (target == null || target.length() <= MAX_TARGET) {
            return target;
        }
        return target.substring(0, MAX_TARGET) + "…";
    }
}
