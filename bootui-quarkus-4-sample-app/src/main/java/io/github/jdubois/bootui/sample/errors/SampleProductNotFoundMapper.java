package io.github.jdubois.bootui.sample.errors;

import jakarta.annotation.Priority;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Global Jakarta REST error contract for the sample application, giving BootUI's error-contract catalogue
 * a realistic {@code @Provider ExceptionMapper} to read.
 *
 * <p>The catalogue reads the declaration only — the {@code @Priority}, the mapped exception type from the
 * {@code ExceptionMapper<X>} signature and the {@code Response} return type. The mapper is never
 * instantiated or invoked to build the panel.</p>
 */
@Provider
@Priority(4000)
public class SampleProductNotFoundMapper implements ExceptionMapper<SampleProductNotFoundException> {

    @Override
    public Response toResponse(SampleProductNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(new SampleErrorBody("product_not_found", exception.getMessage()))
                .build();
    }
}
