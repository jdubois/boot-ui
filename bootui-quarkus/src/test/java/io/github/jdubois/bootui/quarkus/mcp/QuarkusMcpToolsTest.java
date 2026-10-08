package io.github.jdubois.bootui.quarkus.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.MySqlInsightReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceStatsDto;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.web.*;
import io.github.jdubois.bootui.quarkus.web.HibernateStatisticsResource;
import io.github.jdubois.bootui.quarkus.web.RuntimeInsightsResource;
import io.github.jdubois.bootui.quarkus.web.WebSocketsResource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkusMcpToolsTest {

    @Test
    void everyAdvisorPageForwardsAllArgumentsToItsNativeResource() throws Exception {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);
        Map<String, Class<?>> resourceTypes = Map.of(
                "architecture",
                ArchitectureResource.class,
                "hibernate",
                HibernateResource.class,
                "spring",
                SpringResource.class,
                "rest_api",
                RestApiResource.class,
                "memory",
                MemoryResource.class,
                "security",
                SecurityResource.class,
                "database_advisor",
                DatabaseAdvisorResource.class);
        Map<Class<?>, Object> resources = new java.util.HashMap<>();
        resourceTypes.values().forEach(type -> resources.put(type, mock(type)));
        java.lang.reflect.Constructor<?> constructor = java.util.Arrays.stream(
                        QuarkusMcpTools.class.getDeclaredConstructors())
                .max(java.util.Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount))
                .orElseThrow();
        Object[] arguments = java.util.Arrays.stream(constructor.getParameterTypes())
                .map(type -> type == QuarkusPanelAvailability.class
                        ? availability
                        : resources.computeIfAbsent(type, org.mockito.Mockito::mock))
                .toArray();
        List<McpTool> tools = ((QuarkusMcpTools) constructor.newInstance(arguments)).tools();
        resourceTypes.forEach((advisor, resourceType) -> {
            invoke(tools, "get_" + advisor + "_rule_violations", new McpArguments(null, 7, "RULE-1", "scan-1", 22));
            assertThat(mockingDetails(resources.get(resourceType)).getInvocations())
                    .singleElement()
                    .satisfies(invocation -> {
                        assertThat(invocation.getMethod().getName()).isEqualTo("ruleViolations");
                        assertThat(invocation.getArguments()).containsExactly("RULE-1", "scan-1", 22, 7);
                    });
        });
    }

    @Test
    void unavailableAdvisorPanelsOmitTheirDetailTools() {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);
        when(availability.isPanelAvailable(BootUiPanels.HIBERNATE)).thenReturn(false);
        when(availability.isPanelAvailable(BootUiPanels.DATABASE_ADVISOR)).thenReturn(false);
        assertThat(tools(availability))
                .extracting(McpTool::name)
                .doesNotContain("get_hibernate_rule_violations", "get_database_advisor_rule_violations");
    }

    @Test
    void mysqlToolsUseTheNativeResourceAndAreAbsentWithoutTheCapability() throws Exception {
        QuarkusPanelAvailability availability = mock(QuarkusPanelAvailability.class);
        when(availability.isPanelAvailable(anyString())).thenReturn(true);
        MySqlResource mysql = mock(MySqlResource.class);
        var constructor = QuarkusMcpTools.class.getDeclaredConstructors()[0];
        Object[] arguments = java.util.Arrays.stream(constructor.getParameterTypes())
                .map(type -> type == QuarkusPanelAvailability.class
                        ? availability
                        : type == MySqlResource.class ? mysql : mock(type))
                .toArray();
        List<McpTool> tools = ((QuarkusMcpTools) constructor.newInstance(arguments)).tools();
        org.mockito.Mockito.verifyNoInteractions(mysql);
        invoke(tools, "get_mysql_report", new McpArguments(null, 100, null));
        verify(mysql).mysql();
        when(mysql.read())
                .thenReturn(new MySqlInsightReport(
                        true, "Local only.", "OK", null, 1L, 2L, 0, List.of(), List.of(), List.of(), false));
        assertThat(invoke(tools, "mysql_read", new McpArguments(null, 100, null)))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("reportTool", "get_mysql_report")
                .containsEntry("status", "OK");
        verify(mysql).read();
        assertThat(tools)
                .filteredOn(tool -> tool.name().equals("mysql_read"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.action()).isTrue();
                    assertThat(tool.schema()).isEqualTo(McpToolSchema.NONE);
                    assertThat(tool.panelId()).isEqualTo(BootUiPanels.MYSQL);
                });
        when(availability.isPanelAvailable(BootUiPanels.MYSQL)).thenReturn(false);
        assertThat(tools(availability)).extracting(McpTool::name).doesNotContain("mysql_read", "get_mysql_report");
    }

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
        when(sqlTrace.recording(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SqlTraceReport(
                        true,
                        null,
                        false,
                        false,
                        200,
                        7,
                        100,
                        List.of(),
                        SqlTraceStatsDto.empty(),
                        List.of(),
                        List.of(),
                        List.of()));
        when(restClientTrace.recording(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RestClientTraceReport(
                        true,
                        null,
                        true,
                        false,
                        50,
                        3,
                        1000,
                        List.of(),
                        RestClientTraceStatsDto.empty(),
                        List.of(),
                        List.of(),
                        List.of()));

        assertThat(invoke(tools, "pause_sql_trace_recording", noArguments))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("action", "paused")
                .containsEntry("capturing", false)
                .containsEntry("totalCaptured", 7L);
        invoke(tools, "resume_sql_trace_recording", noArguments);
        invoke(tools, "pause_rest_client_recording", noArguments);
        assertThat(invoke(tools, "resume_rest_client_recording", noArguments))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("action", "resumed")
                .containsEntry("capacity", 50);

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
                        mock(MySqlResource.class),
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
                        mock(ClaudeCodeResource.class),
                        mock(RuntimeInsightsResource.class),
                        mock(JavaAgentResource.class),
                        mock(CodeInventoryResource.class),
                        mock(CodePathsResource.class),
                        mock(SideEffectsResource.class),
                        mock(HibernateStatisticsResource.class),
                        mock(WebSocketsResource.class))
                .tools();
    }

    private static Object invoke(List<McpTool> tools, String name, McpArguments arguments) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow()
                .invoke(arguments);
    }
}
