package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.activity.LiveActivityController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpTools;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLiveActivityController;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.journal.JournalActivityReports;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveActivityMcpBindingTests {

    private static final String QUERY =
            "SQL SLOW /orders namespace:prod application:shop run:run-7 instance:host-a route:/api/orders";

    @Test
    void mvcPassesArchiveQualifiersAndTheFetchLimitAsPageSize() {
        LiveActivityController controller = mock(LiveActivityController.class);
        LiveActivityReport report = emptyReport();
        when(controller.activityForMcp(
                        JournalActivityReports.MAX_LIMIT,
                        "SQL",
                        "/orders",
                        "/api/orders",
                        "prod",
                        "shop",
                        "host-a",
                        "run-7"))
                .thenReturn(report);

        liveActivityTool(McpToolsRegistryFixture.maximalRegistry(
                        BootUiMcpTools.class, "tools", Map.of(LiveActivityController.class, controller)))
                .invoke(new McpArguments(QUERY, 7, null, null, null));

        verify(controller)
                .activityForMcp(
                        JournalActivityReports.MAX_LIMIT,
                        "SQL",
                        "/orders",
                        "/api/orders",
                        "prod",
                        "shop",
                        "host-a",
                        "run-7");
    }

    @Test
    void webFluxPassesArchiveQualifiersAndTheFetchLimitAsPageSize() {
        ReactiveLiveActivityController controller = mock(ReactiveLiveActivityController.class);
        LiveActivityReport report = emptyReport();
        when(controller.activityForMcp(
                        JournalActivityReports.MAX_LIMIT,
                        "SQL",
                        "/orders",
                        "/api/orders",
                        "prod",
                        "shop",
                        "host-a",
                        "run-7"))
                .thenReturn(report);

        liveActivityTool(McpToolsRegistryFixture.maximalRegistry(
                        ReactiveBootUiMcpTools.class,
                        "tools",
                        Map.of(ReactiveLiveActivityController.class, controller)))
                .invoke(new McpArguments(QUERY, 7, null, null, null));

        verify(controller)
                .activityForMcp(
                        JournalActivityReports.MAX_LIMIT,
                        "SQL",
                        "/orders",
                        "/api/orders",
                        "prod",
                        "shop",
                        "host-a",
                        "run-7");
    }

    private static McpTool liveActivityTool(List<McpTool> tools) {
        return tools.stream()
                .filter(tool -> tool.name().equals("get_live_activity"))
                .findFirst()
                .orElseThrow();
    }

    private static LiveActivityReport emptyReport() {
        return new LiveActivityReport(true, List.of(), Map.of(), null, List.of(), List.of());
    }
}
