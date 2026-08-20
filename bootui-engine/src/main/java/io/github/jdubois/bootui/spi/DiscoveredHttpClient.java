package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * One declarative HTTP client exactly as an adapter discovered it, before any BootUI normalization.
 *
 * <p>Values here are still raw: {@code configuredBaseUrl} keeps its placeholders, and both URL fields may
 * still carry user-info or query values. The engine owns sanitization, ordering, identity and provenance
 * presentation so every adapter produces the same contract.</p>
 *
 * @param name the bean or registration name; never blank
 * @param kind one of the {@code HttpClientVocabulary.KIND_*} tokens
 * @param declaredInterface the declared client interface, or {@code null} for builder beans
 * @param configKey the configuration key the framework binds this client's properties under, or {@code null}
 * @param configuredBaseUrl the base URL exactly as declared, placeholders intact, or {@code null}
 * @param resolvedBaseUrl the base URL after the adapter's own property interpolation, or {@code null}
 * @param baseUrlProvenance one of the {@code HttpClientVocabulary.PROVENANCE_*} tokens
 * @param baseUrlSource the configuration key or annotation attribute the base URL came from, or {@code null}
 * @param settings effective transport settings, in adapter order
 */
public record DiscoveredHttpClient(
        String name,
        String kind,
        String declaredInterface,
        String configKey,
        String configuredBaseUrl,
        String resolvedBaseUrl,
        String baseUrlProvenance,
        String baseUrlSource,
        List<DiscoveredHttpClientSetting> settings) {

    public DiscoveredHttpClient {
        settings = settings == null ? List.of() : List.copyOf(settings);
    }
}
