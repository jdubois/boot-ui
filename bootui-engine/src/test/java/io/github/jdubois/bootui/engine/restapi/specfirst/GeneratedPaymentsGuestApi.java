package io.github.jdubois.bootui.engine.restapi.specfirst;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shape openapi-generator emits for a spec-first API with {@code interfaceOnly=true}: the
 * generated {@code *Api} interface carries {@code @RestController}, every method carries the full
 * path, and there is no type-level {@code @RequestMapping} (that one is emitted only when
 * {@code useRequestMappingOnInterface} is set).
 *
 * <p>The layout is the generator template's decision, not the application author's — an OpenAPI
 * document cannot express whether a base path is hoisted onto the type — and the mappings are
 * inherited by every implementation, so RAPI-MAP-004 must not report it.</p>
 */
@RestController
public interface GeneratedPaymentsGuestApi {

    @RequestMapping(
            method = {RequestMethod.GET},
            value = {"/api/payments/guest"},
            produces = {"application/json"})
    ResponseEntity<PaymentDto> getGuestPayment();

    @RequestMapping(
            method = {RequestMethod.POST},
            value = {"/api/payments/guest/authorizations"},
            produces = {"application/json"})
    ResponseEntity<PaymentDto> authorizeGuestPayment();
}
