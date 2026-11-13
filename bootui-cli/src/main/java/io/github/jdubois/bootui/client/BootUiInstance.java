package io.github.jdubois.bootui.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata for a running local BootUI instance discovered from {@code ~/.bootui/instances/<instanceId>.json}.
 *
 * @param instanceId random JVM instance identifier
 * @param runId current application-context run identifier
 * @param pid operating-system process identifier
 * @param processStartEpochMillis process start timestamp in epoch milliseconds
 * @param appName application name ({@code spring.application.name} or {@code quarkus.application.name})
 * @param framework framework display name ({@code "Spring Boot"} or {@code "Quarkus"})
 * @param baseUrl literal loopback base URL, e.g. {@code http://localhost:8080}
 * @param apiPath full effective BootUI API path, e.g. {@code /bootui/api}
 * @param startedAt when this run started, in epoch milliseconds
 */
public record BootUiInstance(
        String instanceId,
        String runId,
        long pid,
        long processStartEpochMillis,
        String appName,
        String framework,
        String baseUrl,
        String apiPath,
        long startedAt) {

    public BootUiInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(appName, "appName");
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiPath, "apiPath");
    }

    /** Converts this instance to a {@link JsonValue} for CLI output (8 fields so {@code TextRenderer} formats a table). */
    public JsonValue toJsonValue() {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("instanceId", JsonValue.of(instanceId));
        fields.put("runId", JsonValue.of(runId));
        fields.put("appName", JsonValue.of(appName));
        fields.put("framework", JsonValue.of(framework));
        fields.put("pid", JsonValue.of(pid));
        fields.put("baseUrl", JsonValue.of(baseUrl));
        fields.put("apiPath", JsonValue.of(apiPath));
        fields.put("startedAt", JsonValue.of(startedAt));
        return JsonValue.object(fields);
    }
}
