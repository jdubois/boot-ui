package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class McpToolDescriptionsTests {

    private static final List<String> SPRING_TOOLS = List.of(
            "architecture_scan",
            "get_architecture_report",
            "spring_scan",
            "get_spring_report",
            "hibernate_scan",
            "get_hibernate_report",
            "memory_scan",
            "get_memory_report",
            "security_scan",
            "get_security_report",
            "pentest_scan",
            "get_pentest_report",
            "rest_api_scan",
            "get_rest_api_report",
            "graalvm_scan",
            "get_graalvm_report",
            "crac_scan",
            "get_crac_report",
            "database_advisor_scan",
            "get_database_advisor_report",
            "vulnerabilities_scan",
            "get_vulnerabilities_report",
            "get_live_activity",
            "get_exceptions",
            "get_exception_detail",
            "clear_exceptions",
            "get_security_logs",
            "get_sql_traces",
            "clear_sql_traces",
            "pause_sql_trace_recording",
            "resume_sql_trace_recording",
            "get_transactions",
            "clear_transactions",
            "pause_transaction_recording",
            "resume_transaction_recording",
            "get_traces",
            "clear_traces",
            "get_log_tail",
            "get_http_exchanges",
            "get_overview",
            "get_health",
            "get_config",
            "get_beans",
            "get_mappings",
            "get_loggers",
            "get_conditions",
            "get_scheduled_tasks",
            "get_cache_stats",
            "get_database_connection_pools",
            "get_metrics",
            "get_http_sessions",
            "get_live_memory",
            "get_jvm_tuning",
            "get_heap_dump_report",
            "analyze_heap_dump",
            "get_threads",
            "get_startup_timeline",
            "get_profile_diff",
            "get_spring_data_repositories",
            "get_flyway_migrations",
            "get_liquibase_changesets",
            "get_spring_security",
            "get_rest_client_traces",
            "clear_rest_client_traces",
            "pause_rest_client_recording",
            "resume_rest_client_recording",
            "get_ai_overview",
            "get_emails",
            "get_kafka_activity",
            "get_rabbitmq_activity",
            "get_jms_activity",
            "get_grpc_registry",
            "get_devtools_status",
            "trigger_devtools_livereload",
            "get_dev_services",
            "get_github_dashboard",
            "get_copilot_sessions",
            "get_claude_code_sessions");

    private static final List<String> QUARKUS_TOOLS = List.of(
            "architecture_scan",
            "get_architecture_report",
            "spring_scan",
            "get_spring_report",
            "hibernate_scan",
            "get_hibernate_report",
            "database_advisor_scan",
            "get_database_advisor_report",
            "memory_scan",
            "get_memory_report",
            "security_scan",
            "get_security_report",
            "pentest_scan",
            "get_pentest_report",
            "rest_api_scan",
            "get_rest_api_report",
            "vulnerabilities_scan",
            "get_vulnerabilities_report",
            "get_live_activity",
            "get_exceptions",
            "get_exception_detail",
            "clear_exceptions",
            "get_security_logs",
            "get_sql_traces",
            "clear_sql_traces",
            "pause_sql_trace_recording",
            "resume_sql_trace_recording",
            "get_traces",
            "clear_traces",
            "get_log_tail",
            "get_http_exchanges",
            "get_overview",
            "get_health",
            "get_config",
            "get_beans",
            "get_mappings",
            "get_loggers",
            "get_scheduled_tasks",
            "get_cache_stats",
            "get_database_connection_pools",
            "get_metrics",
            "get_live_memory",
            "get_jvm_tuning",
            "get_heap_dump_report",
            "analyze_heap_dump",
            "get_threads",
            "get_profile_diff",
            "get_flyway_migrations",
            "get_liquibase_changesets",
            "get_rest_client_traces",
            "clear_rest_client_traces",
            "pause_rest_client_recording",
            "resume_rest_client_recording",
            "get_ai_overview",
            "get_emails",
            "get_kafka_activity",
            "get_rabbitmq_activity",
            "get_grpc_registry",
            "get_dev_services",
            "get_github_dashboard",
            "get_copilot_sessions",
            "get_claude_code_sessions");

    @Test
    void everySpringToolHasAgentOrientedGuidance() {
        assertDescriptions(SPRING_TOOLS, McpToolDescriptions::spring);
    }

    @Test
    void everyQuarkusToolHasAgentOrientedGuidance() {
        assertDescriptions(QUARKUS_TOOLS, McpToolDescriptions::quarkus);
    }

    private static void assertDescriptions(
            List<String> names, java.util.function.Function<String, String> descriptionProvider) {
        assertThat(names)
                .allSatisfy(name -> assertThat(descriptionProvider.apply(name))
                        .as(name)
                        .hasSizeGreaterThan(60)
                        .endsWith("."));
    }
}
