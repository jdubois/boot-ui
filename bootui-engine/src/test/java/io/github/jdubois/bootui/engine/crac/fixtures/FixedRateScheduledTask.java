package io.github.jdubois.bootui.engine.crac.fixtures;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * A fixed-rate scheduled task (CRAC-SCHED-001), alongside a fixed-delay and a cron-based task that
 * should not be flagged: only an explicitly declared {@code fixedRate}/{@code fixedRateString} risks
 * a catch-up burst after an on-demand restore.
 */
public class FixedRateScheduledTask {

    @Scheduled(fixedRate = 5000)
    public void pollEveryFiveSeconds() {}

    @Scheduled(fixedDelay = 5000)
    public void runFiveSecondsAfterCompletion() {}

    @Scheduled(cron = "0 0 * * * *")
    public void hourlyJob() {}
}
