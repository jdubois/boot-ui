package io.github.jdubois.bootui.quarkus.devservices;

import java.util.List;

/**
 * Build-time-captured holder for the host application's Quarkus Dev Services, produced by
 * {@code DevServicesRecorder} and exposed as a synthetic CDI bean by the deployment processor only in
 * non-production launch modes (Dev Services do not run in production). The provider injects an
 * {@code Instance<QuarkusDevServices>}: absent → no dev services were started (panel unavailable); present
 * (even empty) → panel available. The holder exists rather than injecting a raw {@code List} so it is an
 * unambiguous synthetic-bean type.
 *
 * @param services the captured dev services, in discovery order (the engine applies the stable sort)
 */
public record QuarkusDevServices(List<RawDevService> services) {

    public QuarkusDevServices(List<RawDevService> services) {
        this.services = services == null ? List.of() : List.copyOf(services);
    }
}
