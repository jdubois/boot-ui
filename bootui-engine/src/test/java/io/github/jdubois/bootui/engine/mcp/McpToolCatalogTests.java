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
        assertThat(McpToolCatalog.entries()).hasSize(80);
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_MVC)).hasSize(80);
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX)).hasSize(77);
        assertThat(McpToolCatalog.namesFor(Stack.QUARKUS)).hasSize(62);
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
    void explorerReadsAreMvcOnlyAndUseCanonicalEventIdentity() {
        assertThat(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX)).doesNotContain("get_explorer", "get_explorer_event");
        assertThat(McpToolCatalog.namesFor(Stack.QUARKUS)).doesNotContain("get_explorer", "get_explorer_event");
        for (String name : List.of("get_explorer", "get_explorer_event")) {
            McpToolCatalog.Entry entry = McpToolCatalog.require(name, Stack.SPRING_MVC);
            assertThat(entry.panelId()).isEqualTo(BootUiPanels.EXPLORER);
            assertThat(entry.action()).isFalse();
        }
        assertThat(McpToolCatalog.require("get_explorer", Stack.SPRING_MVC).schema())
                .isEqualTo(McpToolSchema.LIMIT);
        assertThat(McpToolCatalog.require("get_explorer_event", Stack.SPRING_MVC)
                        .schema())
                .isEqualTo(McpToolSchema.ID);
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
