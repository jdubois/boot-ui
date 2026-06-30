package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.DevServiceDto;
import java.util.List;

/**
 * Framework-neutral seam behind the Dev Services panel. Each adapter discovers the host application's local
 * development services (Docker Compose, Testcontainers, service-connection beans on Spring; Quarkus Dev
 * Services on Quarkus) and returns them as already-masked, fully-formed {@link DevServiceDto} entries; the
 * engine {@code DevServicesReportService} owns only the framework-neutral concerns — the stable sort, the
 * count, and the report wrapping.
 *
 * <p>The split is deliberate: discovery is irreducibly framework-specific (Spring reflects over the
 * application context, Quarkus replays a build-time snapshot), and value masking must use each adapter's
 * exposure policy, so both happen here. The log-tail and restart actions are likewise adapter-specific (they
 * exist only on Spring) and stay in each adapter's controller/resource rather than the SPI.</p>
 */
public interface DevServicesProvider {

    /** Whether Docker Compose dev-service support is on the classpath. */
    boolean dockerComposePresent();

    /** Whether Testcontainers is on the classpath. */
    boolean testcontainersPresent();

    /** Epoch-millis timestamp of the captured snapshot. */
    long snapshotTimestamp();

    /** The discovered services, already masked and fully formed, in any order (the engine sorts them). */
    List<DevServiceDto> services();

    /** Any warnings to surface (skipped beans, unnamed services). */
    List<String> warnings();
}
