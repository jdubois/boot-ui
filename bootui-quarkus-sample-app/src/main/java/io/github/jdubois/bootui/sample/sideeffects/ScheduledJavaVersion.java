package io.github.jdubois.bootui.sample.sideeffects;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Starts the JDK's {@code java -version} from a scheduled run: Side Effects attributes that process to the scheduled
 * run, an execution no request owns, as the runtime journal names it, not to a request or to startup
 * ({@code docs/PLAN-v2.md} §5.16). Off unless {@code side-effects-seed.scheduled-every} sets its period, as the
 * agent Playwright leg does, so a plain dev run or the demo image never starts a child JVM on a timer.
 */
@ApplicationScoped
public class ScheduledJavaVersion {

    private static final Logger LOG = Logger.getLogger(ScheduledJavaVersion.class);

    @Inject
    JavaVersionReporter reporter;

    @Scheduled(every = "${side-effects-seed.scheduled-every:off}", delayed = "3s")
    void report() {
        LOG.debugf("Scheduled java -version: %s", reporter.version());
    }
}
