package io.github.jdubois.bootui.sample.codepaths;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Code Paths' seed ({@code docs/PLAN-v2.md} §5.14): the deliberately slow bean method behind {@code GET
 * /api/quotes/{sku}}, about 50 ms of handler time with no recorded call, which the route's tree and
 * {@code route-time-breakdown}'s handler split name. Mirrors the Spring sample's.
 */
@ApplicationScoped
public class SlowPricingService {

    /** How long a quote takes. */
    static final long QUOTE_MILLIS = 50;

    /** Prices {@code sku} slowly, as a remote pricing engine would. */
    public int quote(String sku) {
        try {
            Thread.sleep(QUOTE_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return 100 + Math.floorMod(sku.hashCode(), 900);
    }
}
