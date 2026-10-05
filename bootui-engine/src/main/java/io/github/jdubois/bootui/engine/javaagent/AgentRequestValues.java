package io.github.jdubois.bootui.engine.javaagent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The adapters' and recorders' hooks into the BootUI agent's request value holder ({@code docs/PLAN-v2.md} §5.16,
 * M5-6b): where an adapter pushes the current request's query, path, and form parameter values ({@link #begin}) and
 * removes them where the response really completes ({@link #end}), and where an engine-side sink (an SQL statement, a
 * REST client URL) checks its text against them on the thread that issued it ({@link #match}).
 *
 * <p>Opt-in (D37): nothing is parsed or pushed unless {@code bootui.agent.security-sinks.request-values} is on
 * ({@link #configure}) and the bridge reports the {@code security-sinks} sensor installed and enabled for an armed
 * claim ({@link #active()}). The values go to the bridge and nowhere else: this class keeps none, logs none, and
 * returns none; {@link #match} answers with parameter indices, names, and span offsets only, and its callers redact
 * the matched spans before building anything. Each call is one method handle bound once to the bridge on the bootstrap
 * class path; without the agent, or with one predating the holder, every call does nothing. Never throws.
 */
public final class AgentRequestValues {

    /** The bridge's holder. */
    static final String REQUEST_VALUES_CLASS = "io.github.jdubois.bootui.agent.bridge.RequestValues";

    /** Sink kinds ({@code RequestValues.SINK_*}). */
    public static final int SINK_SQL = 1;

    public static final int SINK_COMMAND = 2;
    public static final int SINK_FILE = 3;
    public static final int SINK_URL = 4;

    /** {@link #match}'s {@code spans} layout ({@code RequestValues.S_*}). */
    public static final int S_COUNT = 0;

    public static final int S_FLAGS = 1;
    public static final int S_FIRST = 2;
    public static final int MAX_SPANS = 8;
    public static final int SPANS_LENGTH = S_FIRST + 3 * MAX_SPANS;
    public static final int MAX_VALUES = 32;

    /** {@link #match}'s flags ({@code RequestValues.F_*}). */
    public static final int F_PARTIAL = 1;

    public static final int F_OVERFLOW = 2;
    public static final int F_STOPPED = 4;
    public static final int F_REPEATED = 8;
    public static final int F_BUSY = 16;

    /** Name-value pairs an adapter passes per request at most: the bridge keeps at most 32 and counts the rest. */
    public static final int MAX_PAIRS = 64;

    /** The raw query string characters an adapter parses at most. */
    public static final int MAX_QUERY = 8 * 1024;

    private static volatile boolean enabled;
    private static volatile Handles handles = Handles.locate();

    private AgentRequestValues() {}

    /** Sets {@code bootui.agent.security-sinks.request-values}. */
    public static void configure(boolean on) {
        enabled = on;
    }

    /** Whether {@code bootui.agent.security-sinks.request-values} is on: a static read. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Whether an adapter should parse and push the current request's values: the setting is on, and the bridge's holder
     * is active (the {@code security-sinks} sensor installed and enabled for an armed claim). Two volatile reads and one
     * method handle call. Never throws.
     */
    public static boolean active() {
        if (!enabled) {
            return false;
        }
        MethodHandle active = handles.active;
        if (active == null) {
            return false;
        }
        try {
            return (boolean) active.invokeExact();
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Pushes {@code values}' pairs for the request {@code requestId}; see {@link #begin(String, Values, Map, String[])}. */
    public static boolean begin(String requestId, Values values) {
        return begin(requestId, values, null, null);
    }

    /**
     * Pushes {@code values}' pairs for the request {@code requestId}, and {@code late}, a map whose values under
     * {@code lateKeys} that are maps of names to values the bridge reads at the request's first check (WebFlux's path
     * variables, set after this push). Does nothing unless {@link #active()}. Returns whether the holder now holds an
     * entry for the request, which {@link #end} must then remove. Never throws.
     */
    public static boolean begin(String requestId, Values values, Map<?, ?> late, String[] lateKeys) {
        MethodHandle begin = handles.begin;
        if (begin == null || requestId == null || values == null || !active()) {
            return false;
        }
        try {
            int held = (int) begin.invokeExact(requestId, values.names(), values.values(), late, lateKeys);
            return held >= 0;
        } catch (Throwable ex) {
            // The agent never fails a request.
            return false;
        }
    }

    /** The request {@code requestId} completed: the bridge forgets its values. Cheap when it holds none. Never throws. */
    public static void end(String requestId) {
        MethodHandle end = handles.end;
        if (end == null || requestId == null) {
            return;
        }
        try {
            end.invokeExact(requestId);
        } catch (Throwable ex) {
            // The agent never fails a request.
        }
    }

    /**
     * Checks {@code text}, a sink of kind {@code kind}, against the calling thread's request's values: the matched
     * indices' bitmask, the spans and flags in {@code spans} ({@link #SPANS_LENGTH} long), and the matched indices' names
     * in {@code names} ({@link #MAX_VALUES} long). 0 without the agent, when the setting is off, or when nothing matched.
     * Never a value. Never throws.
     */
    public static int match(String text, int kind, int[] spans, String[] names) {
        if (!enabled) {
            return 0;
        }
        MethodHandle match = handles.match;
        if (match == null || text == null) {
            return 0;
        }
        try {
            return (int) match.invokeExact(text, kind, spans, names);
        } catch (Throwable ex) {
            return 0;
        }
    }

    /** Record flags of a match ({@code RequestValues.POSITION_*}, {@code FLAG_NUMERIC}). */
    public static final int POSITION_IN_LITERAL = 1;

    public static final int POSITION_OUTSIDE_LITERAL = 2;
    public static final int FLAG_NUMERIC = 4;

    /**
     * Publishes an engine-side sink's match of the calling thread's request's value named {@code name}: the sink's
     * {@code kind}, its {@code target} already redacted and normalized ({@code null} to keep none), {@code flags},
     * {@code rawHash}, a keyed hash of the raw text, and the code-paths {@code stamp}. Never a value. Never throws.
     */
    public static void publish(int kind, String name, int flags, String target, long rawHash, long stamp) {
        MethodHandle publish = handles.publish;
        if (publish == null || name == null) {
            return;
        }
        try {
            publish.invokeExact(kind, name, flags, target, rawHash, stamp);
        } catch (Throwable ex) {
            // The agent never fails a request.
        }
    }

    /** The holder's counters, never a value or a name; empty without the agent. Never throws. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> status() {
        MethodHandle status = handles.status;
        if (status == null) {
            return Map.of();
        }
        try {
            Object value = status.invoke();
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Throwable ex) {
            return Map.of();
        }
    }

    /** Whether the hooks reach a bridge carrying the holder. */
    public static boolean bound() {
        return handles.begin != null;
    }

    /** Tests only: binds the hooks to {@code requestValues}, a {@code RequestValues} class, or unbinds them. */
    static void bind(Class<?> requestValues) {
        handles = requestValues == null ? Handles.NONE : Handles.bind(requestValues);
    }

    /** Tests only: binds the hooks to the bootstrap class path's bridge again. */
    static void rebind() {
        handles = Handles.locate();
    }

    /**
     * One request's parameter names and values, as an adapter collects them, at most {@value #MAX_PAIRS} pairs. Held only
     * on the adapter's stack until {@link #begin} hands them to the bridge; no {@code toString}, by design.
     */
    public static final class Values {

        private final List<String> names = new ArrayList<>();
        private final List<String> values = new ArrayList<>();

        /** Adds a value under {@code name}, which may be {@code null}; ignored past {@value #MAX_PAIRS} pairs. */
        public Values add(String name, String value) {
            if (value != null && values.size() < MAX_PAIRS) {
                names.add(name);
                values.add(value);
            }
            return this;
        }

        /** Adds every value of a multi-valued map's entries. */
        public Values addAll(Map<String, ? extends List<String>> parameters) {
            if (parameters != null) {
                for (Map.Entry<String, ? extends List<String>> entry : parameters.entrySet()) {
                    List<String> list = entry.getValue();
                    if (list != null) {
                        for (String value : list) {
                            add(entry.getKey(), value);
                        }
                    }
                }
            }
            return this;
        }

        /** Adds every value of a single-valued map's entries, such as path variables. */
        public Values addSingle(Map<?, ?> parameters) {
            if (parameters != null) {
                for (Map.Entry<?, ?> entry : parameters.entrySet()) {
                    if (entry.getKey() instanceof String name && entry.getValue() instanceof String value) {
                        add(name, value);
                    }
                }
            }
            return this;
        }

        /**
         * Adds the values of a raw query string, decoded as {@code application/x-www-form-urlencoded} in UTF-8, BootUI's
         * own parse, which never touches the request's body: at most {@value #MAX_QUERY} characters are read, and a pair
         * that does not decode is skipped.
         */
        public Values addQuery(String rawQuery) {
            if (rawQuery == null || rawQuery.isEmpty()) {
                return this;
            }
            String query = rawQuery.length() > MAX_QUERY ? rawQuery.substring(0, MAX_QUERY) : rawQuery;
            int start = 0;
            while (start <= query.length() && values.size() < MAX_PAIRS) {
                int amp = query.indexOf('&', start);
                int stop = amp < 0 ? query.length() : amp;
                if (stop > start) {
                    int equals = query.indexOf('=', start);
                    if (equals >= start && equals < stop) {
                        String name = decode(query.substring(start, equals));
                        String value = decode(query.substring(equals + 1, stop));
                        if (value != null) {
                            add(name, value);
                        }
                    }
                }
                if (amp < 0) {
                    break;
                }
                start = amp + 1;
            }
            return this;
        }

        /** Whether no value was added. */
        public boolean isEmpty() {
            return values.isEmpty();
        }

        /** How many values were added. */
        public int size() {
            return values.size();
        }

        String[] names() {
            return names.toArray(new String[0]);
        }

        String[] values() {
            return values.toArray(new String[0]);
        }

        private static String decode(String text) {
            try {
                return URLDecoder.decode(text, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    private record Handles(
            MethodHandle active,
            MethodHandle begin,
            MethodHandle end,
            MethodHandle match,
            MethodHandle status,
            MethodHandle publish) {

        static final Handles NONE = new Handles(null, null, null, null, null, null);

        static Handles locate() {
            try {
                AgentBridgeAccess access = AgentBridgeAccess.locate();
                if (!access.present() || !access.compatible()) {
                    return NONE;
                }
                return bind(Class.forName(REQUEST_VALUES_CLASS, false, null));
            } catch (Throwable ex) {
                return NONE;
            }
        }

        static Handles bind(Class<?> requestValues) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new Handles(
                        lookup.findStatic(requestValues, "active", MethodType.methodType(boolean.class)),
                        lookup.findStatic(
                                requestValues,
                                "begin",
                                MethodType.methodType(
                                        int.class,
                                        String.class,
                                        String[].class,
                                        String[].class,
                                        Map.class,
                                        String[].class)),
                        lookup.findStatic(requestValues, "end", MethodType.methodType(void.class, String.class)),
                        lookup.findStatic(
                                requestValues,
                                "match",
                                MethodType.methodType(int.class, String.class, int.class, int[].class, String[].class)),
                        lookup.findStatic(requestValues, "status", MethodType.methodType(Map.class)),
                        lookup.findStatic(
                                requestValues,
                                "sinkMatched",
                                MethodType.methodType(
                                        void.class,
                                        int.class,
                                        String.class,
                                        int.class,
                                        String.class,
                                        long.class,
                                        long.class)));
            } catch (Throwable ex) {
                return NONE;
            }
        }
    }
}
