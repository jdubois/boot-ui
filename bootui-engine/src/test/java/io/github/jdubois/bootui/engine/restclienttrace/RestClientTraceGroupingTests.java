package io.github.jdubois.bootui.engine.restclienttrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceGroupDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the grouping/chatty-flagging/call-site-aggregation/path-normalization helper shared by the
 * REST Client panel's own {@code topCalls()} and the future Live Activity list-level chatty badge,
 * so both agree on exactly what counts as a chatty (repeated-call) access pattern.
 */
class RestClientTraceGroupingTests {

    @Test
    void returnsEmptyListForNullOrEmptyInput() {
        assertThat(RestClientTraceGrouping.group(null, 5)).isEmpty();
        assertThat(RestClientTraceGrouping.group(List.of(), 5)).isEmpty();
    }

    @Test
    void groupsByMethodHostAndPathAndOrdersByExecutionCountDescending() {
        List<RestClientTraceEntryDto> entries = List.of(
                call("GET", "api.example.com", "/orders", 1),
                call("GET", "api.example.com", "/items", 2),
                call("GET", "api.example.com", "/orders", 3),
                call("GET", "api.example.com", "/orders", 4));

        List<RestClientTraceGroupDto> groups = RestClientTraceGrouping.group(entries, 5);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).method()).isEqualTo("GET");
        assertThat(groups.get(0).path()).isEqualTo("/orders");
        assertThat(groups.get(0).executions()).isEqualTo(3);
        assertThat(groups.get(1).path()).isEqualTo("/items");
        assertThat(groups.get(1).executions()).isEqualTo(1);
    }

    @Test
    void groupsDifferentHostsSeparatelyEvenWithTheSamePath() {
        List<RestClientTraceEntryDto> entries =
                List.of(call("GET", "a.example.com", "/orders", 1), call("GET", "b.example.com", "/orders", 2));

        assertThat(RestClientTraceGrouping.group(entries, 5)).hasSize(2);
    }

    @Test
    void flagsGroupsAtOrAboveThresholdAsChatty() {
        List<RestClientTraceEntryDto> fourCalls = List.of(
                call("GET", "api.example.com", "/orders/1", 1),
                call("GET", "api.example.com", "/orders/2", 2),
                call("GET", "api.example.com", "/orders/3", 3),
                call("GET", "api.example.com", "/orders/4", 4));

        assertThat(RestClientTraceGrouping.group(fourCalls, 5).get(0).chatty()).isFalse();
        assertThat(RestClientTraceGrouping.group(fourCalls, 4).get(0).chatty()).isTrue();
    }

    @Test
    void flagsChattyRegardlessOfHttpMethodUnlikeSqlsSelectOnlyRule() {
        List<RestClientTraceEntryDto> repeatedPosts = List.of(
                call("POST", "api.example.com", "/orders/1", 1),
                call("POST", "api.example.com", "/orders/2", 2),
                call("POST", "api.example.com", "/orders/3", 3),
                call("POST", "api.example.com", "/orders/4", 4),
                call("POST", "api.example.com", "/orders/5", 5));

        assertThat(RestClientTraceGrouping.group(repeatedPosts, 5).get(0).chatty())
                .isTrue();
    }

    @Test
    void anyChattyReflectsWhetherAnyGroupIsFlagged() {
        List<RestClientTraceEntryDto> repeated = List.of(
                call("GET", "api.example.com", "/orders/1", 1),
                call("GET", "api.example.com", "/orders/2", 2),
                call("GET", "api.example.com", "/orders/3", 3),
                call("GET", "api.example.com", "/orders/4", 4),
                call("GET", "api.example.com", "/orders/5", 5));
        List<RestClientTraceEntryDto> single = List.of(call("GET", "api.example.com", "/orders/1", 1));

        assertThat(RestClientTraceGrouping.anyChatty(repeated, 5)).isTrue();
        assertThat(RestClientTraceGrouping.anyChatty(single, 5)).isFalse();
        assertThat(RestClientTraceGrouping.anyChatty(List.of(), 5)).isFalse();
    }

    @Test
    void aggregatesDistinctCallSitesPerGroupInSuppliedOrder() {
        List<RestClientTraceEntryDto> entries = List.of(
                call("GET", "api.example.com", "/orders/1", 1, "com.example.OrderClient.findOne(OrderClient.java:10)"),
                call("GET", "api.example.com", "/orders/2", 2, "com.example.OrderService.load(OrderService.java:20)"),
                call("GET", "api.example.com", "/orders/3", 3, "com.example.OrderClient.findOne(OrderClient.java:10)"),
                call("GET", "api.example.com", "/orders/4", 4, null));

        RestClientTraceGroupDto group =
                RestClientTraceGrouping.group(entries, 5).get(0);

        assertThat(group.callSites())
                .containsExactly(
                        "com.example.OrderClient.findOne(OrderClient.java:10)",
                        "com.example.OrderService.load(OrderService.java:20)");
    }

    @Test
    void boundsCallSitesPerGroupToMaxCallSitesPerGroup() {
        List<RestClientTraceEntryDto> entries = new java.util.ArrayList<>();
        for (int i = 0; i < RestClientTraceGrouping.MAX_CALL_SITES_PER_GROUP + 3; i++) {
            entries.add(call(
                    "GET",
                    "api.example.com",
                    "/orders/" + i,
                    i,
                    "com.example.Client.method" + i + "(Client.java:" + i + ")"));
        }

        RestClientTraceGroupDto group =
                RestClientTraceGrouping.group(entries, 5).get(0);

        assertThat(group.callSites()).hasSize(RestClientTraceGrouping.MAX_CALL_SITES_PER_GROUP);
    }

    @Test
    void normalizesNumericPathSegmentsForGroupingButKeepsThemLiteralOnEachEntry() {
        assertThat(RestClientTraceGrouping.normalizePath("/orders/42")).isEqualTo("/orders/{id}");
        assertThat(RestClientTraceGrouping.normalizePath("/orders/42/items/7")).isEqualTo("/orders/{id}/items/{id}");

        List<RestClientTraceEntryDto> entries = List.of(
                call("GET", "api.example.com", "/orders/1", 1),
                call("GET", "api.example.com", "/orders/2", 2),
                call("GET", "api.example.com", "/orders/3", 3));

        List<RestClientTraceGroupDto> groups = RestClientTraceGrouping.group(entries, 5);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).path()).isEqualTo("/orders/{id}");
        // Individual entries keep their own literal path for display, unaffected by grouping.
        assertThat(entries)
                .extracting(RestClientTraceEntryDto::path)
                .containsExactly("/orders/1", "/orders/2", "/orders/3");
    }

    @Test
    void normalizesUuidPathSegmentsForGrouping() {
        assertThat(RestClientTraceGrouping.normalizePath("/orders/550e8400-e29b-41d4-a716-446655440000"))
                .isEqualTo("/orders/{id}");
    }

    @Test
    void keepsNonIdentifierSegmentsLiteral() {
        assertThat(RestClientTraceGrouping.normalizePath("/orders/pending")).isEqualTo("/orders/pending");
    }

    @Test
    void normalizesNullOrBlankPathToRoot() {
        assertThat(RestClientTraceGrouping.normalizePath(null)).isEqualTo("/");
        assertThat(RestClientTraceGrouping.normalizePath("")).isEqualTo("/");
        assertThat(RestClientTraceGrouping.normalizePath("/")).isEqualTo("/");
    }

    private static RestClientTraceEntryDto call(String method, String host, String path, long id) {
        return call(method, host, path, id, null);
    }

    private static RestClientTraceEntryDto call(String method, String host, String path, long id, String callSite) {
        return new RestClientTraceEntryDto(
                id,
                id,
                method,
                "https://" + host + path,
                host,
                path,
                200,
                1L,
                true,
                null,
                false,
                "RestClient",
                Map.of(),
                null,
                "worker-1",
                callSite);
    }
}
