package io.github.jdubois.bootui.engine.restapi.newrules.retryafter;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A handler mapped to 429 Too Many Requests via {@code @ResponseStatus} with no statically-visible
 * Retry-After header. Must be flagged by RAPI-ERR-007 (new rule, Part 2 #3).
 */
@RestController
public class ThrottledEndpointController {

    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    @GetMapping("/rate-limited")
    public String rateLimited() {
        return "slow down";
    }
}
