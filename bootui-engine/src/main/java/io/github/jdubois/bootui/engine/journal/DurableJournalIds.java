package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Builds the opaque identities used to partition durable journal data. */
public final class DurableJournalIds {

    private static final String LOCAL_NAMESPACE_PREFIX = "local-";
    private static final String UNKNOWN_USER = "unknown-user";
    private static final String UNKNOWN_HOST = "unknown-host";
    private static final int MAX_HASH_INPUT_LENGTH = 256;

    private DurableJournalIds() {}

    /**
     * Resolves the durable namespace. An explicit id wins, then the legacy activity-persistence
     * instance id, and finally an opaque machine/user namespace.
     */
    public static String resolveNamespaceId(String configured, String legacyInstanceId) {
        return resolveNamespaceId(configured, legacyInstanceId, System.getProperty("user.name"), hostName());
    }

    /** Deterministic variant of {@link #resolveNamespaceId(String, String)} for tests. */
    public static String resolveNamespaceId(
            String configured, String legacyInstanceId, String userName, String hostName) {
        String explicit = trimmed(configured);
        if (explicit != null) {
            return validateNamespace(explicit);
        }
        String legacy = trimmed(legacyInstanceId);
        return legacy != null ? validateNamespace(legacy) : localNamespaceId(userName, hostName);
    }

    /**
     * Derives a stable, opaque namespace from sanitized user and host names.
     *
     * <p>The inputs are used only while calculating the digest. Neither is retained in the returned
     * value, which is always {@code local-} followed by the full lowercase SHA-256 hex digest.</p>
     */
    public static String localNamespaceId(String userName, String hostName) {
        String user = sanitize(userName, UNKNOWN_USER);
        String host = sanitize(hostName, UNKNOWN_HOST);
        byte[] digest = sha256(user.length() + ":" + user + host.length() + ":" + host);
        return LOCAL_NAMESPACE_PREFIX + HexFormat.of().formatHex(digest);
    }

    /** Resolves an explicit application id over the framework-provided run application key. */
    public static String resolveApplicationId(String configured, String runApplicationKey) {
        String application = trimmed(configured);
        if (application == null) {
            application = trimmed(runApplicationKey);
        }
        if (application == null) {
            application = "application";
        }
        return DurableJournalScope.bounded("application", application, DurableJournalScope.MAX_APPLICATION_LENGTH);
    }

    /** A standard 128-bit UUID string suitable for a durable run id. */
    public static String newRunId() {
        return UUID.randomUUID().toString();
    }

    /** The operating-system process id, as text for durable metadata. */
    public static String processId() {
        return Long.toString(ProcessHandle.current().pid());
    }

    /** The BootUI class-loader instance id shared with the existing runtime journal. */
    public static String instanceId() {
        return RunIdentity.instanceId();
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (RuntimeException ex) {
            // Use an environment host hint only together with the user name in the digest.
        } catch (java.net.UnknownHostException ex) {
            // Use an environment host hint only together with the user name in the digest.
        }
        String host = System.getenv("COMPUTERNAME");
        return host == null || host.isBlank() ? System.getenv("HOSTNAME") : host;
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isEmpty()) {
            return fallback;
        }
        return normalized.substring(0, Math.min(normalized.length(), MAX_HASH_INPUT_LENGTH));
    }

    static String offloadTraceFingerprint(DurableJournalScope scope, String traceId) {
        return HexFormat.of()
                .formatHex(sha256("bootui/offload-trace:"
                        + scope.namespace().length() + ":" + scope.namespace()
                        + scope.application().length() + ":" + scope.application()
                        + scope.run().length() + ":" + scope.run()
                        + traceId));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String validateNamespace(String value) {
        String namespace = DurableJournalScope.bounded("namespace", value, DurableJournalScope.MAX_NAMESPACE_LENGTH);
        if ("__bootui_internal__".equals(namespace)) {
            throw new IllegalArgumentException("namespace is reserved for durable journal storage");
        }
        return namespace;
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
