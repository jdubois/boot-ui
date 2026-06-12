package io.github.jdubois.bootui.autoconfigure.otlp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BootUiSpanExporter implements SpanExporter {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    private final TelemetryStore store;

    private final BootUiProperties properties;

    public BootUiSpanExporter(TelemetryStore store, BootUiProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        BootUiProperties.Telemetry telemetry = properties.getTelemetry();
        if (!telemetry.isEnabled() || spans == null || spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        String apiPath = properties.getApiPath();
        boolean excludeSelf = telemetry.isExcludeSelfSpans();
        for (SpanData span : spans) {
            NormalizedSpan normalized = toNormalized(span);
            boolean selfSpan = excludeSelf && TelemetrySpanFilter.isSelfSpan(normalized, apiPath);
            store.add(normalized, selfSpan);
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    private NormalizedSpan toNormalized(SpanData span) {
        long startNs = span.getStartEpochNanos();
        List<NormalizedEvent> events = new ArrayList<>(span.getEvents().size());
        for (EventData event : span.getEvents()) {
            events.add(new NormalizedEvent(
                    truncate(event.getName()),
                    Math.max(0L, event.getEpochNanos() - startNs),
                    toAttributeMap(event.getAttributes())));
        }
        SpanContext parent = span.getParentSpanContext();
        StatusData status = span.getStatus();
        return new NormalizedSpan(
                span.getTraceId(),
                span.getSpanId(),
                parent.isValid() ? parent.getSpanId() : null,
                truncate(span.getName()),
                span.getKind().name(),
                serviceName(span),
                truncate(span.getInstrumentationScopeInfo().getName()),
                startNs,
                span.getEndEpochNanos(),
                status.getStatusCode().name(),
                truncate(emptyToNull(status.getDescription())),
                toAttributeMap(span.getAttributes()),
                events);
    }

    private Map<String, AttributeValue> toAttributeMap(Attributes attributes) {
        Map<String, AttributeValue> map = new LinkedHashMap<>(attributes.size());
        attributes.forEach((key, value) -> {
            AttributeValue attributeValue = toAttributeValue(key, value);
            if (attributeValue != null) {
                map.put(key.getKey(), attributeValue);
            }
        });
        return map;
    }

    private AttributeValue toAttributeValue(AttributeKey<?> key, Object value) {
        if (value == null) {
            return null;
        }
        return switch (key.getType()) {
            case STRING -> AttributeValue.ofString(truncate((String) value));
            case BOOLEAN -> AttributeValue.ofBoolean((Boolean) value);
            case LONG, DOUBLE -> AttributeValue.ofNumber((Number) value);
            case STRING_ARRAY, BOOLEAN_ARRAY, LONG_ARRAY, DOUBLE_ARRAY -> AttributeValue.ofList(toAttributeList(value));
            case VALUE -> AttributeValue.ofString(truncate(stringifyValue(value)));
        };
    }

    private String stringifyValue(Object value) {
        if (value instanceof Value<?> typed) {
            return typed.asString();
        }
        return String.valueOf(value);
    }

    private List<Object> toAttributeList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Object> normalized = new ArrayList<>(values.size());
        for (Object item : values) {
            normalized.add(item instanceof String s ? truncate(s) : item);
        }
        return normalized;
    }

    private String serviceName(SpanData span) {
        String serviceName = span.getResource().getAttribute(SERVICE_NAME);
        if (serviceName == null || serviceName.isBlank()) {
            return "unknown_service";
        }
        return truncate(serviceName);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        int max = Math.max(
                64,
                Math.min(
                        OtlpSpanDecoder.HARD_MAX_ATTRIBUTE_VALUE_CHARS,
                        properties.getTelemetry().getMaxAttributeValueBytes()));
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…[truncated " + (value.length() - max) + " chars]";
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
