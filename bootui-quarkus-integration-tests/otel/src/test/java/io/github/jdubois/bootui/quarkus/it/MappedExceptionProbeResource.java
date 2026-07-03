package io.github.jdubois.bootui.quarkus.it;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A minimal application endpoint used only by {@link BootUiQuarkusMappedExceptionCorrelationTest}. Its
 * failure is fully resolved by a custom {@link ExceptionMapper} that deliberately never logs the throwable,
 * so unlike {@link ExceptionProbeResource} it is invisible to {@code QuarkusExceptionLogHandler} and {@code
 * QuarkusExceptionCaptureFilter}; only {@code QuarkusPreMappingExceptionCaptureHandler} observes it.
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
            // Deliberately does NOT log — proves capture does not depend on logging or on Vert.x routing
            // failure propagation.
            return Response.status(422).entity(exception.getMessage()).build();
        }
    }
}
