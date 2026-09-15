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
        assertThat(McpToolCatalog.entries()).hasSize(89);
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_MVC)).hasSize(89);
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX)).hasSize(88);
        assertThat(McpToolCatalog.namesFor(Stack.QUARKUS)).hasSize(73);
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
}
