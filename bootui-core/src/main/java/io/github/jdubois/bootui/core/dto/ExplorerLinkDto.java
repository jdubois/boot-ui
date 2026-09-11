package io.github.jdubois.bootui.core.dto;

/** Exact capture-time linkage is separate from the canonical event's existing parentId. */
public record ExplorerLinkDto(String eventId, String invocationId) {}
