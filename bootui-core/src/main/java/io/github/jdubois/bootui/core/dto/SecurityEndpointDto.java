package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Authorization rule that applies to a single HTTP endpoint.
 *
 * <p>{@code rule} is one of:
 * <ul>
 *   <li>{@code permitAll} — anyone (authenticated or not) can access</li>
 *   <li>{@code denyAll} — nobody can access</li>
 *   <li>{@code authenticated} — any authenticated user can access</li>
 *   <li>{@code hasRole} — one of {@code roles} is required (already prefixed with {@code ROLE_})</li>
 *   <li>{@code hasAuthority} — one of {@code roles} (here used for arbitrary authorities) is required</li>
 *   <li>{@code unsecured} — no Spring Security filter chain matched the endpoint</li>
 *   <li>{@code custom} — a custom {@code AuthorizationManager} is in effect; see {@code description}</li>
 *   <li>{@code unknown} — no rule could be determined (no matching chain or no authorization filter)</li>
 * </ul>
 *
 * <p>{@code bestEffort} is {@code true} when the resolution relied on a stubbed request
 * that did not include headers/session state.</p>
 */
public record SecurityEndpointDto(
        String method,
        String pattern,
        String handler,
        boolean secured,
        String rule,
        List<String> roles,
        Integer chainIndex,
        String matcherDescription,
        String description,
        boolean bestEffort) {}
