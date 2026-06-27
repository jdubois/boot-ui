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
 * JAX-RS resource for the JVM Tuning panel ({@code GET /bootui/api/jvm-tuning}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code JvmTuningController}: it returns the same shared
 * {@link LiveMemoryReport} the Live Memory panel uses, built by the engine {@link MemoryReportProvider},
 * but the JVM Tuning view also renders the calculator inputs and the Kubernetes recommendation. The query
 * parameters mirror the Spring controller exactly so the same Vue view binds unchanged; each is optional
 * and {@code null} when omitted, in which case the engine falls back to its detected/default value. The
 * report is read-only (no mutation), and the framework-specific facts the view needs — whether an
 * app-wide virtual-threads switch exists, and which Kubernetes health-probe paths to render — come from
 * the Quarkus {@code MemoryRuntimeConfig} binding through the engine, so no Spring detail leaks here.</p>
 */
@Path("/bootui/api/jvm-tuning")
public class JvmTuningResource {

    private final MemoryReportProvider provider;

    @Inject
    public JvmTuningResource(MemoryReportProvider provider) {
        this.provider = provider;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public LiveMemoryReport jvmTuning(
            @QueryParam("totalMemoryMb") Long totalMemoryMb,
            @QueryParam("threadCount") Integer threadCount,
            @QueryParam("headRoomPercent") Integer headRoomPercent,
            @QueryParam("kubernetesBurstableEnabled") Boolean kubernetesBurstableEnabled,
            @QueryParam("kubernetesActuatorEnabled") Boolean kubernetesActuatorEnabled) {
        return provider.buildReport(
                totalMemoryMb, threadCount, headRoomPercent, kubernetesBurstableEnabled, kubernetesActuatorEnabled);
    }
}
