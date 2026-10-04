package io.github.jdubois.bootui.core.dto;

/**
 * One correlation tier and whether the serving adapter can provide it.
 *
 * <p>A tier an adapter cannot prove is reported here as unavailable rather than inferred: Spring MVC
 * serves each request on one thread, so it provides every tier, while Spring WebFlux and Quarkus
 * correlate by trace id only. A profile never attributes a child at a tier it reports unavailable: the
 * {@code PROPAGATED} tier is also available in a profile that shows work the BootUI agent propagated before it
 * stopped, and a note then says it no longer propagates.</p>
 *
 * @param tier {@code REQUEST_ID}, {@code PROPAGATED}, {@code TRACE_ID}, {@code SERVING_THREAD}, or
 *     {@code TIME_WINDOW}
 * @param available whether the adapter provides the tier, or this profile attributed a child at it
 * @param unavailableReason why the adapter cannot provide it, or {@code null} when available
 */
public record RequestProfileTierDto(String tier, boolean available, String unavailableReason) {}
