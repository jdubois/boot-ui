package io.github.jdubois.bootui.engine.restapi.jaxrs.bad;

import io.github.jdubois.bootui.engine.restapi.jaxrs.WidgetDto;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** A JAX-RS resource with a state-changing operation mapped to GET (RAPI-MAP-003, HIGH). */
@Path("/gadgets")
public class BadGadgetResource {

    @GET
    @Path("/create")
    public WidgetDto createGadget() {
        return new WidgetDto("1", "g");
    }
}
