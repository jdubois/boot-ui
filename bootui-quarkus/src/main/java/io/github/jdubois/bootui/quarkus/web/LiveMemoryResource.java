package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryOffloadReport;
import io.github.jdubois.bootui.engine.memory.MemoryOffloadService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Live Memory panel ({@code GET /bootui/api/live-memory}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code LiveMemoryController}: it returns a live JVM
 * memory snapshot built by the shared engine {@link MemoryReportProvider}.
 * The query parameters mirror the Spring controller exactly so the same Vue view binds unchanged; each is
 * optional and {@code null} when omitted, in which case the engine falls back to its detected/default
 * value.</p>
 *
 * <p>{@code POST /offload} is <b>Free BootUI memory</b>, also offered by the JVM Tuning, Heap Dump, and Memory
 * panels. It is gated by the Live Memory panel's enable and read-only toggles in {@code QuarkusPanelAccessFilter}
 * and by the shared write floor in {@code BootUiQuarkusSafetyFilter}. It is {@code @Blocking} because it requests a
 * garbage collection, which must not run on the Vert.x event loop.</p>
 */
@Path("/bootui/api/live-memory")
public class LiveMemoryResource {

    private final MemoryReportProvider provider;
    private final MemoryOffloadService offloadService;

    @Inject
    public LiveMemoryResource(MemoryReportProvider provider, MemoryOffloadService offloadService) {
        this.provider = provider;
        this.offloadService = offloadService;
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

    /** <b>Free BootUI memory</b>: empties BootUI's in-memory stores, then requests a garbage collection. */
    @POST
    @Path("/offload")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public MemoryOffloadReport offload() {
        return offloadService.offload();
    }
}
