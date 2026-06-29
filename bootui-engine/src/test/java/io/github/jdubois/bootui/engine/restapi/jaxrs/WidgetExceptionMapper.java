package io.github.jdubois.bootui.engine.restapi.jaxrs;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** A JAX-RS centralized exception handler; should set {@code hasExceptionHandling}. */
@Provider
public class WidgetExceptionMapper implements ExceptionMapper<RuntimeException> {

    @Override
    public Response toResponse(RuntimeException exception) {
        return Response.serverError().build();
    }
}
