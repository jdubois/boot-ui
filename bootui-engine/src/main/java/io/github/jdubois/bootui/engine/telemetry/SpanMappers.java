package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.SpanAttributeDto;
import io.github.jdubois.bootui.core.dto.SpanEventDto;
import io.github.jdubois.bootui.engine.support.MessageExposure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposure-aware mappers from normalized spans/events to the immutable DTOs the AI Framework panel serializes.
 * The Traces panel and the per-request profile map through {@link SpanValueExposure} instead, which applies
 * attribute-specific value-exposure rules.
 */
public final class SpanMappers {

    private static final SecretMasker MASKER = new SecretMasker();

    private SpanMappers() {}

    public static List<SpanAttributeDto> toAttributeList(Map<String, AttributeValue> attrs, MessageExposure exposure) {
        if (exposure.omitsText() || attrs == null || attrs.isEmpty()) {
            return List.of();
        }
        List<SpanAttributeDto> out = new ArrayList<>(attrs.size());
        for (Map.Entry<String, AttributeValue> entry : attrs.entrySet()) {
            AttributeValue v = entry.getValue();
            Object value = exposedValue(entry.getKey(), v.value(), exposure);
            out.add(new SpanAttributeDto(
                    exposure.apply(entry.getKey()),
                    value instanceof String && !(v.value() instanceof String) ? "string" : v.type(),
                    value));
        }
        return out;
    }

    private static Object exposedValue(String key, Object value, MessageExposure exposure) {
        if (!exposure.masksText() || value == null) {
            return value;
        }
        if (MASKER.shouldMask(key, value)) {
            return SecretMasker.MASKED_VALUE;
        }
        if (value instanceof String text) {
            return exposure.apply(text);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> exposedValue(null, item, exposure)).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((name, item) -> {
                String nestedKey = String.valueOf(name);
                result.put(exposure.apply(nestedKey), exposedValue(nestedKey, item, exposure));
            });
            return result;
        }
        return value instanceof Number || value instanceof Boolean ? value : SecretMasker.MASKED_VALUE;
    }

    public static List<SpanEventDto> toEventList(List<NormalizedEvent> events, MessageExposure exposure) {
        if (exposure.omitsText() || events == null || events.isEmpty()) {
            return List.of();
        }
        List<SpanEventDto> out = new ArrayList<>(events.size());
        for (NormalizedEvent event : events) {
            out.add(new SpanEventDto(
                    exposure.apply(event.name()),
                    event.timeOffsetNanos(),
                    toAttributeList(event.attributes(), exposure)));
        }
        return out;
    }
}
