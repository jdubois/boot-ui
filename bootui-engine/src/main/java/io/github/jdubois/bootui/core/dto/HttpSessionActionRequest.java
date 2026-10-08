package io.github.jdubois.bootui.core.dto;

/**
 * Request for a confirmation-gated HTTP session mutation.
 */
public record HttpSessionActionRequest(Boolean confirm) {}
