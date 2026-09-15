package io.github.jdubois.bootui.engine.mysql;

import java.util.HashMap;
import java.util.Map;

/** PFS performs exact object, schema wildcard, then global wildcard lookups, not SQL LIKE matching. */
final class MySqlObjectInstrumentation {
    static final int MAX_RULES = 1024;
    static final State UNKNOWN = new State("UNKNOWN", "UNKNOWN");
    private static final State DISABLED = new State("DISABLED", "DISABLED");

    private final String schema;
    private final boolean complete;
    private final Map<Key, State> rules = new HashMap<>();

    MySqlObjectInstrumentation(String schema, MySqlQuery.Rows rows) {
        this.schema = schema;
        boolean valid = schema != null && rows != null && !rows.truncated() && rows.reason() == null;
        if (rows != null) {
            for (Map<String, String> row : rows.values()) {
                String ruleSchema = row.get("schema_name");
                String object = row.get("object_name");
                if (ruleSchema == null || object == null) {
                    valid = false;
                    continue;
                }
                Key key = new Key(ruleSchema, object);
                State state = new State(flag(row.get("enabled")), flag(row.get("timed")));
                State previous = rules.putIfAbsent(key, state);
                if (previous != null && !previous.equals(state)) {
                    rules.put(key, UNKNOWN);
                    valid = false;
                }
            }
        }
        complete = valid;
    }

    State forObject(String object) {
        if (schema == null || object == null) {
            return UNKNOWN;
        }
        State exact = rules.get(new Key(schema, object));
        if (exact != null) {
            return exact;
        }
        if (!complete) {
            return UNKNOWN;
        }
        State schemaRule = rules.get(new Key(schema, "%"));
        return schemaRule != null ? schemaRule : rules.getOrDefault(new Key("%", "%"), DISABLED);
    }

    static String flag(String value) {
        return "YES".equals(value) ? "ENABLED" : "NO".equals(value) ? "DISABLED" : "UNKNOWN";
    }

    static String combine(String first, String second) {
        if ("DISABLED".equals(first) || "DISABLED".equals(second)) {
            return "DISABLED";
        }
        return "ENABLED".equals(first) && "ENABLED".equals(second) ? "ENABLED" : "UNKNOWN";
    }

    record State(String collection, String timing) {
        State {
            timing = combine(collection, timing);
        }
    }

    private record Key(String schema, String object) {}
}
