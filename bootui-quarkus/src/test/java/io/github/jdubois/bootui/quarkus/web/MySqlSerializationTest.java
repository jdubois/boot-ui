package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.MySqlInsightReport;
import io.github.jdubois.bootui.core.dto.MySqlMetricDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jackson 2 must preserve annotation-free record names, explicit unknowns and unsigned numeric strings. */
class MySqlSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void initialReportKeepsEveryTopLevelFieldIncludingExplicitNullTimestamps() throws Exception {
        var report = new MySqlInsightReport(
                true,
                "Local observations",
                "NOT_READ",
                "Run an explicit read",
                null,
                null,
                0,
                List.of(),
                List.of(),
                List.of(),
                false);
        assertThat(mapper.writeValueAsString(report))
                .isEqualTo("{\"localOnly\":true,\"disclaimer\":\"Local observations\",\"status\":\"NOT_READ\","
                        + "\"message\":\"Run an explicit read\",\"readStartedAt\":null,\"readAt\":null,"
                        + "\"dataSourcesRead\":0,\"dataSources\":[],\"diagnostics\":[],\"limitations\":[],"
                        + "\"truncated\":false}");
    }

    @Test
    void exactUnsignedCounterIsAStringAndUnknownIsNotObservedZero() throws Exception {
        String maximumUnsigned = "18446744073709551615";
        var exact = mapper.valueToTree(
                new MySqlMetricDto("questions", "Questions", maximumUnsigned, "count", "SERVER", "global_status"));
        assertThat(exact.path("value").isTextual()).isTrue();
        assertThat(exact.path("value").asText()).isEqualTo(maximumUnsigned);
        var unknown = mapper.valueToTree(
                new MySqlMetricDto("questions", "Questions", null, "count", "SERVER", "global_status"));
        assertThat(unknown.path("value").isNull()).isTrue();
        var zero = mapper.valueToTree(
                new MySqlMetricDto("questions", "Questions", "0", "count", "SERVER", "global_status"));
        assertThat(zero.path("value").asText()).isEqualTo("0");
    }
}
