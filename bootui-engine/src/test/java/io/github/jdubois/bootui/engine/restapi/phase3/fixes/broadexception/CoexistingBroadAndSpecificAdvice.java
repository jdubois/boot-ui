package io.github.jdubois.bootui.engine.restapi.phase3.fixes.broadexception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A well-designed advice: specific handlers for known exception types, plus a broad {@code
 * Exception} catch-all mapped to 500 as a deliberate, last-resort fallback (RFC 9110 §15.6.1). This
 * is the CORRECT pattern and must NOT be flagged by RAPI-ERR-005 (fix #4) — only a broad handler that
 * is the sole handler present, or one that maps to a non-5xx status, should be flagged.
 */
@RestControllerAdvice
public class CoexistingBroadAndSpecificAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleBadRequest(IllegalArgumentException ex) {
        return ex.getMessage();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleAll(Exception ex) {
        return ex.getMessage();
    }
}
