package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.SpanAttributeDto;
import io.github.jdubois.bootui.core.dto.SpanEventDto;
import io.github.jdubois.bootui.engine.support.MessageExposure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared mappers from normalized spans/events to the immutable DTOs the Traces and AI Framework panels
 * serialize. Centralized so both {@link TracesService} and {@link AiUsageService} stay in sync.
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
            if (exposure.masksText() && MASKER.shouldMask(entry.getKey(), v.value())) {
                out.add(new SpanAttributeDto(entry.getKey(), "string", SecretMasker.MASKED_VALUE));
            } else if (v.value() instanceof String text) {
                out.add(new SpanAttributeDto(entry.getKey(), v.type(), exposure.apply(text)));
            } else if (v.value() instanceof List<?> values) {
                out.add(new SpanAttributeDto(
                        entry.getKey(),
                        v.type(),
                        values.stream()
                                .map(value -> value instanceof String text ? exposure.apply(text) : value)
                                .toList()));
            } else {
                out.add(new SpanAttributeDto(entry.getKey(), v.type(), v.value()));
            }
        }
        return out;
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
