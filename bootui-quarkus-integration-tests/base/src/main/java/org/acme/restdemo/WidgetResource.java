package org.acme.restdemo;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * A deliberately imperfect JAX-RS resource so the shared REST API advisor finds at least one violation on
 * Quarkus: {@code createWidget} has a mutation-like name mapped to GET (RAPI-MAP-003, LOW). Exercised
 * by {@code BootUiQuarkusRestApiResourceTest}.
 */
@Path("/widgets")
@Produces(MediaType.APPLICATION_JSON)
public class WidgetResource {

    @GET
    public List<String> list() {
        return List.of("a", "b");
    }

    @GET
    @Path("/create")
    public String createWidget() {
        return "created";
    }

    @GET
    @Path("/accepted")
    public Uni<RestResponse<List<String>>> asyncAccepted() {
        return Uni.createFrom().item(RestResponse.status(RestResponse.Status.ACCEPTED, List.of("accepted")));
    }

    @DELETE
    @Path("/configuration")
    public Uni<Void> clearConfiguration() {
        return Uni.createFrom().voidItem();
    }

    @PATCH
    @Path("/configuration.txt")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public String patchConfiguration(String patch) {
        return patch;
    }
}
