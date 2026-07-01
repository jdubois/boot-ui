package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ScheduledReport;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTasksService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Scheduled Tasks panel ({@code GET /bootui/api/scheduled}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ScheduledController}: a thin, read-only transport
 * adapter over the shared engine {@link ScheduledTasksService}, which sorts and wraps the mapped,
 * self-filtered tasks supplied by the (Quarkus) {@code ScheduledTaskProvider}. There is no write path, so the
 * resource carries no {@code LocalhostGuard} write floor.</p>
 *
 * <p>The resource is produced unconditionally and the engine service is always wired (it holds no scheduler
 * types): when {@code quarkus-scheduler} is absent the provider reports unavailable and the engine renders an
 * empty report with {@code schedulingPresent=false}. Availability of the <em>panel</em> in the manifest, by
 * contrast, tracks the build-time {@code bootui.internal.scheduled-present} flag (see
 * {@code QuarkusPanelAvailability}).</p>
 */
@Path("/bootui/api/scheduled")
public class ScheduledResource {

    private final ScheduledTasksService scheduledTasksService;

    @Inject
    public ScheduledResource(ScheduledTasksService scheduledTasksService) {
        this.scheduledTasksService = scheduledTasksService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ScheduledReport scheduled() {
        return scheduledTasksService.report();
    }
}
