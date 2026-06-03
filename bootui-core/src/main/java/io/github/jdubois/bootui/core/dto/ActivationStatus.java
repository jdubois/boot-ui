package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Reason why BootUI activated, plus current safety settings.
 */
public record ActivationStatus(boolean enabled, boolean localhostOnly, String reason, List<String> warnings) {}
