package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.explorer.ExplorerController;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ExplorerMcpToolsTests {

    @Test
    void optionalControllerBindsBothReadsToTheCanonicalService() {
        ExplorerController controller = mock(ExplorerController.class);
        BootUiMcpTools registry = registry(controller);
        assertThat(registry.tools()).extracting(McpTool::name).containsExactly("get_explorer", "get_explorer_event");
        assertThat(registry.tools())
                .allSatisfy(tool -> assertThat(tool.action()).isFalse());
        registry.tools().get(0).invoke(McpArguments.normalize(null, 999, null, 7));
        registry.tools().get(1).invoke(McpArguments.normalize(null, null, " cache-17 ", 7));
        verify(controller).report(null, null, 0, 7, null, null, null, 0);
        verify(controller).event("cache-17");
    }

    @Test
    void absentControllerDoesNotAdvertiseExplorer() {
        BootUiMcpTools registry = new BootUiMcpTools(List.of());
        registry.addExplorerTools(new StaticListableBeanFactory().getBeanProvider(ExplorerController.class));
        assertThat(registry.tools()).isEmpty();
    }

    @Test
    void controllerSourcePolicyFailureIsNotSwallowedByHeadlessBinding() {
        ExplorerController controller = mock(ExplorerController.class);
        when(controller.event("cache-17"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Live Activity is disabled"));
        BootUiMcpTools registry = registry(controller);
        assertThatThrownBy(() -> registry.tools().get(1).invoke(new McpArguments(null, 7, "cache-17")))
                .isInstanceOf(McpToolClientException.class)
                .hasMessageContaining("Live Activity is disabled");
    }

    private BootUiMcpTools registry(ExplorerController controller) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("explorer", controller);
        BootUiMcpTools registry = new BootUiMcpTools(List.of());
        registry.addExplorerTools(factory.getBeanProvider(ExplorerController.class));
        return registry;
    }
}
