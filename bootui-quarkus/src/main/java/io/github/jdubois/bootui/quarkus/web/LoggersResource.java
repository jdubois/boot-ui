package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * JAX-RS resource for the Loggers panel ({@code GET /bootui/api/loggers},
 * {@code POST /bootui/api/loggers/{name}}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code LoggersController}: a thin transport adapter
 * over the shared engine {@link LoggersService}, which owns the framework-neutral reading, self-logger
 * filtering, sorting, paging and the fail-closed write guard. A change to a BootUI-owned logger is
 * rejected by the engine with {@link IllegalArgumentException}, mapped here to a 400 with the same JSON
 * {@code {"error": ...}} body the Spring controller returns.</p>
 */
@Path("/bootui/api/loggers")
public class LoggersResource {

    private final LoggersService loggers;

    @Inject
    public LoggersResource(LoggersService loggers) {
        this.loggers = loggers;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public LoggersReport loggers(
            @QueryParam("q") String query, @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
        return loggers.report(query, offset, limit);
    }

    @POST
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setLevel(@PathParam("name") String name, LevelUpdateRequest request) {
        try {
            LoggerDto updated = loggers.setLevel(name, request == null ? null : request.level());
            return Response.ok(updated).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()))
                    .build();
        }
    }

    public record LevelUpdateRequest(String level) {}
}
