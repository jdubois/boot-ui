package io.github.jdubois.bootui.core.dto;

/**
 * A single JVM memory pool (heap, non-heap, or GC pool).
 */
public record MemoryPoolDto(String name, long usedBytes, long committedBytes, long maxBytes, int usedPercent) {}
