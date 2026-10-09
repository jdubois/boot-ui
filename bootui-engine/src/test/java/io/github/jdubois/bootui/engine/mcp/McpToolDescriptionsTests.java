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
    void agentSensorActionsExplainEvidenceCostAndApprovalOnEveryStack() {
        for (Function<String, String> descriptions :
                List.<Function<String, String>>of(McpToolDescriptions::spring, McpToolDescriptions::quarkus)) {
            for (String tool : List.of("enable_agent_sensor", "disable_agent_sensor")) {
                assertThat(descriptions.apply(tool))
                        .as(tool)
                        .contains(
                                "user's authorization",
                                "get_agent_status",
                                "get_side_effects",
                                "thread-activity",
                                "java.lang.Thread",
                                "6.8%",
                                "11.5%",
                                "thread-locals",
                                "security-sinks",
                                "redacted",
                                "request-values=true",
                                "not persisted",
                                "run comparisons");
            }
        }
    }

    @Test
    void onlyLocatedAdvisorsAdvertiseStructuredViolationLocations() {
        for (Function<String, String> provider :
                List.<Function<String, String>>of(McpToolDescriptions::spring, McpToolDescriptions::quarkus)) {
            for (String advisor : List.of("architecture", "rest_api", "hibernate")) {
                assertThat(provider.apply("get_" + advisor + "_report"))
                        .contains("sampleLocations", "sourcePath", "LINE, MEMBER or CLASS", "locationNotes");
                assertThat(provider.apply("get_" + advisor + "_rule_violations"))
                        .contains("locations list aligns index-for-index with violations");
            }
            for (String advisor : List.of("spring", "memory", "security", "database_advisor")) {
                assertThat(provider.apply("get_" + advisor + "_report")).doesNotContain("sampleLocations");
            }
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
                assertThat(provider.apply("get_" + advisor + "_report"))
                        .contains("bounded previews", "page cached retained", "retention overflow");
                // A scan answers with a summary, so it names the tools that hold the rest instead of their guidance.
                assertThat(provider.apply(advisor + "_scan"))
                        .contains(
                                "compact summary, not the report",
                                "topFindings",
                                "violationDetails.scanId",
                                "get_" + advisor + "_report",
                                "get_" + advisor + "_rule_violations")
                        .doesNotContain("bounded previews");
                assertThat(provider.apply("get_" + advisor + "_rule_violations"))
                        .contains(
                                "page.hasMore",
                                "violationCount",
                                "tool error",
                                "unknown rule",
                                "no findings",
                                "stale or missing scanId",
                                "rather than start a new scan",
                                "-32003",
                                "same scanId and offset",
                                "smaller limit")
                        .as("an MCP tool error carries no HTTP status")
                        .doesNotContain("404", "409");
            }
        }
        assertThat(McpToolDescriptions.spring("get_hibernate_report"))
                .contains("PARTIAL", "diagnostics", "coverageNote");
        assertThat(McpToolDescriptions.quarkus("get_hibernate_report"))
                .contains("PARTIAL", "diagnostics", "coverageNote");
        assertThat(McpToolDescriptions.spring("architecture_scan")).doesNotContain("coverageNote");
        assertThat(McpToolDescriptions.spring("get_spring_report")).contains("up to 10");
        assertThat(McpToolDescriptions.quarkus("get_spring_report")).contains("up to 20");
        assertThat(McpToolDescriptions.quarkus("get_security_report")).contains("up to 20");
    }

    @Test
    void springNamedAdvisorToolsSayTheyRunTheQuarkusApplicationAdvisorOnQuarkus() {
        for (String name : List.of("spring_scan", "get_spring_report", "get_spring_rule_violations")) {
            assertThat(McpToolDescriptions.quarkus(name))
                    .as(name)
                    .contains("Quarkus application advisor", "keeps its Spring name");
            // The CLI manifest is generated from the Spring descriptions and serves every stack.
            assertThat(McpToolDescriptions.spring(name))
                    .as(name)
                    .contains("On Quarkus, the same tool runs the Quarkus application advisor");
        }
        assertThat(McpToolDescriptions.quarkus("get_ai_overview"))
                .contains("aiFrameworkDetected", "springAiDetected is Spring AI only");
    }

    @Test
    void idBasedToolsDescribeAnUnknownIdAsAToolErrorAndAnAbsentCapabilityAsUnavailable() {
        for (Function<String, String> provider :
                List.<Function<String, String>>of(McpToolDescriptions::spring, McpToolDescriptions::quarkus)) {
            for (String tool : List.of(
                    "get_exception_detail",
                    "get_request_profile",
                    "get_runtime_insight",
                    "get_runtime_run_comparison",
                    "get_method_probe")) {
                assertThat(provider.apply(tool)).as(tool).contains("tool error");
            }
            assertThat(provider.apply("get_runtime_insight")).contains("available=false", "get_runtime_insights");
            assertThat(provider.apply("get_request_profile")).contains("available=false", "journal is off");
        }
        assertThat(McpToolDescriptions.spring("trigger_devtools_livereload"))
                .contains("available=false", "unavailableReason", "no_clients");
    }

    @Test
    void analyzeHeapDumpSaysItReadsTheLiveHeapRatherThanADumpFile() {
        for (Function<String, String> provider :
                List.<Function<String, String>>of(McpToolDescriptions::spring, McpToolDescriptions::quarkus)) {
            assertThat(provider.apply("analyze_heap_dump"))
                    .contains("live JVM heap", "not a dump file", "dumpCount 0", "ANALYZED", "never")
                    .doesNotContain("existing BootUI heap dump");
        }
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

    @Test
    void controlToolsDescribeTheirCompactAcknowledgementAndTheLifetimeCount() {
        for (String tool : List.of(
                "clear_sql_traces",
                "pause_sql_trace_recording",
                "resume_sql_trace_recording",
                "clear_rest_client_traces",
                "pause_rest_client_recording",
                "resume_rest_client_recording",
                "clear_exceptions",
                "clear_traces")) {
            for (Function<String, String> provider :
                    List.<Function<String, String>>of(McpToolDescriptions::spring, McpToolDescriptions::quarkus)) {
                assertThat(provider.apply(tool))
                        .as(tool)
                        .contains("compact acknowledgement", "since startup, which a clear does not reset")
                        .doesNotContain("resulting report");
            }
        }
        for (String tool :
                List.of("clear_transactions", "pause_transaction_recording", "resume_transaction_recording")) {
            assertThat(McpToolDescriptions.spring(tool)).contains("compact acknowledgement", "get_transactions");
        }
        assertThat(McpToolDescriptions.spring("get_sql_traces")).contains("since startup", "clear_sql_traces");
        assertThat(McpToolDescriptions.spring("get_exceptions")).contains("clear_exceptions resets it");
        for (String tool : List.of("pentest_scan", "vulnerabilities_scan", "postgresql_read", "mysql_read")) {
            assertThat(McpToolDescriptions.quarkus(tool)).as(tool).contains("summary, not the");
        }
        assertThat(McpToolDescriptions.spring("graalvm_scan")).contains("get_graalvm_report");
        assertThat(McpToolDescriptions.spring("get_vulnerabilities_report"))
                .contains("at most 5 advisories", "advisories.omitted", "exact advisory id or alias");
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
