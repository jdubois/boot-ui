package io.github.jdubois.bootui.autoconfigure.otlp;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.telemetry.AttributeValue;
import io.github.jdubois.bootui.engine.telemetry.NormalizedEvent;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.TelemetryLimits;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes OTLP/HTTP protobuf trace payloads into a flat list of
 * {@link NormalizedSpan} instances suitable for the BootUI telemetry store.
 */
public final class OtlpSpanDecoder {

    private final BootUiProperties.Telemetry config;

    public OtlpSpanDecoder(BootUiProperties.Telemetry config) {
        this.config = config;
    }

    private static String spanKindToString(Span.SpanKind kind) {
        if (kind == null) {
            return "INTERNAL";
        }
        return switch (kind) {
            case SPAN_KIND_SERVER -> "SERVER";
            case SPAN_KIND_CLIENT -> "CLIENT";
            case SPAN_KIND_PRODUCER -> "PRODUCER";
            case SPAN_KIND_CONSUMER -> "CONSUMER";
            case SPAN_KIND_INTERNAL -> "INTERNAL";
            default -> "UNSPECIFIED";
        };
    }

    private static String hex(ByteString bytes) {
        if (bytes == null || bytes.isEmpty()) {
            return "";
        }
        return bytesToHex(bytes);
    }

    private static String bytesToHex(ByteString bytes) {
        StringBuilder sb = new StringBuilder(bytes.size() * 2);
        for (int i = 0; i < bytes.size(); i++) {
            int b = bytes.byteAt(i) & 0xFF;
            sb.append(Character.forDigit(b >>> 4, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    public List<NormalizedSpan> decode(byte[] body) throws InvalidProtocolBufferException {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(body);
        List<NormalizedSpan> out = new ArrayList<>();
        for (ResourceSpans rs : request.getResourceSpansList()) {
            Resource resource = rs.getResource();
            String serviceName = extractServiceName(resource);
            for (ScopeSpans ss : rs.getScopeSpansList()) {
                InstrumentationScope scope = ss.getScope();
                String scopeName = scope != null ? scope.getName() : "";
                for (Span span : ss.getSpansList()) {
                    out.add(toNormalized(span, serviceName, scopeName));
                }
            }
        }
        return out;
    }

    private NormalizedSpan toNormalized(Span span, String serviceName, String scopeName) {
        Map<String, AttributeValue> attrs = toAttributeMap(span.getAttributesList());
        List<NormalizedEvent> events = new ArrayList<>(span.getEventsCount());
        long startNs = span.getStartTimeUnixNano();
        for (Span.Event event : span.getEventsList()) {
            events.add(new NormalizedEvent(
                    event.getName(),
                    Math.max(0L, event.getTimeUnixNano() - startNs),
                    toAttributeMap(event.getAttributesList())));
        }
        Status status = span.getStatus();
        String statusCode =
                switch (status.getCode()) {
                    case STATUS_CODE_OK -> "OK";
                    case STATUS_CODE_ERROR -> "ERROR";
                    default -> "UNSET";
                };
        return new NormalizedSpan(
                hex(span.getTraceId()),
                hex(span.getSpanId()),
                emptyToNull(hex(span.getParentSpanId())),
                truncate(span.getName()),
                spanKindToString(span.getKind()),
                truncate(serviceName),
                truncate(scopeName),
                startNs,
                span.getEndTimeUnixNano(),
                statusCode,
                truncate(status.getMessage()),
                attrs,
                events);
    }

    private Map<String, AttributeValue> toAttributeMap(List<KeyValue> keyValues) {
        Map<String, AttributeValue> map = new LinkedHashMap<>(keyValues.size());
        for (KeyValue kv : keyValues) {
            AttributeValue value = toAttributeValue(kv.getValue());
            if (value != null) {
                map.put(kv.getKey(), value);
            }
        }
        return map;
    }

    private AttributeValue toAttributeValue(AnyValue value) {
        if (value == null) {
            return null;
        }
        return switch (value.getValueCase()) {
            case STRING_VALUE -> AttributeValue.ofString(truncate(value.getStringValue()));
            case BOOL_VALUE -> AttributeValue.ofBoolean(value.getBoolValue());
            case INT_VALUE -> AttributeValue.ofNumber(value.getIntValue());
            case DOUBLE_VALUE -> AttributeValue.ofNumber(value.getDoubleValue());
            case BYTES_VALUE -> AttributeValue.ofString(truncate("0x" + bytesToHex(value.getBytesValue())));
            case ARRAY_VALUE -> {
                List<Object> arr = new ArrayList<>(value.getArrayValue().getValuesCount());
                for (AnyValue v : value.getArrayValue().getValuesList()) {
                    AttributeValue av = toAttributeValue(v);
                    arr.add(av != null ? av.value() : null);
                }
                yield AttributeValue.ofList(arr);
            }
            case KVLIST_VALUE -> {
                Map<String, Object> nested = new LinkedHashMap<>();
                for (KeyValue inner : value.getKvlistValue().getValuesList()) {
                    AttributeValue av = toAttributeValue(inner.getValue());
                    nested.put(inner.getKey(), av != null ? av.value() : null);
                }
                yield new AttributeValue("map", nested);
            }
            default -> null;
        };
    }

    private String truncate(String value) {
        return TelemetryLimits.truncate(value, config.getMaxAttributeValueBytes());
    }

    private String extractServiceName(Resource resource) {
        if (resource == null) {
            return "unknown_service";
        }
        for (KeyValue kv : resource.getAttributesList()) {
            if ("service.name".equals(kv.getKey()) && kv.getValue().hasStringValue()) {
                String name = kv.getValue().getStringValue();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        return "unknown_service";
    }
}
