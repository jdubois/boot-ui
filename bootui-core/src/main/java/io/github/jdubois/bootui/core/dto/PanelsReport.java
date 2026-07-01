package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Panel manifest returned by {@code GET /bootui/api/panels}.
 *
 * <p>The {@code platform} discriminator lets the shared Vue UI tailor framework-specific copy (setup guidance, capture
 * model) without a per-panel probe. Allowed values are {@link #PLATFORM_SPRING_BOOT} and {@link #PLATFORM_QUARKUS}; the
 * field is always populated by the BootUI backend. UI consumers should default to {@link #PLATFORM_SPRING_BOOT} when the
 * field is absent (an older bundle or a unit test without a manifest).
 */
public record PanelsReport(String platform, List<PanelDto> panels) {

    /** Platform value reported by the Spring Boot autoconfigure adapter. */
    public static final String PLATFORM_SPRING_BOOT = "spring-boot";

    /** Platform value reported by the Quarkus extension adapter. */
    public static final String PLATFORM_QUARKUS = "quarkus";
}
