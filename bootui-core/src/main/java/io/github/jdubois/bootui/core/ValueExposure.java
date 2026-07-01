package io.github.jdubois.bootui.core;

/**
 * How configuration and attribute values are displayed across BootUI panels.
 *
 * <p>This is a framework-neutral enum shared by every adapter (Spring Boot, Quarkus) so the
 * UI binds to a single stable contract. It is bound from {@code bootui.expose-values} and
 * consulted by the exposure policy before any secret-bearing value is serialized.</p>
 */
public enum ValueExposure {
    /**
     * Replace secret-like values with stars. Default.
     */
    MASKED,
    /**
     * Hide values entirely and only show metadata.
     */
    METADATA_ONLY,
    /**
     * Show all values, including secrets. Discouraged.
     */
    FULL
}
