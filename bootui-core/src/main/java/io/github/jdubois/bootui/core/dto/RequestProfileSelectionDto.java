package io.github.jdubois.bootui.core.dto;

/**
 * The agent's selected profile for a journal execution or an HTTP request. Exactly one of {@code journal} and
 * {@code buffers} is present when available; the journal is preferred, with HTTP-exchange details included when
 * retained for a request and used as a fallback when the journal cannot find the id.
 */
public record RequestProfileSelectionDto(
        boolean available,
        String unavailableReason,
        String source,
        RequestJournalProfileDto journal,
        RequestProfileDto buffers) {}
