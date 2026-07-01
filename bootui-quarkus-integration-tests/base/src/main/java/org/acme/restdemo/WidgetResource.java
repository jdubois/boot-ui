package org.acme.restdemo;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * A deliberately imperfect JAX-RS resource so the shared REST API advisor finds at least one violation on
 * Quarkus: {@code createWidget} is a state-changing operation mapped to GET (RAPI-MAP-003, HIGH). Exercised
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
}
