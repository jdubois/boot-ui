package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.engine.action.ActionBusyException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Maps framework-neutral single-flight rejection to the canonical BootUI HTTP response. */
@Provider
public class ActionBusyExceptionMapper implements ExceptionMapper<ActionBusyException> {

    @Override
    public Response toResponse(ActionBusyException exception) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(exception.result())
                .build();
    }
}
