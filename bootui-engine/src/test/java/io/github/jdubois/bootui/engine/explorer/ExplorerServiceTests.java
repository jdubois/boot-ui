package io.github.jdubois.bootui.engine.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.activity.LiveActivityQueryService;
import io.github.jdubois.bootui.engine.telemetry.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorerServiceTests {
    private final ExplorerService service = new ExplorerService();
    private final ExplorerSetupDto setup = new ExplorerSetupDto(true, true, null, 1000, List.of());

    @ParameterizedTest
    @ValueSource(
            strings = {
                "REQUEST",
                "SQL",
                "EXCEPTION",
                "SECURITY",
                "CACHE",
                "SCHEDULED",
                "MESSAGING",
                "MAIL",
                "REST_CLIENT",
                "FAULT_TOLERANCE",
                "FUTURE"
            })
    void canonicalEvidenceSurvivesAbsentTraceAndCaptureOff(String type) {
        ActivityEntryDto event = event("event", type, null, null, "WARN");
        var disabled = new ExplorerSetupDto(false, false, "Bean detail unavailable", 1000, List.of());
        var result = service.event(
                new LiveActivityQueryService.Selection(event, List.of(event), false),
                disabled,
                null,
                List.of(),
                List.of(),
                false);
        assertThat(result.found()).isTrue();
        assertThat(result.event()).isSameAs(event);
        assertThat(result.invocations()).isEmpty();
        assertThat(result.partial()).isTrue();
        assertThat(result.warnings()).contains("Bean detail unavailable");
    }

    @Test
    void exactSqlAndCacheLinksPreserveSeverityAndNeverExposePayloadsOrPerTableDuration() {
        ActivityEntryDto request = event("request", "REQUEST", "trace", null, "OK");
        ActivityEntryDto sql = event("sql-1", "SQL", "trace", "request", "SLOW");
        ActivityEntryDto cache = event("cache-1", "CACHE", "trace", "request", "WARN");
        TelemetryStore store = store();
        store.add(span("http", null, "SERVER", "http", false, 1500));
        store.add(span("controller", "http", "INTERNAL", LocalInvocationCapture.SCOPE, false, 1500));
        store.add(span("repository", "controller", "INTERNAL", LocalInvocationCapture.SCOPE, true, 600));
        var statement = new SqlTraceEntryDto(
                1,
                1000,
                "select * from public.orders join lines on 1=1",
                "PREPARED",
                "SELECT",
                600,
                true,
                null,
                null,
                0,
                "conn",
                "thread",
                true,
                List.of(),
                "trace",
                null);
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request, sql, cache), false),
                setup,
                store.findTrace("trace"),
                List.of(new ExplorerService.SqlEvidence(statement, "repository", "ordersDataSource")),
                List.of(new ExplorerService.CacheEvidence(
                        "cache-1", 1000, "trace", "controller", "manager", "orders", "MISS")),
                false);
        assertThat(result.event().severity()).isEqualTo("OK"); // Never reclassify with Live Flow's 500 ms rule.
        assertThat(result.links())
                .containsExactly(
                        new ExplorerLinkDto("sql-1", "repository"), new ExplorerLinkDto("cache-1", "controller"));
        assertThat(result.sqlReferences().get(0).identifiers()).containsExactly("public.orders", "lines");
        assertThat(result.invocations().get(0).parentId()).isEqualTo("request");
        assertThat(result.invocations().get(0).durationMs()).isEqualTo(1500d);
        assertThat(result.invocations())
                .filteredOn(ExplorerInvocationDto::failed)
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.slow()).isFalse();
                    assertThat(call.exceptionType()).isNull();
                });
        assertThat(result.related()).containsExactly(sql, cache);
        assertThat(result.cacheOperations())
                .containsExactly(new ExplorerCacheDto("cache-1", "manager", "orders", "MISS"));
    }

    @Test
    void duplicateTraceRequestAnchorsAndRestartedSourceSequencesNeverCreateFalseLinks() {
        ActivityEntryDto request = event("request", "REQUEST", "trace", null, "OK");
        ActivityEntryDto another = event("another", "REQUEST", "trace", null, "OK");
        ActivityEntryDto sql = event("sql-1", "SQL", "trace", "request", "OK");
        TelemetryStore store = store();
        store.add(span("controller", "missing", "INTERNAL", LocalInvocationCapture.SCOPE, false, 1));
        var statement = new SqlTraceEntryDto(
                1,
                9999,
                "select * from new_process",
                "STATEMENT",
                "SELECT",
                1,
                true,
                null,
                null,
                0,
                null,
                null,
                false,
                List.of(),
                "trace",
                null);
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request, another, sql), false),
                setup,
                store.findTrace("trace"),
                List.of(new ExplorerService.SqlEvidence(statement, "controller", null)),
                List.of(),
                true);
        assertThat(result.invocations()).isEmpty();
        assertThat(result.sqlReferences()).isEmpty();
        assertThat(result.links()).isEmpty();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("Multiple request anchors"));
    }

    @Test
    void lateRootLeavesLocalCallsVisibleButDoesNotManufactureRequestParentage() {
        ActivityEntryDto request = event("request", "REQUEST", "trace", null, "OK");
        TelemetryStore store = store();
        store.add(span("controller", "not-yet-exported", "INTERNAL", LocalInvocationCapture.SCOPE, false, 1));
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request), false),
                setup,
                store.findTrace("trace"),
                List.of(),
                List.of(),
                true);
        assertThat(result.invocations())
                .singleElement()
                .satisfies(call -> assertThat(call.parentId()).isEqualTo("not-yet-exported"));
        assertThat(result.partial()).isTrue();
    }

    @Test
    void independentlyRetainedOldTraceCannotAttachToANewRequestWithTheSameTraceId() {
        ActivityEntryDto request = request("new-request", 100_000);
        TelemetryStore store = store();
        store.add(span("http", null, "SERVER", "http", false, 20));
        store.add(span("old-call", "http", "INTERNAL", LocalInvocationCapture.SCOPE, false, 10));
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request), false),
                setup,
                store.findTrace("trace"),
                List.of(),
                List.of(),
                true);
        assertThat(result.event()).isSameAs(request);
        assertThat(result.invocations()).isEmpty();
        assertThat(result.links()).isEmpty();
        assertThat(result.partial()).isTrue();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("different request capture"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http.method", "url.path", "http.status_code"})
    void contradictoryHttpAttributesCannotIdentifyARequestEvenWhenTimingMatches(String key) {
        ActivityEntryDto request = request("request", 1000);
        var attributes = new java.util.HashMap<>(Map.of(
                "http.method", AttributeValue.ofString("GET"),
                "url.path", AttributeValue.ofString("/orders"),
                "http.status_code", AttributeValue.ofString("200")));
        attributes.put(
                key,
                AttributeValue.ofString(
                        switch (key) {
                            case "http.method" -> "POST";
                            case "url.path" -> "/other";
                            default -> "500";
                        }));
        TelemetryStore store = store();
        store.add(server(attributes));
        store.add(span("call", "http", "INTERNAL", LocalInvocationCapture.SCOPE, false, 10));
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request), false),
                setup,
                store.findTrace("trace"),
                List.of(),
                List.of(),
                true);
        assertThat(result.invocations()).isEmpty();
        assertThat(result.partial()).isTrue();
    }

    @Test
    void traceAndTimingWithoutCapturedHttpIdentityLeaveAnOrphanRatherThanGuessing() {
        ActivityEntryDto request = request("request", 1000);
        TelemetryStore store = store();
        store.add(server(Map.of()));
        store.add(span("call", "http", "INTERNAL", LocalInvocationCapture.SCOPE, false, 10));
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request), false),
                setup,
                store.findTrace("trace"),
                List.of(),
                List.of(),
                true);
        assertThat(result.invocations())
                .singleElement()
                .satisfies(call -> assertThat(call.parentId()).isEqualTo("http"));
        assertThat(result.partial()).isTrue();
    }

    @Test
    void literalHttpAttributesMatchWithoutExposingQueryAndTemplatedRoutesDoNotContradict() {
        ActivityEntryDto request = request("request", 1000);
        TelemetryStore store = store();
        store.add(server(Map.of(
                "http.request.method", AttributeValue.ofString("GET"),
                "url.full", AttributeValue.ofString("http://localhost/orders?token=hidden"),
                "http.route", AttributeValue.ofString("/{resource}"))));
        store.add(span("call", "http", "INTERNAL", LocalInvocationCapture.SCOPE, false, 10));
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request), false),
                setup,
                store.findTrace("trace"),
                List.of(),
                List.of(),
                true);
        assertThat(result.invocations())
                .singleElement()
                .satisfies(call -> assertThat(call.parentId()).isEqualTo("request"));
        assertThat(result.partial()).isFalse();
        assertThat(result.toString()).doesNotContain("hidden", "token");
    }

    @Test
    void micrometerHttpMethodAndStatusConventionsAlsoProvideCapturedIdentity() {
        ActivityEntryDto request = request("request", 1000);
        TelemetryStore store = store();
        store.add(server(Map.of(
                "method", AttributeValue.ofString("GET"),
                "status", AttributeValue.ofString("200"),
                "http.url", AttributeValue.ofString("http://localhost/orders"),
                "uri", AttributeValue.ofString("/{resource}"))));
        store.add(span("call", "http", "INTERNAL", LocalInvocationCapture.SCOPE, false, 10));
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request), false),
                setup,
                store.findTrace("trace"),
                List.of(),
                List.of(),
                true);
        assertThat(result.invocations())
                .singleElement()
                .satisfies(call -> assertThat(call.parentId()).isEqualTo("request"));
        assertThat(result.partial()).isFalse();
    }

    @Test
    void sameRequestIdAtDifferentTimestampsCannotHideTraceAmbiguityDuringDeduplication() {
        ActivityEntryDto request = request("request", 1000);
        ActivityEntryDto reused = request("request", 100_000);
        TelemetryStore store = store();
        store.add(span("http", null, "SERVER", "http", false, 20));
        store.add(span("call", "http", "INTERNAL", LocalInvocationCapture.SCOPE, false, 10));
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request, reused), false),
                setup,
                store.findTrace("trace"),
                List.of(),
                List.of(),
                true);
        assertThat(result.invocations()).isEmpty();
        assertThat(result.partial()).isTrue();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("Multiple request anchors"));
    }

    @Test
    void incompleteActivitySelectionDoesNotEstablishUniqueHttpParentage() {
        ActivityEntryDto request = request("request", 1000);
        TelemetryStore store = store();
        store.add(span("http", null, "SERVER", "http", false, 20));
        store.add(span("call", "http", "INTERNAL", LocalInvocationCapture.SCOPE, false, 10));
        var result = service.event(
                new LiveActivityQueryService.Selection(request, List.of(request), true),
                setup,
                store.findTrace("trace"),
                List.of(),
                List.of(),
                true);
        assertThat(result.invocations())
                .singleElement()
                .satisfies(call -> assertThat(call.parentId()).isEqualTo("http"));
        assertThat(result.partial()).isTrue();
    }

    private static ActivityEntryDto request(String id, long timestamp) {
        return new ActivityEntryDto(
                id,
                "REQUEST",
                timestamp,
                "OK",
                "GET /orders",
                null,
                600L,
                "trace",
                "GET",
                "/orders",
                200,
                null,
                true,
                null,
                null,
                false);
    }

    private static NormalizedSpan server(Map<String, AttributeValue> attributes) {
        return new NormalizedSpan(
                "trace",
                "http",
                null,
                "http",
                "SERVER",
                "app",
                "http",
                1_000_000_000L,
                1_020_000_000L,
                "OK",
                null,
                attributes,
                List.of());
    }

    private static ActivityEntryDto event(String id, String type, String trace, String parent, String severity) {
        return new ActivityEntryDto(
                id,
                type,
                1000,
                severity,
                "safe summary",
                null,
                600L,
                trace,
                "REQUEST".equals(type) ? "GET" : null,
                "REQUEST".equals(type) ? "/orders" : null,
                "REQUEST".equals(type) ? 200 : null,
                null,
                false,
                parent,
                null,
                false);
    }

    private static TelemetryStore store() {
        return new TelemetryStore(TelemetrySettings.of(true, true, 100, 200, 1024));
    }

    private static NormalizedSpan span(String id, String parent, String kind, String scope, boolean error, long ms) {
        return new NormalizedSpan(
                "trace",
                id,
                parent,
                id,
                kind,
                "app",
                scope,
                1_000_000_000L,
                1_000_000_000L + ms * 1_000_000L,
                error ? "ERROR" : "OK",
                null,
                Map.of(
                        "bootui.explorer.bean",
                        AttributeValue.ofString(id),
                        "bootui.explorer.type",
                        AttributeValue.ofString("example." + id),
                        "bootui.explorer.method",
                        AttributeValue.ofString("load()"),
                        "bootui.explorer.role",
                        AttributeValue.ofString("COMPONENT"),
                        "http.method",
                        AttributeValue.ofString("GET"),
                        "http.url",
                        AttributeValue.ofString("http://localhost/orders"),
                        "exception.type",
                        AttributeValue.ofString("java.lang.IllegalStateException")),
                List.of());
    }
}
