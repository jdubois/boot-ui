package io.github.jdubois.bootui.sample.codepaths;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Code Paths' seeded slow blocking route ({@code docs/PLAN-v2.md} §5.14), as on the Spring sample: {@code GET
 * /api/quotes/{sku}} runs on a worker thread and calls {@link QuoteService}, which calls
 * {@link SlowPricingService#quote}.
 */
@Path("/api/quotes")
@Produces(MediaType.APPLICATION_JSON)
public class QuoteResource {

    private final QuoteService quotes;

    public QuoteResource(QuoteService quotes) {
        this.quotes = quotes;
    }

    @GET
    @Path("/{sku}")
    public QuoteService.Quote quote(@PathParam("sku") String sku) {
        return quotes.quote(sku);
    }
}
