package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.core.dto.LowFootprintRequest;
import io.github.jdubois.bootui.core.dto.LowFootprintStatus;
import io.github.jdubois.bootui.core.dto.MemoryOffloadReport;
import io.github.jdubois.bootui.engine.memory.LowFootprintService;
import io.github.jdubois.bootui.engine.memory.MemoryOffloadService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
 *
 * <p>{@code GET} and {@code POST /footprint} read and toggle low-footprint mode, which pauses BootUI's recording
 * components and then frees memory; the {@code POST} is gated like {@code /offload}.</p>
 */
@Path("/bootui/api/live-memory")
public class LiveMemoryResource {

    private final MemoryReportProvider provider;
    private final MemoryOffloadService offloadService;
    private final LowFootprintService lowFootprintService;

    @Inject
    public LiveMemoryResource(
            MemoryReportProvider provider,
            MemoryOffloadService offloadService,
            LowFootprintService lowFootprintService) {
        this.provider = provider;
        this.offloadService = offloadService;
        this.lowFootprintService = lowFootprintService;
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

    /** Whether low-footprint mode is active and which recording components it paused. Does no work. */
    @GET
    @Path("/footprint")
    @Produces(MediaType.APPLICATION_JSON)
    public LowFootprintStatus footprint() {
        return lowFootprintService.status();
    }

    /** Turns low-footprint mode on (pause recording, then free memory) or off (resume recording). */
    @POST
    @Path("/footprint")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public LowFootprintStatus setFootprint(LowFootprintRequest request) {
        return request != null && request.enabled() ? lowFootprintService.enable() : lowFootprintService.disable();
    }
}
