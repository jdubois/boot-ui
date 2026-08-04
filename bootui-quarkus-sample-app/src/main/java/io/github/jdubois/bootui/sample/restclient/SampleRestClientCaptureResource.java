package io.github.jdubois.bootui.sample.restclient;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Deterministic on-demand REST Client call used by the sample UI and browser tests. */
@Path("/api/sample/rest-client-capture")
public class SampleRestClientCaptureResource {

    @RestClient
    SampleApiClient apiClient;

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    public String capture() {
        return apiClient.listProducts(3);
    }
}
