package io.github.jdubois.bootui.quarkus;

import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Reads the per-panel and global BootUI access-control settings live from MicroProfile {@link Config} —
 * the Quarkus analogue of the Spring adapter's {@code BootUiProperties.isPanelEnabled} /
 * {@code isPanelReadOnly} / {@code panelDisabledReason} / {@code panelReadOnlyReason} methods, at
 * behavioral parity.
 *
 * <p>Config surface: {@code bootui.panels.<id>.enabled} (default {@code true}),
 * {@code bootui.panels.<id>.read-only} (default {@code false}), and the global
 * {@code bootui.read-only} (default {@code false}) which forces every action-capable panel read-only
 * regardless of its own per-panel setting. Values are read live (per call), matching the "live-policy SPI"
 * idiom already used by {@link QuarkusExposurePolicy}, so a runtime config change takes effect on the next
 * request with no restart. A missing or invalid value <em>fails closed</em> to the same default as an
 * absent key — never widening access on a typo.
 *
 * <p>This is a plain class (not itself a CDI bean, so it needs no producer/qualifier wiring), constructed
 * inline wherever it is needed ({@link QuarkusPanelAccessFilter}, {@link QuarkusPanelAvailability}, and
 * {@code QuarkusMcpPanelPolicy} in the sibling {@code mcp} package) — mirroring how
 * {@code QuarkusCopilotProperties} is used elsewhere in this adapter — so all three call sites share
 * byte-identical enable/read-only semantics without duplicating the MicroProfile Config reads.
 */
public final class QuarkusPanelAccessConfig {

    private static final Logger LOG = Logger.getLogger(QuarkusPanelAccessConfig.class);

    static final String GLOBAL_READ_ONLY_KEY = "bootui.read-only";

    private final Config config;

    public QuarkusPanelAccessConfig(Config config) {
        this.config = config;
    }

    /** Whether the panel with the given id is enabled. Defaults to {@code true} (opt-out model). */
    public boolean isPanelEnabled(String id) {
        return booleanValue("bootui.panels." + id + ".enabled", true);
    }

    /**
     * Whether the panel with the given id is read-only: either the global {@code bootui.read-only} switch
     * is on, or the panel's own {@code bootui.panels.<id>.read-only} is set.
     */
    public boolean isPanelReadOnly(String id) {
        return isGlobalReadOnly() || booleanValue("bootui.panels." + id + ".read-only", false);
    }

    /** The reason a disabled panel is blocked, matching Spring's message byte-for-byte. */
    public String panelDisabledReason(String id) {
        return "Panel is disabled via bootui.panels." + id + ".enabled=false";
    }

    /**
     * The reason a read-only panel's action is blocked: the global reason when {@code bootui.read-only}
     * is on, otherwise the per-panel reason — matching Spring's message byte-for-byte.
     */
    public String panelReadOnlyReason(String id) {
        if (isGlobalReadOnly()) {
            return "BootUI is read-only via bootui.read-only=true";
        }
        return "Panel is read-only via bootui.panels." + id + ".read-only=true";
    }

    private boolean isGlobalReadOnly() {
        return booleanValue(GLOBAL_READ_ONLY_KEY, false);
    }

    private boolean booleanValue(String key, boolean defaultValue) {
        try {
            return config.getOptionalValue(key, Boolean.class).orElse(defaultValue);
        } catch (IllegalArgumentException ex) {
            LOG.warnf("Ignoring invalid BootUI property '%s'; falling back to %s.", key, defaultValue);
            return defaultValue;
        }
    }
}
