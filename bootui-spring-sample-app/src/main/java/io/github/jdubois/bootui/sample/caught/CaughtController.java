package io.github.jdubois.bootui.sample.caught;

import io.github.jdubois.bootui.sample.catalog.ProductSummary;
import io.github.jdubois.bootui.sample.catalog.SampleCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seeds of the Exceptions panel's <b>Caught in application code</b> section ({@code docs/PLAN-v2.md} M5-6). With the
 * BootUI agent's {@code caught-exceptions} sensor, {@code GET /api/caught/swallowed} is a finding: the
 * {@code IOException} {@link StockLookup#stockOrDefault} caught was not seen rethrown or logged at {@code WARN} or above.
 * Its counterexamples {@code /logged}, {@code /logged-message}, {@code /rethrown} (a 404), and {@code /handed-on} are
 * not. {@code /benchmark} is the agent overhead benchmark's route.
 */
@RestController
@RequestMapping("/api/caught")
public class CaughtController {

    private static final String SKU = "console";

    private final StockLookup stock;
    private final SampleCatalog catalog;
    private final CaughtBenchmark benchmark;

    public CaughtController(StockLookup stock, SampleCatalog catalog, CaughtBenchmark benchmark) {
        this.stock = stock;
        this.catalog = catalog;
        this.benchmark = benchmark;
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
    public Map<String, Integer> handedOn() {
        return Map.of("stock", stock.stockLater(SKU).join());
    }

    /**
     * The agent overhead benchmark's caught-exceptions route (M5-6a2): the product search of {@code GET
     * /api/sample/product-search}, plus one caught exception ({@link CaughtBenchmark}).
     */
    @GetMapping("/benchmark")
    public List<ProductSummary> benchmark(@RequestParam(name = "term", defaultValue = "console") String term) {
        benchmark.run();
        return catalog.searchProducts(term);
    }
}
