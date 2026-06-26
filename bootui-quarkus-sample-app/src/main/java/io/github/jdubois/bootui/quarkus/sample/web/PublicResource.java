package io.github.jdubois.bootui.quarkus.sample.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Public endpoints (mirrors the Spring sample's HelloController public methods). */
@Path("/api")
@Produces(MediaType.TEXT_PLAIN)
public class PublicResource {

    @GET
    @Path("/hello")
    public String hello() {
        return "Hello, world";
    }
}
