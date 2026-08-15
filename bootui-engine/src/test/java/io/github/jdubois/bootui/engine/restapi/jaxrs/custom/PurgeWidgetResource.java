package io.github.jdubois.bootui.engine.restapi.jaxrs.custom;

import jakarta.ws.rs.Path;

@Path("/widgets")
public class PurgeWidgetResource {

    @Purge
    @Path("/{id}")
    public void purge() {}
}
