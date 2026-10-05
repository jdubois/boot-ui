package io.github.jdubois.bootui.autoconfigure.mappings;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ContextMappingsDescriptor;
import org.springframework.util.ClassUtils;

/**
 * Spring Boot {@link MappingProvider} backed by Actuator's {@link MappingsEndpoint}, for Spring MVC and Spring WebFlux.
 *
 * <p>This class is the single touch-point for the Actuator mappings descriptor, and it is only instantiated inside the
 * {@code @ConditionalOnClass} nested configuration in {@code BootUiEngineConfiguration}, so {@link MappingsEndpoint} is
 * never linked in an Actuator-absent application. The endpoint is resolved <em>live</em> through a supplier because
 * the endpoint bean may be absent (Actuator present but the mappings endpoint not exposed), in which case
 * {@link #available()} reports {@code false} and the engine serves an empty report.</p>
 *
 * <p>Spring MVC's routes are described under {@code dispatcherServlets} and WebFlux's under {@code dispatcherHandlers},
 * each in types of its own web module. {@link ServletMappingDescriptions} and {@link ReactiveMappingDescriptions} read
 * them, each loaded only when its module is present, so a WebFlux application without Spring MVC, or the reverse,
 * never links the other's types.</p>
 *
 * <p>The flattening and BootUI self-data filtering live here (not in the engine) on purpose: the raw predicate string
 * that {@link BootUiSelfDataFilter#isBootUiMapping} inspects is lost once a row is flattened to a {@link MappingDto}
 * (the DTO has no predicate field). Filtering while the predicate string is still available is byte-identical to the
 * original controller; the engine {@code MappingsService} then only sorts, queries and pages.</p>
 */
public final class SpringMappingProvider implements MappingProvider {

    static final String SERVLET_DESCRIPTION =
            "org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDescription";

    static final String REACTIVE_DESCRIPTION =
            "org.springframework.boot.webflux.actuate.web.mappings.DispatcherHandlerMappingDescription";

    private static final ClassLoader LOADER = SpringMappingProvider.class.getClassLoader();

    private static final boolean SERVLET = ClassUtils.isPresent(SERVLET_DESCRIPTION, LOADER);

    private static final boolean REACTIVE = ClassUtils.isPresent(REACTIVE_DESCRIPTION, LOADER);

    private final Supplier<MappingsEndpoint> endpoint;

    private final BootUiSelfDataFilter selfDataFilter;

    public SpringMappingProvider(Supplier<MappingsEndpoint> endpoint, BootUiSelfDataFilter selfDataFilter) {
        this.endpoint = endpoint;
        this.selfDataFilter = selfDataFilter;
    }

    @Override
    public boolean available() {
        return endpoint.get() != null;
    }

    @Override
    public List<MappingDto> mappings() {
        MappingsEndpoint me = endpoint.get();
        if (me == null) {
            return List.of();
        }
        return flatten(me.mappings());
    }

    private List<MappingDto> flatten(ApplicationMappingsDescriptor descriptor) {
        List<MappingDto> mappings = new ArrayList<>();
        for (ContextMappingsDescriptor context : descriptor.getContexts().values()) {
            Map<String, Object> described = context.getMappings();
            if (SERVLET && described.get("dispatcherServlets") instanceof Map<?, ?> dispatchers) {
                mappings.addAll(ServletMappingDescriptions.flatten(dispatchers, selfDataFilter));
            }
            if (REACTIVE && described.get("dispatcherHandlers") instanceof Map<?, ?> dispatchers) {
                mappings.addAll(ReactiveMappingDescriptions.flatten(dispatchers, selfDataFilter));
            }
        }
        return mappings;
    }
}
