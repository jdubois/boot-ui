package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.jdubois.bootui.engine.mcp.McpToolCatalog.Stack;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolCatalogTests {

    @Test
    void advertisesTheFullToolSurfacePerStack() {
        assertThat(McpToolCatalog.entries()).hasSize(105);
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_MVC)).hasSize(105);
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX)).hasSize(104);
        assertThat(McpToolCatalog.namesFor(Stack.QUARKUS)).hasSize(89);
    }

    @Test
    void hibernateStatisticsAndWebSocketsArePassiveReadsOnEveryStackOnAnExistingSchema() {
        // The published CLI binds options by schema name, so both reuse NONE and older CLIs keep working.
        assertThat(McpToolCatalog.byName("get_hibernate_statistics").orElseThrow())
                .isEqualTo(new McpToolCatalog.Entry(
                        "get_hibernate_statistics",
                        McpToolSchema.NONE,
                        BootUiPanels.HIBERNATE_STATISTICS,
                        false,
                        Set.of(Stack.values())));
        assertThat(McpToolCatalog.byName("get_websockets").orElseThrow())
                .isEqualTo(new McpToolCatalog.Entry(
                        "get_websockets", McpToolSchema.NONE, BootUiPanels.WEBSOCKETS, false, Set.of(Stack.values())));
    }

    @Test
    void sideEffectsIsAReadOnTheExistingQueryLimitSchemaOnEveryStackWithACompactDefault() {
        McpToolCatalog.Entry sideEffects =
                McpToolCatalog.byName("get_side_effects").orElseThrow();

        // The published CLI binds options by schema name, so the tool reuses the existing QUERY_LIMIT schema.
        assertThat(sideEffects.schema()).isEqualTo(McpToolSchema.QUERY_LIMIT);
        assertThat(sideEffects.action()).isFalse();
        assertThat(sideEffects.panelId()).isEqualTo(BootUiPanels.SIDE_EFFECTS);
        assertThat(McpToolCatalog.namesFor(Stack.QUARKUS)).contains("get_side_effects");
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX)).contains("get_side_effects");
        assertThat(McpToolCatalog.defaultLimit("get_side_effects"))
                .isEqualTo(io.github.jdubois.bootui.core.dto.SideEffectsAgentReport.DEFAULT_LIMIT);
    }

    @Test
    void largeReadsAreAgentSizedOnTheExistingQueryLimitSchema() {
        // Widened from NONE (LIMIT for live activity): a 1.x CLI still sends only the arguments its manifest knows,
        // and every one of them is still accepted, so it keeps working and gets the short default page.
        for (String name : List.of(
                "get_sql_traces",
                "get_startup_timeline",
                "get_log_tail",
                "get_copilot_sessions",
                "get_claude_code_sessions",
                "get_vulnerabilities_report",
                "get_live_activity")) {
            McpToolCatalog.Entry entry = McpToolCatalog.byName(name).orElseThrow();
            assertThat(entry.schema()).as(name).isEqualTo(McpToolSchema.QUERY_LIMIT);
            assertThat(entry.action()).as(name).isFalse();
        }
        // The agent status lists every matching sensor, so it takes a query but no limit; the limit a 1.x CLI may
        // still send is tolerated and ignored rather than rejected.
        McpToolCatalog.Entry agentStatus =
                McpToolCatalog.byName("get_agent_status").orElseThrow();
        assertThat(agentStatus.schema()).isEqualTo(McpToolSchema.QUERY);
        assertThat(agentStatus.schema().ignoredArgumentNames()).containsExactly("limit");
        assertThat(McpToolCatalog.defaultLimit("get_agent_status")).isNull();
        assertThat(McpToolCatalog.defaultLimit("get_sql_traces")).isEqualTo(20);
        assertThat(McpToolCatalog.defaultLimit("get_startup_timeline")).isEqualTo(25);
        assertThat(McpToolCatalog.defaultLimit("get_log_tail")).isEqualTo(50);
        assertThat(McpToolCatalog.defaultLimit("get_copilot_sessions")).isEqualTo(10);
        assertThat(McpToolCatalog.defaultLimit("get_claude_code_sessions")).isEqualTo(10);
        assertThat(McpToolCatalog.defaultLimit("get_vulnerabilities_report")).isEqualTo(10);
        assertThat(McpToolCatalog.defaultLimit("get_live_activity")).isEqualTo(25);
        for (String inventory : List.of(
                "get_http_exchanges", "get_beans", "get_metrics", "get_conditions", "get_config", "get_threads")) {
            assertThat(McpToolCatalog.defaultLimit(inventory)).as(inventory).isEqualTo(25);
        }
        // Agent status lists every sensor: the summary is small, and a query narrows it to one sensor's hooks.
        assertThat(McpToolCatalog.defaultLimit("get_agent_status")).isNull();
    }

    @Test
    void codeInventoryIsAReadOnTheExistingQueryLimitSchemaOnEveryStackWithACompactDefault() {
        McpToolCatalog.Entry inventory =
                McpToolCatalog.byName("get_code_inventory").orElseThrow();

        // The published CLI binds options by schema name, so the tool reuses the existing QUERY_LIMIT schema.
        assertThat(inventory.schema()).isEqualTo(McpToolSchema.QUERY_LIMIT);
        assertThat(inventory.action()).isFalse();
        assertThat(inventory.panelId()).isEqualTo(BootUiPanels.CODE_INVENTORY);
        assertThat(inventory.stacks()).containsExactlyInAnyOrder(Stack.values());
        assertThat(McpToolCatalog.defaultLimit("get_code_inventory")).isEqualTo(25);
    }

    @Test
    void codePathsIsAReadOnTheExistingQueryLimitSchemaOnEveryStackWithACompactDefault() {
        McpToolCatalog.Entry codePaths = McpToolCatalog.byName("get_code_paths").orElseThrow();

        // The published CLI binds options by schema name, so the tool reuses the existing QUERY_LIMIT schema.
        assertThat(codePaths.schema()).isEqualTo(McpToolSchema.QUERY_LIMIT);
        assertThat(codePaths.action()).isFalse();
        assertThat(codePaths.panelId()).isEqualTo(BootUiPanels.CODE_PATHS);
        assertThat(codePaths.stacks()).containsExactlyInAnyOrder(Stack.values());
        assertThat(McpToolCatalog.defaultLimit("get_code_paths")).isEqualTo(10);
    }

    @Test
    void methodProbesAreACodePathsActionAndReadOnTheExistingIdSchemaOnEveryStack() {
        McpToolCatalog.Entry start = McpToolCatalog.byName("start_method_probe").orElseThrow();
        McpToolCatalog.Entry read = McpToolCatalog.byName("get_method_probe").orElseThrow();

        // D24: an action, so read-only policy refuses it; the CLI binds options by schema name.
        assertThat(start.schema()).isEqualTo(McpToolSchema.ID);
        assertThat(start.action()).isTrue();
        assertThat(start.panelId()).isEqualTo(BootUiPanels.CODE_PATHS);
        assertThat(start.stacks()).containsExactlyInAnyOrder(Stack.values());
        assertThat(read.schema()).isEqualTo(McpToolSchema.ID);
        assertThat(read.action()).isFalse();
        assertThat(read.panelId()).isEqualTo(BootUiPanels.CODE_PATHS);
        assertThat(read.stacks()).containsExactlyInAnyOrder(Stack.values());
    }

    @Test
    void agentSensorSwitchesAreJavaAgentActionsOnEveryStack() {
        for (String name : List.of("enable_agent_sensor", "disable_agent_sensor")) {
            McpToolCatalog.Entry entry = McpToolCatalog.byName(name).orElseThrow();
            assertThat(entry.schema()).isEqualTo(McpToolSchema.ID);
            assertThat(entry.action()).isTrue();
            assertThat(entry.panelId()).isEqualTo(BootUiPanels.JAVA_AGENT);
            assertThat(entry.stacks()).containsExactlyInAnyOrder(Stack.values());
        }
    }

    @Test
    void requestProfileIsAnActivityReadOnTheExistingIdSchemaOnEveryStack() {
        McpToolCatalog.Entry profile =
                McpToolCatalog.byName("get_request_profile").orElseThrow();
        McpToolCatalog.Entry activity =
                McpToolCatalog.byName("get_live_activity").orElseThrow();

        // The published CLI binds options by schema name, so the tool reuses the existing ID schema.
        assertThat(profile.schema()).isEqualTo(McpToolSchema.ID);
        assertThat(profile.action()).isFalse();
        assertThat(profile.panelId()).isEqualTo(BootUiPanels.ACTIVITY);
        assertThat(profile.stacks()).isEqualTo(activity.stacks()).containsExactlyInAnyOrder(Stack.values());
    }

    @Test
    void httpRouteRankingsAreAReadOnTheExistingLimitSchemaOnEveryStack() {
        McpToolCatalog.Entry routes = McpToolCatalog.byName("get_http_routes").orElseThrow();
        McpToolCatalog.Entry exchanges =
                McpToolCatalog.byName("get_http_exchanges").orElseThrow();

        // The published CLI binds options by schema name, so a new tool must reuse an existing schema.
        assertThat(routes.schema()).isEqualTo(McpToolSchema.LIMIT);
        assertThat(routes.action()).isFalse();
        assertThat(routes.panelId()).isEqualTo(BootUiPanels.HTTP_EXCHANGES);
        assertThat(routes.stacks()).isEqualTo(exchanges.stacks()).containsExactlyInAnyOrder(Stack.values());
    }

    @Test
    void mysqlCachedReadAndActionShareThePanelOnEveryStack() {
        for (Stack stack : Stack.values()) {
            var read = McpToolCatalog.require("get_mysql_report", stack);
            var action = McpToolCatalog.require("mysql_read", stack);
            assertThat(read.panelId()).isEqualTo(BootUiPanels.MYSQL);
            assertThat(action.panelId()).isEqualTo(read.panelId());
            assertThat(read.action()).isFalse();
            assertThat(action.action()).isTrue();
            assertThat(read.schema()).isEqualTo(McpToolSchema.NONE);
            assertThat(action.schema()).isEqualTo(McpToolSchema.NONE);
        }
    }

    @Test
    void everyAdvisorDetailToolIsAReadOnTheSameStacksAndPanelAsItsReport() {
        for (String advisor :
                List.of("architecture", "hibernate", "spring", "rest_api", "memory", "security", "database_advisor")) {
            McpToolCatalog.Entry details =
                    McpToolCatalog.byName("get_" + advisor + "_rule_violations").orElseThrow();
            McpToolCatalog.Entry report =
                    McpToolCatalog.byName("get_" + advisor + "_report").orElseThrow();
            assertThat(details.schema()).isEqualTo(McpToolSchema.RULE_VIOLATIONS);
            assertThat(details.action()).isFalse();
            assertThat(details.panelId()).isEqualTo(report.panelId());
            assertThat(details.stacks()).isEqualTo(report.stacks());
        }
    }

    @Test
    void springMvcIsTheCompleteReferenceStack() {
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_MVC)).isEqualTo(McpToolCatalog.names());
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_MVC)).containsAll(McpToolCatalog.namesFor(Stack.QUARKUS));
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_MVC))
                .containsAll(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX));
    }

    @Test
    void servletOnlyHttpSessionsToolIsNotAdvertisedByTheReactiveStack() {
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX)).doesNotContain("get_http_sessions");
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_MVC)).contains("get_http_sessions");
    }

    @Test
    void toolNamesAreUniqueAndMachineReadable() {
        List<String> names = McpToolCatalog.entries().stream()
                .map(McpToolCatalog.Entry::name)
                .toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).allSatisfy(name -> assertThat(name).matches("[a-z][a-z0-9_]*"));
    }

    @Test
    void everyEntryBindsToARealPanelAndActionsRequireAnActionCapablePanel() {
        assertThat(McpToolCatalog.entries()).allSatisfy(entry -> {
            BootUiPanels.Panel panel = BootUiPanels.byId(entry.panelId()).orElseThrow();
            if (entry.action()) {
                assertThat(panel.actionCapable()).as(entry.name()).isTrue();
            }
        });
    }

    @Test
    void byNameResolvesKnownToolsOnly() {
        assertThat(McpToolCatalog.byName("get_beans")).isPresent();
        assertThat(McpToolCatalog.byName("no_such_tool")).isEmpty();
    }

    @Test
    void requireRejectsUnknownToolsAndToolsAbsentFromTheStack() {
        assertThat(McpToolCatalog.require("get_http_sessions", Stack.SPRING_MVC).panelId())
                .isEqualTo(BootUiPanels.HTTP_SESSIONS);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpToolCatalog.require("no_such_tool", Stack.SPRING_MVC))
                .withMessageContaining("Unknown BootUI MCP tool");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpToolCatalog.require("get_http_sessions", Stack.SPRING_WEBFLUX))
                .withMessageContaining("not advertised by stack");
    }

    @Test
    void schemasCoverTheArgumentShapesTheCliProjectsOnto() {
        assertThat(McpToolCatalog.require("get_http_sessions", Stack.SPRING_MVC).schema())
                .isEqualTo(McpToolSchema.NONE);
        assertThat(McpToolCatalog.require("architecture_scan", Stack.SPRING_MVC).action())
                .isTrue();
        assertThat(McpToolCatalog.require("get_traces", Stack.SPRING_MVC).schema())
                .isEqualTo(McpToolSchema.LIMIT);
        assertThat(McpToolCatalog.require("get_beans", Stack.SPRING_MVC).schema())
                .isEqualTo(McpToolSchema.QUERY_LIMIT);
        assertThat(McpToolCatalog.require("get_exception_detail", Stack.SPRING_MVC)
                        .schema())
                .isEqualTo(McpToolSchema.ID);
    }

    @Test
    void entryRejectsAnUnknownPanel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new McpToolCatalog.Entry(
                        "bogus_tool", McpToolSchema.NONE, "no-such-panel", false, Set.of(Stack.SPRING_MVC)))
                .withMessageContaining("Unknown BootUI panel id");
    }

    @Test
    void entryRejectsAToolNoStackAdvertises() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new McpToolCatalog.Entry("bogus_tool", McpToolSchema.NONE, BootUiPanels.BEANS, false, Set.of()))
                .withMessageContaining("at least one stack");
    }

    @Test
    void onlyToolsWithMeasuredPhasesReportProgress() {
        assertThat(McpToolCatalog.reportsProgress("architecture_scan")).isTrue();
        assertThat(McpToolCatalog.reportsProgress("vulnerabilities_scan")).isTrue();
        assertThat(McpToolCatalog.reportsProgress("memory_scan"))
                .as("a GC and a histogram are not measured units")
                .isFalse();
        assertThat(McpToolCatalog.reportsProgress("get_overview")).isFalse();
        assertThat(McpToolCatalog.reportsProgress("unknown_tool")).isFalse();
        assertThat(McpToolCatalog.names()).contains("architecture_scan");
    }
}
