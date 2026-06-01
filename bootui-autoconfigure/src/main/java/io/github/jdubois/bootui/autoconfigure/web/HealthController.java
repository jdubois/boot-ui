package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.HealthNodeDto;
import io.github.jdubois.bootui.core.BootUiDtos.HealthSetupStepDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final String ACTUATOR_UNAVAILABLE_REASON = "Spring Boot Actuator health endpoint is not available";

    private static final String DEFAULT_CONTRIBUTORS_REASON =
            "Only Spring Boot default health indicators are available";

    private static final Set<String> DEFAULT_HEALTH_CONTRIBUTORS =
            Set.of("diskSpace", "livenessState", "readinessState", "ping", "ssl");

    private static final List<HealthSetupStepDto> ACTUATOR_SETUP = List.of(
            new HealthSetupStepDto(
                    "Add Spring Boot Actuator",
                    "Use bootui-spring-boot-starter, or add spring-boot-starter-actuator alongside"
                            + " bootui-autoconfigure so Spring creates the HealthEndpoint bean.",
                    List.of("org.springframework.boot:spring-boot-starter-actuator")),
            new HealthSetupStepDto(
                    "Expose health details locally",
                    "BootUI can render contributors and details when the local Actuator health endpoint exposes them.",
                    List.of(
                            "management.endpoints.web.exposure.include=health,info",
                            "management.endpoint.health.show-details=always")),
            new HealthSetupStepDto(
                    "Enable availability probes when you need them",
                    "Keep probes enabled and point your runtime platform at the liveness and readiness health groups.",
                    List.of("management.endpoint.health.probes.enabled=true")),
            new HealthSetupStepDto(
                    "Configure SSL certificate health intentionally",
                    "Configure Spring SSL bundles when you want certificate checks, or disable the SSL indicator when"
                            + " the application has no SSL bundles to validate.",
                    List.of("spring.ssl.bundle.*", "management.health.ssl.enabled=false")));

    private static final List<HealthSetupStepDto> DEFAULT_CONTRIBUTOR_SETUP = List.of(
            new HealthSetupStepDto(
                    "Add application health contributors",
                    "Create a HealthIndicator bean or add starters that provide dependency indicators, such as JDBC,"
                            + " Redis, RabbitMQ, or other services your app depends on.",
                    List.of("class MyHealthIndicator implements HealthIndicator")),
            new HealthSetupStepDto(
                    "Keep availability probes explicit",
                    "Use liveness and readiness for platform probes, but do not treat them as dependency health until"
                            + " application-specific contributors are present.",
                    List.of("management.endpoint.health.probes.enabled=true")),
            new HealthSetupStepDto(
                    "Configure or disable SSL certificate health",
                    "Configure Spring SSL bundles when certificate health matters, or disable the SSL indicator for"
                            + " applications without SSL bundles.",
                    List.of("spring.ssl.bundle.*", "management.health.ssl.enabled=false")));

    private final ObjectProvider<HealthEndpoint> endpoint;

    public HealthController(ObjectProvider<HealthEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    public HealthNodeDto health() {
        HealthEndpoint he = endpoint.getIfAvailable();
        if (he == null) {
            return disabledRoot(ACTUATOR_UNAVAILABLE_REASON, ACTUATOR_SETUP, List.of());
        }
        HealthNodeDto root = toNode("application", he.health());
        if (hasOnlyDefaultContributors(root)) {
            return withGuidance(root, DEFAULT_CONTRIBUTORS_REASON, DEFAULT_CONTRIBUTOR_SETUP);
        }
        return root;
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

    private HealthNodeDto disabledRoot(String reason, List<HealthSetupStepDto> setup, List<HealthNodeDto> components) {
        return new HealthNodeDto("application", "DISABLED", null, components, false, reason, null, setup);
    }

    private HealthNodeDto withGuidance(HealthNodeDto root, String reason, List<HealthSetupStepDto> setup) {
        return new HealthNodeDto(
                root.name(), root.status(), root.details(), root.components(), true, null, reason, setup);
    }

    private boolean hasOnlyDefaultContributors(HealthNodeDto root) {
        List<HealthNodeDto> components = new ArrayList<>();
        collectComponents(root.components(), components);
        Set<String> componentNames = components.stream()
                .map(HealthNodeDto::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return isHealthyDefaultStatus(root)
                && !components.isEmpty()
                && DEFAULT_HEALTH_CONTRIBUTORS.containsAll(componentNames)
                && components.stream().allMatch(this::isHealthyDefaultStatus);
    }

    private void collectComponents(List<HealthNodeDto> nodes, List<HealthNodeDto> components) {
        for (HealthNodeDto node : nodes) {
            components.add(node);
            collectComponents(node.components(), components);
        }
    }

    private boolean isHealthyDefaultStatus(HealthNodeDto node) {
        return "UP".equals(node.status()) || "UNKNOWN".equals(node.status());
    }
}
