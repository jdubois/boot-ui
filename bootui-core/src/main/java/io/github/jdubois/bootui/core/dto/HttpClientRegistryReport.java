package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The HTTP Clients panel report: which declarative HTTP clients the application registered, how each one
 * resolves its target, and which effective transport policies apply before the first request.
 *
 * <p>Rendering this report performs no network call. It complements — and never replaces — the REST Client
 * panel, which reports calls that already happened.</p>
 *
 * @param available whether at least one supported declarative HTTP client technology was discovered
 * @param unavailableReason framework-correct setup hint when {@code available} is {@code false}
 * @param total number of discovered clients
 * @param valueExposure the live value-exposure mode used to render settings
 * @param observedCallsAvailable whether REST Client trace evidence could be consulted for links
 * @param observedCallsUnavailableReason why trace links are unavailable, or {@code null}
 * @param clients the discovered clients in stable order
 * @param warnings non-fatal advisories about the report itself
 */
public record HttpClientRegistryReport(
        boolean available,
        String unavailableReason,
        int total,
        String valueExposure,
        boolean observedCallsAvailable,
        String observedCallsUnavailableReason,
        List<HttpClientDto> clients,
        List<String> warnings) {

    public HttpClientRegistryReport {
        clients = clients == null ? List.of() : List.copyOf(clients);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** The stable empty report served when no supported HTTP client technology is available. */
    public static HttpClientRegistryReport unavailable(String reason, String valueExposure) {
        return new HttpClientRegistryReport(false, reason, 0, valueExposure, false, null, List.of(), List.of());
    }
}
