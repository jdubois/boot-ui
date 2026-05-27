package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.AttributeValue;
import io.github.jdubois.bootui.autoconfigure.otlp.NormalizedEvent;
import io.github.jdubois.bootui.autoconfigure.otlp.NormalizedSpan;
import io.github.jdubois.bootui.autoconfigure.otlp.TelemetryStore;
import io.github.jdubois.bootui.core.BootUiDtos.SpanAttributeDto;
import io.github.jdubois.bootui.core.BootUiDtos.SpanDto;
import io.github.jdubois.bootui.core.BootUiDtos.SpanEventDto;
import io.github.jdubois.bootui.core.BootUiDtos.TraceDetailDto;
import io.github.jdubois.bootui.core.BootUiDtos.TraceSummaryDto;
import io.github.jdubois.bootui.core.BootUiDtos.TracesReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only API for the BootUI Traces panel.
 */
@RestController
@RequestMapping("/bootui/api/traces")
public class TracesController {

    private static final int MAX_SUMMARY_SERVICES = 20;

    private final TelemetryStore store;

    private final BootUiProperties properties;

    public TracesController(TelemetryStore store, BootUiProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping
    public TracesReport list(@RequestParam(name = "limit", required = false, defaultValue = "100") int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        List<TraceSummaryDto> summaries = new ArrayList<>();
        for (TelemetryStore.TraceBucket bucket : store.recentTraces(safeLimit)) {
            summaries.add(toSummary(bucket));
        }
        return new TracesReport(properties.getTelemetry().isEnabled(), store.retainedTraceCount(), store.capacity(), summaries);
    }

    @GetMapping("/{traceId}")
    public TraceDetailDto detail(@PathVariable String traceId) {
        TelemetryStore.TraceBucket bucket = store.findTrace(traceId);
        if (bucket == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trace " + traceId + " not found");
        }
        List<SpanDto> spans = new ArrayList<>(bucket.spans().size());
        for (NormalizedSpan span : bucket.spans()) {
            spans.add(toSpanDto(span));
        }
        spans.sort(Comparator.comparingLong(SpanDto::startEpochNanos));
        return new TraceDetailDto(bucket.traceId(), spans);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        store.clear();
    }

    static TraceSummaryDto toSummary(TelemetryStore.TraceBucket bucket) {
        long minStart = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;
        Set<String> services = new LinkedHashSet<>();
        boolean hasError = false;
        boolean hasAi = false;
        String rootSpanName = null;
        NormalizedSpan earliest = null;
        for (NormalizedSpan span : bucket.spans()) {
            if (span.startEpochNanos() < minStart) {
                minStart = span.startEpochNanos();
            }
            if (span.endEpochNanos() > maxEnd) {
                maxEnd = span.endEpochNanos();
            }
            if (span.serviceName() != null) {
                services.add(span.serviceName());
            }
            if (span.isError()) {
                hasError = true;
            }
            if (AiSpanRecognizer.isAi(span)) {
                hasAi = true;
            }
            if (span.parentSpanId() == null) {
                if (earliest == null || span.startEpochNanos() < earliest.startEpochNanos()) {
                    earliest = span;
                }
            }
        }
        if (earliest != null) {
            rootSpanName = earliest.name();
        } else if (!bucket.spans().isEmpty()) {
            NormalizedSpan first = bucket.spans().stream()
                    .min(Comparator.comparingLong(NormalizedSpan::startEpochNanos))
                    .orElse(bucket.spans().get(0));
            rootSpanName = first.name();
        }
        if (minStart == Long.MAX_VALUE) {
            minStart = 0L;
            maxEnd = 0L;
        }
        return new TraceSummaryDto(
                bucket.traceId(),
                rootSpanName,
                firstServices(services),
                minStart,
                maxEnd,
                Math.max(0L, maxEnd - minStart),
                bucket.spans().size(),
                hasError,
                hasAi);
    }

    static SpanDto toSpanDto(NormalizedSpan span) {
        return new SpanDto(
                span.traceId(),
                span.spanId(),
                span.parentSpanId(),
                span.name(),
                span.kind(),
                span.serviceName(),
                span.scope(),
                span.startEpochNanos(),
                span.endEpochNanos(),
                span.durationNanos(),
                span.statusCode(),
                span.statusMessage(),
                toAttributeList(span.attributes()),
                toEventList(span.events()));
    }

    static List<SpanAttributeDto> toAttributeList(Map<String, AttributeValue> attrs) {
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

    static List<SpanEventDto> toEventList(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<SpanEventDto> out = new ArrayList<>(events.size());
        for (NormalizedEvent event : events) {
            out.add(new SpanEventDto(event.name(), event.timeOffsetNanos(),
                    toAttributeList(event.attributes())));
        }
        return out;
    }

    private static List<String> firstServices(Set<String> services) {
        if (services.size() <= MAX_SUMMARY_SERVICES) {
            return new ArrayList<>(services);
        }
        List<String> out = new ArrayList<>(MAX_SUMMARY_SERVICES + 1);
        int count = 0;
        for (String service : services) {
            if (count++ >= MAX_SUMMARY_SERVICES) {
                break;
            }
            out.add(service);
        }
        out.add("...");
        return out;
    }
}
