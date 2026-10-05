package io.github.jdubois.bootui.sample.sideeffects;

import io.github.jdubois.bootui.sample.catalog.ProductSummary;
import io.github.jdubois.bootui.sample.catalog.SampleCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thread activity's seeded routes ({@code docs/PLAN-v2.md} §5.16, M5-5e): with the BootUI agent's
 * {@code thread-activity} sensor, {@code GET /api/thread-activity/left-running} leaves a {@code report-refresher-{n}}
 * thread running and {@code GET /api/thread-activity/own-pool} an executor never shut down; their counterexamples,
 * {@code GET /api/thread-activity/joined} and {@code GET /api/thread-activity/closed-pool}, leave nothing running.
 */
@RestController
@RequestMapping("/api/thread-activity")
public class ThreadActivityController {

    private final BackgroundWork work;
    private final SampleCatalog catalog;

    public ThreadActivityController(BackgroundWork work, SampleCatalog catalog) {
        this.work = work;
        this.catalog = catalog;
    }

    /**
     * The agent overhead benchmark's thread route (M5-5e): the product search of {@code GET
     * /api/sample/product-search}, plus one thread started and joined and one executor created and shut down, so the
     * thread-activity sensor's hooks run on every request.
     */
    @GetMapping("/benchmark")
    public List<ProductSummary> benchmark(@RequestParam(name = "term", defaultValue = "console") String term)
            throws Exception {
        work.refreshNow();
        work.createAndShutDown();
        return catalog.searchProducts(term);
    }

    @GetMapping("/left-running")
    public Map<String, String> leftRunning() {
        return Map.of("thread", work.startRefresher());
    }

    @GetMapping("/joined")
    public Map<String, String> joined() throws InterruptedException {
        return Map.of("thread", work.refreshNow());
    }

    @GetMapping("/own-pool")
    public Map<String, Integer> ownPool() throws Exception {
        return Map.of("result", work.exportWithOwnPool());
    }

    @GetMapping("/closed-pool")
    public Map<String, Integer> closedPool() throws Exception {
        return Map.of("result", work.exportWithClosedPool());
    }
}
