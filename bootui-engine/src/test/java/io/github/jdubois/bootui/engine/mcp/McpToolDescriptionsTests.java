package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class McpToolDescriptionsTests {

    @Test
    void mysqlGuidanceDistinguishesActiveWorkScopeAndMissingEvidence() {
        for (Function<String, String> descriptions :
                List.<Function<String, String>>of(McpToolDescriptions::spring, McpToolDescriptions::quarkus)) {
            assertThat(descriptions.apply("mysql_read"))
                    .contains(
                            "bounded",
                            "read-only",
                            "scope",
                            "server-wide",
                            "exact decimal strings",
                            "permission",
                            "instrumentation",
                            "timeout",
                            "raw session/sample SQL");
            assertThat(descriptions.apply("get_mysql_report"))
                    .contains("without contacting", "NOT_READ", "exposure-policy", "readAt", "approved");
        }
    }

    @Test
    void advisorDescriptionsDistinguishSamplesRetentionAndCachedPagination() {
        for (String advisor :
                List.of("architecture", "hibernate", "spring", "rest_api", "memory", "security", "database_advisor")) {
            for (Function<String, String> provider :
                    List.<Function<String, String>>of(McpToolDescriptions::spring, McpToolDescriptions::quarkus)) {
                assertThat(provider.apply("get_" + advisor + "_report"))
                        .contains(
                                "sampleViolations",
                                "violationDetails.scanId",
                                "get_" + advisor + "_rule_violations",
                                "truncated",
                                "Verify each finding");
                assertThat(provider.apply(advisor + "_scan"))
                        .contains("bounded previews", "page cached retained", "retention overflow");
                assertThat(provider.apply("get_" + advisor + "_rule_violations"))
                        .contains(
                                "page.hasMore",
                                "violationCount",
                                "404",
                                "409",
                                "cached report, not a new scan",
                                "-32003",
                                "same scanId and offset",
                                "smaller limit");
            }
        }
        assertThat(McpToolDescriptions.spring("get_spring_report")).contains("up to 10");
        assertThat(McpToolDescriptions.quarkus("get_spring_report")).contains("up to 20");
        assertThat(McpToolDescriptions.quarkus("get_security_report")).contains("up to 20");
    }

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

    /**
     * The PostgreSQL panel reports an incompletely read section as {@code AVAILABLE} with a non-null
     * {@code reason}, not as {@code SKIPPED}. An agent that branches on {@code status} alone therefore reads a
     * partial section as complete evidence, so the description must state the rule rather than imply that
     * unreadable evidence always changes the status.
     */
    @Test
    void postgresqlReadDescriptionSeparatesAPartiallyReadSectionFromAnUnreadOne() {
        assertThat(List.of(
                        McpToolDescriptions.spring("postgresql_read"), McpToolDescriptions.quarkus("postgresql_read")))
                .allSatisfy(description -> assertThat(description)
                        .contains("stays `AVAILABLE` and carries a non-null `reason`")
                        .contains("`status` alone does not mean complete")
                        .contains("`SKIPPED` and `FAILED`")
                        .contains("`hint` is a methodology caveat")
                        .contains("`truncated`")
                        .contains("Budget exhaustion is reported in `reason`, not as row-cap truncation")
                        .contains("`replication.replicasAvailable` is true")
                        .contains("`PARTIAL`")
                        .doesNotContain("reported as skipped with its reason"));
        assertThat(List.of(
                        McpToolDescriptions.spring("get_postgresql_report"),
                        McpToolDescriptions.quarkus("get_postgresql_report")))
                .allSatisfy(description -> assertThat(description)
                        .contains("`NOT_READ`")
                        .contains("rather than that nothing is wrong")
                        .contains("`replication.replicasAvailable` is true"));
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
