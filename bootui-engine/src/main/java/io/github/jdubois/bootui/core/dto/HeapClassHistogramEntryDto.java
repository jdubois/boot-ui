package io.github.jdubois.bootui.core.dto;

/**
 * One class entry from a JVM class histogram, sorted by retained bytes.
 *
 * <p>Only class names and aggregate sizes are exposed; object field values are never
 * read, so secrets held in live objects are not disclosed by this view.</p>
 */
public record HeapClassHistogramEntryDto(int rank, String className, long instances, long bytes) {}
