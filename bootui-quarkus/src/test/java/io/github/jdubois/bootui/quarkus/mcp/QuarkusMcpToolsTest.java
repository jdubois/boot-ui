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
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.web.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusMcpToolsTest {

    private static final List<String> MAXIMUM_CATALOG = List.of(
            "architecture_scan",
            "get_architecture_report",
            "spring_scan",
            "get_spring_report",
            "hibernate_scan",
            "get_hibernate_report",
            "database_advisor_scan",
            "get_database_advisor_report",
            "memory_scan",
            "get_memory_report",
            "security_scan",
            "get_security_report",
            "pentest_scan",
            "get_pentest_report",
            "rest_api_scan",
            "get_rest_api_report",
            "vulnerabilities_scan",
            "get_vulnerabilities_report",
            "get_live_activity",
            "get_exceptions",
            "get_exception_detail",
            "clear_exceptions",
            "get_security_logs",
            "get_sql_traces",
            "clear_sql_traces",
            "pause_sql_trace_recording",
            "resume_sql_trace_recording",
            "get_traces",
            "clear_traces",
            "get_log_tail",
            "get_http_exchanges",
            "get_overview",
            "get_health",
            "get_config",
            "get_beans",
            "get_mappings",
            "get_loggers",
            "get_scheduled_tasks",
            "get_cache_stats",
            "get_database_connection_pools",
            "get_metrics",
            "get_live_memory",
            "get_jvm_tuning",
            "get_heap_dump_report",
            "analyze_heap_dump",
            "get_threads",
            "get_profile_diff",
            "get_flyway_migrations",
            "get_liquibase_changesets",
            "get_rest_client_traces",
            "clear_rest_client_traces",
            "pause_rest_client_recording",
            "resume_rest_client_recording",
            "get_ai_overview",
            "get_emails",
            "get_kafka_activity",
            "get_rabbitmq_activity",
            "get_grpc_registry",
            "get_dev_services",
            "get_github_dashboard",
            "get_copilot_sessions",
            "get_claude_code_sessions");

    @Test
    void advertisesCompleteMaximumCatalogWhenEveryPanelIsAvailable() {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);

        assertThat(tools(availability)).extracting(McpTool::name).containsExactlyElementsOf(MAXIMUM_CATALOG);
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
                        mock(VulnerabilitiesResource.class),
                        mock(LoggersResource.class),
                        mock(ScheduledResource.class),
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
                        mock(GrpcResource.class),
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
