package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;

/**
 * The code-paths stamps the recorders put on SQL, REST client, cache, and AI events ({@code docs/PLAN-v2.md} §5.14,
 * M5-4c): which kind of call an event is, its stamp, and what a stamp names, as the bridge's {@code CodePaths.stamp()}
 * packs it: the fragment's sequence, the node's index in that fragment, and the node's method id plus one (0 for an
 * Other node). Stateless.
 */
public final class CodePathStamps {

    /** Call kinds, in the order the trees keep them. */
    public static final int SQL = 0;

    public static final int REST = 1;
    public static final int CACHE = 2;
    public static final int AI = 3;

    /** How many call kinds there are. */
    public static final int KINDS = 4;

    /**
     * The stamp of a call issued on a request's thread while its fragment was open but no instrumented method was, as
     * in a filter, while the response was written, or in a transaction commit after the outermost instrumented method
     * returned: the bridge's {@code CodePaths.STAMP_OUTSIDE}. Negative, as no node's stamp ever is.
     */
    public static final long OUTSIDE = -1L;

    /** The kinds' names, as the API spells them. */
    static final String[] NAMES = {"SQL", "REST", "CACHE", "AI"};

    static final int NODE_BITS = 9;
    static final int METHOD_BITS = 19;

    private CodePathStamps() {}

    /** The call kind of {@code payload}: {@link #SQL}, {@link #REST}, {@link #CACHE}, {@link #AI}, or -1. */
    public static int kind(Object payload) {
        if (payload instanceof SqlPayload sql && sql.executed()) {
            return SQL;
        }
        if (payload instanceof RestClientPayload) {
            return REST;
        }
        if (payload instanceof CachePayload) {
            return CACHE;
        }
        if (payload instanceof AiPayload) {
            return AI;
        }
        return -1;
    }

    /** The code-paths stamp of {@code payload}, 0 when it has none or is not a stampable call. */
    public static long of(Object payload) {
        if (payload instanceof SqlPayload sql) {
            return sql.codePathStamp();
        }
        if (payload instanceof RestClientPayload call) {
            return call.codePathStamp();
        }
        if (payload instanceof CachePayload cache) {
            return cache.codePathStamp();
        }
        if (payload instanceof AiPayload ai) {
            return ai.codePathStamp();
        }
        return 0L;
    }

    /** The code-paths stamp of {@code event}'s payload, 0 when none. */
    public static long of(RuntimeEvent event) {
        return event == null ? 0L : of(event.payload());
    }

    /** Whether {@code stamp} names a node: neither 0, unknown, nor {@link #OUTSIDE} or another negative value. */
    public static boolean placed(long stamp) {
        return stamp > 0L;
    }

    /** The fragment sequence {@code stamp} carries. */
    public static long sequence(long stamp) {
        return stamp >>> (NODE_BITS + METHOD_BITS);
    }

    /** The node index in its fragment {@code stamp} carries. */
    public static int node(long stamp) {
        return (int) ((stamp >>> METHOD_BITS) & ((1 << NODE_BITS) - 1));
    }

    /** The method id {@code stamp} carries, or {@link CodePathFragment#OTHER} for an Other node. */
    public static int method(long stamp) {
        return (int) (stamp & ((1L << METHOD_BITS) - 1)) - 1;
    }

    /**
     * {@code SimpleClass.method} for a method key, {@code class#name+descriptor}, as Code Paths labels a method; the key
     * itself when it is not one.
     */
    public static String label(String key) {
        int hash = key == null ? -1 : key.indexOf('#');
        if (hash <= 0) {
            return key;
        }
        String type = key.substring(0, hash);
        int paren = key.indexOf('(', hash);
        String name = key.substring(hash + 1, paren < 0 ? key.length() : paren);
        return type.substring(type.lastIndexOf('.') + 1) + "." + name;
    }

    /**
     * Distinct labels for distinct method keys, in their order: {@code SimpleClass.method} where that is unique among
     * them, else with its parameter types, {@code SimpleClass.method(String, int)}, as for overloads, else with the
     * class's package too, as for same-named classes in different packages; the key itself as a last resort. Equal keys
     * get equal labels.
     */
    public static java.util.List<String> labels(java.util.List<String> keys) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String key : keys) {
            labels.add(label(key));
        }
        disambiguate(keys, labels, key -> label(key) + parameters(key));
        disambiguate(keys, labels, key -> qualified(key) + parameters(key));
        disambiguate(keys, labels, key -> key);
        return labels;
    }

    /** Replaces, with {@code richer}, every label two different keys share. */
    private static void disambiguate(
            java.util.List<String> keys,
            java.util.List<String> labels,
            java.util.function.Function<String, String> richer) {
        java.util.Map<String, java.util.Set<String>> keysByLabel = new java.util.HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            keysByLabel
                    .computeIfAbsent(labels.get(i), ignored -> new java.util.HashSet<>())
                    .add(keys.get(i));
        }
        for (int i = 0; i < keys.size(); i++) {
            if (keysByLabel.get(labels.get(i)).size() > 1) {
                labels.set(i, richer.apply(keys.get(i)));
            }
        }
    }

    /** {@code package.SimpleClass.method} for a method key; the key itself when it is not one. */
    static String qualified(String key) {
        int hash = key == null ? -1 : key.indexOf('#');
        if (hash <= 0) {
            return key;
        }
        int paren = key.indexOf('(', hash);
        return key.substring(0, hash) + "." + key.substring(hash + 1, paren < 0 ? key.length() : paren);
    }

    /**
     * A method key's parameter types as Java names them, without packages: {@code (String, int[])} for
     * {@code (Ljava/lang/String;[I)V}; empty when the key has no descriptor.
     */
    static String parameters(String key) {
        int open = key == null ? -1 : key.indexOf('(');
        int close = open < 0 ? -1 : key.indexOf(')', open);
        if (close < 0) {
            return "";
        }
        java.util.List<String> types = new java.util.ArrayList<>();
        int i = open + 1;
        while (i < close) {
            int dimensions = 0;
            while (i < close && key.charAt(i) == '[') {
                dimensions++;
                i++;
            }
            if (i >= close) {
                break;
            }
            String type;
            char c = key.charAt(i);
            if (c == 'L') {
                int end = key.indexOf(';', i);
                if (end < 0 || end > close) {
                    break;
                }
                String name = key.substring(i + 1, end);
                type = name.substring(name.lastIndexOf('/') + 1).replace('$', '.');
                i = end + 1;
            } else {
                type = switch (c) {
                    case 'Z' -> "boolean";
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'S' -> "short";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'F' -> "float";
                    case 'D' -> "double";
                    default -> String.valueOf(c);
                };
                i++;
            }
            types.add(type + "[]".repeat(dimensions));
        }
        return "(" + String.join(", ", types) + ")";
    }

    /** {@code (sequence, node, method)} as the bridge packs them: for tests and fixtures. */
    public static long pack(long sequence, int node, int method) {
        long methodBits = method < 0 ? 0L : (method + 1L) & ((1L << METHOD_BITS) - 1);
        return (sequence << (NODE_BITS + METHOD_BITS))
                | ((long) (node & ((1 << NODE_BITS) - 1)) << METHOD_BITS)
                | methodBits;
    }
}
