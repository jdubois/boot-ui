package io.github.jdubois.bootui.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discovers and handshake-verifies running local BootUI instances from {@code ~/.bootui/instances/}.
 *
 * <p>Stale lease files whose PID is dead or whose {@link ProcessHandle.Info#startInstant()} differs from
 * {@code processStartEpochMillis} by more than 2 seconds are automatically deleted and skipped. Before
 * {@code --app <name>} returns a target to the caller (and before any authentication token is attached),
 * the target's {@code baseUrl} is verified to be a literal loopback origin and an unauthenticated request
 * to {@code /overview} must confirm that {@code run.instanceId} matches the lease's {@code instanceId}.
 */
public final class BootUiInstances {

    /** Environment variable overriding the {@code ~/.bootui} home directory. */
    public static final String BOOTUI_HOME_ENV = "BOOTUI_HOME";

    /** Environment variable directly overriding the instances directory. */
    public static final String BOOTUI_INSTANCES_DIR_ENV = "BOOTUI_INSTANCES_DIR";

    /** System property overriding the instances directory. */
    public static final String INSTANCES_DIR_PROPERTY = "bootui.cli.instances-dir";

    /** Tolerance in milliseconds when comparing {@code ProcessHandle.Info.startInstant()} with the lease. */
    public static final long PROCESS_START_TOLERANCE_MILLIS = 2_000L;

    private static final Pattern SAFE_ID = Pattern.compile("^[0-9A-Za-z_-]{1,64}$");
    private static final int MAX_LEASE_BYTES = 16 * 1024;
    private static final int MAX_OVERVIEW_BYTES = 64 * 1024;
    private static final Duration MAX_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

    /** Functional hook for verifying whether a leased OS process is still alive with the expected start time. */
    @FunctionalInterface
    public interface ProcessVerifier {
        boolean isAliveAndMatching(long pid, long expectedStartEpochMillis);
    }

    private BootUiInstances() {}

    /** Resolves the lease directory from environment variables, system properties, or {@code ~/.bootui/instances}. */
    public static Path resolveInstancesDir(Map<String, String> environment) {
        String envInstancesDir = environment == null ? null : environment.get(BOOTUI_INSTANCES_DIR_ENV);
        if (envInstancesDir != null && !envInstancesDir.isBlank()) {
            return Path.of(envInstancesDir.trim()).toAbsolutePath().normalize();
        }
        String sysProp = System.getProperty(INSTANCES_DIR_PROPERTY);
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp.trim()).toAbsolutePath().normalize();
        }
        String bootUiHome = environment == null ? null : environment.get(BOOTUI_HOME_ENV);
        if (bootUiHome != null && !bootUiHome.isBlank()) {
            return Path.of(bootUiHome.trim(), "instances").toAbsolutePath().normalize();
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            return Path.of(userHome.trim(), ".bootui", "instances")
                    .toAbsolutePath()
                    .normalize();
        }
        return null;
    }

    /**
     * Lists live local BootUI instances in {@code instancesDir}, pruning any lease files whose PID is dead
     * or whose process start timestamp does not match within 2 seconds.
     */
    public static List<BootUiInstance> list(Path instancesDir) {
        return list(instancesDir, BootUiInstances::verifyProcessHandle);
    }

    /**
     * Lists live local BootUI instances in {@code instancesDir} using the supplied {@link ProcessVerifier}.
     */
    public static List<BootUiInstance> list(Path instancesDir, ProcessVerifier processVerifier) {
        Objects.requireNonNull(processVerifier, "processVerifier");
        if (instancesDir == null
                || !Files.exists(instancesDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(instancesDir)
                || !Files.isDirectory(instancesDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<BootUiInstance> live = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(instancesDir)) {
            for (Path entry : stream) {
                BootUiInstance instance = readAndVerifyLease(entry, processVerifier);
                if (instance != null) {
                    live.add(instance);
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        live.sort(Comparator.comparing(BootUiInstance::appName)
                .thenComparing(BootUiInstance::baseUrl)
                .thenComparing(BootUiInstance::instanceId));
        return List.copyOf(live);
    }

    /**
     * Resolves a unique live BootUI instance matching {@code selector} (by {@code appName} or {@code instanceId}
     * prefix) and verifies its identity against {@code /overview} over loopback without sending any bearer token.
     */
    public static BootUiInstance resolve(
            Path instancesDir,
            String selector,
            Duration timeout,
            Function<BootUiClientOptions, BootUiClient> clientFactory) {
        return resolve(instancesDir, selector, timeout, clientFactory, BootUiInstances::verifyProcessHandle);
    }

    /**
     * Resolves a unique live BootUI instance matching {@code selector} using the supplied {@link ProcessVerifier}
     * and client factory.
     */
    public static BootUiInstance resolve(
            Path instancesDir,
            String selector,
            Duration timeout,
            Function<BootUiClientOptions, BootUiClient> clientFactory,
            ProcessVerifier processVerifier) {
        Objects.requireNonNull(clientFactory, "clientFactory");
        Objects.requireNonNull(processVerifier, "processVerifier");
        if (selector == null || selector.isBlank()) {
            throw new BootUiClientException("--app must not be blank.");
        }
        String trimmed = selector.trim();
        List<BootUiInstance> live = list(instancesDir, processVerifier);
        List<BootUiInstance> byAppName = live.stream()
                .filter(instance -> instance.appName().equalsIgnoreCase(trimmed))
                .toList();
        List<BootUiInstance> matches = !byAppName.isEmpty()
                ? byAppName
                : live.stream()
                        .filter(instance -> instance.instanceId()
                                .toLowerCase(Locale.ROOT)
                                .startsWith(trimmed.toLowerCase(Locale.ROOT)))
                        .toList();

        if (matches.isEmpty()) {
            if (live.isEmpty()) {
                throw new BootUiClientException("No running BootUI instance found for '" + trimmed
                        + "'. Is the application running in development mode?");
            }
            String running = live.stream()
                    .map(instance ->
                            instance.appName() + " (" + instance.instanceId() + " at " + instance.baseUrl() + ")")
                    .collect(Collectors.joining(", "));
            throw new BootUiClientException(
                    "No running BootUI instance found for '" + trimmed + "'. Running instances: " + running);
        }

        if (matches.size() > 1) {
            StringBuilder message = new StringBuilder("Multiple running BootUI instances match '")
                    .append(trimmed)
                    .append("'. Disambiguate with --url or a unique instanceId prefix:");
            for (BootUiInstance candidate : matches) {
                message.append("\n  - ")
                        .append(candidate.appName())
                        .append(" (instanceId ")
                        .append(candidate.instanceId())
                        .append(", pid ")
                        .append(candidate.pid())
                        .append("): --url ")
                        .append(candidate.baseUrl());
                if (!BootUiClientOptions.DEFAULT_API_PATH.equals(candidate.apiPath())) {
                    message.append(" --api-path ").append(candidate.apiPath());
                }
            }
            throw new BootUiClientException(message.toString());
        }

        BootUiInstance target = matches.get(0);
        verifyLoopbackAndHandshake(target, timeout, clientFactory);
        return target;
    }

    private static void verifyLoopbackAndHandshake(
            BootUiInstance target, Duration timeout, Function<BootUiClientOptions, BootUiClient> clientFactory) {
        String validatedBaseUrl = validateLoopbackBaseUrl(target.baseUrl());
        String validatedApiPath = validateApiPath(target.apiPath());
        if (validatedBaseUrl == null || validatedApiPath == null) {
            throw new BootUiClientException(
                    "Refusing to connect to non-loopback or invalid lease URL for '" + target.appName() + "'.");
        }
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? MAX_HANDSHAKE_TIMEOUT
                : (timeout.compareTo(MAX_HANDSHAKE_TIMEOUT) < 0 ? timeout : MAX_HANDSHAKE_TIMEOUT);
        // Never attach a token during the identity handshake: loopback requires none, and an imposter process
        // on a reused port must never receive a configured bearer token.
        BootUiClientOptions handshakeOptions =
                new BootUiClientOptions(validatedBaseUrl, validatedApiPath, null, effectiveTimeout);
        JsonValue overview;
        try (BootUiClient client = clientFactory.apply(handshakeOptions)) {
            overview = client.getBounded("/overview", MAX_OVERVIEW_BYTES, effectiveTimeout);
        } catch (BootUiClientException failure) {
            throw new BootUiClientException(
                    "Cannot verify BootUI instance '"
                            + target.appName()
                            + "' (instanceId "
                            + target.instanceId()
                            + ") at "
                            + validatedBaseUrl
                            + ": "
                            + failure.getMessage(),
                    failure);
        }
        String remoteInstanceId = overview.get("run").get("instanceId").asString(null);
        if (remoteInstanceId == null || !remoteInstanceId.equals(target.instanceId())) {
            throw new BootUiClientException("Instance identity mismatch for '"
                    + target.appName()
                    + "' at "
                    + validatedBaseUrl
                    + ": lease expected instanceId '"
                    + target.instanceId()
                    + "' but "
                    + validatedApiPath
                    + "/overview reported "
                    + (remoteInstanceId == null ? "no run.instanceId" : "'" + remoteInstanceId + "'")
                    + ". Refusing to send command.");
        }
    }

    private static BootUiInstance readAndVerifyLease(Path file, ProcessVerifier processVerifier) {
        if (file == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
            return null;
        }
        String fileName = fileNamePath.toString();
        if (!fileName.endsWith(".json") || fileName.startsWith(".")) {
            return null;
        }
        String fileStem = fileName.substring(0, fileName.length() - ".json".length());
        if (!isSafeId(fileStem)) {
            return null;
        }
        String content;
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_LEASE_BYTES) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = in.readNBytes(MAX_LEASE_BYTES + 1);
                if (bytes.length > MAX_LEASE_BYTES) {
                    return null;
                }
                content = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException unreadable) {
            return null;
        }

        JsonValue parsed;
        try {
            parsed = JsonValue.parseStrict(content);
        } catch (JsonParseException invalidJson) {
            deleteQuietly(file);
            return null;
        }
        if (!parsed.isObject()) {
            deleteQuietly(file);
            return null;
        }
        // Reject any lease file that attempts to smuggle credentials.
        for (String key : parsed.names()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("token") || lower.contains("secret") || lower.contains("authorization")) {
                deleteQuietly(file);
                return null;
            }
        }

        String instanceId = parsed.get("instanceId").asString(null);
        String runId = parsed.get("runId").asString(null);
        long pid = parsed.get("pid").asLong(-1L);
        long processStartEpochMillis = parsed.get("processStartEpochMillis").asLong(-1L);
        String appName = parsed.get("appName").asString("application");
        String framework = parsed.get("framework").asString(null);
        String baseUrl = validateLoopbackBaseUrl(parsed.get("baseUrl").asString(null));
        String apiPath = validateApiPath(parsed.get("apiPath").asString(null));
        long startedAt = parsed.get("startedAt").asLong(-1L);

        if (!fileStem.equals(instanceId)
                || !isSafeId(runId)
                || pid <= 0
                || processStartEpochMillis < 0
                || appName == null
                || appName.isBlank()
                || framework == null
                || framework.isBlank()
                || baseUrl == null
                || apiPath == null
                || startedAt <= 0) {
            deleteQuietly(file);
            return null;
        }

        if (!processVerifier.isAliveAndMatching(pid, processStartEpochMillis)) {
            deleteQuietly(file);
            return null;
        }

        return new BootUiInstance(
                instanceId,
                runId,
                pid,
                processStartEpochMillis,
                appName.trim(),
                framework.trim(),
                baseUrl,
                apiPath,
                startedAt);
    }

    /** Default {@link ProcessVerifier} backed by {@link ProcessHandle#of(long)}. */
    public static boolean verifyProcessHandle(long pid, long expectedStartEpochMillis) {
        if (pid <= 0 || expectedStartEpochMillis < 0) {
            return false;
        }
        Optional<ProcessHandle> handleOpt;
        try {
            handleOpt = ProcessHandle.of(pid);
        } catch (RuntimeException ex) {
            return false;
        }
        if (handleOpt.isEmpty()) {
            return false;
        }
        ProcessHandle handle = handleOpt.get();
        if (!handle.isAlive()) {
            return false;
        }
        Optional<Instant> startOpt = handle.info().startInstant();
        if (startOpt.isEmpty()) {
            return pid == ProcessHandle.current().pid();
        }
        long actualStartMillis = startOpt.get().toEpochMilli();
        return Math.abs(actualStartMillis - expectedStartEpochMillis) <= PROCESS_START_TOLERANCE_MILLIS;
    }

    /** Validates that {@code rawUrl} is an {@code http} or {@code https} literal loopback origin with an explicit port. */
    public static String validateLoopbackBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException invalid) {
            return null;
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
            return null;
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    static String validateApiPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String trimmed = rawPath.trim();
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("/")
                || "/".equals(trimmed)
                || trimmed.contains("..")
                || trimmed.contains("\\")
                || trimmed.contains("?")
                || trimmed.contains("#")) {
            return null;
        }
        return trimmed;
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

    private static boolean isSafeId(String value) {
        return value != null && SAFE_ID.matcher(value).matches() && !".".equals(value) && !"..".equals(value);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort pruning of stale lease files.
        }
    }
}
