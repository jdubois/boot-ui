package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** Capture configuration and availability are distinct from access to the canonical activity feed. */
public record ExplorerSetupDto(
        boolean beanCaptureEnabled,
        boolean beanDetailAvailable,
        String reason,
        long requestSlowThresholdMs,
        List<String> limitations) {
    public ExplorerSetupDto {
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
