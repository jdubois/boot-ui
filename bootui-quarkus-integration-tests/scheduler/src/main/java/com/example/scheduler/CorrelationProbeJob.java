package com.example.scheduler;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A scheduled job that fires quickly and records the BootUI execution id current while it runs, so the scheduler
 * integration test can prove each run carries its own execution context ({@code docs/PLAN-v2.md} §5.1) and that the
 * recorded run carries the same id.
 */
@ApplicationScoped
public class CorrelationProbeJob {

    public static final List<String> SEEN = new CopyOnWriteArrayList<>();

    @Scheduled(every = "1s")
    void probe() {
        SEEN.add(String.valueOf(BootUiCorrelation.current().executionId()));
    }
}
