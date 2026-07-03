package io.github.jdubois.bootui.engine.activity;

import java.security.SecureRandom;
import java.util.Map;

/**
 * Resolves the multi-tenant partition key ({@link ActivityPersistenceSettings#instanceId()}) a running
 * BootUI instance uses when persistence is enabled, so several instances can safely share one database
 * table.
 *
 * <p>Framework-neutral and adapter-reusable: each adapter calls {@link #resolveOrDefault} exactly once
 * per process, typically while building its {@link ActivityPersistenceSettings}, and keeps the result for
 * the life of the JVM (both the durable store and the capture coordinator's {@link ActivitySequencer}
 * must agree on the same value, or captured entries would be stamped with an id that the store's own
 * queries never look for).</p>
 */
public final class ActivityInstanceIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RANDOM_SUFFIX_LENGTH = 6;
    private static final String FALLBACK_PREFIX = "bootui";
    private static final String HOSTNAME_VARIABLE = "HOSTNAME";

    private ActivityInstanceIds() {}

    /**
     * @param configured an explicit {@code bootui.activity.persistence.instance-id} value, or {@code
     *     null}/blank to derive a default
     * @param applicationNameHint a naming hint to prefix a generated id with (for example Spring's
     *     {@code spring.application.name}), or {@code null}/blank to fall back to a generic prefix
     * @return {@code configured} trimmed, if non-blank; otherwise the {@code HOSTNAME} environment
     *     variable (a stable identity on a typical container/Kubernetes deployment), if set; otherwise a
     *     freshly generated {@code <hint>-<random6>} id
     */
    public static String resolveOrDefault(String configured, String applicationNameHint) {
        return resolveOrDefault(configured, applicationNameHint, System.getenv());
    }

    /** Testable variant taking the environment map explicitly instead of reading {@link System#getenv()}. */
    static String resolveOrDefault(String configured, String applicationNameHint, Map<String, String> environment) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String hostname = environment == null ? null : environment.get(HOSTNAME_VARIABLE);
        if (hostname != null && !hostname.isBlank()) {
            return hostname.trim();
        }
        String prefix = applicationNameHint == null || applicationNameHint.isBlank()
                ? FALLBACK_PREFIX
                : applicationNameHint.trim();
        return prefix + "-" + randomSuffix();
    }

    private static String randomSuffix() {
        StringBuilder suffix = new StringBuilder(RANDOM_SUFFIX_LENGTH);
        for (int i = 0; i < RANDOM_SUFFIX_LENGTH; i++) {
            suffix.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return suffix.toString();
    }
}
