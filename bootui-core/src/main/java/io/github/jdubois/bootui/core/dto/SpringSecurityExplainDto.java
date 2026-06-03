package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Result of the best-effort chain-matching explain.
 *
 * <p>{@code bestEffort} is {@code true} when the matching was performed
 * with limited request state and may be inaccurate for header- or
 * session-based matchers.</p>
 */
public record SpringSecurityExplainDto(
        boolean matched, boolean bestEffort, Integer chainIndex, String matcherDescription, List<String> filters) {}
