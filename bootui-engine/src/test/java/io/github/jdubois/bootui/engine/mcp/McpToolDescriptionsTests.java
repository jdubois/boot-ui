package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class McpToolDescriptionsTests {

    @Test
    void everySpringToolHasAgentOrientedGuidance() {
        assertDescriptions(McpToolCatalog.namesFor(McpToolCatalog.Stack.SPRING_MVC), McpToolDescriptions::spring);
    }

    @Test
    void everyReactiveToolHasAgentOrientedGuidance() {
        assertDescriptions(McpToolCatalog.namesFor(McpToolCatalog.Stack.SPRING_WEBFLUX), McpToolDescriptions::spring);
    }

    @Test
    void everyQuarkusToolHasAgentOrientedGuidance() {
        assertDescriptions(McpToolCatalog.namesFor(McpToolCatalog.Stack.QUARKUS), McpToolDescriptions::quarkus);
    }

    private static void assertDescriptions(Set<String> names, Function<String, String> descriptionProvider) {
        assertThat(names).isNotEmpty();
        assertThat(names)
                .allSatisfy(name -> assertThat(descriptionProvider.apply(name))
                        .as(name)
                        .hasSizeGreaterThan(60)
                        .endsWith("."));
    }

    /**
     * The Configuration search guidance is the one description agents were observed to misread: it must state the
     * relaxed-binding rule rather than merely name it, and it must keep {@code total} (every property, before
     * filtering) apart from {@code matched} (the query hits), so a large {@code total} beside {@code matched: 0}
     * is not read as a missing property.
     */
    @Test
    void configSearchDescriptionStatesTheMatchingRuleAndThePagingCounts() {
        assertThat(List.of(McpToolDescriptions.spring("get_config"), McpToolDescriptions.quarkus("get_config")))
                .allSatisfy(description -> assertThat(description)
                        .contains("ignores case")
                        .contains("`_` and `-` as `.`")
                        .contains("BOOTUI_MCP_ENABLED")
                        .contains("Values are matched literally")
                        .contains("`total` counts every property before filtering")
                        .contains("`matched` counts the query hits")
                        .contains("not that the property is absent"));
    }
}
