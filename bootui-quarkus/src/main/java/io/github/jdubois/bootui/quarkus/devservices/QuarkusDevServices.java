package io.github.jdubois.bootui.quarkus.devservices;

import java.util.List;

/**
 * Build-time-captured list of the host application's Quarkus Dev Services, published by
 * {@code DevServicesRecorder} into {@link CapturedDevServices} only in non-production launch modes (Dev
 * Services do not run in production) and only when at least one Dev Service was started.
 *
 * @param services the captured dev services, in discovery order (the engine applies the stable sort)
 */
public record QuarkusDevServices(List<RawDevService> services) {

    public QuarkusDevServices(List<RawDevService> services) {
        this.services = services == null ? List.of() : List.copyOf(services);
    }
}
