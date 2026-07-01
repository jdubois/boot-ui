package io.github.jdubois.bootui.engine.restapi.jaxrs;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * A clean JAX-RS resource: class-level base path, plural noun collection, item path with an {id}
 * matching the @PathParam, a record body, explicit media types, and item-targeted mutations.
 */
@Path("/widgets")
@Produces(MediaType.APPLICATION_JSON)
public class GoodWidgetResource {

    @GET
    public List<WidgetDto> list(@QueryParam("page") int page, @QueryParam("size") int size) {
        return List.of();
    }

    @GET
    @Path("/{id}")
    public WidgetDto get(@PathParam("id") String id) {
        return new WidgetDto(id, "w");
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public WidgetDto create(WidgetDto body) {
        return body;
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {}
}
