package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.List;

/**
 * Framework-neutral seam behind the Mappings panel: it reports the host application's HTTP route
 * mappings, already flattened to one {@link MappingDto} per (pattern, method) and with BootUI's own
 * endpoints filtered out.
 *
 * <p>The Spring Boot adapter implements this over Actuator's {@code MappingsEndpoint}. The flattening
 * and self-data filtering stay in the adapter on purpose: the raw mapping descriptor carries a
 * predicate string that the self-data filter inspects but which is <em>lost</em> once a row is
 * flattened to a {@link MappingDto} (the DTO has no predicate field, and must not gain one — it is the
 * stable UI contract). Performing the filter where the predicate string still exists is provably
 * byte-identical to the original controller; an engine-side {@code Predicate<MappingDto>} could only
 * approximate it. The engine {@code MappingsService} therefore owns only the framework-neutral
 * concerns (sorting, querying and paging).</p>
 *
 * <p>No clean Quarkus <em>runtime</em> route-enumeration API exists (Vert.x exposes paths but not the
 * per-route method/produces/consumes detail this panel needs), so the Quarkus adapter captures the
 * application's JAX-RS {@code @Path} resources from the build-time Jandex index and surfaces them via a
 * recorder (a synthetic {@code QuarkusMappings} bean read back by {@code QuarkusMappingProvider}),
 * filtering out BootUI's own routes at build time where both the path and the resource class FQN are
 * available.</p>
 */
public interface MappingProvider {

    /**
     * Whether a route-mapping backend is currently available. {@code false} means the backend type is
     * on the classpath but no usable instance exists (for example Actuator present but the mappings
     * endpoint bean is absent); the engine then serves an empty report.
     */
    boolean available();

    /**
     * The flattened, self-filtered, <em>unsorted</em> and <em>unpaged</em> mappings: one entry per
     * (pattern, method), with BootUI's own endpoints already removed. The engine applies sorting,
     * querying and paging on top of this. Returns an empty list when {@link #available()} is
     * {@code false}.
     */
    List<MappingDto> mappings();
}
