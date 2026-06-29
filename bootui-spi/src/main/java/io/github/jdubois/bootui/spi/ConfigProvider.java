package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import java.util.List;

/**
 * Framework-neutral seam behind the Configuration and Profile Diff panels: it reports the host
 * application's effective configuration as <em>raw</em>, unmasked rows, leaving every display-time concern
 * (masking, sorting, filtering, paging, profile grouping) to the engine {@code ConfigService} so the two
 * panels behave identically on both adapters.
 *
 * <p>The Spring Boot adapter implements this over the live {@code ConfigurableEnvironment}; the Quarkus
 * adapter over SmallRye/MicroProfile {@code Config}. Two concerns deliberately stay in the adapter (not the
 * engine):</p>
 *
 * <ul>
 *   <li><strong>Enumeration + first-source-wins</strong> — each framework exposes property sources
 *       differently (Spring ordered {@code PropertySource}s, Quarkus {@code ConfigSource}s by ordinal), so
 *       the winning value/source resolution is done where those types live and the engine just sees a flat
 *       merged list.</li>
 *   <li><strong>Suggestion metadata + override source</strong> — the Spring suggestions come from
 *       {@code spring-configuration-metadata.json} (parsed with Jackson, banned in the engine) and the
 *       override property source is a Spring-bootstrap concept; Quarkus returns no suggestions and a
 *       {@code null} override source (its config panel is read-only).</li>
 * </ul>
 */
public interface ConfigProvider {

    /** The currently active profiles, in order. Empty when none are active. */
    List<String> activeProfiles();

    /** All property source names in priority order, for the Configuration panel's source filter. */
    List<String> sources();

    /**
     * The merged, first-source-wins entries (one per property name, raw/unmasked), unsorted. The engine
     * masks, sorts, filters and pages on top.
     */
    List<ConfigEntry> entries();

    /**
     * The name of the BootUI runtime-overrides property source, or {@code null} when overrides are not
     * available (Quarkus). Used to flag and count override rows; never masked.
     */
    String overrideSourceName();

    /**
     * The profile-specific sources for the Profile Diff panel, raw/unmasked. Empty when no profile-specific
     * sources are active.
     */
    List<ProfileSource> profileSources();

    /** Known-property suggestions for the override picker; empty on Quarkus (read-only). Never {@code null}. */
    List<ConfigPropertySuggestionDto> suggestions();
}
