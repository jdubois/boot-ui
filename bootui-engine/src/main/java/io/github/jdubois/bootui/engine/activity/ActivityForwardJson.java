package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.List;

/**
 * Hand-rolled, minimal JSON encoding of a {@code List<StoredActivityEntry>} batch, used only by {@link
 * HttpActivityStore} to build its POST body.
 *
 * <p>{@code bootui-engine} is deliberately JSON-library-free (see {@code EngineBoundaryArchitectureTests}):
 * Spring Boot 4 ships Jackson 3 ({@code tools.jackson.*}) while Quarkus ships Jackson 2 ({@code
 * com.fasterxml.jackson.*}) — incompatible artifact <em>and</em> package — so no JSON library can be
 * pulled into the engine without picking a side. The shape being encoded here is small and fixed (one
 * {@code StoredActivityEntry} record wrapping one flat {@code ActivityEntryDto} record), so a tiny,
 * dependency-free encoder is far cheaper than the alternative of, say, vendoring a whole JSON library
 * shaded twice for two incompatible package names.
 *
 * <p>The receiving side needs no matching hand-rolled decoder: it runs in an adapter (Spring or Quarkus),
 * which already has its own Jackson on the classpath and deserializes the identically-shaped {@code
 * ActivityForwardBatchRequest}/{@code ActivityForwardEntryDto} core records automatically via its
 * framework's normal request-body binding. Field names here are written to match those records' component
 * names exactly ({@code instanceId}, {@code seq}, {@code entry}, and {@code ActivityEntryDto}'s own 16
 * component names) since Jackson's record support binds JSON object fields to record components by name.
 */
final class ActivityForwardJson {

    private ActivityForwardJson() {}

    /** Encodes {@code entries} as {@code {"entries":[{...},...]}}, matching {@code ActivityForwardBatchRequest}. */
    static String encodeBatch(List<StoredActivityEntry> entries) {
        StringBuilder json = new StringBuilder();
        json.append("{\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendStoredEntry(json, entries.get(i));
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendStoredEntry(StringBuilder json, StoredActivityEntry stored) {
        json.append('{');
        appendStringField(json, "instanceId", stored.instanceId());
        json.append(',').append("\"seq\":").append(stored.seq());
        json.append(',').append("\"entry\":");
        appendEntryDto(json, stored.entry());
        json.append('}');
    }

    private static void appendEntryDto(StringBuilder json, ActivityEntryDto entry) {
        json.append('{');
        appendStringField(json, "id", entry.id());
        json.append(',');
        appendStringField(json, "type", entry.type());
        json.append(',').append("\"timestamp\":").append(entry.timestamp());
        json.append(',');
        appendStringField(json, "severity", entry.severity());
        json.append(',');
        appendStringField(json, "summary", entry.summary());
        json.append(',');
        appendStringField(json, "detail", entry.detail());
        json.append(',');
        appendNumberField(json, "durationMs", entry.durationMs());
        json.append(',');
        appendStringField(json, "correlationId", entry.correlationId());
        json.append(',');
        appendStringField(json, "method", entry.method());
        json.append(',');
        appendStringField(json, "path", entry.path());
        json.append(',');
        appendNumberField(json, "status", entry.status());
        json.append(',');
        appendStringField(json, "thread", entry.thread());
        json.append(',').append("\"profileable\":").append(entry.profileable());
        json.append(',');
        appendStringField(json, "parentId", entry.parentId());
        json.append(',');
        appendStringField(json, "securedPrincipal", entry.securedPrincipal());
        json.append(',').append("\"sqlNPlusOneSuspected\":").append(entry.sqlNPlusOneSuspected());
        json.append('}');
    }

    private static void appendStringField(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            appendEscapedString(json, value);
        }
    }

    private static void appendNumberField(StringBuilder json, String name, Number value) {
        json.append('"').append(name).append("\":");
        json.append(value == null ? "null" : value.toString());
    }

    /**
     * Escapes exactly what the JSON spec requires: the two structural characters ({@code "} and {@code
     * \}), the standard short escapes, and every other control character as {@code \\uXXXX}. Everything
     * else (including all non-ASCII text) is written through as-is: the request is sent with an explicit
     * UTF-8 body ({@link java.nio.charset.StandardCharsets#UTF_8}) and {@code Content-Type: ...;
     * charset=UTF-8}, so multi-byte characters need no further escaping to round-trip correctly.
     */
    private static void appendEscapedString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                case '\b':
                    json.append("\\b");
                    break;
                case '\f':
                    json.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                    break;
            }
        }
        json.append('"');
    }
}
