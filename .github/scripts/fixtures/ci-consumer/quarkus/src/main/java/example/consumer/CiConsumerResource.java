package example.consumer;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
public class CiConsumerResource {

    @Inject
    @ConfigProperty(name = "ci.consumer.instance")
    String instance;

    @GET
    public List<String> orders() {
        return List.of("order", instance);
    }
}
