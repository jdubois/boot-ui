package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.List;
import java.util.function.Predicate;

/** Applies current source access to retained canonical history without rewriting event evidence. */
public final class ActivitySourcePolicy {
    private ActivitySourcePolicy() {}

    /** Source names come from the canonical live report, including available sources with no events. */
    public static boolean available(ActivityEntryDto entry, List<String> sources) {
        String source =
                switch (entry.type()) {
                    case "REQUEST" -> "HTTP Exchanges";
                    case "SQL" -> "SQL Trace";
                    case "EXCEPTION" -> "Exceptions";
                    case "SECURITY" -> "Security Logs";
                    case "CACHE" -> "Cache";
                    case "SCHEDULED" -> "Scheduled Tasks";
                    case "MAIL" -> "Email";
                    case "REST_CLIENT" -> "REST Client";
                    case "FAULT_TOLERANCE" -> "Fault Tolerance";
                    case "MESSAGING" ->
                        entry.id().startsWith("kafka-")
                                ? "Kafka"
                                : entry.id().startsWith("rabbit-")
                                        ? "RabbitMQ"
                                        : entry.id().startsWith("jms-") ? "JMS" : null;
                    default -> null;
                };
        return source == null || sources.contains(source);
    }

    public static boolean permitted(ActivityEntryDto entry, Predicate<String> enabled) {
        if (!enabled.test("activity")) return false;
        String panel =
                switch (entry.type()) {
                    case "REQUEST" -> "http-exchanges";
                    case "SQL" -> "sql-trace";
                    case "EXCEPTION" -> "exceptions";
                    case "SECURITY" -> "security-logs";
                    case "CACHE" -> "cache";
                    case "SCHEDULED" -> "scheduled";
                    case "MAIL" -> "email";
                    case "REST_CLIENT" -> "rest-client-trace";
                    case "FAULT_TOLERANCE" -> "fault-tolerance";
                    case "MESSAGING" ->
                        entry.id().startsWith("kafka-")
                                ? "kafka"
                                : entry.id().startsWith("rabbit-")
                                        ? "rabbitmq"
                                        : entry.id().startsWith("jms-") ? "jms" : null;
                    default -> null; // Forward-compatible generic evidence.
                };
        return panel == null || enabled.test(panel);
    }
}
