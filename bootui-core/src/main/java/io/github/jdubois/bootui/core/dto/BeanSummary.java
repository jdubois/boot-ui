package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Spring-managed bean summary.
 */
public record BeanSummary(
        String name,
        String type,
        String scope,
        String resource,
        List<String> dependencies,
        List<String> aliases,
        String classification) {}
