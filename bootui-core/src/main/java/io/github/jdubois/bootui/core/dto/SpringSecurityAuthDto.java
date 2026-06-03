package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Authentication and user-details summary.
 */
public record SpringSecurityAuthDto(
        List<String> authenticationProviderTypes, List<String> userDetailsServiceTypes, String configuredUsername) {}
