package io.github.jdubois.bootui.console.web;

import io.github.jdubois.bootui.console.activity.ConsoleActivityForwardService;
import io.github.jdubois.bootui.core.dto.ActivityForwardBatchRequest;
import io.github.jdubois.bootui.core.dto.ActivityForwardResult;
import io.github.jdubois.bootui.engine.activity.ActivityForwardService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Receiving end of Live Activity HTTP forwarding, for any BootUI instance (Spring Boot or Quarkus)
 * configured to send its captured activity to this console instead of &mdash; or in addition to
 * &mdash; its own local panel. Served at exactly {@link ActivityForwardService#FORWARD_PATH}, the same
 * path a host application's own {@code ActivityForwardingController} serves, so an {@code
 * HttpActivityStore} sender's configured {@code peer-base-url} can point at either kind of receiver
 * with no other change.
 */
@RestController
public class ConsoleActivityForwardController {

    private final ConsoleActivityForwardService forwardService;

    public ConsoleActivityForwardController(ConsoleActivityForwardService forwardService) {
        this.forwardService = forwardService;
    }

    @PostMapping(ActivityForwardService.FORWARD_PATH)
    public Mono<ResponseEntity<ActivityForwardResult>> forward(
            @RequestHeader(name = ActivityForwardService.FORWARD_TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) ActivityForwardBatchRequest request) {
        return forwardService
                .receive(token, request)
                .map(response -> ResponseEntity.status(HttpStatus.valueOf(response.status()))
                        .body(response.body()));
    }
}
