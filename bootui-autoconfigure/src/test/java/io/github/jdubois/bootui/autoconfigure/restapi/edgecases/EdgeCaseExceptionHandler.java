package io.github.jdubois.bootui.autoconfigure.restapi.edgecases;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * An @ExceptionHandler that renders a plain body without an explicit error status, so it falls back
 * to 200 OK (RAPI-ERR-004).
 */
@RestControllerAdvice
public class EdgeCaseExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public String handle(RuntimeException ex) {
        return ex.getMessage();
    }
}
