package io.github.jdubois.bootui.engine.restapi.newrules.responsecontracts.jaxrs;

import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/widgets")
public class JaxRsHeadResource {

    @HEAD
    public String head() {
        return "body-that-must-not-be-sent";
    }

    @HEAD
    @Path("/headers-only")
    public Response headersOnly() {
        return Response.ok().build();
    }
}
