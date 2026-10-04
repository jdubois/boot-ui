package io.github.jdubois.bootui.sample.codepaths;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Code Paths' seeded slow blocking route ({@code docs/PLAN-v2.md} §5.14): {@code GET /api/quotes/{sku}} calls
 * {@link QuoteService}, which calls {@link SlowPricingService#quote}, so with the BootUI agent the route's tree and
 * {@code route-time-breakdown} name {@code SlowPricingService.quote} as where the handler's time goes.
 */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/{sku}")
    public QuoteService.Quote quote(@PathVariable String sku) {
        return quotes.quote(sku);
    }
}
