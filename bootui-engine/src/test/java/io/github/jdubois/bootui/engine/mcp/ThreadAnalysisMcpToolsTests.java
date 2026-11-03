package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.threads.ThreadAnalysisException;
import io.github.jdubois.bootui.engine.threads.ThreadAnalysisService;
import org.junit.jupiter.api.Test;

class ThreadAnalysisMcpToolsTests {
    @Test
    void highTransportCapsRemainWithinTheEngineLimitIncludingCancellation() {
        var service = mock(ThreadAnalysisService.class);
        var tools = ThreadAnalysisMcpTools.tools(service, McpToolCatalog.Stack.SPRING_MVC);
        for (String name : java.util.List.of(
                "get_thread_snapshots", "get_thread_analysis", "get_thread_observation", "cancel_thread_observation")) {
            tools.stream()
                    .filter(tool -> tool.name().equals(name))
                    .findFirst()
                    .orElseThrow()
                    .invoke(new McpArguments(null, 2000, "selected"));
            assertThat(McpToolCatalog.defaultLimit(name)).isBetween(1, 25);
        }
        verify(service).list(1000);
        verify(service).analysis("selected", 0, 1000);
        verify(service, org.mockito.Mockito.times(2)).observation("selected", 0, 1000);
        verify(service).cancel("selected");
    }

    @Test
    void everyStackUsesTheSameEightCataloguedHandlersAndPreservesTheExistingLiveTool() {
        var service = mock(ThreadAnalysisService.class);
        for (var stack : McpToolCatalog.Stack.values()) {
            var tools = ThreadAnalysisMcpTools.tools(service, stack);
            assertThat(tools).hasSize(8);
            assertThat(tools).filteredOn(McpTool::action).hasSize(4);
            for (var tool : tools) {
                var entry = McpToolCatalog.require(tool.name(), stack);
                assertThat(tool.schema()).isEqualTo(entry.schema());
                assertThat(tool.panelId()).isEqualTo(entry.panelId());
                assertThat(tool.description()).isNotBlank();
            }
            assertThat(McpToolCatalog.require("get_threads", stack).schema()).isEqualTo(McpToolSchema.QUERY_LIMIT);
        }
    }

    @Test
    void retainedReadsUseTheNormalizedResultCapAndTranslateExpectedFailures() {
        var service = mock(ThreadAnalysisService.class);
        var tools = ThreadAnalysisMcpTools.tools(service, McpToolCatalog.Stack.SPRING_MVC);
        var analysis = tools.stream()
                .filter(tool -> tool.name().equals("get_thread_analysis"))
                .findFirst()
                .orElseThrow();
        analysis.invoke(new McpArguments(null, 2, null));
        verify(service).analysis(null, 0, 2);
        when(service.analysis("missing", 0, 2)).thenThrow(new ThreadAnalysisException(409, "Expired evidence."));
        assertThatThrownBy(() -> analysis.invoke(new McpArguments(null, 2, "missing")))
                .isInstanceOfSatisfying(
                        McpToolClientException.class,
                        failure -> assertThat(failure.status()).isEqualTo(409))
                .hasMessage("Expired evidence.");
        var serverFailure = new ThreadAnalysisException(503, "Collection unavailable.");
        when(service.analysis("unavailable", 0, 2)).thenThrow(serverFailure);
        assertThatThrownBy(() -> analysis.invoke(new McpArguments(null, 2, "unavailable")))
                .isSameAs(serverFailure);
    }
}
