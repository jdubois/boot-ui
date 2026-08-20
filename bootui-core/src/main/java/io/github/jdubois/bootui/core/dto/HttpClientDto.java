package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One declarative HTTP client the running application registered, as shown by the HTTP Clients panel.
 *
 * <p>The record describes configuration, never traffic: it is assembled from registrations, bean
 * definitions and configuration properties without instantiating a lazy client, contacting a target, or
 * resolving DNS.</p>
 *
 * @param id stable identity of this client within the report, unique across kinds and names
 * @param name the bean or registration name
 * @param kind one of {@code HTTP_INTERFACE}, {@code OPEN_FEIGN}, {@code MICROPROFILE_REST_CLIENT},
 *     {@code REST_CLIENT_BUILDER} or {@code WEB_CLIENT_BUILDER}
 * @param kindLabel display label for {@code kind}
 * @param framework the client technology that owns this registration
 * @param declaredInterface the declared client interface, or {@code null} for builder beans
 * @param configKey the configuration key the framework binds this client's properties under, or {@code null}
 * @param configuredBaseUrl the base URL exactly as declared (placeholders intact), or {@code null}
 * @param resolvedBaseUrl the base URL after safe property interpolation, or {@code null} when unresolved
 * @param baseUrlStatus one of {@code RESOLVED}, {@code UNRESOLVED}, {@code NOT_DECLARED}
 * @param baseUrlProvenance provenance of the base URL, matching {@link HttpClientSettingDto#provenance()}
 * @param baseUrlSource the configuration key or annotation member the base URL came from, or {@code null}
 * @param settings effective transport settings with provenance, in stable order
 * @param observedCalls safely attributable REST Client trace groups, most-executed first
 * @param observedCallsStatus one of {@code LINKED}, {@code NO_CALLS}, {@code NOT_ATTRIBUTABLE},
 *     {@code UNAVAILABLE}
 */
public record HttpClientDto(
        String id,
        String name,
        String kind,
        String kindLabel,
        String framework,
        String declaredInterface,
        String configKey,
        String configuredBaseUrl,
        String resolvedBaseUrl,
        String baseUrlStatus,
        String baseUrlProvenance,
        String baseUrlSource,
        List<HttpClientSettingDto> settings,
        List<HttpClientCallLinkDto> observedCalls,
        String observedCallsStatus) {

    public HttpClientDto {
        settings = settings == null ? List.of() : List.copyOf(settings);
        observedCalls = observedCalls == null ? List.of() : List.copyOf(observedCalls);
    }
}
