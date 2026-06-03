package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level Spring Security report.
 */
public record SecurityReport(
        boolean springSecurityPresent, List<SecurityFilterChainDto> chains, SecurityAuthDto auth) {}
