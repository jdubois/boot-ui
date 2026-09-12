package io.github.jdubois.bootui.quarkus.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.web.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusMcpToolsTest {

    @Test
    void advertisesCompleteMaximumCatalogWhenEveryPanelIsAvailable() {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);

        assertThat(tools(availability))
                .extracting(McpTool::name)
                .containsExactlyInAnyOrderElementsOf(McpToolCatalog.namesFor(McpToolCatalog.Stack.QUARKUS));
    }

    @Test
    void matchesTheSharedCatalogSchemaPanelAndActionKind() {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);

        assertThat(tools(availability)).allSatisfy(tool -> {
            McpToolCatalog.Entry entry = McpToolCatalog.require(tool.name(), McpToolCatalog.Stack.QUARKUS);
            assertThat(tool.schema()).as("%s schema", tool.name()).isEqualTo(entry.schema());
            assertThat(tool.panelId()).as("%s panel", tool.name()).isEqualTo(entry.panelId());
            assertThat(tool.action()).as("%s action", tool.name()).isEqualTo(entry.action());
        });
    }

    @Test
    void boundedNoArgumentToolsAreActions() {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);

        assertThat(tools(availability))
                .filteredOn(tool -> tool.name()
                        .matches("clear_(exceptions|sql_traces|traces|rest_client_traces)|"
                                + "(pause|resume)_(sql_trace|rest_client)_recording|analyze_heap_dump"))
                .allSatisfy(tool -> {
                    assertThat(tool.action()).as(tool.name()).isTrue();
                    assertThat(tool.schema()).as(tool.name()).isEqualTo(McpToolSchema.NONE);
                });
    }

    @Test
    void recordingActionsPassExplicitStateToNativeResources() {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);
        SqlTraceResource sqlTrace = mock(SqlTraceResource.class);
        RestClientTraceResource restClientTrace = mock(RestClientTraceResource.class);
        List<McpTool> tools = tools(availability, sqlTrace, restClientTrace);
        McpArguments noArguments = new McpArguments(null, 100, null);

        invoke(tools, "pause_sql_trace_recording", noArguments);
        invoke(tools, "resume_sql_trace_recording", noArguments);
        invoke(tools, "pause_rest_client_recording", noArguments);
        invoke(tools, "resume_rest_client_recording", noArguments);

        verify(sqlTrace).recording(new SqlTraceRecordingRequest(false));
        verify(sqlTrace).recording(new SqlTraceRecordingRequest(true));
        verify(restClientTrace).recording(new RestClientTraceRecordingRequest(false));
        verify(restClientTrace).recording(new RestClientTraceRecordingRequest(true));
    }

    @Test
    void omitsToolsWhenTheirPanelIsUnavailable() {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);
        when(availability.isPanelAvailable(BootUiPanels.SQL_TRACE)).thenReturn(false);

        assertThat(tools(availability))
                .extracting(McpTool::name)
                .doesNotContain(
                        "get_sql_traces",
                        "clear_sql_traces",
                        "pause_sql_trace_recording",
                        "resume_sql_trace_recording");
    }

    private static List<McpTool> tools(QuarkusPanelAvailability availability) {
        return tools(availability, mock(SqlTraceResource.class), mock(RestClientTraceResource.class));
    }

    private static List<McpTool> tools(
            QuarkusPanelAvailability availability, SqlTraceResource sqlTrace, RestClientTraceResource restClientTrace) {
        return new QuarkusMcpTools(
                        availability,
                        mock(ArchitectureResource.class),
                        mock(SpringResource.class),
                        mock(HibernateResource.class),
                        mock(MemoryResource.class),
                        mock(SecurityResource.class),
                        mock(PentestingResource.class),
                        mock(RestApiResource.class),
                        mock(ExceptionsResource.class),
                        mock(LiveActivityResource.class),
                        mock(SecurityLogsResource.class),
                        sqlTrace,
                        mock(TracesResource.class),
                        mock(LogTailResource.class),
                        mock(HttpExchangesResource.class),
                        mock(HealthResource.class),
                        mock(ConfigResource.class),
                        mock(BeansResource.class),
                        mock(MappingsResource.class),
                        mock(OverviewResource.class),
                        mock(DatabaseAdvisorResource.class),
                        mock(PostgresqlResource.class),
                        mock(VulnerabilitiesResource.class),
                        mock(LoggersResource.class),
                        mock(ScheduledResource.class),
                        mock(FaultToleranceResource.class),
                        mock(CacheResource.class),
                        mock(ConnectionPoolsResource.class),
                        mock(MetricsResource.class),
                        mock(LiveMemoryResource.class),
                        mock(JvmTuningResource.class),
                        mock(HeapDumpResource.class),
                        mock(ThreadsResource.class),
                        mock(ProfileDiffResource.class),
                        mock(FlywayResource.class),
                        mock(LiquibaseResource.class),
                        restClientTrace,
                        mock(AiResource.class),
                        mock(EmailResource.class),
                        mock(KafkaResource.class),
                        mock(RabbitResource.class),
                        mock(DevServicesResource.class),
                        mock(GitHubResource.class),
                        mock(CopilotResource.class),
                        mock(ClaudeCodeResource.class))
                .tools();
    }

    private static void invoke(List<McpTool> tools, String name, McpArguments arguments) {
        tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow()
                .invoke(arguments);
    }
}
