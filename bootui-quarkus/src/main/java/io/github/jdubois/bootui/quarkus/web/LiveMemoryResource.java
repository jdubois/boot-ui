package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Live Memory panel ({@code GET /bootui/api/live-memory}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code LiveMemoryController}: it returns a live JVM
 * memory snapshot built by the shared engine {@link MemoryReportProvider} and is passive (read-only).
 * The query parameters mirror the Spring controller exactly so the same Vue view binds unchanged; each is
 * optional and {@code null} when omitted, in which case the engine falls back to its detected/default
 * value.</p>
 */
@Path("/bootui/api/live-memory")
public class LiveMemoryResource {

    private final MemoryReportProvider provider;

    @Inject
    public LiveMemoryResource(MemoryReportProvider provider) {
        this.provider = provider;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public LiveMemoryReport memory(
            @QueryParam("totalMemoryMb") Long totalMemoryMb,
            @QueryParam("threadCount") Integer threadCount,
            @QueryParam("headRoomPercent") Integer headRoomPercent,
            @QueryParam("kubernetesBurstableEnabled") Boolean kubernetesBurstableEnabled,
            @QueryParam("kubernetesActuatorEnabled") Boolean kubernetesActuatorEnabled) {
        return provider.buildReport(
                totalMemoryMb, threadCount, headRoomPercent, kubernetesBurstableEnabled, kubernetesActuatorEnabled);
    }
}
