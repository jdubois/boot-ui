package io.github.jdubois.bootui.autoconfigure.restapi.phase3.bad;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * An advice that catches {@code Exception} broadly and maps it to a single fixed 500 status
 * (triggers RAPI-ERR-005).
 */
@RestControllerAdvice
public class Phase3BroadAdvice {

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleAll(Exception ex) {
        return ex.getMessage();
    }
}
