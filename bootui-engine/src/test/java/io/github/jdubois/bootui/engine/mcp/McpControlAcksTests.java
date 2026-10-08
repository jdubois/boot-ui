package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceStatsDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import io.github.jdubois.bootui.core.dto.TracesReport;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.core.dto.TransactionStatsDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpControlAcksTests {

    private static final List<String> SHAPE =
            List.of("action", "available", "unavailableReason", "capturing", "retained", "capacity", "totalCaptured");

    @Test
    void everyControlToolAnswersWithTheSameCompactShape() {
        SqlTraceReport sql = new SqlTraceReport(
                true,
                null,
                false,
                false,
                200,
                872,
                100,
                List.of("ds"),
                SqlTraceStatsDto.empty(),
                List.of(),
                List.of(),
                List.of());
        TransactionReport transactions = new TransactionReport(
                true, null, true, 200, 40, 200, 500, TransactionStatsDto.empty(), List.of(), List.of());
        RestClientTraceReport rest = new RestClientTraceReport(
                true,
                null,
                true,
                false,
                50,
                4,
                1000,
                List.of("RestClient"),
                RestClientTraceStatsDto.empty(),
                List.of(),
                List.of(),
                List.of());

        List<Map<String, Object>> acks = List.of(
                McpControlAcks.sqlTrace(McpControlAcks.PAUSED, sql),
                McpControlAcks.transactions(McpControlAcks.RESUMED, transactions),
                McpControlAcks.restClientTrace(McpControlAcks.CLEARED, rest),
                McpControlAcks.exceptionsCleared(new ExceptionsReport(true, null, 100, 0, List.of())),
                McpControlAcks.tracesCleared(new TracesReport(true, 0, 500, List.of())));

        assertThat(acks).allSatisfy(ack -> assertThat(ack.keySet()).containsExactlyElementsOf(SHAPE));
        assertThat(acks.get(0))
                .containsEntry("action", "paused")
                .containsEntry("capturing", false)
                .containsEntry("capacity", 200)
                .containsEntry("totalCaptured", 872L);
        assertThat(acks.get(1)).containsEntry("action", "resumed").containsEntry("totalCaptured", 40L);
        assertThat(acks.get(2))
                .containsEntry("action", "cleared")
                .containsEntry("capacity", 50)
                .containsEntry("totalCaptured", 4L);
        assertThat(acks.get(3)).containsEntry("capacity", 100).containsEntry("totalCaptured", null);
        assertThat(acks.get(4)).containsEntry("capacity", 500).containsEntry("totalCaptured", null);
    }

    @Test
    void anUnavailablePanelSaysWhyAndIsNotCapturing() {
        Map<String, Object> sql = McpControlAcks.sqlTrace(
                McpControlAcks.CLEARED,
                new SqlTraceReport(
                        false,
                        "No DataSource.",
                        false,
                        false,
                        0,
                        0,
                        0,
                        List.of(),
                        SqlTraceStatsDto.empty(),
                        List.of(),
                        List.of(),
                        List.of()));
        assertThat(sql)
                .containsEntry("available", false)
                .containsEntry("unavailableReason", "No DataSource.")
                .containsEntry("capturing", false);

        Map<String, Object> traces = McpControlAcks.tracesCleared(new TracesReport(false, 0, 500, List.of()));
        assertThat(traces).containsEntry("available", false).containsEntry("capturing", false);
        assertThat((String) traces.get("unavailableReason")).contains("bootui.telemetry.enabled");

        assertThat(McpControlAcks.exceptionsCleared(ExceptionsReport.unavailable("Off.", 100)))
                .containsEntry("available", false)
                .containsEntry("capturing", false)
                .containsEntry("unavailableReason", "Off.");
    }
}
