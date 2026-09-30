package io.github.jdubois.bootui.core.dto;

/**
 * One correlation tier and whether the serving adapter can provide it.
 *
 * <p>A tier an adapter cannot prove is reported here as unavailable rather than inferred: Spring MVC
 * serves each request on one thread, so it provides every tier, while Spring WebFlux and Quarkus
 * correlate by trace id only.</p>
 *
 * @param tier {@code TRACE_ID}, {@code SERVING_THREAD}, or {@code TIME_WINDOW}
 * @param available whether the adapter provides the tier
 * @param unavailableReason why the adapter cannot provide it, or {@code null}
 */
public record RequestProfileTierDto(String tier, boolean available, String unavailableReason) {}
