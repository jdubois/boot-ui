package io.github.jdubois.bootui.spi;

/**
 * One raw effective setting an adapter discovered for a declared HTTP client.
 *
 * <p>Adapters report what their framework actually exposes and nothing more: a setting the framework does
 * not expose safely must be omitted, or reported with a {@code null} value and
 * {@link HttpClientVocabulary#PROVENANCE_UNAVAILABLE}. The engine masks the value, orders the settings and
 * turns them into the stable {@code HttpClientSettingDto} contract.</p>
 *
 * @param category one of the {@code HttpClientVocabulary.CATEGORY_*} tokens
 * @param name human-readable setting name, shared across adapters where the concept is shared
 * @param value the raw value, or {@code null} when the framework does not expose it
 * @param provenance one of the {@code HttpClientVocabulary.PROVENANCE_*} tokens
 * @param source the configuration key or annotation attribute the value came from, or {@code null}
 */
public record DiscoveredHttpClientSetting(
        String category, String name, String value, String provenance, String source) {

    /** A setting the host framework does not expose safely, reported as explicitly unknown. */
    public static DiscoveredHttpClientSetting unavailable(String category, String name) {
        return new DiscoveredHttpClientSetting(category, name, null, HttpClientVocabulary.PROVENANCE_UNAVAILABLE, null);
    }
}
