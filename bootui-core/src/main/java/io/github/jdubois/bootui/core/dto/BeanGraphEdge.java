package io.github.jdubois.bootui.core.dto;

/**
 * A directed dependency edge from a dependency to the bean that consumes it.
 */
public record BeanGraphEdge(String source, String target) {}
