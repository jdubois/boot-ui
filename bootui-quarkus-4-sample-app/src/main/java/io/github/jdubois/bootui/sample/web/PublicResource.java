package io.github.jdubois.bootui.sample.web;

import io.github.jdubois.bootui.sample.inventory.GreetingService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Public endpoints (mirrors the Spring sample's HelloController public methods). */
@Path("/api")
@Produces(MediaType.TEXT_PLAIN)
public class PublicResource {

    private final GreetingService greetings;

    public PublicResource(GreetingService greetings) {
        this.greetings = greetings;
    }

    @GET
    @Path("/hello")
    public String hello() {
        return greetings.greet("world");
    }
}
