package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.databaseadvisor.DatabaseAdvisorController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpTools;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.security.SecurityController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog.Stack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins each Spring registry to {@link McpToolCatalog}.
 *
 * <p>The catalog is what the {@code bootui} CLI command tree and the {@code /bootui/api/cli} facade are
 * generated from, so a tool that exists in a registry but not in the catalog — or vice versa — would silently
 * be unreachable from the CLI. These tests make that condition a build failure instead.
 */
class McpToolCatalogParityTests {

    @Test
    void bothSpringStacksForwardEveryAdvisorPageArgumentToTheirNativeController() {
        for (Class<?> registry : List.of(BootUiMcpTools.class, ReactiveBootUiMcpTools.class)) {
            Map<String, Class<?>> controllerTypes = Map.of(
                    "architecture", ArchitectureController.class,
                    "hibernate", HibernateController.class,
                    "spring", SpringController.class,
                    "rest_api", RestApiController.class,
                    "memory", MemoryController.class,
                    "security",
                            registry == BootUiMcpTools.class
                                    ? SecurityController.class
                                    : ReactiveSecurityController.class,
                    "database_advisor", DatabaseAdvisorController.class);
            Map<Class<?>, Object> controllers = new LinkedHashMap<>();
            controllerTypes.values().forEach(type -> controllers.put(type, mock(type)));
            List<McpTool> tools = McpToolsRegistryFixture.maximalRegistry(registry, "tools", controllers);
            controllerTypes.forEach((advisor, controllerType) -> {
                tools.stream()
                        .filter(tool -> tool.name().equals("get_" + advisor + "_rule_violations"))
                        .findFirst()
                        .orElseThrow()
                        .invoke(new McpArguments(null, 7, "RULE-1", "scan-1", 22));
                assertThat(mockingDetails(controllers.get(controllerType)).getInvocations())
                        .as("%s %s controller invocation", registry.getSimpleName(), advisor)
                        .singleElement()
                        .satisfies(invocation -> {
                            assertThat(invocation.getMethod().getName()).isEqualTo("ruleViolations");
                            assertThat(invocation.getArguments()).containsExactly("RULE-1", "scan-1", 22, 7);
                        });
            });
        }
    }

    @Test
    void springMvcRegistryAdvertisesExactlyTheCatalog() {
        assertThat(names(springMvcTools()))
                .containsExactlyInAnyOrderElementsOf(McpToolCatalog.namesFor(Stack.SPRING_MVC));
    }

    @Test
    void reactiveRegistryAdvertisesExactlyTheCatalog() {
        assertThat(names(reactiveTools()))
                .containsExactlyInAnyOrderElementsOf(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX));
    }

    @Test
    void springMvcRegistryMatchesTheCatalogSchemaPanelAndActionKind() {
        assertCatalogShape(springMvcTools(), Stack.SPRING_MVC);
    }

    @Test
    void reactiveRegistryMatchesTheCatalogSchemaPanelAndActionKind() {
        assertCatalogShape(reactiveTools(), Stack.SPRING_WEBFLUX);
    }

    private static void assertCatalogShape(List<McpTool> tools, Stack stack) {
        assertThat(tools).isNotEmpty();
        assertThat(tools).allSatisfy(tool -> {
            McpToolCatalog.Entry entry = McpToolCatalog.require(tool.name(), stack);
            assertThat(tool.schema()).as("%s schema", tool.name()).isEqualTo(entry.schema());
            assertThat(tool.panelId()).as("%s panel", tool.name()).isEqualTo(entry.panelId());
            assertThat(tool.action()).as("%s action", tool.name()).isEqualTo(entry.action());
        });
    }

    private static List<McpTool> springMvcTools() {
        return McpToolsRegistryFixture.maximalRegistry(BootUiMcpTools.class, "tools");
    }

    private static List<McpTool> reactiveTools() {
        return McpToolsRegistryFixture.maximalRegistry(ReactiveBootUiMcpTools.class, "tools");
    }

    private static List<String> names(List<McpTool> tools) {
        return tools.stream().map(McpTool::name).toList();
    }
}
