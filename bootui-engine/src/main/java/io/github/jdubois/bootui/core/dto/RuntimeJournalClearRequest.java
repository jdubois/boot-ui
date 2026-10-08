package io.github.jdubois.bootui.core.dto;

/** Request body for <b>Clear recording</b>; {@code confirm} must be {@code true}. */
public record RuntimeJournalClearRequest(Boolean confirm) {}
