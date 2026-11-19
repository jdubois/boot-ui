package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorContributions;
import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Snapshots property ownership at bootstrap; runtime overrides cannot disarm an existing HTTP guard. */
public final class ActuatorGuardScope {

    private final boolean exposure;
    private final boolean details;
    private final WebEndpointProperties web;
    private final Supplier<PathMappedEndpoints> endpoints;
    private final boolean endpointsHere;
    private final WebServerNamespace namespace;

    public ActuatorGuardScope(Environment environment, Supplier<PathMappedEndpoints> endpoints) {
        this(environment, endpoints, true, WebServerNamespace.SERVER);
    }

    public ActuatorGuardScope(
            Environment environment,
            Supplier<PathMappedEndpoints> endpoints,
            boolean endpointsHere,
            WebServerNamespace namespace) {
        exposure = BootUiActuatorContributions.owns(environment, "management.endpoints.web.exposure.include");
        details = BootUiActuatorContributions.owns(environment, "management.endpoint.health.show-details");
        web = Binder.get(environment)
                .bind("management.endpoints.web", WebEndpointProperties.class)
                .orElseGet(WebEndpointProperties::new);
        this.endpoints = endpoints;
        this.endpointsHere = endpointsHere;
        this.namespace = namespace;
    }

    public boolean guards(String path) {
        if (!exposure && !details) {
            return false;
        }
        PathMappedEndpoints mapped = endpoints.get();
        String base = web.getBasePath();
        if (endpointsHere && exposure && !base.isEmpty() && path.equals(base)) {
            return true;
        }
        Set<String> ids = new LinkedHashSet<>();
        if (endpointsHere && exposure) {
            ids.addAll(Arrays.asList(BootUiActuatorDefaultsEnvironmentPostProcessor.REQUIRED_ENDPOINTS.split(",")));
            ids.remove("health");
        }
        if (endpointsHere && details) {
            ids.add("health");
        }
        for (String id : ids) {
            String root = mapped == null ? null : mapped.getPath(EndpointId.of(id));
            if (root == null) {
                root = base + "/" + web.getPathMapping().getOrDefault(id, id);
            }
            if (matches(path, root)) {
                return true;
            }
        }
        if (details && mapped != null) {
            for (String additional : mapped.getAdditionalPaths(namespace, EndpointId.of("health"))) {
                if (matches(path, additional)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}
