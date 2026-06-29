package io.github.jdubois.bootui.autoconfigure.health;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.spi.HealthProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;

/**
 * Spring Boot {@link HealthProvider} backed by Actuator's {@link HealthEndpoint}.
 *
 * <p>This class is the single touch-point for the Actuator health descriptor types, and it is only
 * instantiated inside the {@code @ConditionalOnClass} nested configuration in
 * {@code BootUiEngineConfiguration}, so {@link HealthEndpoint} and the descriptor types are never linked
 * in an Actuator-absent application. The endpoint is resolved <em>live</em> through a supplier because
 * the endpoint bean may be absent (Actuator present but the health endpoint not exposed), in which case
 * {@link #readRoot()} returns {@code null} and the engine renders the DISABLED root with setup
 * guidance.</p>
 *
 * <p>The Actuator {@code HealthDescriptor} tree is mapped onto the neutral {@link HealthNodeDto} here so
 * the engine and UI never see a framework type. The engine {@code HealthService} owns the structural
 * "only default contributors" test and the DISABLED/guidance shaping.</p>
 */
public final class SpringHealthProvider implements HealthProvider {

    private final Supplier<HealthEndpoint> endpoint;

    public SpringHealthProvider(Supplier<HealthEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public HealthNodeDto readRoot() {
        HealthEndpoint he = endpoint.get();
        if (he == null) {
            return null;
        }
        return toNode("application", he.health());
    }

    private HealthNodeDto toNode(String name, HealthDescriptor descriptor) {
        if (descriptor == null) {
            return new HealthNodeDto(name, "UNKNOWN", null, List.of());
        }
        String status = descriptor.getStatus() == null
                ? "UNKNOWN"
                : descriptor.getStatus().getCode();
        if (descriptor instanceof CompositeHealthDescriptor composite) {
            List<HealthNodeDto> children = new ArrayList<>();
            Map<String, HealthDescriptor> components = composite.getComponents();
            if (components != null) {
                for (Map.Entry<String, HealthDescriptor> e : components.entrySet()) {
                    children.add(toNode(e.getKey(), e.getValue()));
                }
            }
            return new HealthNodeDto(name, status, null, children);
        }
        if (descriptor instanceof IndicatedHealthDescriptor indicated) {
            return new HealthNodeDto(name, status, indicated.getDetails(), List.of());
        }
        return new HealthNodeDto(name, status, null, List.of());
    }
}
