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

    /**
     * Resolves which of the two independently-configured instance ids ({@link
     * ActivityPersistenceSettings#instanceId()} or {@link ActivityForwardingSettings#instanceId()}) is the
     * one a running instance's capture poller is actually stamping captured entries with — the id every
     * read path (the panel's own query, the per-request "remote activity" own-instance exclusion) must
     * query/compare against too, or it silently looks for the wrong partition key.
     *
     * <p>{@code ActivityStoreFactory} enforces that at most one of {@code persistenceSettings.enabled()}
     * and {@code forwardingSettings.enabled()} is {@code true} (failing fast at startup otherwise), and
     * both adapters' capture-poller startup already branches on exactly this condition (see {@code
     * QuarkusActivityCapture#onStart} and the Spring {@code LiveActivityController} constructor) — this
     * method is that same branch, reusable by every read path instead of each one re-deriving it (and
     * risking, as happened here, silently defaulting to {@code persistenceSettings.instanceId()}
     * unconditionally). Persistence wins when both happen to be enabled or neither is (preserving the
     * pre-forwarding default behavior); forwarding's id is used only when it alone is enabled.
     *
     * @param persistenceSettings this instance's JDBC persistence settings
     * @param forwardingSettings this instance's HTTP-forwarding settings
     * @return {@code persistenceSettings.instanceId()} unless persistence is disabled and forwarding is
     *     enabled instead, in which case {@code forwardingSettings.instanceId()}
     */
    public static String activeInstanceId(
            ActivityPersistenceSettings persistenceSettings, ActivityForwardingSettings forwardingSettings) {
        if (!persistenceSettings.enabled() && forwardingSettings.enabled()) {
            return forwardingSettings.instanceId();
        }
        return persistenceSettings.instanceId();
    }

    private static String randomSuffix() {
        StringBuilder suffix = new StringBuilder(RANDOM_SUFFIX_LENGTH);
        for (int i = 0; i < RANDOM_SUFFIX_LENGTH; i++) {
            suffix.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return suffix.toString();
    }
}
