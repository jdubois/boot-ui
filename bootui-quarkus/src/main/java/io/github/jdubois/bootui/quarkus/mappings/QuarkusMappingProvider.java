package io.github.jdubois.bootui.quarkus.mappings;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.spi.MappingProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Quarkus {@link MappingProvider} backed by the build-time-captured {@link QuarkusMappings} holder.
 *
 * <p>The deployment processor exposes the synthetic {@code QuarkusMappings} bean, captured at build time
 * from the Jandex index (the application's {@code @Path} resources), in non-production launch modes. This
 * provider is
 * therefore wired unconditionally but tolerates the bean's absence: an unsatisfied {@code Instance} (for
 * example in production, where the BootUI API is not wired) means {@link #available()} is {@code false} and
 * the engine renders an empty report.</p>
 *
 * <p>The flattening, slash-normalization and BootUI self-data filtering all happen at <em>build time</em> in
 * the {@code registerMappings} build step (which is where both the request path <em>and</em> the resource
 * class FQN are available — the two things the Spring {@code BootUiSelfDataFilter} inspects). So this
 * provider only maps the already-prepared {@link RawMapping} rows one-to-one onto the neutral
 * {@link MappingDto} contract; the engine {@code MappingsService} then sorts, queries and pages them.</p>
 */
@Singleton
public class QuarkusMappingProvider implements MappingProvider {

    private final Instance<QuarkusMappings> capturedMappings;

    @Inject
    public QuarkusMappingProvider(Instance<QuarkusMappings> capturedMappings) {
        this.capturedMappings = capturedMappings;
    }

    @Override
    public boolean available() {
        return !capturedMappings.isUnsatisfied();
    }

    @Override
    public List<MappingDto> mappings() {
        if (capturedMappings.isUnsatisfied()) {
            return List.of();
        }
        return toDtos(capturedMappings.get().mappings());
    }

    /**
     * Maps the build-time-prepared rows one-to-one onto the neutral contract. Package-private and static so
     * the mapping is unit-testable without the CDI {@code Instance} plumbing.
     */
    static List<MappingDto> toDtos(List<RawMapping> rows) {
        return rows.stream()
                .map(raw -> new MappingDto(raw.method(), raw.pattern(), raw.handler(), raw.produces(), raw.consumes()))
                .toList();
    }
}
