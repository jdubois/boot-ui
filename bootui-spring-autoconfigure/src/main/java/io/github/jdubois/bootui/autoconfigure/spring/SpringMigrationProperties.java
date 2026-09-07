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
        // JDBC exceptiontranslation and Jackson read/write feature maps remain independently live.
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void renamed(Map<String, String> result, String oldPrefix, String newPrefix, String... keys) {
        for (String key : keys) result.put(oldPrefix + key, "renamed to " + newPrefix + key);
    }
}
