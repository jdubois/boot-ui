package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/** Canonical safe errors for retained advisor reads. */
@Provider
public class AdvisorViolationExceptionMapper implements ExceptionMapper<AdvisorViolationException> {

    @Override
    public Response toResponse(AdvisorViolationException exception) {
        return Response.status(exception.status())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("error", exception.getMessage()))
                .build();
    }
}
