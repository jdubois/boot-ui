package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ExplorerCacheDto;
import io.github.jdubois.bootui.core.dto.ExplorerEventDto;
import io.github.jdubois.bootui.core.dto.ExplorerInvocationDto;
import io.github.jdubois.bootui.core.dto.ExplorerLinkDto;
import io.github.jdubois.bootui.core.dto.ExplorerReport;
import io.github.jdubois.bootui.core.dto.ExplorerSetupDto;
import io.github.jdubois.bootui.core.dto.ExplorerSqlDto;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExplorerSerializationTests {

    private final com.fasterxml.jackson.databind.ObjectMapper jackson2 =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final tools.jackson.databind.ObjectMapper jackson3 = new tools.jackson.databind.ObjectMapper();

    @Test
    void canonicalFeedAndEveryEnrichmentRecordRoundTripIdenticallyWithBothJacksonGenerations() throws Exception {
        List<ActivityEntryDto> entries = List.of(
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
                        "FUTURE_TYPE")
                .stream()
                .map(type -> new ActivityEntryDto(
                        type + "-1",
                        type,
                        1750000000000L,
                        "WARN",
                        "safe summary",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        false))
                .toList();
        ExplorerReport report = new ExplorerReport(
                true,
                new LiveActivityReport(true, entries, Map.of("CACHE", 1), null, List.of("Cache"), List.of()),
                new ExplorerSetupDto(false, false, "Bean detail unavailable", 1000, List.of("Restart required")));
        ExplorerEventDto detail = new ExplorerEventDto(
                true,
                entries.get(4),
                entries,
                List.of(new ExplorerInvocationDto(
                        "call-1",
                        null,
                        "orders",
                        "example.Orders",
                        "load()",
                        "SERVICE",
                        0.125,
                        1000.5,
                        true,
                        true,
                        "java.lang.IllegalStateException")),
                List.of(new ExplorerLinkDto("CACHE-1", "call-1")),
                List.of(new ExplorerSqlDto("SQL-1", null, List.of("\"Order\""), "PARTIAL")),
                List.of(new ExplorerCacheDto("CACHE-1", "cacheManager", "orders", "MISS")),
                List.of("Unknown datasource scope"),
                true,
                2);

        assertCompatible(report, ExplorerReport.class);
        assertCompatible(detail, ExplorerEventDto.class);
        var json = jackson2.readTree(jackson3.writeValueAsString(detail));
        assertThat(json.path("invocations").get(0).path("offsetMs").asDouble()).isEqualTo(0.125);
        assertThat(json.path("sqlReferences").get(0).path("dataSource").isNull())
                .isTrue();
        assertThat(json.path("cacheOperations").get(0).size()).isEqualTo(4);
    }

    @Test
    void expiredOrMissingDetailKeepsEmptyCollectionsAndNullableEvent() throws Exception {
        ExplorerEventDto missing =
                new ExplorerEventDto(false, null, null, null, null, null, null, List.of("Evidence expired"), true, 0);
        assertCompatible(missing, ExplorerEventDto.class);
        var json = jackson2.readTree(jackson3.writeValueAsString(missing));
        assertThat(json.path("event").isNull()).isTrue();
        assertThat(json.path("related").isArray()).isTrue();
        assertThat(json.path("invocations").isEmpty()).isTrue();
    }

    private <T> void assertCompatible(T dto, Class<T> type) throws Exception {
        String json2 = jackson2.writeValueAsString(dto);
        String json3 = jackson3.writeValueAsString(dto);
        assertThat(json3).isEqualTo(json2);
        assertThat(jackson2.readTree(json3)).isEqualTo(jackson2.readTree(json2));
        assertThat(jackson2.readValue(json3, type)).isEqualTo(dto);
        assertThat(jackson3.readValue(json2, type)).isEqualTo(dto);
    }
}
