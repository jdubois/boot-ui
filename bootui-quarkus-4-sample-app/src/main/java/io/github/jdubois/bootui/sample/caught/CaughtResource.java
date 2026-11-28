package io.github.jdubois.bootui.sample.caught;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Seeds of the Exceptions panel's <b>Caught in application code</b> section on Quarkus ({@code docs/PLAN-v2.md} M5-6),
 * as on the Spring samples. With the BootUI agent's {@code caught-exceptions} sensor, {@code GET /api/caught/swallowed}
 * is a finding; its counterexamples {@code /logged}, {@code /logged-message}, {@code /rethrown} (a 404), and {@code
 * /handed-on} (a failed {@code Uni} that recovers) are not. Each runs on a worker thread.
 */
@Path("/api/caught")
@Produces(MediaType.APPLICATION_JSON)
public class CaughtResource {

    private static final String SKU = "console";

    private final StockLookup stock;

    public CaughtResource(StockLookup stock) {
        this.stock = stock;
    }

    @GET
    @Path("/swallowed")
    public Map<String, Integer> swallowed() {
        return Map.of("stock", stock.stockOrDefault(SKU));
    }

    @GET
    @Path("/logged")
    public Map<String, Integer> logged() {
        return Map.of("stock", stock.stockLogged(SKU));
    }

    @GET
    @Path("/logged-message")
    public Map<String, Integer> loggedMessage() {
        return Map.of("stock", stock.stockLoggedMessage(SKU));
    }

    @GET
    @Path("/rethrown")
    public Map<String, Integer> rethrown() {
        return Map.of("stock", stock.stockOrFail(SKU));
    }

    @GET
    @Path("/handed-on")
    public Uni<Map<String, Integer>> handedOn() {
        return stock.stockLater(SKU).map(value -> Map.of("stock", value));
    }
}
