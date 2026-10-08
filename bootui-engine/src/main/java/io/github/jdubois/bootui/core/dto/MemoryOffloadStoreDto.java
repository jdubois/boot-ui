package io.github.jdubois.bootui.core.dto;

/**
 * One BootUI in-memory store that <b>Free BootUI memory</b> tried to empty.
 *
 * @param id stable kebab-case store id, such as {@code runtime-journal} or {@code sql-trace}
 * @param label human-readable store name
 * @param cleared whether the store was emptied
 * @param entriesCleared the retained entries the store dropped; {@code 0} when it failed
 * @param failure why the store could not be emptied, or {@code null} when it was
 */
public record MemoryOffloadStoreDto(String id, String label, boolean cleared, long entriesCleared, String failure) {}
