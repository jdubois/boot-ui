package io.github.jdubois.bootui.engine.restapi.newrules.idempotency;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A POST creation endpoint that accepts a client-supplied Idempotency-Key header, matching the IETF
 * HTTPAPI working-group Idempotency-Key Internet-Draft pattern already used by Stripe/PayPal/Azure.
 * Must PASS RAPI-VALID-005.
 */
@RestController
public class CreateWidgetWithIdempotencyKeyController {

    @PostMapping("/widgets")
    public String createWidget(@RequestBody String body, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return "ok";
    }
}
