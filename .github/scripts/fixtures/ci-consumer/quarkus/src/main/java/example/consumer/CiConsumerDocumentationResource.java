package example.consumer;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/bootui-ci-private-finding-payload")
@Produces(MediaType.TEXT_HTML)
public class CiConsumerDocumentationResource {

    @GET
    public String documentation() {
        return "<!doctype html><html><head><title>Orders API</title></head><body>Orders API</body></html>";
    }
}
