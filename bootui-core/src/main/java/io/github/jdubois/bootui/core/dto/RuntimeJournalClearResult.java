package io.github.jdubois.bootui.core.dto;

/**
 * Outcome of <b>Clear recording</b>.
 *
 * @param status {@code cleared}, {@code blocked} when the request was not confirmed, or {@code unavailable} when the
 *     journal is disabled
 * @param message a sentence describing the outcome
 * @param clearedEvents the retained events the action dropped
 */
public record RuntimeJournalClearResult(String status, String message, int clearedEvents) {}
