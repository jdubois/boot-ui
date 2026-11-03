package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.core.dto.ThreadAnalysisReport;
import io.github.jdubois.bootui.core.dto.ThreadCaptureArtifact;
import io.github.jdubois.bootui.core.dto.ThreadCaptureList;
import io.github.jdubois.bootui.core.dto.ThreadCapturePage;
import io.github.jdubois.bootui.core.dto.ThreadCaptureSummary;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.core.dto.ThreadLockReport;
import io.github.jdubois.bootui.core.dto.ThreadObservationReport;
import io.github.jdubois.bootui.core.dto.ThreadObservationRequest;
import io.github.jdubois.bootui.engine.threads.ThreadAnalysisException;
import io.github.jdubois.bootui.engine.threads.ThreadAnalysisService;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * JAX-RS resource for the Threads panel ({@code GET /bootui/api/threads} plus the raw-dump download).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ThreadDumpController}: {@code GET} returns a
 * filtered, paged snapshot of the JVM's live threads from the shared engine {@link ThreadDumpService}
 * and is passive. The raw text dump is exposed as a {@code POST} so it is treated as an explicit,
 * state-changing action gated by the shared {@code LocalhostGuard} write floor enforced by
 * {@code BootUiQuarkusSafetyFilter}; it runs on a worker thread ({@link Blocking}) since it walks every
 * live stack.</p>
 */
@Path("/bootui/api/threads")
@Blocking
public class ThreadsResource {

    private final ThreadDumpService service;
    private final ThreadAnalysisService analysis;

    @Inject
    public ThreadsResource(ThreadDumpService service, ThreadAnalysisService analysis) {
        this.service = service;
        this.analysis = analysis;
    }

    public ThreadAnalysisService analysisService() {
        return analysis;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadDumpReport threads(
            @QueryParam("q") String query,
            @QueryParam("state") String state,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit,
            @QueryParam("includeBootUi") @DefaultValue("true") boolean includeBootUi) {
        return service.report(query, state, offset, limit, includeBootUi);
    }

    public ThreadDumpReport threads(String query, String state, Integer offset, Integer limit) {
        return service.report(query, state, offset, limit);
    }

    @POST
    @Blocking
    @Path("/download")
    @Produces(MediaType.TEXT_PLAIN)
    public Response download() {
        String dump = service.rawDump();
        if (dump == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dump)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"thread-dump.txt\"")
                .type(MediaType.TEXT_PLAIN)
                .build();
    }

    @POST
    @Path("/captures")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadCaptureSummary capture() {
        return analysis.capture();
    }

    @GET
    @Path("/captures")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadCaptureList captures(@QueryParam("limit") Integer limit) {
        return analysis.list(limit);
    }

    @GET
    @Path("/captures/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadAnalysisReport analysis(
            @PathParam("id") String id, @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
        return analysis.analysis(id, offset, limit);
    }

    @GET
    @Path("/captures/{id}/contention")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadAnalysisReport contention(
            @PathParam("id") String id, @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
        return analysis.analysis(id, offset, limit);
    }

    @GET
    @Path("/captures/{id}/threads")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadCapturePage capturedThreads(
            @PathParam("id") String id,
            @QueryParam("q") String query,
            @QueryParam("state") String state,
            @QueryParam("sort") String sort,
            @QueryParam("direction") String direction,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit,
            @QueryParam("includeBootUi") @DefaultValue("true") boolean includeBootUi) {
        return analysis.threads(id, query, state, sort, direction, offset, limit, includeBootUi);
    }

    @GET
    @Path("/captures/{id}/threads/{observationId}")
    @Produces(MediaType.APPLICATION_JSON)
    public CapturedThreadDto thread(@PathParam("id") String id, @PathParam("observationId") String observationId) {
        if (!observationId.startsWith(id + ":")) {
            throw new ThreadAnalysisException(400, "Thread observation does not belong to this capture.");
        }
        return analysis.thread(observationId);
    }

    @GET
    @Path("/captures/{id}/locks/{lockId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadLockReport lock(
            @PathParam("id") String id,
            @PathParam("lockId") String lockId,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        return analysis.lock(id, lockId, offset, limit);
    }

    @POST
    @Path("/captures/clear")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Long> clearCaptures() {
        return Map.of("clearedCaptures", analysis.clear());
    }

    @POST
    @Path("/captures/{id}/export")
    @Produces(MediaType.APPLICATION_JSON)
    public Response export(@PathParam("id") String id) {
        ThreadCaptureArtifact artifact = analysis.export(id);
        return Response.ok(artifact)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bootui-thread-capture-"
                                + artifact.capture().id() + ".json\"")
                .build();
    }

    @POST
    @Path("/observations")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadObservationReport observe(
            ThreadObservationRequest request,
            @QueryParam("includeBootUi") @DefaultValue("true") boolean includeBootUi) {
        return analysis.observe(
                request == null ? null : request.samples(),
                request == null ? null : request.intervalMillis(),
                includeBootUi);
    }

    @GET
    @Path("/observations/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadObservationReport observation(
            @PathParam("id") String id,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit,
            @QueryParam("includeBootUi") @DefaultValue("true") boolean includeBootUi) {
        return analysis.observation(id, offset, limit, includeBootUi);
    }

    @POST
    @Path("/observations/{id}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadObservationReport cancelObservation(
            @PathParam("id") String id, @QueryParam("includeBootUi") @DefaultValue("true") boolean includeBootUi) {
        return analysis.cancel(id, includeBootUi);
    }

    @ServerExceptionMapper
    public Response analysisFailure(ThreadAnalysisException exception) {
        return Response.status(exception.status())
                .entity(Map.of("error", exception.getMessage()))
                .build();
    }
}
