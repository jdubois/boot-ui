package io.github.jdubois.bootui.engine.restapi.newrules.idempotency;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A POST creation endpoint with no client-supplied Idempotency-Key header — a client cannot safely
 * retry this request after a network failure (timeout, dropped connection) without risking a
 * duplicate resource. Must be flagged by RAPI-VALID-005 (new rule, Part 2 #1).
 */
@RestController
public class CreateWidgetWithoutIdempotencyKeyController {

    @PostMapping("/widgets")
    public String createWidget(@RequestBody String body) {
        return "ok";
    }
}
