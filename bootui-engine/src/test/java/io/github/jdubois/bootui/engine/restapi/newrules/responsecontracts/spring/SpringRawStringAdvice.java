package io.github.jdubois.bootui.engine.restapi.newrules.responsecontracts.spring;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SpringRawStringAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handle(IllegalArgumentException exception) {
        return exception.getMessage();
    }
}
