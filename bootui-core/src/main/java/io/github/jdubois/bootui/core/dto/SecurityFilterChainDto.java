package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Spring Security filter chain.
 */
public record SecurityFilterChainDto(
        int order,
        String requestMatcher,
        String requestMatcherType,
        List<String> filters,
        boolean csrfEnabled,
        boolean corsEnabled,
        boolean sessionManagementPresent) {}
