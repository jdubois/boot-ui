package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Canonical safe errors for retained advisor reads on MVC and WebFlux. */
@RestControllerAdvice
public class AdvisorViolationExceptionHandler {

    @ExceptionHandler(AdvisorViolationException.class)
    public ResponseEntity<Map<String, String>> handle(AdvisorViolationException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
    }
}
