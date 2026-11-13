package io.github.jdubois.bootui.engine.cli;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import io.github.jdubois.bootui.core.dto.CliServerStatus;
import io.github.jdubois.bootui.core.dto.CliToolInfo;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequest;
import io.github.jdubois.bootui.engine.mcp.McpRuntimeStats;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class CliService {

    private final boolean enabled;
    private final Supplier<List<McpTool>> tools;
    private final McpPanelPolicy policy;
    private final McpDispatcher dispatcher;
    private final String serverVersion;
    private final String endpoint;
    private final int maxResults;

    public CliService(
            boolean enabled,
            Supplier<List<McpTool>> tools,
            McpPanelPolicy policy,
            String serverVersion,
            String endpoint,
            int maxResults,
            int maxConcurrentCalls,
            long executionTimeoutMillis,
            McpFailureReporter failureReporter) {
        this(
                enabled,
                tools,
                policy,
                serverVersion,
                endpoint,
                maxResults,
                maxConcurrentCalls,
                executionTimeoutMillis,
                failureReporter,
                panelId -> null);
    }

    public CliService(
            boolean enabled,
            Supplier<List<McpTool>> tools,
            McpPanelPolicy policy,
            String serverVersion,
            String endpoint,
            int maxResults,
            int maxConcurrentCalls,
            long executionTimeoutMillis,
            McpFailureReporter failureReporter,
            Function<String, String> panelUnavailableReason) {
        this.enabled = enabled;
        this.tools = Objects.requireNonNull(tools, "tools");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.serverVersion = serverVersion == null ? "dev" : serverVersion;
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.maxResults = Math.max(1, maxResults);
        this.dispatcher = new McpDispatcher(
                tools,
                List.of(),
                policy,
                this.serverVersion,
                "",
                this.maxResults,
                maxConcurrentCalls,
                executionTimeoutMillis,
                failureReporter,
                panelUnavailableReason);
    }

    public boolean enabled() {
        return enabled;
    }

    public McpRuntimeStats runtimeStats() {
        return dispatcher.runtimeStats();
    }

    public static String composeApiPath(String apiPath, String... prefixes) {
        return InstanceLease.composeApiPath(apiPath, prefixes);
    }

    public static Path writeInstanceLease(
            RunIdentity run,
            String appName,
            String framework,
            String baseUrl,
            String apiPath,
            String configuredInstancesDir) {
        Path instancesDir = InstanceLease.resolveInstancesDir(configuredInstancesDir);
        if (instancesDir == null) {
            return null;
        }
        try {
            return InstanceLease.forCurrentProcess(run, appName, framework, baseUrl, apiPath)
                    .writeQuietlyTo(instancesDir);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static void deleteInstanceLease(Path leaseFile) {
        InstanceLease.deleteQuietly(leaseFile);
    }

    public CliServerStatus status() {
        List<CliToolInfo> infos = enabled
                ? tools.get().stream()
                        .map(tool -> new CliToolInfo(
                                tool.name(),
                                CliCommandPaths.commandFor(tool.name()),
                                tool.description(),
                                tool.panelId(),
                                tool.action(),
                                tool.schema().name(),
                                List.copyOf(tool.schema().argumentNames()),
                                policy.isEnabled(tool.panelId()),
                                policy.isReadOnly(tool.panelId())))
                        .toList()
                : List.of();
        McpRuntimeStats.Snapshot stats = runtimeStats().snapshot();
        return new CliServerStatus(
                enabled,
                McpProtocol.SERVER_NAME,
                serverVersion,
                endpoint,
                maxResults,
                stats.callCount(),
                stats.totalLatencyMillis(),
                stats.capacityRefusals(),
                stats.timeouts(),
                infos.size(),
                infos);
    }

    public CliToolResponse invoke(String name, Map<String, Object> arguments) {
        if (!enabled) {
            return CliOutcomes.disabled();
        }
        Map<String, Object> body = arguments == null ? Map.of() : arguments;
        ParsedArguments parsed = parseArguments(body);
        McpRequest request = new McpRequest(
                McpProtocol.JSONRPC_VERSION,
                "tools/call",
                false,
                null,
                name,
                parsed.query,
                parsed.limit,
                parsed.id,
                body.keySet(),
                parsed.error,
                parsed.scanId,
                parsed.offset);
        McpDispatchOutcome outcome = dispatcher.dispatch(request);
        return CliOutcomes.toResponse(outcome);
    }

    public CliToolResponse invoke(String name, String query, Integer limit, String id) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (query != null) {
            arguments.put("query", query);
        }
        if (limit != null) {
            arguments.put("limit", limit);
        }
        if (id != null) {
            arguments.put("id", id);
        }
        return invoke(name, arguments);
    }

    private static ParsedArguments parseArguments(Map<String, Object> arguments) {
        String query = null;
        Integer limit = null;
        String id = null;
        String scanId = null;
        Integer offset = null;
        if (arguments.containsKey("query")) {
            Object value = arguments.get("query");
            if (!(value instanceof String)) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("query", "a string"));
            }
            query = (String) value;
        }
        if (arguments.containsKey("id")) {
            Object value = arguments.get("id");
            if (!(value instanceof String)) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("id", "a string"));
            }
            id = (String) value;
        }
        if (arguments.containsKey("limit")) {
            Object value = arguments.get("limit");
            Long integral = asIntegral(value);
            if (integral == null || integral < Integer.MIN_VALUE || integral > Integer.MAX_VALUE) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("limit", "an integer"));
            }
            if (integral < 1) {
                return ParsedArguments.error(McpProtocol.invalidArgumentMinimumMessage("limit", 1));
            }
            limit = integral.intValue();
        }
        if (arguments.containsKey("scanId")) {
            Object value = arguments.get("scanId");
            if (!(value instanceof String)) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("scanId", "a string"));
            }
            scanId = (String) value;
        }
        if (arguments.containsKey("offset")) {
            Long integral = asIntegral(arguments.get("offset"));
            if (integral == null || integral < Integer.MIN_VALUE || integral > Integer.MAX_VALUE) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("offset", "an integer"));
            }
            if (integral < 0) {
                return ParsedArguments.error(McpProtocol.invalidArgumentMinimumMessage("offset", 0));
            }
            offset = integral.intValue();
        }
        return new ParsedArguments(query, limit, id, null, scanId, offset);
    }

    private static Long asIntegral(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger) {
            BigInteger number = (BigInteger) value;
            return number.bitLength() < 64 ? number.longValue() : null;
        }
        return null;
    }

    private static final class ParsedArguments {

        private final String query;
        private final Integer limit;
        private final String id;
        private final String error;
        private final String scanId;
        private final Integer offset;

        private ParsedArguments(String query, Integer limit, String id, String error, String scanId, Integer offset) {
            this.query = query;
            this.limit = limit;
            this.id = id;
            this.error = error;
            this.scanId = scanId;
            this.offset = offset;
        }

        private static ParsedArguments error(String error) {
            return new ParsedArguments(null, null, null, error, null, null);
        }
    }
}

record InstanceLease(
        String instanceId,
        String runId,
        long pid,
        long processStartEpochMillis,
        String appName,
        String framework,
        String baseUrl,
        String apiPath,
        long startedAt) {

    public static final String BOOTUI_HOME_ENV = "BOOTUI_HOME";

    public static final Pattern SAFE_ID = Pattern.compile("^[0-9A-Za-z_-]{1,64}$");

    private static final Logger log = Logger.getLogger(InstanceLease.class.getName());
    private static final Set<PosixFilePermission> DIR_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

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

    public static Path resolveInstancesDir(String configuredInstancesDir) {
        return resolveInstancesDir(configuredInstancesDir, System.getenv(), System.getProperty("user.home"));
    }

    public static Path resolveInstancesDir(
            String configuredInstancesDir, Map<String, String> environment, String userHome) {
        if (configuredInstancesDir != null && !configuredInstancesDir.isBlank()) {
            return Path.of(configuredInstancesDir.trim()).toAbsolutePath().normalize();
        }
        String bootUiHome = environment == null ? null : environment.get(BOOTUI_HOME_ENV);
        if (bootUiHome != null && !bootUiHome.isBlank()) {
            return Path.of(bootUiHome.trim(), "instances").toAbsolutePath().normalize();
        }
        if (userHome != null && !userHome.isBlank()) {
            return Path.of(userHome.trim(), ".bootui", "instances")
                    .toAbsolutePath()
                    .normalize();
        }
        return null;
    }

    public Path writeTo(Path instancesDir) throws IOException {
        if (instancesDir == null) {
            return null;
        }
        Path normalizedDir = instancesDir.toAbsolutePath().normalize();
        ensureOwnerDirectory(normalizedDir);
        Path target = normalizedDir.resolve(fileName()).toAbsolutePath().normalize();
        if (!normalizedDir.equals(target.getParent())) {
            throw new IOException("Refusing to write lease outside instances directory: " + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to overwrite symbolic link lease file: " + target);
        }

        boolean posix = supportsPosix(normalizedDir);
        Path temp = posix
                ? Files.createTempFile(
                        normalizedDir,
                        ".lease-" + instanceId + "-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
                : Files.createTempFile(normalizedDir, ".lease-" + instanceId + "-", ".tmp");
        try {
            if (posix) {
                Files.setPosixFilePermissions(temp, FILE_PERMISSIONS);
            }
            Files.writeString(temp, toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
            if (posix) {
                Files.setPosixFilePermissions(temp, FILE_PERMISSIONS);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (posix) {
                Files.setPosixFilePermissions(target, FILE_PERMISSIONS);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    public Path writeQuietlyTo(Path instancesDir) {
        try {
            return writeTo(instancesDir);
        } catch (IOException | RuntimeException ex) {
            log.log(Level.WARNING, "Could not write BootUI instance lease file", ex);
            return null;
        }
    }

    public static void deleteQuietly(Path leaseFile) {
        if (leaseFile != null) {
            try {
                Files.deleteIfExists(leaseFile);
            } catch (IOException ex) {
                log.log(Level.FINE, "Could not delete BootUI instance lease file on shutdown", ex);
            }
        }
    }

    private static void ensureOwnerDirectory(Path instancesDir) throws IOException {
        Path parent = instancesDir.getParent();
        if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(parent)) {
            throw new IOException("Refusing to use symbolic link parent directory: " + parent);
        }
        if (Files.exists(instancesDir, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(instancesDir)) {
            throw new IOException("Refusing to use symbolic link instances directory: " + instancesDir);
        }
        Files.createDirectories(instancesDir);
        if (!Files.isDirectory(instancesDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Instances path is not a regular directory: " + instancesDir);
        }
        if (supportsPosix(instancesDir)) {
            if (parent != null
                    && parent.getFileName() != null
                    && ".bootui".equals(parent.getFileName().toString())
                    && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(parent, DIR_PERMISSIONS);
            }
            Files.setPosixFilePermissions(instancesDir, DIR_PERMISSIONS);
        }
    }

    private static boolean supportsPosix(Path path) {
        try {
            return Files.getFileStore(path).supportsFileAttributeView("posix");
        } catch (IOException ex) {
            return false;
        }
    }

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

    public static String requireSafeId(String id, String fieldName) {
        if (id == null || !SAFE_ID.matcher(id).matches() || ".".equals(id) || "..".equals(id)) {
            throw new IllegalArgumentException(fieldName + " must match [0-9A-Za-z_-]{1,64}");
        }
        return id;
    }

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

    public String fileName() {
        return instanceId + ".json";
    }

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
