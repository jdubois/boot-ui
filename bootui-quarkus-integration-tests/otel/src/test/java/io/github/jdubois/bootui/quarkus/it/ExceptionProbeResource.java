package io.github.jdubois.bootui.quarkus.it;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A minimal application endpoint used only by {@link BootUiQuarkusLiveActivityExceptionCorrelationTest}. It
 * throws unconditionally on every call so the test can prove the exception surfaces in the Live Activity
 * feed nested under its owning request, carrying that request's method and path.
 */
@Path("/it/boom")
public class ExceptionProbeResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String boom() {
        throw new IllegalStateException("it-boom");
    }
}
