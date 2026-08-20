package io.github.jdubois.bootui.core.dto;

/**
 * A retained REST Client trace group that BootUI could safely attribute to one declared HTTP client.
 *
 * <p>Links only ever appear when exactly one registered client resolves to the observed host, so an
 * ambiguous or builder-derived client is left unlinked rather than matched by a guessed host or bean
 * name.</p>
 */
public record HttpClientCallLinkDto(String method, String path, long executions, long maxDurationMillis) {}
