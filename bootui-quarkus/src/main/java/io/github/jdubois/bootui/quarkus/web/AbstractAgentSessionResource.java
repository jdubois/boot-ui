package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CopilotDashboardDto;
import io.github.jdubois.bootui.core.dto.CopilotEventListDto;
import io.github.jdubois.bootui.core.dto.CopilotRawEventDto;
import io.github.jdubois.bootui.core.dto.CopilotSessionDetail;
import io.github.jdubois.bootui.core.dto.CopilotSessionListDto;
import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Shared transport adapter for the Copilot and Claude Code panels, the Quarkus twin of the Spring
 * {@code AgentSessionController}. All parsing/dashboard/aggregation lives in the shared engine
 * {@link AgentSessionStore}; subclasses only supply the panel's store + exposure policy. The
 * Spring-only SSE {@code /stream} endpoint is intentionally not mirrored: the UI polls these GETs, so
 * the panels render identically without it.
 */
public abstract class AbstractAgentSessionResource {

    private final AgentSessionStore store;
    private final QuarkusExposurePolicy exposure;

    protected AbstractAgentSessionResource(AgentSessionStore store, QuarkusExposurePolicy exposure) {
        this.store = store;
        this.exposure = exposure;
    }

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public CopilotSessionListDto sessions(@QueryParam("since") Long since, @QueryParam("until") Long until) {
        return store.listSessions(since, until);
    }

    @GET
    @Path("/dashboard")
    @Produces(MediaType.APPLICATION_JSON)
    public CopilotDashboardDto dashboard() {
        return store.dashboard();
    }

    @GET
    @Path("/sessions/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response session(@PathParam("id") String id) {
        CopilotSessionDetail detail = store.getSession(id);
        return detail == null
                ? Response.status(Response.Status.NOT_FOUND).build()
                : Response.ok(detail).build();
    }

    @GET
    @Path("/sessions/{id}/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response events(
            @PathParam("id") String id,
            @QueryParam("category") String category,
            @QueryParam("since") Long since,
            @QueryParam("limit") Integer limit) {
        if (store.getSession(id) == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        int effectiveLimit = limit == null ? 200 : limit;
        var events = store.listEvents(id, category, since, effectiveLimit);
        int total = store.totalEvents(id, category, since);
        return Response.ok(new CopilotEventListDto(id, total, events.size(), events))
                .build();
    }

    @GET
    @Path("/sessions/{id}/events/{eventId}/raw")
    @Produces(MediaType.APPLICATION_JSON)
    public Response raw(@PathParam("id") String id, @PathParam("eventId") String eventId) {
        if (!store.isRawRevealAllowed() || exposure.valueExposure() == ValueExposure.METADATA_ONLY) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String json = store.getRawEventJson(id, eventId);
        return json == null
                ? Response.status(Response.Status.NOT_FOUND).build()
                : Response.ok(new CopilotRawEventDto(id, eventId, json)).build();
    }
}
