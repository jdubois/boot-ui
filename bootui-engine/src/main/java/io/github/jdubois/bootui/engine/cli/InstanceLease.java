package io.github.jdubois.bootui.engine.cli;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Handshake-verifiable metadata for a running local BootUI instance, written to
 * {@code ~/.bootui/instances/<instanceId>.json}.
 *
 * <p>Never contains authentication tokens, secrets, or mutable runtime state such as {@code mcpEnabled}.
 *
 * @param instanceId random JVM instance identifier (validated hex/slug; used as the filename stem)
 * @param runId current application-context run identifier
 * @param pid operating-system process identifier
 * @param processStartEpochMillis process start timestamp in epoch milliseconds from {@link ProcessHandle.Info#startInstant()}
 * @param appName configured application name, defaulting to {@code "application"}
 * @param framework framework display name ({@code "Spring Boot"} or {@code "Quarkus"})
 * @param baseUrl literal loopback base URL with explicit port, e.g. {@code http://localhost:8080}
 * @param apiPath full effective BootUI API path including context/servlet/root prefix, e.g. {@code /bootui/api}
 * @param startedAt when this run started, in epoch milliseconds
 */
public record InstanceLease(
        String instanceId,
        String runId,
        long pid,
        long processStartEpochMillis,
        String appName,
        String framework,
        String baseUrl,
        String apiPath,
        long startedAt) {

    /** Strict identifier pattern for {@code instanceId} and {@code runId}, preventing path traversal. */
    public static final Pattern SAFE_ID = Pattern.compile("^[0-9A-Za-z_-]{1,64}$");

    public InstanceLease {
        instanceId = requireSafeId(instanceId, "instanceId");
        runId = requireSafeId(runId, "runId");
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        if (processStartEpochMillis < 0) {
            throw new IllegalArgumentException("processStartEpochMillis must be non-negative");
        }
        appName = (appName == null || appName.isBlank()) ? "application" : appName.trim();
        if (framework == null || framework.isBlank()) {
            throw new IllegalArgumentException("framework must not be blank");
        }
        framework = framework.trim();
        baseUrl = validateLoopbackBaseUrl(baseUrl);
        apiPath = BootUiPathNormalizer.normalizeApiPath(apiPath);
        if (startedAt <= 0) {
            throw new IllegalArgumentException("startedAt must be positive");
        }
    }

    /** Creates a lease for the current JVM process and active {@link RunIdentity}. */
    public static InstanceLease forCurrentProcess(
            RunIdentity run, String appName, String framework, String baseUrl, String apiPath) {
        Objects.requireNonNull(run, "run");
        ProcessHandle current = ProcessHandle.current();
        long pid = current.pid();
        long processStart =
                current.info().startInstant().map(Instant::toEpochMilli).orElse(run.startedAtEpochMillis());
        return new InstanceLease(
                RunIdentity.instanceId(),
                run.id(),
                pid,
                processStart,
                appName,
                framework,
                baseUrl,
                apiPath,
                run.startedAtEpochMillis());
    }

    /**
     * Composes one or more optional path prefixes (such as {@code server.servlet.context-path},
     * {@code spring.mvc.servlet.path}, {@code spring.webflux.base-path}, or {@code quarkus.http.root-path})
     * with the normalized {@code bootui.api-path}.
     */
    public static String composeApiPath(String apiPath, String... prefixes) {
        StringBuilder full = new StringBuilder();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                full.append(normalizePrefix(prefix));
            }
        }
        String normalizedApi = BootUiPathNormalizer.normalizeApiPath(
                apiPath == null || apiPath.isBlank() ? BootUiPathNormalizer.DEFAULT_PATH + "/api" : apiPath);
        full.append(normalizedApi);
        return BootUiPathNormalizer.normalizeApiPath(full.toString());
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return "";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /** Validates that {@code id} is a safe identifier suitable for a filename stem. */
    public static String requireSafeId(String id, String fieldName) {
        if (id == null || !SAFE_ID.matcher(id).matches() || ".".equals(id) || "..".equals(id)) {
            throw new IllegalArgumentException(fieldName + " must match [0-9A-Za-z_-]{1,64}");
        }
        return id;
    }

    /** Validates that {@code rawUrl} is an {@code http} or {@code https} literal loopback origin with an explicit port. */
    public static String validateLoopbackBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String trimmed = rawUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("baseUrl is not a valid URI: " + rawUrl, invalid);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getRawPath();
        if (scheme == null
                || (!"http".equals(scheme) && !"https".equals(scheme))
                || host == null
                || !isLoopbackHost(host)
                || port < 1
                || port > 65535
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new IllegalArgumentException(
                    "baseUrl must be a literal loopback origin with an explicit port: " + rawUrl);
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if ("localhost".equalsIgnoreCase(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)) {
            return true;
        }
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                return false;
            }
            int octet = Integer.parseInt(parts[i]);
            if (octet < 0 || octet > 255) {
                return false;
            }
            if (i == 0 && octet != 127) {
                return false;
            }
        }
        return true;
    }

    /** The lease filename: always {@code <instanceId>.json}, never derived from {@code appName}. */
    public String fileName() {
        return instanceId + ".json";
    }

    /** Renders the lease as a standalone JSON object without any external JSON dependency. */
    public String toJson() {
        return "{\n"
                + "  \"instanceId\": " + quote(instanceId) + ",\n"
                + "  \"runId\": " + quote(runId) + ",\n"
                + "  \"pid\": " + pid + ",\n"
                + "  \"processStartEpochMillis\": " + processStartEpochMillis + ",\n"
                + "  \"appName\": " + quote(appName) + ",\n"
                + "  \"framework\": " + quote(framework) + ",\n"
                + "  \"baseUrl\": " + quote(baseUrl) + ",\n"
                + "  \"apiPath\": " + quote(apiPath) + ",\n"
                + "  \"startedAt\": " + startedAt + "\n"
                + "}";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
