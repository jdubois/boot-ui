package io.github.jdubois.bootui.webfluxsample.caught;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Seeds of the Exceptions panel's <b>Caught in application code</b> section on Spring WebFlux ({@code docs/PLAN-v2.md}
 * M5-6), as on the Spring MVC sample. With the BootUI agent's {@code caught-exceptions} sensor, {@code GET
 * /api/caught/swallowed} is a finding; its counterexamples {@code /logged}, {@code /logged-message}, {@code /rethrown}
 * (a 404), and {@code /handed-on} (an error signal that recovers) are not.
 */
@RestController
@RequestMapping("/api/caught")
public class CaughtController {

    private static final String SKU = "console";

    private final StockLookup stock;

    public CaughtController(StockLookup stock) {
        this.stock = stock;
    }

    @GetMapping("/swallowed")
    public Map<String, Integer> swallowed() {
        return Map.of("stock", stock.stockOrDefault(SKU));
    }

    @GetMapping("/logged")
    public Map<String, Integer> logged() {
        return Map.of("stock", stock.stockLogged(SKU));
    }

    @GetMapping("/logged-message")
    public Map<String, Integer> loggedMessage() {
        return Map.of("stock", stock.stockLoggedMessage(SKU));
    }

    @GetMapping("/rethrown")
    public Map<String, Integer> rethrown() {
        return Map.of("stock", stock.stockOrFail(SKU));
    }

    @GetMapping("/handed-on")
    public Mono<Map<String, Integer>> handedOn() {
        return stock.stockLater(SKU).map(value -> Map.of("stock", value));
    }
}
