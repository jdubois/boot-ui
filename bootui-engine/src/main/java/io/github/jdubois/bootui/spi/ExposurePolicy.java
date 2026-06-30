package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.ValueExposure;

/**
 * Resolves the display-time exposure decision for secret-bearing values.
 *
 * <p>This is the framework-neutral contract behind every BootUI panel that surfaces a property,
 * attribute, header, or session value to the browser. The Spring Boot adapter implements it by
 * re-binding {@code bootui.expose-values} / {@code bootui.mask-secrets} from the live environment (so
 * runtime config overrides are honored); the Quarkus adapter implements it over its own config. Engine
 * services depend on this interface instead of any adapter type so they can mask consistently on both
 * platforms.
 */
public interface ExposurePolicy {

    /**
     * How values should be displayed right now: {@link ValueExposure#MASKED} (default),
     * {@link ValueExposure#METADATA_ONLY}, or {@link ValueExposure#FULL}.
     */
    ValueExposure valueExposure();

    /**
     * Whether secret-like values should be masked when {@link #valueExposure()} is
     * {@link ValueExposure#MASKED}. Defaults to {@code true} (fail-closed).
     */
    boolean maskSecrets();
}
