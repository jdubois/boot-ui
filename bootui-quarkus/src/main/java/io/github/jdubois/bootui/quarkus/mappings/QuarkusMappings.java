package io.github.jdubois.bootui.quarkus.mappings;

import java.util.List;

/**
 * Build-time-captured holder for the host application's JAX-RS route mappings, produced by
 * {@code MappingsRecorder} and exposed as a synthetic CDI bean by the deployment processor (in a
 * non-production launch mode; the panel is always available because {@code quarkus-rest} is a hard
 * dependency of the BootUI extension, so the application always has at least BootUI's own JAX-RS resources
 * in the Jandex index).
 *
 * <p>{@code QuarkusMappingProvider} injects an {@code Instance<QuarkusMappings>}: when the bean is absent
 * (for example in production, where the BootUI API is not wired at all) the provider reports the panel's
 * backend unavailable and the engine serves an empty report; when present it maps the {@link RawMapping}
 * rows to the neutral {@code MappingDto} contract. The holder exists (rather than injecting a raw
 * {@code List}) so it is an unambiguous synthetic-bean type.</p>
 *
 * @param mappings the captured, self-filtered mappings in Jandex index order (the engine applies the
 *     stable sort, free-text query and paging)
 */
public record QuarkusMappings(List<RawMapping> mappings) {

    public QuarkusMappings(List<RawMapping> mappings) {
        this.mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }
}
