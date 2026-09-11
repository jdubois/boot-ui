package io.github.jdubois.bootui.client;

import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** Renders JSON text: quoting for the tree model, and pretty-printing for terminal output. */
public final class JsonWriter {

    private static final String INDENT = "  ";

    private JsonWriter() {}

    /** The value as a JSON string literal, with the escapes RFC 8259 requires. */
    public static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"':
                    quoted.append("\\\"");
                    break;
                case '\\':
                    quoted.append("\\\\");
                    break;
                case '\b':
                    quoted.append("\\b");
                    break;
                case '\f':
                    quoted.append("\\f");
                    break;
                case '\n':
                    quoted.append("\\n");
                    break;
                case '\r':
                    quoted.append("\\r");
                    break;
                case '\t':
                    quoted.append("\\t");
                    break;
                default:
                    if (current < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) current));
                    } else {
                        quoted.append(current);
                    }
            }
        }
        return quoted.append('"').toString();
    }

    /** A flat object as compact JSON, used to build request bodies. */
    public static String object(Map<String, JsonValue> members) {
        return JsonValue.object(members).toJson();
    }

    /** The value as indented JSON, for reading rather than piping. */
    public static String pretty(JsonValue value) {
        StringBuilder json = new StringBuilder();
        write(value, json, 0);
        return json.toString();
    }

    private static void write(JsonValue value, StringBuilder json, int depth) {
        if (value.isObject()) {
            List<String> names = value.names();
            writeContainer(json, depth, '{', '}', names.size(), index -> {
                String name = names.get(index);
                indent(json, depth + 1).append(quote(name)).append(": ");
                write(value.get(name), json, depth + 1);
            });
            return;
        }
        if (value.isArray()) {
            List<JsonValue> elements = value.values();
            writeContainer(json, depth, '[', ']', elements.size(), index -> {
                indent(json, depth + 1);
                write(elements.get(index), json, depth + 1);
            });
            return;
        }
        json.append(value.toJson());
    }

    /** Writes an object or array: an empty pair when there are no entries, otherwise one indented entry per line. */
    private static void writeContainer(
            StringBuilder json, int depth, char open, char close, int size, IntConsumer writeEntry) {
        if (size == 0) {
            json.append(open).append(close);
            return;
        }
        json.append(open).append('\n');
        for (int index = 0; index < size; index++) {
            writeEntry.accept(index);
            json.append(index + 1 < size ? ",\n" : "\n");
        }
        indent(json, depth).append(close);
    }

    private static StringBuilder indent(StringBuilder json, int depth) {
        return json.append(INDENT.repeat(depth));
    }
}
