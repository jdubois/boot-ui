package io.github.jdubois.bootui.engine.telemetry;

/**
 * Normalized representation of an OTLP attribute value.
 *
 * <p>The {@code type} field uses one of {@code string}, {@code number}, {@code boolean},
 * or {@code list}. The {@code value} is the JSON-friendly Java representation
 * ({@link String}, {@link Number}, {@link Boolean}, or a {@link java.util.List} of
 * those).</p>
 */
public record AttributeValue(String type, Object value) {

    public static AttributeValue ofString(String s) {
        return new AttributeValue("string", s);
    }

    public static AttributeValue ofNumber(Number n) {
        return new AttributeValue("number", n);
    }

    public static AttributeValue ofBoolean(boolean b) {
        return new AttributeValue("boolean", b);
    }

    public static AttributeValue ofList(java.util.List<?> list) {
        return new AttributeValue("list", list);
    }

    public String asString() {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    public Long asLong() {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
