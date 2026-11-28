package io.github.jdubois.bootui.sample.codepaths;

import jakarta.enterprise.context.ApplicationScoped;

/** The bean layer between the quote route and its slow pricing ({@code docs/PLAN-v2.md} §5.14). */
@ApplicationScoped
public class QuoteService {

    private final SlowPricingService pricing;

    public QuoteService(SlowPricingService pricing) {
        this.pricing = pricing;
    }

    /** A quote for {@code sku}, in cents. */
    public Quote quote(String sku) {
        return new Quote(sku, pricing.quote(sku));
    }

    /** A price quote. */
    public record Quote(String sku, int cents) {}
}
