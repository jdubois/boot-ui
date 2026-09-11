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

    @Test
    void vulnerabilityGuidanceExplainsOutboundDataAndEvidenceLimitsOnBothAdapters() {
        assertThat(List.of(
                        McpToolDescriptions.spring("vulnerabilities_scan"),
                        McpToolDescriptions.quarkus("vulnerabilities_scan")))
                .allSatisfy(description -> assertThat(description)
                        .contains("package names/versions", "FIRST", "only with approval")
                        .contains("`scan.status`", "`scan.message`", "`coverage`", "`scan.packagesSkipped`")
                        .contains("not verified installable upgrades", "not a combined"));
        assertThat(List.of(
                        McpToolDescriptions.spring("get_vulnerabilities_report"),
                        McpToolDescriptions.quarkus("get_vulnerabilities_report")))
                .allSatisfy(description ->
                        assertThat(description).contains("without contacting", "UNKNOWN severity is not zero risk"));
    }

    private static void assertDescriptions(Set<String> names, Function<String, String> descriptionProvider) {
        assertThat(names).isNotEmpty();
        assertThat(names)
                .allSatisfy(name -> assertThat(descriptionProvider.apply(name))
                        .as(name)
                        .hasSizeGreaterThan(60)
                        .endsWith("."));
    }

    @Test
    void explorerGuidanceDistinguishesCanonicalActivityFromOptionalDetail() {
        assertThat(McpToolDescriptions.spring("get_explorer"))
                .contains("Read-only", "off by default", "restart", "All ten", "source policy", "canonical event id");
        assertThat(McpToolDescriptions.spring("get_explorer_event"))
                .contains("not a trace id", "expired", "not table health", "not extra exception", "no keys or values");
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
