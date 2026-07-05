package io.github.jdubois.bootui.webfluxsample.greeting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Exposes {@link GreetingService} so a request actually populates the "sample-greetings" cache. */
@RestController
public class GreetingController {

    private final GreetingService greetingService;

    public GreetingController(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @GetMapping("/api/greetings/{name}")
    public Mono<String> greet(@PathVariable String name) {
        return Mono.fromCallable(() -> greetingService.greet(name));
    }
}
