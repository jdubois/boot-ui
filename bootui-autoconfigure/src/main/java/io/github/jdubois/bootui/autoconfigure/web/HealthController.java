package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.HealthNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/health")
public class HealthController {

    private final ObjectProvider<HealthEndpoint> endpoint;

    public HealthController(ObjectProvider<HealthEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    public HealthNodeDto health() {
        HealthEndpoint he = endpoint.getIfAvailable();
        if (he == null) {
            return new HealthNodeDto("application", "UNKNOWN", null, List.of());
        }
        return toNode("application", he.health());
    }

    private HealthNodeDto toNode(String name, HealthDescriptor descriptor) {
        if (descriptor == null) {
            return new HealthNodeDto(name, "UNKNOWN", null, List.of());
        }
        String status = descriptor.getStatus() == null ? "UNKNOWN" : descriptor.getStatus().getCode();
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
