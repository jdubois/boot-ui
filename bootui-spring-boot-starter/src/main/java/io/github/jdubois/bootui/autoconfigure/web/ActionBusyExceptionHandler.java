package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.ActionBusyResult;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps framework-neutral single-flight rejection to the canonical BootUI HTTP response. */
@RestControllerAdvice
public class ActionBusyExceptionHandler {

    @ExceptionHandler(ActionBusyException.class)
    public ResponseEntity<ActionBusyResult> handle(ActionBusyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.result());
    }
}
