package io.github.jdubois.bootui.quarkus.it;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A minimal application endpoint used only by {@link BootUiQuarkusMappedExceptionCaptureTest}. Its failure
 * is fully resolved by a custom {@link ExceptionMapper} that deliberately never logs the throwable, so the
 * failure never reaches {@code QuarkusExceptionLogHandler}. Because a mapper produced a normal response,
 * RESTEasy Reactive also never calls {@code RoutingContext.fail(...)}, so {@code QuarkusExceptionCaptureFilter}
 * never fires either. Only {@code QuarkusPreMappingExceptionCaptureHandler} — hooked in via the RESTEasy
 * Reactive {@code PreExceptionMapperHandlerBuildItem} extension point, which Quarkus guarantees runs for
 * every exception about to be mapped — observes this class of failure. This mirrors a Spring
 * {@code @ExceptionHandler} that maps straight to a response without logging, which
 * {@code BootUiExceptionHandlerResolver} already captures on the Spring adapter.
 */
@Path("/it/mapped-boom")
public class MappedExceptionProbeResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String mappedBoom() {
        throw new MappedBusinessException("it-mapped-boom");
    }

    static class MappedBusinessException extends RuntimeException {
        MappedBusinessException(String message) {
            super(message);
        }
    }

    @Provider
    public static class MappedBusinessExceptionMapper implements ExceptionMapper<MappedBusinessException> {
        @Override
        public Response toResponse(MappedBusinessException exception) {
            // Deliberately does NOT log — the point of this fixture is to prove capture never depends on
            // the exception being logged or propagated to Vert.x as a routing failure.
            return Response.status(422).entity(exception.getMessage()).build();
        }
    }
}
