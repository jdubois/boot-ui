package com.example.bootui.it.errors;

import jakarta.annotation.Priority;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A declared exception mapper that exists only so {@code BootUiQuarkusApiConformanceTest} can prove the
 * error-contract catalogue really discovers the host application's handlers on Quarkus, rather than
 * returning a well-shaped empty catalogue.
 *
 * <p>It deliberately lives outside {@code io.github.jdubois.bootui.quarkus4}: BootUI excludes its own
 * package from every declaration scan, and this integration-test application is itself hosted inside that
 * package, so a fixture declared there would be filtered out exactly like BootUI's own resources. Nothing
 * throws {@link ItCatalogueException}, so this mapper is never invoked; only its declaration is read.</p>
 */
@Provider
@Priority(4500)
public class ItCatalogueExceptionMapper implements ExceptionMapper<ItCatalogueException> {

    @Override
    public Response toResponse(ItCatalogueException exception) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(exception.getMessage())
                .build();
    }
}
