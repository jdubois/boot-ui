package io.github.jdubois.bootui.cli;

import java.util.Set;

/**
 * The tools that take {@code --fail-on}: the advisors whose answer carries a top-level {@code severityCounts}
 * and a {@code scan.status}.
 *
 * <p>The command tree is built from the bundled manifest before any call, and the manifest carries only input
 * schemas, so eligibility is a CLI-side list rather than something read from a response. {@code FailOnToolsTests}
 * fails when a new {@code *_scan} or {@code get_*_report} tool is in neither set, so an advisor cannot be left
 * out silently.
 */
final class FailOnTools {

    /** Every advisor scan and cached report, which share the compact severity summary. */
    static final Set<String> TOOLS = Set.of(
            "architecture_scan",
            "get_architecture_report",
            "crac_scan",
            "get_crac_report",
            "database_advisor_scan",
            "get_database_advisor_report",
            "graalvm_scan",
            "get_graalvm_report",
            "hibernate_scan",
            "get_hibernate_report",
            "memory_scan",
            "get_memory_report",
            "pentest_scan",
            "get_pentest_report",
            "rest_api_scan",
            "get_rest_api_report",
            "security_scan",
            "get_security_report",
            "spring_scan",
            "get_spring_report",
            "vulnerabilities_scan",
            "get_vulnerabilities_report");

    /** Reads that grade nothing: runtime views and a heap-dump inventory have no severities to gate on. */
    static final Set<String> EXCLUDED = Set.of("get_postgresql_report", "get_mysql_report", "get_heap_dump_report");

    private FailOnTools() {}

    static boolean supports(String toolName) {
        return TOOLS.contains(toolName);
    }
}
