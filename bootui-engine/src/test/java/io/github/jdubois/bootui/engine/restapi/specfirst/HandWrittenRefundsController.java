package io.github.jdubois.bootui.engine.restapi.specfirst;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A hand-written class controller that repeats the same leading segment on every method. The author
 * owns this layout and can hoist it, so RAPI-MAP-004 must keep reporting it.
 */
@RestController
public class HandWrittenRefundsController {

    @GetMapping("/api/refunds")
    public PaymentDto listRefunds() {
        return new PaymentDto("r-1", "PENDING");
    }

    @GetMapping("/api/refunds/{id}")
    public PaymentDto getRefund(@PathVariable String id) {
        return new PaymentDto(id, "PENDING");
    }
}
