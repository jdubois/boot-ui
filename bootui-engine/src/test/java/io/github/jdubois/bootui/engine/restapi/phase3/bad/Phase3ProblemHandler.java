package io.github.jdubois.bootui.engine.restapi.phase3.bad;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * An advice that returns ProblemDetail — establishes that this project uses RFC 9457 error
 * handling (needed as context for RAPI-ERR-006 to fire).
 */
@RestControllerAdvice
public class Phase3ProblemHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadInput(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setDetail(ex.getMessage());
        return pd;
    }
}
