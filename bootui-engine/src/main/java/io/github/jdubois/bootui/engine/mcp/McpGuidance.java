package io.github.jdubois.bootui.engine.mcp;

import java.util.List;

/** Shared agent guidance for the Spring and Quarkus MCP adapters. */
public final class McpGuidance {

    private McpGuidance() {}

    public static String instructions(String framework) {
        return "BootUI exposes a running " + framework
                + " application for local diagnosis. Start with get_overview and get_health, then choose the "
                + "smallest relevant read tool; use get_live_activity to correlate requests, SQL, exceptions, and "
                + "security events, and follow an exception id with get_exception_detail. Advisor *_scan tools "
                + "actively inspect the application: memory_scan may trigger a full GC and pentest_scan sends bounded "
                + "loopback probes. Run scans only when needed, treat findings as evidence to verify against source "
                + "and configuration, and do not modify code blindly. Configuration secrets are masked, but "
                + "application-controlled logs, SQL, traces, and exception messages may still contain sensitive data; "
                + "do not copy results outside the local development context.";
    }

    public static List<McpPrompt> prompts(String framework) {
        return List.of(
                new McpPrompt(
                        "diagnose_runtime_issue",
                        "Investigate a runtime failure by correlating health, activity, exceptions, traces, SQL, and logs.",
                        "Diagnose the current runtime issue in this " + framework
                                + " application. Begin with get_overview and get_health. Inspect get_live_activity for "
                                + "the relevant time window, then use the smallest supporting tools needed. If an "
                                + "exception appears, call get_exception_detail with its id. Correlate trace ids, "
                                + "request paths, SQL timings, and log timestamps. Separate observed evidence from "
                                + "hypotheses, note missing telemetry, and propose the smallest fix plus a verification "
                                + "step. Do not expose sensitive runtime data."),
                new McpPrompt(
                        "review_application",
                        "Review application structure and configuration with BootUI advisors before proposing changes.",
                        "Review this " + framework
                                + " application using BootUI. Establish context with get_overview, get_health, "
                                + "get_config, get_beans, and get_mappings, using narrow queries and limits where "
                                + "possible. Run only advisors relevant to the requested review. Remember that "
                                + "memory_scan may trigger a full GC and pentest_scan performs bounded loopback probes. "
                                + "Validate each advisor finding against source and effective configuration, discard "
                                + "false positives, prioritize by impact and confidence, and recommend focused changes "
                                + "with concrete verification steps."));
    }
}
