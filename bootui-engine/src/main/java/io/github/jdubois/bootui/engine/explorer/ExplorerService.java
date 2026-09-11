package io.github.jdubois.bootui.engine.explorer;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.activity.LiveActivityQueryService;
import io.github.jdubois.bootui.engine.telemetry.AttributeValue;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** On-demand evidence projection. Coordinates, camera state and animation never enter the backend. */
public final class ExplorerService {
    /** Only safe SQL text from the existing source projection, plus internal capture-time identity. */
    public record SqlEvidence(SqlTraceEntryDto statement, String invocationId, String dataSource) {}

    public record CacheEvidence(
            String eventId,
            long timestamp,
            String traceId,
            String invocationId,
            String managerName,
            String cacheName,
            String operation) {}

    private final SqlReferenceExtractor extractor = new SqlReferenceExtractor();
    private final SecretMasker masker = new SecretMasker();

    public ExplorerReport report(LiveActivityReport activity, ExplorerSetupDto setup) {
        return new ExplorerReport(activity.available(), activity, setup);
    }

    public ExplorerEventDto event(
            LiveActivityQueryService.Selection selection,
            ExplorerSetupDto setup,
            TelemetryStore.TraceBucket trace,
            List<SqlEvidence> sqlEvidence,
            List<CacheEvidence> cacheEvidence,
            boolean exceptionDetails) {
        if (selection.event() == null) {
            return new ExplorerEventDto(
                    false,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("Activity evidence expired, was cleared, or its source is disabled."),
                    true,
                    0);
        }
        ActivityEntryDto selected = selection.event();
        Map<String, ActivityEntryDto> events = new LinkedHashMap<>();
        selection.entries().forEach(entry -> events.put(entry.id(), entry));
        events.put(selected.id(), selected);
        List<String> warnings = new ArrayList<>();
        boolean partial = selection.partial();
        if (partial) warnings.add("Some related activity was omitted or has ambiguous capture identity.");
        List<ExplorerInvocationDto> invocations = new ArrayList<>();
        Set<String> invocationIds = new HashSet<>();
        int omitted = trace == null ? 0 : trace.omittedLocalSpans();
        List<NormalizedSpan> local = trace == null
                        || !setup.beanDetailAvailable()
                        || !Objects.equals(selected.correlationId(), trace.traceId())
                ? List.of()
                : trace.spans().stream()
                        .filter(span -> LocalInvocationCapture.SCOPE.equals(span.scope()))
                        .sorted(Comparator.comparingLong(NormalizedSpan::startEpochNanos)
                                .thenComparing(NormalizedSpan::spanId))
                        .limit(LocalInvocationCapture.MAX_CALLS)
                        .toList();
        List<ActivityEntryDto> requests = selection.entries().stream()
                .filter(entry -> "REQUEST".equals(entry.type())
                        && selected.correlationId() != null
                        && selected.correlationId().equals(entry.correlationId()))
                .distinct()
                .toList();
        if ("REQUEST".equals(selected.type()) && !requests.contains(selected)) {
            requests = new ArrayList<>(requests);
            requests.add(selected);
        }
        List<NormalizedSpan> servers = trace == null
                ? List.of()
                : trace.spans().stream()
                        .filter(span -> "SERVER".equals(span.kind()))
                        .toList();
        NormalizedSpan server = servers.size() == 1 ? servers.get(0) : null;
        ActivityEntryDto request = requests.size() == 1 ? requests.get(0) : null;
        boolean httpIdentityKnown =
                request != null && server != null && !selection.partial() && matchingHttpIdentity(request, server);
        if (requests.size() > 1) {
            local = List.of();
            warnings.add("Multiple request anchors share this trace ID; bean detail is not attributed.");
        } else if (servers.size() > 1) {
            local = List.of();
            warnings.add("Multiple HTTP spans share this trace ID; bean detail is not attributed.");
        } else if (request != null) {
            int retained = local.size();
            local = server != null && contradictsRequest(request, server)
                    ? List.of()
                    : local.stream()
                            .filter(span -> !outsideRequest(request, span))
                            .toList();
            if (retained != local.size()) {
                partial = true;
                warnings.add("Retained bean/HTTP evidence belongs to a different request capture.");
            }
        }
        if (local.isEmpty()) {
            partial = true;
            warnings.add(
                    !setup.beanDetailAvailable()
                            ? setup.reason()
                            : "Bean/detail evidence expired, is unsampled, or has not arrived. Canonical activity is retained.");
        } else {
            local.forEach(span -> invocationIds.add(span.spanId()));
            Map<String, NormalizedSpan> spans = new HashMap<>();
            trace.spans().forEach(span -> spans.put(span.spanId(), span));
            long start = local.stream()
                    .mapToLong(NormalizedSpan::startEpochNanos)
                    .min()
                    .orElse(0);
            if (server != null) start = Math.min(start, server.startEpochNanos());
            for (NormalizedSpan span : local) {
                String parent = span.parentSpanId();
                if (!invocationIds.contains(parent)) {
                    if (httpIdentityKnown && reaches(span, server.spanId(), spans)) {
                        parent = request.id();
                    } else {
                        partial = true;
                    }
                }
                double duration = span.durationNanos() / 1_000_000d;
                invocations.add(new ExplorerInvocationDto(
                        span.spanId(),
                        parent,
                        attribute(span, "bootui.explorer.bean"),
                        attribute(span, "bootui.explorer.type"),
                        attribute(span, "bootui.explorer.method"),
                        attribute(span, "bootui.explorer.role"),
                        Math.max(0, (span.startEpochNanos() - start) / 1_000_000d),
                        duration,
                        span.isError(),
                        duration >= setup.requestSlowThresholdMs(),
                        exceptionDetails ? attribute(span, "exception.type") : null));
            }
            if (partial)
                warnings.add("Some invocation parents or the HTTP root are not retained; no parent was guessed.");
        }
        if (omitted > 0) {
            partial = true;
            warnings.add(omitted + " bean invocations omitted by capture/retention budgets.");
        }
        List<ExplorerLinkDto> links = new ArrayList<>();
        List<ExplorerSqlDto> sql = new ArrayList<>();
        List<ExplorerCacheDto> cache = new ArrayList<>();
        int references = 0;
        for (SqlEvidence evidence : sqlEvidence) {
            SqlTraceEntryDto statement = evidence.statement();
            ActivityEntryDto event = events.get("sql-" + statement.id());
            if (!sameCapture(event, statement.timestamp(), statement.traceId())) continue;
            link(event, evidence.invocationId(), invocationIds, links);
            SqlReferenceExtractor.Result result = extractor.extract(statement.sql(), statement.batchSize());
            List<String> targets = result.identifiers().stream()
                    .limit(Math.max(0, SqlReferenceExtractor.MAX_REFERENCES - references))
                    .map(this::safe)
                    .toList();
            references += targets.size();
            boolean capped = targets.size() != result.identifiers().size();
            sql.add(new ExplorerSqlDto(
                    event.id(), safe(evidence.dataSource()), targets, capped ? "PARTIAL" : result.status()));
            partial |= capped || !"COMPLETE".equals(result.status());
        }
        for (CacheEvidence evidence : cacheEvidence) {
            ActivityEntryDto event = events.get(evidence.eventId());
            if (!sameCapture(event, evidence.timestamp(), evidence.traceId())) continue;
            link(event, evidence.invocationId(), invocationIds, links);
            cache.add(new ExplorerCacheDto(
                    event.id(), safe(evidence.managerName()), safe(evidence.cacheName()), evidence.operation()));
        }
        long sqlEvents = events.values().stream()
                .filter(event -> "SQL".equals(event.type()))
                .count();
        if (sqlEvents > sql.size()) {
            partial = true;
            warnings.add("Some SQL detail expired or is unavailable; those canonical executions remain visible.");
        }
        if (sql.stream().anyMatch(reference -> !"COMPLETE".equals(reference.status()))) {
            warnings.add("Table references are partial/unavailable for unsupported, truncated or batch SQL.");
        }
        return new ExplorerEventDto(
                true,
                selected,
                events.values().stream()
                        .filter(entry -> !entry.id().equals(selected.id()))
                        .toList(),
                invocations,
                links,
                sql,
                cache,
                warnings.stream().filter(Objects::nonNull).distinct().toList(),
                partial,
                omitted);
    }

    private static boolean reaches(NormalizedSpan span, String ancestor, Map<String, NormalizedSpan> spans) {
        Set<String> visited = new HashSet<>();
        String parent = span.parentSpanId();
        while (parent != null && visited.add(parent)) {
            if (parent.equals(ancestor)) return true;
            NormalizedSpan next = spans.get(parent);
            parent = next == null ? null : next.parentSpanId();
        }
        return false;
    }

    private static boolean matchingHttpIdentity(ActivityEntryDto request, NormalizedSpan server) {
        if (contradictsRequest(request, server)) return false;
        boolean method = false;
        for (String key : List.of("http.request.method", "http.method", "method")) {
            method |= request.method() != null && request.method().equals(rawAttribute(server, key));
        }
        boolean path = false;
        for (String key :
                List.of("url.path", "http.target", "http.path", "url.full", "http.url", "uri", "http.route")) {
            path |= request.path() != null && request.path().equals(httpPath(rawAttribute(server, key)));
        }
        // A shared trace ID and overlapping timestamps alone do not identify an HTTP exchange.
        return method && path;
    }

    private static boolean contradictsRequest(ActivityEntryDto request, NormalizedSpan span) {
        if (outsideRequest(request, span)) return true;
        for (String key : List.of("http.request.method", "http.method", "method")) {
            String method = rawAttribute(span, key);
            if (method != null && request.method() != null && !method.equals(request.method())) return true;
        }
        for (String key :
                List.of("url.path", "http.target", "http.path", "url.full", "http.url", "uri", "http.route")) {
            String path = httpPath(rawAttribute(span, key));
            if (path != null && request.path() != null && !path.equals(request.path())) return true;
        }
        for (String key : List.of("http.response.status_code", "http.status_code", "status")) {
            String status = rawAttribute(span, key);
            if (status != null
                    && request.status() != null
                    && !status.equals(request.status().toString())) return true;
        }
        return false;
    }

    private static boolean outsideRequest(ActivityEntryDto request, NormalizedSpan span) {
        double start = span.startEpochNanos() / 1_000_000d;
        double end = span.endEpochNanos() / 1_000_000d;
        return end < request.timestamp() - 50d
                || (request.durationMs() != null && start > request.timestamp() + (double) request.durationMs() + 50);
    }

    private static String httpPath(String value) {
        // Route templates are not literal request paths, and cannot contradict or establish identity.
        if (value == null || value.contains("{") || value.contains("*")) return null;
        try {
            String path = URI.create(value).getPath();
            return path == null || !path.startsWith("/") ? null : path;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String rawAttribute(NormalizedSpan span, String key) {
        AttributeValue value = span.attributes().get(key);
        return value == null ? null : value.asString();
    }

    private static boolean sameCapture(ActivityEntryDto event, long timestamp, String traceId) {
        // Source sequence IDs restart; never enrich an old persistent ID with a newer process's event.
        return event != null && event.timestamp() == timestamp && Objects.equals(event.correlationId(), traceId);
    }

    private static void link(
            ActivityEntryDto event, String invocationId, Set<String> known, List<ExplorerLinkDto> links) {
        if (invocationId != null && known.contains(invocationId)) {
            links.add(new ExplorerLinkDto(event.id(), invocationId));
        }
    }

    private String attribute(NormalizedSpan span, String name) {
        AttributeValue attribute = span.attributes().get(name);
        return safe(attribute == null ? null : attribute.asString());
    }

    private String safe(String value) {
        if (value == null) return null;
        String bounded = value.length() > 512 ? value.substring(0, 512) + "…" : value;
        return String.valueOf(masker.mask("explorer.metadata", bounded)).replaceAll("[\\p{Cntrl}]", " ");
    }
}
