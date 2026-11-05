package io.github.jdubois.bootui.cli;

import java.util.Set;

/**
 * The tools that take {@code --fail-on}, separated by the kind of evidence they can evaluate.
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

    /** Reports with explicit evidence sufficiency states. */
    static final Set<String> INSUFFICIENT_TOOLS =
            Set.of("get_exceptions", "get_runtime_insight", "get_runtime_insights");

    /** Reads that grade nothing: runtime views and a heap-dump inventory have no severities to gate on. */
    static final Set<String> EXCLUDED = Set.of("get_postgresql_report", "get_mysql_report", "get_heap_dump_report");

    private FailOnTools() {}

    static boolean supports(String toolName) {
        return TOOLS.contains(toolName) || INSUFFICIENT_TOOLS.contains(toolName);
    }

    static boolean supportsSeverity(String toolName) {
        return TOOLS.contains(toolName);
    }

    static boolean supportsInsufficient(String toolName) {
        return INSUFFICIENT_TOOLS.contains(toolName);
    }

    static String accepted(String toolName) {
        if (supportsSeverity(toolName) && supportsInsufficient(toolName)) {
            return FailOn.accepted() + ", insufficient";
        }
        return supportsInsufficient(toolName) ? "insufficient" : FailOn.accepted();
    }
}
