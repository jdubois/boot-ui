package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import io.github.jdubois.bootui.core.dto.ConfigReport;
import io.github.jdubois.bootui.core.dto.CopilotSessionListDto;
import io.github.jdubois.bootui.core.dto.CopilotSessionSummary;
import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import io.github.jdubois.bootui.core.dto.JavaAgentHookDto;
import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorDto;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.StartupReport;
import io.github.jdubois.bootui.core.dto.StartupStepDto;
import io.github.jdubois.bootui.core.dto.TagDto;
import io.github.jdubois.bootui.engine.journal.JournalActivityReports;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpAgentViewsTests {

    @Test
    void configDropsOnlyThePropertySuggestions() {
        ConfigReport report = new ConfigReport(
                List.of("dev"),
                List.of("application.properties"),
                List.of(),
                List.of(new ConfigPropertySuggestionDto("server.port", "java.lang.Integer", "The port.", "8080")),
                new PageMetadata(0, 0, 0, 50, 0, false),
                0);

        ConfigReport view = McpAgentViews.config(report);

        assertThat(view.propertySuggestions()).isEmpty();
        assertThat(view.activeProfiles()).isEqualTo(report.activeProfiles());
        assertThat(view.sources()).isEqualTo(report.sources());
        assertThat(view.page()).isEqualTo(report.page());
    }

    @Test
    void sqlTracesListTheNewestMatchingStatementsInTheirOrderWithAPage() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            entries.add(sql(i, i % 2 == 0 ? "select * from orders" : "select * from customers"));
        }
        SqlTraceReport report = new SqlTraceReport(
                true,
                null,
                true,
                false,
                500,
                30,
                100,
                List.of("dataSource"),
                null,
                entries,
                List.of(
                        new SqlTraceGroupDto("select * from orders", "SELECT", 15, 1, 1, false, List.of()),
                        new SqlTraceGroupDto("select * from customers", "SELECT", 15, 1, 1, false, List.of())),
                List.of());

        Map<String, Object> all = McpAgentViews.sqlTraces(report, null, 5);
        assertThat(ids(all.get("entries"))).containsExactly(26L, 27L, 28L, 29L, 30L);
        assertThat(all.get("page")).isEqualTo(new PageMetadata(30, 30, 0, 5, 5, true));
        assertThat(all.get("totalCaptured")).isEqualTo(30L);

        Map<String, Object> orders = McpAgentViews.sqlTraces(report, "ORDERS", 100);
        assertThat(ids(orders.get("entries"))).hasSize(15).allMatch(id -> id % 2 == 0);
        assertThat((List<?>) orders.get("topStatements")).hasSize(1);
        assertThat(orders.get("page")).isEqualTo(new PageMetadata(30, 15, 0, 100, 15, false));
    }

    @Test
    void startupListsTheSlowestMatchingStepsFirst() {
        StartupReport report = new StartupReport(List.of(
                new StartupStepDto(1, null, "spring.context.refresh", 900, List.of()),
                new StartupStepDto(2, 1L, "spring.beans.instantiate", 40, List.of(new TagDto("beanName", "orders"))),
                new StartupStepDto(3, 1L, "spring.beans.instantiate", 300, List.of(new TagDto("beanName", "db")))));

        Map<String, Object> slowest = McpAgentViews.startup(report, null, 2);
        assertThat(((List<?>) slowest.get("steps")).stream().map(step -> ((StartupStepDto) step).id()))
                .containsExactly(1L, 3L);
        assertThat(slowest.get("page")).isEqualTo(new PageMetadata(3, 3, 0, 2, 2, true));

        Map<String, Object> bean = McpAgentViews.startup(report, "orders", 25);
        assertThat(((List<?>) bean.get("steps")).stream().map(step -> ((StartupStepDto) step).id()))
                .containsExactly(2L);
    }

    @Test
    void logTailKeepsTheNewestMatchingLinesOldestFirst() {
        List<LogLineDto> lines = List.of(
                new LogLineDto(1, "INFO", "app.Orders", "one", "main", false),
                new LogLineDto(2, "WARN", "app.Orders", "two", "main", false),
                new LogLineDto(3, "INFO", "app.Billing", "three", "main", false),
                new LogLineDto(4, "WARN", "app.Billing", "four", "main", false));

        Map<String, Object> newest = McpAgentViews.logTail(lines, null, 2);
        assertThat(messages(newest)).containsExactly("three", "four");
        assertThat(newest.get("page")).isEqualTo(new PageMetadata(4, 4, 0, 2, 2, true));

        Map<String, Object> warnings = McpAgentViews.logTail(lines, "warn", 50);
        assertThat(messages(warnings)).containsExactly("two", "four");
        assertThat(warnings.get("page")).isEqualTo(new PageMetadata(4, 2, 0, 50, 2, false));
    }

    @Test
    void sessionsAreFilteredAndBoundedWithTheInventoryCounts() {
        CopilotSessionListDto list = new CopilotSessionListDto(
                true,
                null,
                "/home/me/.copilot",
                3,
                3,
                100,
                List.of(session("a", "/work/shop"), session("b", "/work/blog"), session("c", "/work/shop")),
                List.of());

        Map<String, Object> view = McpAgentViews.sessions(list, "shop", 1);

        assertThat(view.get("total")).isEqualTo(3);
        assertThat(view.get("returned")).isEqualTo(1);
        assertThat(((List<?>) view.get("sessions")).stream().map(s -> ((CopilotSessionSummary) s).id()))
                .containsExactly("a");
        assertThat(view.get("page")).isEqualTo(new PageMetadata(3, 2, 0, 1, 1, true));
    }

    @Test
    void vulnerabilitiesListVulnerableDependenciesFirstAndMatchAdvisoryIds() {
        DependenciesReport report = new DependenciesReport(
                true,
                3,
                1,
                List.of(),
                null,
                null,
                List.of(
                        dependency("org.example", "safe", List.of()),
                        dependency("org.example", "risky", List.of(vulnerability("GHSA-1", "CVE-2026-1"))),
                        dependency("org.other", "fine", List.of())),
                null,
                null);

        Map<String, Object> first = McpAgentViews.vulnerabilities(report, null, 2);
        assertThat(artifacts(first)).containsExactly("risky", "safe");
        assertThat(first.get("page")).isEqualTo(new PageMetadata(3, 3, 0, 2, 2, true));
        assertThat(first.get("total")).isEqualTo(3);

        assertThat(artifacts(McpAgentViews.vulnerabilities(report, "cve-2026-1", 10)))
                .containsExactly("risky");
        assertThat(artifacts(McpAgentViews.vulnerabilities(report, "org.other", 10)))
                .containsExactly("fine");
    }

    @Test
    void agentStatusSummarizesSensorsAndDetailsTheOneAQueryNames() {
        JavaAgentReport report = agentReport(List.of(sensor("executors"), sensor("network")));

        JavaAgentReport summary = McpAgentViews.agentStatus(report, null);
        assertThat(summary.sensors()).extracting(JavaAgentSensorDto::id).containsExactly("executors", "network");
        assertThat(summary.sensors()).allSatisfy(sensor -> {
            assertThat(sensor.hooks()).isEmpty();
            assertThat(sensor.selfTestSteps()).isEmpty();
            assertThat(sensor.selfTestPassed()).isTrue();
            assertThat(sensor.state()).isEqualTo("INSTALLED");
        });
        assertThat(summary.state()).isEqualTo(JavaAgentReport.ARMED);

        JavaAgentReport detail = McpAgentViews.agentStatus(report, "Network");
        assertThat(detail.sensors()).singleElement().satisfies(sensor -> {
            assertThat(sensor.id()).isEqualTo("network");
            assertThat(sensor.hooks()).hasSize(1);
            assertThat(sensor.selfTestSteps()).containsEntry("connect", "PASSED");
        });
    }

    @Test
    void liveActivityKeepsTheNewestMatchingEntriesAndSaysWhetherMoreMatched() {
        LiveActivityReport report = new LiveActivityReport(
                true,
                List.of(
                        activity("3", "GET /orders/1"),
                        activity("2", "GET /customers"),
                        activity("1", "GET /orders/2")),
                Map.of("REQUEST", 3),
                null,
                List.of(),
                List.of());

        LiveActivityReport newest = McpAgentViews.liveActivity(report, McpAgentViews.ActivityFilter.of(null), 2);
        assertThat(newest.entries()).extracting(ActivityEntryDto::id).containsExactly("3", "2");
        assertThat(newest.pageInfo()).isEqualTo(new ActivityPageInfo(false, null, true));
        assertThat(newest.typeCounts()).containsEntry("REQUEST", 3);

        LiveActivityReport orders = McpAgentViews.liveActivity(report, McpAgentViews.ActivityFilter.of("/ORDERS"), 2);
        assertThat(orders.entries()).extracting(ActivityEntryDto::id).containsExactly("3", "1");
        assertThat(orders.pageInfo().hasMore()).isFalse();

        // A type is applied here too: not every adapter's feed source filters by type.
        assertThat(McpAgentViews.liveActivity(report, McpAgentViews.ActivityFilter.of("SQL"), 2)
                        .entries())
                .isEmpty();
        assertThat(McpAgentViews.liveActivity(report, McpAgentViews.ActivityFilter.of("request"), 5)
                        .entries())
                .hasSize(3);

        assertThat(McpAgentViews.liveActivityFetch(McpAgentViews.ActivityFilter.of(null), 25))
                .isEqualTo(26);
        assertThat(McpAgentViews.liveActivityFetch(McpAgentViews.ActivityFilter.of("SQL"), 25))
                .isEqualTo(JournalActivityReports.MAX_LIMIT);
        assertThat(McpAgentViews.liveActivityFetch(McpAgentViews.ActivityFilter.of("/orders"), 25))
                .isEqualTo(JournalActivityReports.MAX_LIMIT);
        assertThat(McpAgentViews.adapterType(McpAgentViews.ActivityFilter.of("slow")))
                .as("severity and text are applied by the view, to the newest entries")
                .isNull();
        assertThat(McpAgentViews.adapterType(McpAgentViews.ActivityFilter.of("sql")))
                .isEqualTo("SQL");
    }

    @Test
    void aFilteredLiveActivityReadWhoseWindowMissedOlderEntriesSaysSoInsteadOfReportingNoMatch() {
        // A buffers feed answers with its newest 200 entries of 300 retained: the only /old match is older.
        List<ActivityEntryDto> window = new ArrayList<>();
        for (int i = 300; i > 100; i--) {
            window.add(activity(String.valueOf(i), "GET /new"));
        }
        LiveActivityReport report =
                new LiveActivityReport(true, window, Map.of("REQUEST", 300), null, List.of(), List.of());

        LiveActivityReport old = McpAgentViews.liveActivity(report, McpAgentViews.ActivityFilter.of("/old"), 25);

        assertThat(old.entries()).isEmpty();
        assertThat(old.pageInfo().hasMore()).as("not searched is not absent").isTrue();
        assertThat(old.warnings())
                .singleElement()
                .asString()
                .startsWith("Searched the newest 200 of 300 retained entries;");

        LiveActivityReport requests =
                McpAgentViews.liveActivity(report, McpAgentViews.ActivityFilter.of("REQUEST"), 25);
        assertThat(requests.entries()).hasSize(25);
        assertThat(requests.warnings())
                .singleElement()
                .asString()
                .contains("200 of 300 retained entries of type REQUEST");

        LiveActivityReport whole =
                new LiveActivityReport(true, window.subList(0, 10), Map.of("REQUEST", 10), null, List.of(), List.of());
        LiveActivityReport complete = McpAgentViews.liveActivity(whole, McpAgentViews.ActivityFilter.of("/old"), 25);
        assertThat(complete.pageInfo().hasMore()).isFalse();
        assertThat(complete.warnings()).isEmpty();
    }

    @Test
    void liveActivityQueriesSelectATypeOrSeverityByNameAndOtherwiseMatchText() {
        assertThat(McpAgentViews.ActivityFilter.of("sql"))
                .isEqualTo(new McpAgentViews.ActivityFilter("SQL", null, null));
        assertThat(McpAgentViews.ActivityFilter.of("rest-client"))
                .isEqualTo(new McpAgentViews.ActivityFilter("REST_CLIENT", null, null));
        assertThat(McpAgentViews.ActivityFilter.of("Slow"))
                .isEqualTo(new McpAgentViews.ActivityFilter(null, "SLOW", null));
        assertThat(McpAgentViews.ActivityFilter.of(" /orders "))
                .isEqualTo(new McpAgentViews.ActivityFilter(null, null, "/orders"));
        assertThat(McpAgentViews.ActivityFilter.of(null)).isEqualTo(new McpAgentViews.ActivityFilter(null, null, null));
    }

    @Test
    void pagedViewsKeepEveryFieldOfTheReportTheyProject() {
        SqlTraceReport sql =
                new SqlTraceReport(true, null, true, false, 1, 0, 1, List.of(), null, List.of(), List.of(), List.of());
        assertThat(McpAgentViews.sqlTraces(sql, null, 1).keySet())
                .containsExactlyElementsOf(withPage(SqlTraceReport.class));

        DependenciesReport dependencies =
                new DependenciesReport(true, 0, 0, List.of(), null, null, List.of(), null, null);
        assertThat(McpAgentViews.vulnerabilities(dependencies, null, 1).keySet())
                .containsExactlyElementsOf(withPage(DependenciesReport.class));

        CopilotSessionListDto sessions = new CopilotSessionListDto(true, null, null, 0, 0, 0, List.of(), List.of());
        assertThat(McpAgentViews.sessions(sessions, null, 1).keySet())
                .containsExactlyElementsOf(withPage(CopilotSessionListDto.class));

        assertThat(McpAgentViews.startup(new StartupReport(List.of()), null, 1).keySet())
                .containsExactlyElementsOf(withPage(StartupReport.class));
    }

    private static List<String> withPage(Class<? extends Record> type) {
        List<String> names = new ArrayList<>(Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList());
        names.add("page");
        return names;
    }

    private static List<Long> ids(Object entries) {
        return ((List<?>) entries)
                .stream().map(entry -> ((SqlTraceEntryDto) entry).id()).toList();
    }

    private static List<String> messages(Map<String, Object> view) {
        return ((List<?>) view.get("entries"))
                .stream().map(line -> ((LogLineDto) line).message()).toList();
    }

    private static List<String> artifacts(Map<String, Object> view) {
        return ((List<?>) view.get("dependencies"))
                .stream()
                        .map(dependency -> ((DependencyDto) dependency).artifactId())
                        .toList();
    }

    private static ActivityEntryDto activity(String id, String summary) {
        String[] parts = summary.split(" ");
        return new ActivityEntryDto(
                id,
                "REQUEST",
                Long.parseLong(id),
                "OK",
                summary,
                null,
                5L,
                null,
                parts[0],
                parts[1],
                200,
                "main",
                false,
                null,
                null,
                false,
                List.of());
    }

    private static SqlTraceEntryDto sql(long id, String statement) {
        return new SqlTraceEntryDto(
                id,
                1_000 + id,
                statement,
                "SELECT",
                "SELECT",
                10,
                0,
                true,
                null,
                null,
                1,
                "c1",
                "main",
                false,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static CopilotSessionSummary session(String id, String workingDirectory) {
        return new CopilotSessionSummary(
                id, id + ".jsonl", 1L, 2L, "model", workingDirectory, "completed", 3, 1, null, null, 0, "done", false);
    }

    private static DependencyDto dependency(
            String groupId, String artifactId, List<DependencyVulnerabilityDto> vulnerabilities) {
        return new DependencyDto(
                groupId,
                artifactId,
                "1.0",
                null,
                "maven",
                vulnerabilities.size(),
                vulnerabilities.isEmpty() ? null : "HIGH",
                vulnerabilities,
                null,
                null);
    }

    private static DependencyVulnerabilityDto vulnerability(String id, String alias) {
        return new DependencyVulnerabilityDto(
                id,
                "summary",
                "details",
                "HIGH",
                8.0,
                List.of(alias),
                List.of(),
                List.of(),
                false,
                null,
                null,
                false,
                List.of(),
                null,
                null);
    }

    private static JavaAgentSensorDto sensor(String id) {
        return new JavaAgentSensorDto(
                id,
                "INSTALLED",
                true,
                3,
                List.of(),
                5L,
                3L,
                2L,
                0L,
                true,
                null,
                Map.of("connect", "PASSED"),
                List.of(new JavaAgentHookDto(id + ".hook", "advice", "java.net.Socket", true, true, "PASSED", 4)),
                0,
                0,
                3,
                0,
                null,
                null,
                null);
    }

    private static JavaAgentReport agentReport(List<JavaAgentSensorDto> sensors) {
        return new JavaAgentReport(
                JavaAgentReport.ARMED,
                null,
                "2.0.0",
                "2.0.0",
                1,
                1,
                "21",
                "javaagent",
                "/tmp/bootui-agent.jar",
                10L,
                null,
                null,
                sensors,
                List.of(),
                null,
                null,
                List.of("BootUI agent 2.0.0 attached (javaagent)"),
                List.of(),
                null);
    }
}
