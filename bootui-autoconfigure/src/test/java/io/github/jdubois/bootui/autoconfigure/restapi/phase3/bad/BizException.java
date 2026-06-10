package io.github.jdubois.bootui.autoconfigure.restapi.phase3.bad;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * An application exception annotated with @ResponseStatus while the project uses ProblemDetail
 * elsewhere (triggers RAPI-ERR-006).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
