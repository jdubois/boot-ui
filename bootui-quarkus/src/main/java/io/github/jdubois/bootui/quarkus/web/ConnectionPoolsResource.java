package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HikariPoolSnapshotDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the Database Connection Pools panel
 * ({@code GET /bootui/api/database-connection-pools/pools} and {@code .../pools/{name}/snapshot}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code DatabaseConnectionPoolsController}: a thin transport
 * adapter over the shared engine {@link ConnectionPoolService}, which owns the framework-neutral assembly,
 * sorting and credential masking. Pool discovery is the Quarkus-specific
 * {@code QuarkusAgroalConnectionPoolProvider}; the route and DTO contract are identical across adapters.</p>
 *
 * <p>The panel is strictly read-only — there are no state-changing endpoints and therefore no write gate.</p>
 */
@Path("/bootui/api/database-connection-pools")
public class ConnectionPoolsResource {

    private final ConnectionPoolService service;

    @Inject
    public ConnectionPoolsResource(ConnectionPoolService service) {
        this.service = service;
    }

    @GET
    @Path("/pools")
    @Produces(MediaType.APPLICATION_JSON)
    public HikariPoolsReport pools() {
        return service.report();
    }

    @GET
    @Path("/pools/{name}/snapshot")
    @Produces(MediaType.APPLICATION_JSON)
    public Response snapshot(@PathParam("name") String name) {
        HikariPoolSnapshotDto snapshot = service.snapshot(name);
        if (snapshot == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(snapshot).build();
    }
}
