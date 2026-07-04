package io.github.jdubois.bootui.engine.restapi.phase3.fixes.broadexception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * An advice with a specific handler AND a broad {@code Exception} catch-all — but the catch-all
 * collapses to a fixed non-5xx status (400) instead of a generic server-error status. This is still
 * the RAPI-ERR-005 anti-pattern even though a specific handler coexists, because a client cannot
 * distinguish "your request was malformed" from "the server broke" if every unmodeled failure also
 * reports 400.
 */
@RestControllerAdvice
public class BroadHandlerMappedToNonServerErrorAdvice {

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleConflict(IllegalStateException ex) {
        return ex.getMessage();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleAll(Exception ex) {
        return ex.getMessage();
    }
}
