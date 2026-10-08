package io.github.jdubois.bootui.conformance;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyScanStatusDto;
import io.github.jdubois.bootui.core.dto.DependencySeverityCountDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.core.dto.HibernateScanStatusDto;
import io.github.jdubois.bootui.core.dto.HibernateSeverityCountDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import io.github.jdubois.bootui.engine.mcp.McpAgentViews;
import io.github.jdubois.bootui.engine.mcp.McpControlAcks;
import io.github.jdubois.bootui.engine.mcp.McpScanSummaries;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.List;

/**
 * The byte contract of the compact MCP answers, which every adapter codec renders the same way: an active scan answers
 * with a summary and a capture-control tool with an acknowledgement, both far below the panel report they used to
 * return, while a report tool stays bounded by {@code max-response-bytes} and pages instead.
 *
 * <p>Each codec test serves {@link #tools()}, sized like the reports the Spring sample produced (a 400 KB vulnerability
 * scan, a 124 KB Hibernate scan, a 282 KB SQL Trace pause), under {@link #MAX_RESPONSE_BYTES}, and checks that:
 *
 * <ul>
 *   <li>{@code vulnerabilities_scan}, {@code hibernate_scan} and {@code pause_sql_trace_recording} render below
 *       {@link #COMPACT_BYTES};
 *   <li>{@code get_hibernate_report} renders whole, above {@link #COMPACT_BYTES} and below the budget;
 *   <li>{@code get_vulnerabilities_report} at its default page is refused with {@code -32003}, and the same call with
 *       {@code limit} 1 fits.
 * </ul>
 */
public final class McpCompactAnswerContract {

    /** The budget each codec test runs with: the default {@code max-response-bytes} is 4 MiB. */
    public static final int MAX_RESPONSE_BYTES = 512 * 1024;

    /** The most a rendered compact answer may take, JSON-RPC envelope and text mirror included. */
    public static final int COMPACT_BYTES = 16 * 1024;

    /** Vulnerable dependencies in the fixture, more than a scan summary lists. */
    public static final int VULNERABLE_DEPENDENCIES = 30;

    /** Violated Hibernate rules in the fixture, more than a scan summary lists. */
    public static final int HIBERNATE_RULES = 40;

    private McpCompactAnswerContract() {}

    /** The five tools the codec tests call, each over a large fixture report. */
    public static List<McpTool> tools() {
        DependenciesReport vulnerabilities = vulnerabilities();
        HibernateReport hibernate = hibernate();
        SqlTraceReport paused = sqlTrace(false);
        return List.of(
                new McpTool(
                        "vulnerabilities_scan",
                        "Scan.",
                        McpToolSchema.NONE,
                        BootUiPanels.VULNERABILITIES,
                        true,
                        args -> McpScanSummaries.vulnerabilities(vulnerabilities)),
                new McpTool(
                        "get_vulnerabilities_report",
                        "Report.",
                        McpToolSchema.QUERY_LIMIT,
                        BootUiPanels.VULNERABILITIES,
                        false,
                        args -> McpAgentViews.vulnerabilities(vulnerabilities, args.query(), args.limit())),
                new McpTool(
                        "hibernate_scan",
                        "Scan.",
                        McpToolSchema.NONE,
                        BootUiPanels.HIBERNATE,
                        true,
                        args -> McpScanSummaries.hibernate(hibernate)),
                new McpTool(
                        "get_hibernate_report",
                        "Report.",
                        McpToolSchema.NONE,
                        BootUiPanels.HIBERNATE,
                        false,
                        args -> hibernate),
                new McpTool(
                        "pause_sql_trace_recording",
                        "Pause.",
                        McpToolSchema.NONE,
                        BootUiPanels.SQL_TRACE,
                        true,
                        args -> McpControlAcks.sqlTrace(McpControlAcks.PAUSED, paused)));
    }

    /** A {@code tools/call} request for {@code tool} with the given JSON {@code arguments}. */
    public static String call(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
    }

    /** {@value #VULNERABLE_DEPENDENCIES} vulnerable dependencies, each with eight long advisories. */
    public static DependenciesReport vulnerabilities() {
        List<DependencyDto> dependencies = new ArrayList<>();
        for (int i = 0; i < VULNERABLE_DEPENDENCIES; i++) {
            List<DependencyVulnerabilityDto> advisories = new ArrayList<>();
            for (int j = 0; j < 8; j++) {
                advisories.add(new DependencyVulnerabilityDto(
                        "GHSA-" + i + "-" + j,
                        "Advisory " + j + " of dependency " + i,
                        "Details ".repeat(500),
                        j == 0 ? "CRITICAL" : "MEDIUM",
                        7.5,
                        List.of("CVE-2026-" + (1000 + i * 10 + j)),
                        List.of("https://osv.dev/vulnerability/GHSA-" + i + "-" + j),
                        List.of("2.0." + j),
                        true,
                        null,
                        null,
                        false,
                        List.of(),
                        null,
                        null));
            }
            dependencies.add(new DependencyDto(
                    "org.example",
                    "library-" + i,
                    "1.0." + i,
                    null,
                    "maven",
                    advisories.size(),
                    "CRITICAL",
                    advisories,
                    null,
                    null));
        }
        return new DependenciesReport(
                true,
                dependencies.size(),
                dependencies.size(),
                List.of(new DependencySeverityCountDto("CRITICAL", dependencies.size())),
                new DependencyScanStatusDto(
                        "OSV", "COMPLETE", null, 1L, dependencies.size(), 0, dependencies.size() * 8),
                null,
                dependencies,
                new AdvisorEvidenceDto(true, true, List.of()),
                null);
    }

    /** {@value #HIBERNATE_RULES} violated rules, each with ten located sample violations. */
    public static HibernateReport hibernate() {
        List<HibernateRuleResultDto> results = new ArrayList<>();
        for (int i = 0; i < HIBERNATE_RULES; i++) {
            List<String> samples = new ArrayList<>();
            List<AdvisorViolationLocationDto> locations = new ArrayList<>();
            for (int j = 0; j < 10; j++) {
                samples.add("com.example.domain.Entity" + j + "#association" + i + " violates the rule");
                locations.add(new AdvisorViolationLocationDto(
                        "com.example.domain.Entity" + j,
                        "association" + i,
                        "FIELD",
                        "Entity" + j + ".java",
                        10 + i,
                        "src/main/java/com/example/domain/Entity" + j + ".java",
                        "LINE"));
            }
            results.add(new HibernateRuleResultDto(
                    "HIB-" + i,
                    "Rule " + i,
                    "PERFORMANCE",
                    i % 4 == 0 ? "HIGH" : "MEDIUM",
                    "A long description of the rule. ".repeat(6),
                    "FAILED",
                    10 + i,
                    samples,
                    "A long recommendation for the rule. ".repeat(6),
                    "https://docs.example.com/hibernate/rule-" + i,
                    false,
                    null,
                    locations));
        }
        int violations = results.size();
        return new HibernateReport(
                true,
                "Local only.",
                List.of("com.example.domain"),
                10,
                60,
                violations,
                List.of(
                        new HibernateSeverityCountDto("HIGH", HIBERNATE_RULES / 4),
                        new HibernateSeverityCountDto("MEDIUM", HIBERNATE_RULES - HIBERNATE_RULES / 4)),
                new HibernateScanStatusDto("Hibernate", "COMPLETE", null, 1L, 60, 10, violations),
                results,
                List.of(),
                new AdvisorEvidenceDto(true, true, List.of()),
                new AdvisorViolationDetailsDto("scan-1", 1000, 1000, 10000, false, List.of()));
    }

    /** A full SQL Trace buffer of long statements, as the panel returns it after a pause or a resume. */
    public static SqlTraceReport sqlTrace(boolean capturing) {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            entries.add(new SqlTraceEntryDto(
                    i,
                    1_000L + i,
                    "select o.id, o.status, o.total from orders o where o.customer_id = ? and o.status in (?, ?) "
                            + "order by o.created_at desc ".repeat(4),
                    "SELECT",
                    "SELECT",
                    1500,
                    1,
                    true,
                    null,
                    null,
                    1,
                    "conn-1",
                    "http-nio-1",
                    false,
                    List.of(),
                    null,
                    "com.example.OrderRepository#findRecent(OrderRepository.java:42)",
                    "req-" + i,
                    null,
                    "REQUEST",
                    null));
        }
        return new SqlTraceReport(
                true,
                null,
                capturing,
                false,
                200,
                872,
                100,
                List.of("dataSource"),
                SqlTraceStatsDto.empty(),
                entries,
                List.of(),
                List.of());
    }
}
