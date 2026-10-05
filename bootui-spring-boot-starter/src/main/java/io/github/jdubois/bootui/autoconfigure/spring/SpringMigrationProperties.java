package io.github.jdubois.bootui.autoconfigure.spring;

import java.util.LinkedHashMap;
import java.util.Map;

/** Individually verified Boot 4 migrations; not a blanket deprecation-metadata import. */
final class SpringMigrationProperties {
    static final Map<String, String> ENTRIES = entries();

    private SpringMigrationProperties() {}

    private static Map<String, String> entries() {
        Map<String, String> result = new LinkedHashMap<>();
        renamed(
                result,
                "server.error.",
                "spring.web.error.",
                "include-binding-errors",
                "include-exception",
                "include-message",
                "include-path",
                "include-stacktrace",
                "path",
                "whitelabel.enabled");
        // encoding.mapping remains live under server.servlet.encoding.
        renamed(
                result,
                "server.servlet.encoding.",
                "spring.servlet.encoding.",
                "charset",
                "enabled",
                "force",
                "force-request",
                "force-response");
        renamed(
                result,
                "spring.http.client.",
                "spring.http.clients.",
                "connect-timeout",
                "read-timeout",
                "redirects",
                "ssl.bundle");
        renamed(
                result,
                "spring.http.reactiveclient.",
                "spring.http.clients.",
                "connect-timeout",
                "read-timeout",
                "redirects",
                "ssl.bundle");
        result.put("spring.http.client.factory", "renamed to spring.http.clients.imperative.factory");
        result.put("spring.http.reactiveclient.connector", "renamed to spring.http.clients.reactive.connector");
        renamed(result, "spring.codec.", "spring.http.codecs.", "max-in-memory-size", "log-request-details");
        // Spring Data auto-index, field-naming, gridfs and representation settings are NOT connection renames.
        renamed(
                result,
                "spring.data.mongodb.",
                "spring.mongodb.",
                "additional-hosts",
                "authentication-database",
                "database",
                "host",
                "password",
                "port",
                "protocol",
                "replica-set-name",
                "ssl.bundle",
                "ssl.enabled",
                "uri",
                "username");
        result.put("spring.data.mongodb.uuid-representation", "renamed to spring.mongodb.representation.uuid");
        result.put("management.tracing.enabled", "renamed to management.tracing.export.enabled");
        renamed(
                result,
                "spring.session.redis.",
                "spring.session.data.redis.",
                "cleanup-cron",
                "configure-action",
                "flush-mode",
                "namespace",
                "repository-type",
                "save-mode");
        for (String key : new String[] {"threads.io", "threads.worker", "accesslog.enabled", "buffer-size"})
            result.put("server.undertow." + key, "Boot 4 removed Undertow support");
        result.put(
                "spring.session.mongodb.collection-name",
                "Boot MongoDB session auto-configuration was removed; review org.mongodb:mongodb-spring-session");
        // Observability exporters: Boot 4.0 moved OTLP/OpenTelemetry/Brave/Zipkin keys. OTLP metrics keys are live.
        String[] otlp = {"compression", "connect-timeout", "endpoint", "headers", "timeout", "transport"};
        renamed(result, "management.otlp.logging.", "management.opentelemetry.logging.export.otlp.", otlp);
        result.put("management.otlp.logging.export.enabled", "renamed to management.logging.export.otlp.enabled");
        renamed(result, "management.otlp.tracing.", "management.opentelemetry.tracing.export.otlp.", otlp);
        result.put("management.otlp.tracing.export.enabled", "renamed to management.tracing.export.otlp.enabled");
        renamed(
                result,
                "management.tracing.opentelemetry.export.",
                "management.opentelemetry.tracing.export.",
                "include-unsampled",
                "max-batch-size",
                "max-queue-size",
                "schedule-delay",
                "timeout");
        result.put(
                "management.tracing.brave.span-joining-supported",
                "renamed to management.brave.tracing.span-joining-supported");
        result.put("management.zipkin.tracing.export.enabled", "renamed to management.tracing.export.zipkin.enabled");
        result.put("management.wavefront", "Boot 4 removed Wavefront support (end-of-life)");
        result.put("management.health.mongo.enabled", "renamed to management.health.mongodb.enabled");
        renamed(
                result,
                "management.metrics.mongo.",
                "management.metrics.mongodb.",
                "command.enabled",
                "connectionpool.enabled");
        result.put("server.use-forward-headers", "replaced by server.forward-headers-strategy");
        // Spring AMQP and Kafka retry moved from Spring Retry to Framework core retry.
        for (String scope : new String[] {"listener.simple", "listener.direct", "template"})
            result.put(
                    "spring.rabbitmq." + scope + ".retry.max-attempts",
                    "replaced by spring.rabbitmq." + scope
                            + ".retry.max-retries, which counts retries after the first attempt; review the value");
        result.put(
                "spring.kafka.retry.topic.backoff.random",
                "replaced by spring.kafka.retry.topic.backoff.jitter, a Duration rather than a boolean");
        result.put(
                "spring.jackson.generator",
                "removed with Jackson 3; JSON write features moved to spring.jackson.json.write, others need a JsonMapperBuilderCustomizer");
        result.put(
                "spring.jackson.parser",
                "removed with Jackson 3; JSON read features moved to spring.jackson.json.read, others need a JsonMapperBuilderCustomizer");
        for (String template : new String[] {"thymeleaf", "freemarker", "mustache", "groovy.template"})
            result.put(
                    "spring." + template + ".enabled",
                    "removed; to opt out, exclude the auto-configuration or depend on the template engine directly");
        result.put(
                "spring.neo4j.pool.metrics-enabled",
                "removed; restrict metrics with management.metrics.enable instead");
        result.put(
                "spring.data.redis.lettuce.cluster.refresh.adaptive",
                "removed in Boot 4.1; all adaptive refresh triggers are enabled by default");
        // JDBC still reads spring.dao.exceptiontranslation.enabled; Jackson stream read/write maps remain live.
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void renamed(Map<String, String> result, String oldPrefix, String newPrefix, String... keys) {
        for (String key : keys) result.put(oldPrefix + key, "renamed to " + newPrefix + key);
    }
}
