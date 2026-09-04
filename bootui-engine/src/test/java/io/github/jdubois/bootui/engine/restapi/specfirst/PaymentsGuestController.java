package io.github.jdubois.bootui.engine.restapi.specfirst;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hand-written controller that implements the generated interface. Its overriding methods carry
 * no mapping annotations of their own, so it contributes no handlers to the model.
 */
@RestController
public class PaymentsGuestController implements GeneratedPaymentsGuestApi {

    @Override
    public ResponseEntity<PaymentDto> getGuestPayment() {
        return ResponseEntity.ok(new PaymentDto("p-1", "SETTLED"));
    }

    @Override
    public ResponseEntity<PaymentDto> authorizeGuestPayment() {
        return ResponseEntity.ok(new PaymentDto("p-1", "AUTHORIZED"));
    }
}
