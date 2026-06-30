package io.github.jdubois.bootui.spi;

/**
 * A single configuration property as read from a backing config system, before any display-time masking.
 *
 * <p>Framework-neutral seam carrier behind the Configuration and Profile Diff panels: the value is the
 * <em>raw</em> object (Spring keeps the original {@code Object}; Quarkus the raw, un-interpolated string)
 * so the engine can apply {@link ExposurePolicy} masking consistently on both adapters. {@code source} is
 * the name of the property source the value won from.
 */
public record ConfigEntry(String name, Object value, String source) {}
