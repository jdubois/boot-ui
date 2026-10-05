package io.github.jdubois.bootui.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable JSON value: the tree BootUI responses are read as.
 *
 * <p>Deliberately hand-rolled rather than Jackson-backed. A client is embedded in other people's builds,
 * where a second Jackson generation on the classpath is a conflict waiting to happen, and the CLI has to stay
 * reflection-free so the deferred native-image build is a build-file change rather than a rewrite.
 *
 * <p>Accessors never throw on a type mismatch; they return the supplied default. Server payloads are opaque
 * and evolve independently of the client, so a field that is absent, null, or of an unexpected shape is a
 * normal condition to render around, not an error to fail on.
 */
public abstract class JsonValue {

    /** The singleton JSON {@code null}. */
    public static final JsonValue NULL = new JsonNull();

    /** The singleton returned for any lookup that does not resolve, so callers can chain without null checks. */
    public static final JsonValue MISSING = new JsonMissing();

    private JsonValue() {}

    /** Parses one JSON document. */
    public static JsonValue parse(String json) {
        return JsonReader.read(json);
    }

    /** A JSON string. */
    public static JsonValue of(String value) {
        return value == null ? NULL : new JsonString(value);
    }

    /** A JSON number. */
    public static JsonValue of(long value) {
        return new JsonNumber(Long.toString(value), value, value);
    }

    /** A JSON boolean. */
    public static JsonValue of(boolean value) {
        return new JsonBoolean(value);
    }

    /** A JSON array. */
    public static JsonValue array(List<JsonValue> values) {
        return new JsonArray(List.copyOf(values));
    }

    /** A JSON object, preserving the given iteration order. */
    public static JsonValue object(Map<String, JsonValue> values) {
        return new JsonObject(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /** Whether this value came from the document at all. */
    public boolean isMissing() {
        return false;
    }

    /** Whether this is JSON {@code null}. */
    public boolean isNull() {
        return false;
    }

    /** Whether this is a JSON object. */
    public boolean isObject() {
        return false;
    }

    /** Whether this is a JSON array. */
    public boolean isArray() {
        return false;
    }

    /** Whether this is a JSON string. */
    public boolean isString() {
        return false;
    }

    /** Whether this is a JSON number. */
    public boolean isNumber() {
        return false;
    }

    /** Whether this is a JSON boolean. */
    public boolean isBoolean() {
        return false;
    }

    /** The named member, or {@link #MISSING} when this is not an object or has no such member. */
    public JsonValue get(String name) {
        return MISSING;
    }

    /** The element at the given index, or {@link #MISSING} when out of range or not an array. */
    public JsonValue get(int index) {
        return MISSING;
    }

    /** The member names in document order, or an empty list when this is not an object. */
    public List<String> names() {
        return List.of();
    }

    /** The elements in order, or an empty list when this is not an array. */
    public List<JsonValue> values() {
        return List.of();
    }

    /** The number of members or elements; {@code 0} for scalars. */
    public int size() {
        return 0;
    }

    /** This value as a string, or {@code fallback} when it is not a string. */
    public String asString(String fallback) {
        return fallback;
    }

    /** This value as an int, or {@code fallback} when it is not a number. */
    public int asInt(int fallback) {
        return fallback;
    }

    /** This value as a long, or {@code fallback} when it is not a number. */
    public long asLong(long fallback) {
        return fallback;
    }

    /** This value as a double, or {@code fallback} when it is not a number. */
    public double asDouble(double fallback) {
        return fallback;
    }

    /** This value as a boolean, or {@code fallback} when it is not a boolean. */
    public boolean asBoolean(boolean fallback) {
        return fallback;
    }

    /**
     * The scalar rendered for display: a string without quotes, a number in its original text, a boolean as
     * {@code true}/{@code false}, JSON null as an empty string. Objects and arrays render as compact JSON.
     */
    public String asDisplayText() {
        return toJson();
    }

    /** This value re-rendered as compact JSON. */
    public abstract String toJson();

    @Override
    public String toString() {
        return toJson();
    }

    private static final class JsonNull extends JsonValue {

        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public String asDisplayText() {
            return "";
        }

        @Override
        public String toJson() {
            return "null";
        }
    }

    private static final class JsonMissing extends JsonValue {

        @Override
        public boolean isMissing() {
            return true;
        }

        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public String asDisplayText() {
            return "";
        }

        @Override
        public String toJson() {
            return "null";
        }
    }

    private static final class JsonString extends JsonValue {

        private final String value;

        private JsonString(String value) {
            this.value = value;
        }

        @Override
        public boolean isString() {
            return true;
        }

        @Override
        public String asString(String fallback) {
            return value;
        }

        @Override
        public String asDisplayText() {
            return value;
        }

        @Override
        public String toJson() {
            return JsonWriter.quote(value);
        }
    }

    private static final class JsonNumber extends JsonValue {

        private final String text;
        private final double doubleValue;
        private final long longValue;

        private JsonNumber(String text, double doubleValue, long longValue) {
            this.text = text;
            this.doubleValue = doubleValue;
            this.longValue = longValue;
        }

        @Override
        public boolean isNumber() {
            return true;
        }

        @Override
        public int asInt(int fallback) {
            return (int) longValue;
        }

        @Override
        public long asLong(long fallback) {
            return longValue;
        }

        @Override
        public double asDouble(double fallback) {
            return doubleValue;
        }

        @Override
        public String asDisplayText() {
            return text;
        }

        @Override
        public String toJson() {
            return text;
        }
    }

    private static final class JsonBoolean extends JsonValue {

        private final boolean value;

        private JsonBoolean(boolean value) {
            this.value = value;
        }

        @Override
        public boolean isBoolean() {
            return true;
        }

        @Override
        public boolean asBoolean(boolean fallback) {
            return value;
        }

        @Override
        public String asDisplayText() {
            return Boolean.toString(value);
        }

        @Override
        public String toJson() {
            return Boolean.toString(value);
        }
    }

    private static final class JsonArray extends JsonValue {

        private final List<JsonValue> elements;

        private JsonArray(List<JsonValue> elements) {
            this.elements = elements;
        }

        @Override
        public boolean isArray() {
            return true;
        }

        @Override
        public JsonValue get(int index) {
            return index < 0 || index >= elements.size() ? MISSING : elements.get(index);
        }

        @Override
        public List<JsonValue> values() {
            return elements;
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public String toJson() {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(elements.get(i).toJson());
            }
            return json.append(']').toString();
        }
    }

    private static final class JsonObject extends JsonValue {

        private final Map<String, JsonValue> members;

        private JsonObject(Map<String, JsonValue> members) {
            this.members = members;
        }

        @Override
        public boolean isObject() {
            return true;
        }

        @Override
        public JsonValue get(String name) {
            JsonValue value = members.get(name);
            return value == null ? MISSING : value;
        }

        @Override
        public List<String> names() {
            return List.copyOf(members.keySet());
        }

        @Override
        public List<JsonValue> values() {
            return List.copyOf(members.values());
        }

        @Override
        public int size() {
            return members.size();
        }

        @Override
        public String toJson() {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonValue> member : members.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append(JsonWriter.quote(member.getKey()))
                        .append(':')
                        .append(member.getValue().toJson());
            }
            return json.append('}').toString();
        }
    }

    static JsonValue number(String text) {
        double doubleValue;
        try {
            doubleValue = Double.parseDouble(text);
        } catch (NumberFormatException notADouble) {
            doubleValue = 0;
        }
        long longValue;
        try {
            longValue = Long.parseLong(text);
        } catch (NumberFormatException notALong) {
            longValue = (long) doubleValue;
        }
        return new JsonNumber(text, doubleValue, longValue);
    }

    static JsonValue bool(boolean value) {
        return new JsonBoolean(value);
    }

    static JsonValue string(String value) {
        return new JsonString(value);
    }

    static JsonValue arrayOf(List<JsonValue> elements) {
        return new JsonArray(List.copyOf(elements));
    }

    static JsonValue objectOf(Map<String, JsonValue> members) {
        return new JsonObject(Collections.unmodifiableMap(members));
    }
}
