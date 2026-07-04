package io.github.jdubois.bootui.engine.restapi.newrules.retryafter;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * An exception handler mapped to 503 Service Unavailable with no statically-visible Retry-After
 * header — exercises RAPI-ERR-007's {@code context.exceptionHandlers()} path (as opposed to {@link
 * ThrottledEndpointController}, which exercises the direct-handler {@code @ResponseStatus} path).
 * Must be flagged by RAPI-ERR-007.
 */
@RestControllerAdvice
public class ServiceUnavailableAdvice {

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String handleUnavailable(IllegalStateException ex) {
        return ex.getMessage();
    }
}
