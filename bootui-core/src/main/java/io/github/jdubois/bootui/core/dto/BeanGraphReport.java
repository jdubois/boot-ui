package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A bounded, focus-first view of the managed-bean dependency graph.
 */
public record BeanGraphReport(
        boolean available,
        BeanSummary focus,
        List<BeanSummary> dependencies,
        List<BeanSummary> dependents,
        List<BeanGraphEdge> edges,
        List<String> unresolvedDependencies,
        int hiddenDependencies,
        int hiddenDependents) {

    public static BeanGraphReport unavailable() {
        return new BeanGraphReport(false, null, List.of(), List.of(), List.of(), List.of(), 0, 0);
    }

    public static BeanGraphReport empty() {
        return new BeanGraphReport(true, null, List.of(), List.of(), List.of(), List.of(), 0, 0);
    }
}
