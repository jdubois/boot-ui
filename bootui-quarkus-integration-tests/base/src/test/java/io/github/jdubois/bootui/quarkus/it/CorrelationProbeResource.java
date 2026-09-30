package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.common.annotation.NonBlocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Minimal endpoints used only by {@link BootUiQuarkusRequestCorrelationTest}: each returns the thread it ran on and
 * the request id BootUI's {@link CorrelationContextProvider} reports there, once on a worker thread and once on the
 * event loop.
 */
@Path("/it/correlation")
public class CorrelationProbeResource {

    @Inject
    CorrelationContextProvider correlation;

    @GET
    @Path("/worker")
    @Blocking
    @Produces(MediaType.TEXT_PLAIN)
    public String worker() {
        return describe();
    }

    @GET
    @Path("/event-loop")
    @NonBlocking
    @Produces(MediaType.TEXT_PLAIN)
    public String eventLoop() {
        return describe();
    }

    private String describe() {
        return Thread.currentThread().getName() + "|" + correlation.current().requestId();
    }
}
