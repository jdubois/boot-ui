package io.github.jdubois.bootui.engine.devservices;

import io.github.jdubois.bootui.core.dto.DevServiceDto;
import io.github.jdubois.bootui.core.dto.DevServicesReport;
import io.github.jdubois.bootui.spi.DevServicesProvider;
import java.util.Comparator;
import java.util.List;

/**
 * Framework-neutral engine service behind the Dev Services panel. It assembles the {@link DevServicesReport}
 * from a {@link DevServicesProvider}: the services are sorted by {@code source} then {@code name} (the stable
 * order both adapters share), counted, and wrapped with the provider's presence flags, snapshot timestamp and
 * warnings. The adapter does discovery + masking; the engine owns shaping only, so the JSON is byte-identical
 * across Spring and Quarkus for an identical service list.
 */
public class DevServicesReportService {

    public DevServicesReport report(DevServicesProvider provider) {
        List<DevServiceDto> sorted = provider.services().stream()
                .sorted(Comparator.comparing(DevServiceDto::source).thenComparing(DevServiceDto::name))
                .toList();
        return new DevServicesReport(
                provider.dockerComposePresent(),
                provider.testcontainersPresent(),
                provider.snapshotTimestamp(),
                sorted.size(),
                sorted,
                List.copyOf(provider.warnings()));
    }
}
