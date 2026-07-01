package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.core.dto.SpanDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.core.dto.TraceSummaryDto;
import io.github.jdubois.bootui.core.dto.TracesReport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Framework-neutral read model for the BootUI Traces panel. Transforms spans accumulated in the
 * {@link TelemetryStore} into the immutable trace DTOs the UI renders, applying the transform-side
 * self-trace filter (see {@link SelfTelemetryClassifier}).
 */
public final class TracesService {

    private static final int MAX_SUMMARY_SERVICES = 20;

    /**
     * Span attribute keys that can carry an HTTP request path, ordered from the most literal
     * request path to the most templated/abstract, so the Traces list can label a trace with the
     * URL path it served instead of a generic root span name such as {@code security filterchain
     * before}.
     */
    private static final List<String> HTTP_PATH_ATTRIBUTE_KEYS =
            List.of("url.path", "http.target", "http.path", "path", "uri", "http.route", "url.full", "http.url");

    private final TelemetryStore store;

    private final TelemetrySettings settings;

    private final SelfTelemetryClassifier selfClassifier;

    public TracesService(TelemetryStore store, TelemetrySettings settings, SelfTelemetryClassifier selfClassifier) {
        this.store = store;
        this.settings = settings;
        this.selfClassifier = selfClassifier;
    }

    public TracesReport list(int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        List<TraceSummaryDto> summaries = new ArrayList<>();
        int retained = 0;
        for (TelemetryStore.TraceBucket bucket : store.recentTraces(store.capacity())) {
            if (!selfClassifier.shouldIncludeTrace(bucket.spans())) {
                continue;
            }
            retained++;
            if (summaries.size() < safeLimit) {
                summaries.add(toSummary(bucket));
            }
        }
        return new TracesReport(settings.enabled(), retained, store.capacity(), summaries);
    }

    public Optional<TraceDetailDto> detail(String traceId) {
        TelemetryStore.TraceBucket bucket = store.findTrace(traceId);
        if (bucket == null) {
            return Optional.empty();
        }
        if (!selfClassifier.shouldIncludeTrace(bucket.spans())) {
            return Optional.empty();
        }
        List<SpanDto> spans = new ArrayList<>(bucket.spans().size());
        for (NormalizedSpan span : bucket.spans()) {
            spans.add(toSpanDto(span));
        }
        spans.sort(Comparator.comparingLong(SpanDto::startEpochNanos));
        return Optional.of(new TraceDetailDto(bucket.traceId(), spans));
    }

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
                resolveHttpPath(bucket.spans(), earliest),
                firstServices(services),
                minStart,
                maxEnd,
                Math.max(0L, maxEnd - minStart),
                bucket.spans().size(),
                hasError,
                hasAi);
    }

    /**
     * Resolves the HTTP request path a trace served, when one is available, so the Traces list can
     * show something more useful than the raw root span name. The root span is preferred; otherwise
     * the earliest server span carrying a path wins, falling back to the earliest span of any kind.
     */
    static String resolveHttpPath(Collection<NormalizedSpan> spans, NormalizedSpan root) {
        String rootPath = httpPath(root);
        if (rootPath != null) {
            return rootPath;
        }
        if (spans == null) {
            return null;
        }
        NormalizedSpan bestServer = null;
        String bestServerPath = null;
        NormalizedSpan bestAny = null;
        String bestAnyPath = null;
        for (NormalizedSpan span : spans) {
            String path = httpPath(span);
            if (path == null) {
                continue;
            }
            if (bestAny == null || span.startEpochNanos() < bestAny.startEpochNanos()) {
                bestAny = span;
                bestAnyPath = path;
            }
            if (isServer(span) && (bestServer == null || span.startEpochNanos() < bestServer.startEpochNanos())) {
                bestServer = span;
                bestServerPath = path;
            }
        }
        return bestServerPath != null ? bestServerPath : bestAnyPath;
    }

    static String httpPath(NormalizedSpan span) {
        if (span == null) {
            return null;
        }
        Map<String, AttributeValue> attributes = span.attributes();
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        for (String key : HTTP_PATH_ATTRIBUTE_KEYS) {
            AttributeValue value = attributes.get(key);
            if (value == null) {
                continue;
            }
            String path = normalizePath(value.asString());
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private static String normalizePath(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.trim();
        if (path.isEmpty()) {
            return null;
        }
        if (path.contains("://")) {
            try {
                String uriPath = URI.create(path).getRawPath();
                if (uriPath != null && !uriPath.isEmpty()) {
                    path = uriPath;
                }
            } catch (IllegalArgumentException ignored) {
                // Keep the raw value if it cannot be parsed as a URI.
            }
        }
        int cut = path.length();
        int query = path.indexOf('?');
        if (query >= 0) {
            cut = query;
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0 && fragment < cut) {
            cut = fragment;
        }
        path = path.substring(0, cut).trim();
        return path.isEmpty() ? null : path;
    }

    private static boolean isServer(NormalizedSpan span) {
        return span.kind() != null && span.kind().contains("SERVER");
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
                SpanMappers.toAttributeList(span.attributes()),
                SpanMappers.toEventList(span.events()));
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
