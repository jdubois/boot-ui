package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level Spring Security report.
 */
public record SpringSecurityReport(
        boolean springSecurityPresent, List<SpringSecurityFilterChainDto> chains, SpringSecurityAuthDto auth) {}
