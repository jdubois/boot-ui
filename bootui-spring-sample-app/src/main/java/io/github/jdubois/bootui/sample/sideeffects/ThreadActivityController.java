package io.github.jdubois.bootui.sample.sideeffects;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public ThreadActivityController(BackgroundWork work) {
        this.work = work;
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
