package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Authentication and user-details summary.
 */
public record SecurityAuthDto(
        List<String> authenticationProviderTypes,
        List<String> userDetailsServiceTypes,
        String configuredUsername) {}
