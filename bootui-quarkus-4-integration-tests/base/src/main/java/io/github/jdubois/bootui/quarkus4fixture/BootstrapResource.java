package io.github.jdubois.bootui.quarkus4fixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/fixture/bootstrap")
public class BootstrapResource {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public State state() {
        return new State("quarkus", List.of(new Item("nested", List.of("one", "two"))));
    }

    public record State(String platform, List<Item> items) {}

    public record Item(String name, List<String> values) {}
}
