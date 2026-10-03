package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.core.dto.SpanAttributeDto;
import io.github.jdubois.bootui.core.dto.SpanEventDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verbatim mappers from normalized spans/events to the immutable DTOs the AI Framework panel serializes. The Traces
 * panel and the per-request profile map through {@link SpanValueExposure} instead, which applies the live
 * value-exposure policy.
 */
public final class SpanMappers {

    private SpanMappers() {}

    public static List<SpanAttributeDto> toAttributeList(Map<String, AttributeValue> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return List.of();
        }
        List<SpanAttributeDto> out = new ArrayList<>(attrs.size());
        for (Map.Entry<String, AttributeValue> entry : attrs.entrySet()) {
            AttributeValue v = entry.getValue();
            out.add(new SpanAttributeDto(entry.getKey(), v.type(), v.value()));
        }
        return out;
    }

    public static List<SpanEventDto> toEventList(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<SpanEventDto> out = new ArrayList<>(events.size());
        for (NormalizedEvent event : events) {
            out.add(new SpanEventDto(event.name(), event.timeOffsetNanos(), toAttributeList(event.attributes())));
        }
        return out;
    }
}
